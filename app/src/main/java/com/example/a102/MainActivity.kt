package com.example.a102

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.activity.ComponentActivity
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {

    // UI primes
    private lateinit var tvIndex: TextView
    private lateinit var tvPrime: TextView
    private lateinit var btnStartPause: Button
    private lateinit var btnNext: Button
    private lateinit var btnReset: Button

    // UI températures
    private lateinit var tvTemps: TextView
    private lateinit var spTempSource: Spinner
    private lateinit var chartTemp: TemperatureChartView

    private val ui = Handler(Looper.getMainLooper())

    // === Prime executors ===
    private val worker = Executors.newSingleThreadExecutor() // coordinateur (non-UI)
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
    private val primePool = Executors.newFixedThreadPool(cores)

    // === Temp executors ===
    private val tempsWorker = Executors.newSingleThreadExecutor()

    @Volatile private var running = false
    @Volatile private var inFlight = false
    @Volatile private var generation = 0

    private var primeIndex = 0L
    private var currentPrime = 1L
    private val primes = mutableListOf<Long>() // historique (optionnel)

    private val tickMs = 300L

    // === Temp sampling ===
    private val tempTickMs = 1000L // 1 Hz
    private val maxHistoryMs = 2 * 60 * 1000L // 2 minutes
    private val maxPoints: Int
        get() = (maxHistoryMs / tempTickMs).toInt().coerceAtLeast(2)

    private var tempsInFlight = false
    private val history = mutableMapOf<String, ArrayDeque<TempSample>>()
    private val sources = mutableListOf<String>()
    private lateinit var sourcesAdapter: ArrayAdapter<String>
    private var selectedSource: String? = null

    // Capteur ambiant
    private lateinit var sensorManager: SensorManager
    private var ambientTempC: Float? = null
    private var ambientSensor: Sensor? = null

    private val ambientListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            ambientTempC = event.values.firstOrNull()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val primeAutoRunnable = Runnable {
        requestNextPrime(autoReschedule = true)
    }

    private val tempLoopRunnable = object : Runnable {
        override fun run() {
            requestTemperatures()
            ui.postDelayed(this, tempTickMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Primes UI
        tvIndex = findViewById(R.id.tvIndex)
        tvPrime = findViewById(R.id.tvPrime)
        btnStartPause = findViewById(R.id.btnStartPause)
        btnNext = findViewById(R.id.btnNext)
        btnReset = findViewById(R.id.btnReset)

        // Temps UI
        tvTemps = findViewById(R.id.tvTemps)
        spTempSource = findViewById(R.id.spTempSource)
        chartTemp = findViewById(R.id.chartTemp)

        // Spinner sources
        sourcesAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sources).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spTempSource.adapter = sourcesAdapter
        spTempSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                selectedSource = sources.getOrNull(position)
                refreshChart()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Capteur ambiant (si dispo)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        ambientSensor =
            sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_TEMPERATURE) // deprecated, mais parfois présent

        updateUi()

        btnStartPause.setOnClickListener {
            running = !running
            updateUi()
            ui.removeCallbacks(primeAutoRunnable)
            if (running) scheduleAuto()
        }

        btnNext.setOnClickListener {
            requestNextPrime(autoReschedule = false)
        }

        btnReset.setOnClickListener {
            resetAll()
        }
    }

    override fun onStart() {
        super.onStart()

        // Réactive l'ambiance + loop température
        ambientSensor?.let {
            sensorManager.registerListener(ambientListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        ui.removeCallbacks(tempLoopRunnable)
        ui.post(tempLoopRunnable)
    }

    override fun onStop() {
        super.onStop()

        // Stop primes auto
        running = false
        ui.removeCallbacks(primeAutoRunnable)

        // Stop température loop + capteur
        ui.removeCallbacks(tempLoopRunnable)
        runCatching { sensorManager.unregisterListener(ambientListener) }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
        primePool.shutdownNow()
        tempsWorker.shutdownNow()
    }

    private fun resetAll() {
        running = false
        ui.removeCallbacks(primeAutoRunnable)

        generation++          // annule calculs en cours
        inFlight = false

        primeIndex = 0L
        currentPrime = 1L
        primes.clear()

        updateUi()
    }

    private fun scheduleAuto() {
        ui.postDelayed(primeAutoRunnable, tickMs)
    }

    private fun requestNextPrime(autoReschedule: Boolean) {
        if (inFlight) return
        inFlight = true
        val localGen = generation

        worker.execute {
            val next = computeNextPrimeParallel(localGen)

            ui.post {
                if (localGen != generation) return@post // reset pendant calcul
                inFlight = false
                currentPrime = next
                primeIndex++
                primes.add(next) // optionnel (historique)
                updateUi()
                if (running && autoReschedule) scheduleAuto()
            }
        }
    }

    // === PARALLEL NEXT PRIME ===
    private fun computeNextPrimeParallel(localGen: Int): Long {
        if (currentPrime < 2L) return 2L

        // prochain impair
        var start = currentPrime + 1L
        if (start % 2L == 0L) start++

        val best = AtomicLong(Long.MAX_VALUE)
        val latch = CountDownLatch(cores)

        for (i in 0 until cores) {
            primePool.execute {
                try {
                    var cand = start + 2L * i.toLong()
                    val step = 2L * cores.toLong()

                    while (true) {
                        // Annulation si reset
                        if (localGen != generation) return@execute

                        val currentBest = best.get()
                        if (cand >= currentBest) break

                        if (isPrimeTrialDivision(cand)) {
                            while (true) {
                                val prev = best.get()
                                if (cand >= prev) break
                                if (best.compareAndSet(prev, cand)) break
                            }
                            // on continue, la condition cand>=best stoppera vite
                        }

                        cand += step
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        val result = best.get()
        return if (result != Long.MAX_VALUE) result else computeNextPrimeFallback()
    }

    private fun isPrimeTrialDivision(n: Long): Boolean {
        if (n < 2L) return false
        if (n == 2L) return true
        if (n % 2L == 0L) return false

        var d = 3L
        while (d * d <= n) {
            if (n % d == 0L) return false
            d += 2L
        }
        return true
    }

    private fun computeNextPrimeFallback(): Long {
        var candidate = if (currentPrime < 2L) 2L else currentPrime + 1L
        while (true) {
            if (isPrimeTrialDivision(candidate)) return candidate
            candidate++
        }
    }

    private fun updateUi() {
        val nf = NumberFormat.getInstance(Locale.FRANCE)
        tvIndex.text = "n = ${nf.format(primeIndex)}"
        tvPrime.text = if (primeIndex == 0L) "Prime = -" else "Prime = ${nf.format(currentPrime)}"
        btnStartPause.text = if (running) "Pause" else "Start"
    }

    // ===== TEMPERATURES =====

    private fun requestTemperatures() {
        if (tempsInFlight) return
        tempsInFlight = true

        tempsWorker.execute {
            val temps = readAllTemperatures()
            ui.post {
                tempsInFlight = false
                applyTemperatures(temps)
            }
        }
    }

    private fun readAllTemperatures(): Map<String, Float> {
        val out = linkedMapOf<String, Float>()

        // Batterie via broadcast sticky
        readBatteryTempC()?.let { out["Battery"] = it }

        // Capteur ambiant (si dispo)
        ambientTempC?.let { out["Ambient sensor"] = it }

        // Thermal zones sysfs (best effort)
        out.putAll(readThermalZonesSysfs(limit = 64))

        return out
    }

    private fun readBatteryTempC(): Float? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths == Int.MIN_VALUE) return null
        return tenths / 10f
    }

    private fun readThermalZonesSysfs(limit: Int): Map<String, Float> {
        val out = linkedMapOf<String, Float>()
        return try {
            val base = File("/sys/class/thermal")
            val zones = base.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("thermal_zone") }
                ?.sortedBy { it.name }
                ?.take(limit)
                .orEmpty()

            for (z in zones) {
                val typeFile = File(z, "type")
                val tempFile = File(z, "temp")
                if (!tempFile.canRead()) continue

                val type = runCatching { typeFile.readText().trim() }.getOrNull().orEmpty()
                val raw = runCatching { tempFile.readText().trim() }.getOrNull() ?: continue
                val v = raw.toFloatOrNull() ?: continue

                // souvent en milli°C
                val c = if (v > 1000f) v / 1000f else v

                val label = if (type.isNotBlank()) "${z.name} ($type)" else z.name
                out[label] = c
            }
            out
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun applyTemperatures(temps: Map<String, Float>) {
        val now = System.currentTimeMillis()

        // MAJ historique rolling 2 min
        for ((k, v) in temps) {
            val q = history.getOrPut(k) { ArrayDeque() }
            q.addLast(TempSample(now, v))
            while (q.size > maxPoints) q.removeFirst()
        }

        // MAJ sources spinner
        val keys = temps.keys.sorted()
        var changed = false
        for (k in keys) {
            if (!sources.contains(k)) {
                sources.add(k)
                changed = true
            }
        }
        sources.sort()
        if (changed) sourcesAdapter.notifyDataSetChanged()

        // Choix par défaut
        if (selectedSource == null) {
            selectedSource = when {
                sources.contains("Battery") -> "Battery"
                sources.isNotEmpty() -> sources[0]
                else -> null
            }
        }

        // Sync spinner sur selectedSource
        selectedSource?.let { sel ->
            val idx = sources.indexOf(sel)
            if (idx >= 0 && spTempSource.selectedItemPosition != idx) {
                spTempSource.setSelection(idx)
            }
        }

        // Texte: toutes les températures
        val sb = StringBuilder()
        val sorted = temps.entries.sortedBy { it.key }
        for ((k, v) in sorted) {
            sb.append(k)
                .append(" : ")
                .append(String.format(Locale.FRANCE, "%.1f", v))
                .append(" °C\n")
        }
        tvTemps.text = sb.toString().trimEnd()

        refreshChart()
    }

    private fun refreshChart() {
        val sel = selectedSource ?: run {
            chartTemp.setSeries("Aucune source", emptyList())
            return
        }
        val data = history[sel]?.toList().orEmpty()
        chartTemp.setSeries(sel, data)
    }
}
