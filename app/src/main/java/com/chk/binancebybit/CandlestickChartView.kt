package com.chk.binancebybit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CandlestickChartView(context: Context) : View(context) {
    private var snapshot: IndicatorSnapshot? = null
    private var visibleCount = 70
    private var offsetFromEnd = 0
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 43, 51); strokeWidth = 1f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 160, 172); textSize = 24f }
    private val up = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(52, 199, 137); strokeWidth = 2f }
    private val down = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 83, 80); strokeWidth = 2f }
    private val ema20Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 142, 30); strokeWidth = 2.5f; style = Paint.Style.STROKE }
    private val ema50Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(93, 148, 255); strokeWidth = 2.5f; style = Paint.Style.STROKE }
    private val bbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(176, 126, 255); strokeWidth = 1.6f; style = Paint.Style.STROKE; alpha = 180 }
    private val pricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 235, 240); strokeWidth = 1.4f; textSize = 22f }

    private var lastX = 0f
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            visibleCount = (visibleCount / detector.scaleFactor).toInt().coerceIn(25, 160)
            offsetFromEnd = offsetFromEnd.coerceAtMost(maxOffset())
            invalidate()
            return true
        }
    })

    fun setSnapshot(value: IndicatorSnapshot) {
        snapshot = value
        offsetFromEnd = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(9, 11, 14))
        val s = snapshot ?: run {
            canvas.drawText("Chargement du graphique…", 30f, 60f, textPaint)
            return
        }
        val all = s.candles
        if (all.size < 5) return
        val end = (all.size - offsetFromEnd).coerceIn(1, all.size)
        val start = (end - visibleCount).coerceAtLeast(0)
        val candles = all.subList(start, end)
        if (candles.isEmpty()) return

        val left = 18f
        val right = width - 105f
        val top = 25f
        val bottom = height * 0.82f
        val volumeTop = bottom + 12f
        val volumeBottom = height - 26f
        val maxPrice = candles.maxOf { it.high }
        val minPrice = candles.minOf { it.low }
        val pad = (maxPrice - minPrice).coerceAtLeast(maxPrice * 0.001) * 0.08
        val hi = maxPrice + pad
        val lo = minPrice - pad
        val priceRange = (hi - lo).coerceAtLeast(1e-12)
        fun y(p: Double): Float = (bottom - ((p - lo) / priceRange * (bottom - top))).toFloat()
        val cell = (right - left) / candles.size

        for (i in 0..4) {
            val yy = top + (bottom - top) * i / 4f
            canvas.drawLine(left, yy, right, yy, grid)
            val p = hi - priceRange * i / 4.0
            canvas.drawText(format(p), right + 8f, yy + 8f, textPaint)
        }
        for (i in 0..4) {
            val xx = left + (right - left) * i / 4f
            canvas.drawLine(xx, top, xx, bottom, grid)
        }

        val maxVol = candles.maxOf { it.volume }.coerceAtLeast(1e-12)
        candles.forEachIndexed { i, c ->
            val x = left + cell * (i + 0.5f)
            val paint = if (c.close >= c.open) up else down
            canvas.drawLine(x, y(c.high), x, y(c.low), paint)
            val half = max(1.8f, cell * 0.32f)
            val y1 = y(c.open)
            val y2 = y(c.close)
            canvas.drawRect(x - half, min(y1, y2), x + half, max(y1, y2).coerceAtLeast(min(y1, y2) + 2f), paint)
            val vh = ((c.volume / maxVol) * (volumeBottom - volumeTop)).toFloat()
            val vp = Paint(paint).apply { alpha = 120 }
            canvas.drawRect(x - half, volumeBottom - vh, x + half, volumeBottom, vp)
        }

        val closes = all.map { it.close }
        drawSeries(canvas, ema(closes, 20), start, end, left, cell, ::y, ema20Paint)
        drawSeries(canvas, ema(closes, 50), start, end, left, cell, ::y, ema50Paint)
        val bbs = rollingBollinger(closes, 20)
        drawSeries(canvas, bbs.map { it.first }, start, end, left, cell, ::y, bbPaint)
        drawSeries(canvas, bbs.map { it.third }, start, end, left, cell, ::y, bbPaint)

        val py = y(s.lastPrice)
        canvas.drawLine(left, py, right, py, pricePaint)
        canvas.drawText(" ${format(s.lastPrice)}", right + 4f, py - 6f, pricePaint)
        canvas.drawText("Volume", left, volumeTop + 22f, textPaint)
    }

    private fun drawSeries(canvas: Canvas, values: List<Double>, start: Int, end: Int, left: Float, cell: Float, mapper: (Double) -> Float, paint: Paint) {
        if (values.isEmpty()) return
        val path = Path()
        var moved = false
        for (idx in start until end) {
            if (idx !in values.indices) continue
            val v = values[idx]
            if (!v.isFinite()) continue
            val x = left + cell * (idx - start + 0.5f)
            val yy = mapper(v)
            if (!moved) { path.moveTo(x, yy); moved = true } else path.lineTo(x, yy)
        }
        if (moved) canvas.drawPath(path, paint)
    }

    private fun ema(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val out = ArrayList<Double>(values.size)
        val k = 2.0 / (period + 1.0)
        var e = values.first()
        values.forEachIndexed { i, v ->
            e = if (i == 0) v else v * k + e * (1 - k)
            out += e
        }
        return out
    }

    private fun rollingBollinger(values: List<Double>, period: Int): List<Triple<Double, Double, Double>> {
        val out = ArrayList<Triple<Double, Double, Double>>(values.size)
        for (i in values.indices) {
            val from = (i - period + 1).coerceAtLeast(0)
            val w = values.subList(from, i + 1)
            val mid = w.average()
            val sd = kotlin.math.sqrt(w.sumOf { (it - mid) * (it - mid) } / w.size)
            out += Triple(mid + 2 * sd, mid, mid - 2 * sd)
        }
        return out
    }

    private fun maxOffset(): Int {
        val n = snapshot?.candles?.size ?: 0
        return (n - visibleCount).coerceAtLeast(0)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> lastX = event.x
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress) {
                val dx = event.x - lastX
                val threshold = width / max(visibleCount, 1).toFloat()
                if (abs(dx) > threshold) {
                    val shift = (abs(dx) / threshold).toInt().coerceAtLeast(1)
                    offsetFromEnd = if (dx > 0) (offsetFromEnd + shift).coerceAtMost(maxOffset()) else (offsetFromEnd - shift).coerceAtLeast(0)
                    lastX = event.x
                    invalidate()
                }
            }
        }
        return true
    }

    private fun format(v: Double): String = when {
        v >= 1000 -> String.format(java.util.Locale.US, "%.2f", v)
        v >= 1 -> String.format(java.util.Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
        else -> String.format(java.util.Locale.US, "%.7f", v).trimEnd('0').trimEnd('.')
    }
}
