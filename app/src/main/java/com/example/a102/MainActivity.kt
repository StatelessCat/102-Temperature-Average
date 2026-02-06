package com.example.a102

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    private lateinit var tvIndex: TextView
    private lateinit var tvPrime: TextView
    private lateinit var btnStartPause: Button
    private lateinit var btnNext: Button
    private lateinit var btnReset: Button

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var running = false
    private var inFlight = false
    private var generation = 0

    private var primeIndex = 0L
    private var currentPrime = 1L
    private val primes = mutableListOf<Long>() // cache des primes trouvés

    private val tickMs = 300L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // UI XML
        setContentView(R.layout.activity_main)

        tvIndex = findViewById(R.id.tvIndex)
        tvPrime = findViewById(R.id.tvPrime)
        btnStartPause = findViewById(R.id.btnStartPause)
        btnNext = findViewById(R.id.btnNext)
        btnReset = findViewById(R.id.btnReset)

        updateUi()

        btnStartPause.setOnClickListener {
            running = !running
            updateUi()
            ui.removeCallbacksAndMessages(null)
            if (running) scheduleAuto()
        }

        btnNext.setOnClickListener {
            requestNextPrime(autoReschedule = false)
        }

        btnReset.setOnClickListener {
            resetAll()
        }
    }

    private fun resetAll() {
        running = false
        ui.removeCallbacksAndMessages(null)

        generation++
        inFlight = false

        primeIndex = 0L
        currentPrime = 1L
        primes.clear()

        updateUi()
    }

    private fun scheduleAuto() {
        ui.postDelayed({ requestNextPrime(autoReschedule = true) }, tickMs)
    }

    private fun requestNextPrime(autoReschedule: Boolean) {
        if (inFlight) return
        inFlight = true
        val localGen = generation

        worker.execute {
            val next = computeNextPrime()

            ui.post {
                if (localGen != generation) return@post // reset pendant calcul
                inFlight = false
                currentPrime = next
                primeIndex++
                updateUi()
                if (running && autoReschedule) scheduleAuto()
            }
        }
    }

    private fun computeNextPrime(): Long {
        var candidate = if (currentPrime < 2L) 2L else currentPrime + 1L
        while (true) {
            if (isPrime(candidate)) {
                primes.add(candidate)
                return candidate
            }
            candidate++
        }
    }

    private fun isPrime(n: Long): Boolean {
        if (n < 2L) return false
        if (n == 2L) return true
        if (n % 2L == 0L) return false

        val limit = sqrt(n.toDouble()).toLong()

        // division par les primes déjà connus (plus rapide)
        for (p in primes) {
            if (p > limit) break
            if (n % p == 0L) return false
        }

        // au tout début (liste vide), on fait une boucle impaire simple
        if (primes.isEmpty()) {
            var d = 3L
            while (d <= limit) {
                if (n % d == 0L) return false
                d += 2L
            }
        }

        return true
    }

    private fun updateUi() {
        val nf = NumberFormat.getInstance(Locale.FRANCE)
        tvIndex.text = "n = ${nf.format(primeIndex)}"
        tvPrime.text = if (primeIndex == 0L) "Prime = -" else "Prime = ${nf.format(currentPrime)}"
        btnStartPause.text = if (running) "Pause" else "Start"
    }

    override fun onStop() {
        super.onStop()
        running = false
        ui.removeCallbacksAndMessages(null)
    }
}
