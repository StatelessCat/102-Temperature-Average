package com.example.a102

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

data class TempSample(val tMillis: Long, val celsius: Float)

class TemperatureChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var label: String = ""
    private var samples: List<TempSample> = emptyList()

    // ==== Réglages "time-warp" ====
    // Plus tauMs est petit => plus on "zoome" sur la droite (temps récents).
    // 1000ms (1s) = très réactif sur les dernières secondes, compression forte du passé.
    private val tauMs: Float = 1000f

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f
    }

    fun setSeries(label: String, samples: List<TempSample>) {
        this.label = label
        this.samples = samples
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val padL = paddingLeft.toFloat().coerceAtLeast(24f)
        val padT = paddingTop.toFloat().coerceAtLeast(24f)
        val padR = paddingRight.toFloat().coerceAtLeast(24f)
        val padB = paddingBottom.toFloat().coerceAtLeast(24f)

        val plot = RectF(
            padL,
            padT + 40f,
            w - padR,
            h - padB
        )

        // Axes
        canvas.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, axisPaint)
        canvas.drawLine(plot.left, plot.top, plot.left, plot.bottom, axisPaint)

        if (samples.size < 2) {
            canvas.drawText(if (label.isBlank()) "Températures" else label, padL, padT + 30f, textPaint)
            canvas.drawText("Pas assez de données", padL, plot.centerY(), textPaint)
            return
        }

        // === Fenêtre temporelle (ancrée à droite = maintenant/dernier point) ===
        val tMin = samples.first().tMillis
        val tMax = samples.last().tMillis
        val maxDt = max(1L, tMax - tMin).toFloat()

        // Normalisation log : u(dt)=ln(1+dt/tau) / ln(1+maxDt/tau)
        val denom = ln(1f + (maxDt / tauMs)).coerceAtLeast(1e-6f)

        fun xFor(t: Long): Float {
            val dt = (tMax - t).coerceAtLeast(0L).toFloat() // 0 à droite, maxDt à gauche
            val u = ln(1f + (dt / tauMs)) / denom          // 0..1
            return plot.right - u * plot.width()
        }

        // === Y scale ===
        var vMin = Float.POSITIVE_INFINITY
        var vMax = Float.NEGATIVE_INFINITY
        for (s in samples) {
            vMin = min(vMin, s.celsius)
            vMax = max(vMax, s.celsius)
        }
        if (vMin == vMax) {
            vMin -= 1f
            vMax += 1f
        } else {
            val pad = (vMax - vMin) * 0.1f
            vMin -= pad
            vMax += pad
        }
        val vSpan = (vMax - vMin).coerceAtLeast(0.001f)

        fun yFor(v: Float): Float {
            return plot.bottom - ((v - vMin) / vSpan) * plot.height()
        }

        // Titre + dernière valeur
        val last = samples.last().celsius
        val title = if (label.isBlank()) "Températures" else label
        canvas.drawText("$title — ${"%.1f".format(last)} °C", padL, padT + 30f, textPaint)

        // Courbe
        val path = android.graphics.Path()
        for ((i, s) in samples.withIndex()) {
            val x = xFor(s.tMillis)
            val y = yFor(s.celsius)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)

        // Min/Max
        canvas.drawText("${"%.1f".format(vMax)}°", plot.left + 8f, plot.top + 34f, textPaint)
        canvas.drawText("${"%.1f".format(vMin)}°", plot.left + 8f, plot.bottom - 8f, textPaint)
    }
}
