package com.chk.binancebybit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ChartTradeMarker(
    val time: Long,
    val price: Double,
    val side: String,
    val label: String = ""
)

data class ChartOrderLevel(
    val price: Double,
    val side: String,
    val label: String = ""
)

class CandlestickChartView(context: Context) : View(context) {
    private var snapshot: IndicatorSnapshot? = null
    private var tradeMarkers: List<ChartTradeMarker> = emptyList()
    private var orderLevels: List<ChartOrderLevel> = emptyList()
    private var visibleCount = 72
    private var offsetFromEnd = 0
    private var selectedGlobalIndex: Int? = null
    private var selectedPrice: Double? = null
    private var alertRequest: ((String, Double) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(37, 42, 49); strokeWidth = dp(0.6f) }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 160, 172); textSize = dp(9.5f) }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(180, 188, 199); textSize = dp(8.5f) }
    private val up = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 200, 137); strokeWidth = dp(1.25f) }
    private val down = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 83, 80); strokeWidth = dp(1.25f) }
    private val ema20Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 142, 30); strokeWidth = dp(1.3f); style = Paint.Style.STROKE }
    private val ema50Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(93, 148, 255); strokeWidth = dp(1.3f); style = Paint.Style.STROKE }
    private val bbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(176, 126, 255); strokeWidth = dp(0.9f); style = Paint.Style.STROKE; alpha = 165 }
    private val currentPricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 235, 240); strokeWidth = dp(0.8f); textSize = dp(9f) }
    private val crosshair = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 198, 210); strokeWidth = dp(0.8f); pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f) }
    private val supportPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(93, 148, 255); strokeWidth = dp(0.9f); pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f); alpha = 190 }
    private val resistancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 142, 30); strokeWidth = dp(0.9f); pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f); alpha = 190 }
    private val orderBuyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(57, 197, 128); strokeWidth = dp(1f); pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f) }
    private val orderSellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(238, 91, 91); strokeWidth = dp(1f); pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f) }
    private val tooltipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 23, 27, 33) }

    private var lastTouchX = 0f

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            visibleCount = (visibleCount / detector.scaleFactor).toInt().coerceIn(20, 180)
            offsetFromEnd = offsetFromEnd.coerceAtMost(maxOffset())
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            lastTouchX = e.x
            updateSelection(e.x, e.y)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            updateSelection(e.x, e.y)
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            visibleCount = 72
            offsetFromEnd = 0
            selectedGlobalIndex = null
            selectedPrice = null
            invalidate()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            updateSelection(e.x, e.y)
            val s = snapshot ?: return
            val p = selectedPrice ?: return
            if (p > 0.0) alertRequest?.invoke(s.requestedSymbol, p)
        }
    })

    fun setSnapshot(value: IndicatorSnapshot) {
        snapshot = value
        offsetFromEnd = 0
        selectedGlobalIndex = null
        selectedPrice = null
        invalidate()
    }

    fun setTradeMarkers(value: List<ChartTradeMarker>) {
        tradeMarkers = value
        invalidate()
    }

    fun setOrderLevels(value: List<ChartOrderLevel>) {
        orderLevels = value
        invalidate()
    }

    fun setOnAlertRequestListener(listener: ((String, Double) -> Unit)?) {
        alertRequest = listener
    }

    fun selectedTargetPrice(): Double? = selectedPrice

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(9, 11, 14))
        val s = snapshot ?: run {
            canvas.drawText("Chargement du graphique…", dp(16f), dp(34f), axisText)
            return
        }
        val all = s.candles
        if (all.size < 5) return

        val end = (all.size - offsetFromEnd).coerceIn(1, all.size)
        val start = (end - visibleCount).coerceAtLeast(0)
        val candles = all.subList(start, end)
        if (candles.isEmpty()) return

        val left = dp(10f)
        val right = width - dp(72f)
        val top = dp(14f)
        val priceBottom = height * 0.73f
        val volumeTop = priceBottom + dp(10f)
        val volumeBottom = height - dp(34f)
        val timeY = height - dp(9f)

        val maxPrice0 = candles.maxOf { it.high }
        val minPrice0 = candles.minOf { it.low }
        val range0 = (maxPrice0 - minPrice0).coerceAtLeast(maxPrice0 * 0.0005).coerceAtLeast(1e-12)
        val hi = maxPrice0 + range0 * 0.08
        val lo = minPrice0 - range0 * 0.08
        val priceRange = (hi - lo).coerceAtLeast(1e-12)
        fun y(price: Double): Float = (priceBottom - ((price - lo) / priceRange * (priceBottom - top))).toFloat()
        val cell = (right - left) / candles.size

        for (i in 0..5) {
            val yy = top + (priceBottom - top) * i / 5f
            canvas.drawLine(left, yy, right, yy, grid)
            val p = hi - priceRange * i / 5.0
            canvas.drawText(format(p), right + dp(5f), yy + dp(3f), axisText)
        }

        for (i in 0..4) {
            val xx = left + (right - left) * i / 4f
            canvas.drawLine(xx, top, xx, volumeBottom, grid)
            val idx = (start + ((candles.size - 1) * i / 4.0).toInt()).coerceIn(start, end - 1)
            val label = timeLabel(all[idx].time, s.interval)
            val w = axisText.measureText(label)
            canvas.drawText(label, (xx - w / 2f).coerceAtLeast(left), timeY, axisText)
        }

        drawHorizontalLevel(canvas, s.support, left, right, lo, hi, ::y, supportPaint, "S ${format(s.support)}")
        drawHorizontalLevel(canvas, s.resistance, left, right, lo, hi, ::y, resistancePaint, "R ${format(s.resistance)}")
        orderLevels.forEach { level ->
            val paint = if (level.side.uppercase(Locale.US) == "BUY") orderBuyPaint else orderSellPaint
            val prefix = if (level.side.uppercase(Locale.US) == "BUY") "BUY" else "SELL"
            drawHorizontalLevel(canvas, level.price, left, right, lo, hi, ::y, paint, level.label.ifBlank { "$prefix ${format(level.price)}" })
        }

        val maxVol = candles.maxOf { it.volume }.coerceAtLeast(1e-12)
        candles.forEachIndexed { i, c ->
            val x = left + cell * (i + 0.5f)
            val paint = if (c.close >= c.open) up else down
            canvas.drawLine(x, y(c.high), x, y(c.low), paint)
            val half = max(dp(1.2f), cell * 0.32f)
            val y1 = y(c.open)
            val y2 = y(c.close)
            canvas.drawRect(x - half, min(y1, y2), x + half, max(y1, y2).coerceAtLeast(min(y1, y2) + dp(1f)), paint)
            val vh = ((c.volume / maxVol) * (volumeBottom - volumeTop)).toFloat()
            val vp = Paint(paint).apply { alpha = 105 }
            canvas.drawRect(x - half, volumeBottom - vh, x + half, volumeBottom, vp)
        }

        val closes = all.map { it.close }
        drawSeries(canvas, ema(closes, 20), start, end, left, cell, ::y, ema20Paint)
        drawSeries(canvas, ema(closes, 50), start, end, left, cell, ::y, ema50Paint)
        val bbs = rollingBollinger(closes, 20)
        drawSeries(canvas, bbs.map { it.first }, start, end, left, cell, ::y, bbPaint)
        drawSeries(canvas, bbs.map { it.third }, start, end, left, cell, ::y, bbPaint)

        tradeMarkers.forEach { marker ->
            val idx = nearestIndexByTime(all, marker.time)
            if (idx !in start until end) return@forEach
            val c = all[idx]
            val x = left + cell * (idx - start + 0.5f)
            val py = y(marker.price.takeIf { it > 0.0 } ?: c.close)
            val markerPaint = if (marker.side.uppercase(Locale.US) == "BUY") up else down
            canvas.drawCircle(x, py, dp(4.5f), markerPaint)
            val letter = if (marker.side.uppercase(Locale.US) == "BUY") "A" else "V"
            val tp = Paint(smallText).apply { color = Color.WHITE; textSize = dp(7f); textAlign = Paint.Align.CENTER }
            canvas.drawText(letter, x, py + dp(2.4f), tp)
        }

        val currentY = y(s.lastPrice)
        canvas.drawLine(left, currentY, right, currentY, currentPricePaint)
        val priceLabel = format(s.lastPrice)
        val box = RectF(right + dp(2f), currentY - dp(9f), width - dp(2f), currentY + dp(8f))
        canvas.drawRoundRect(box, dp(3f), dp(3f), Paint().apply { color = Color.rgb(67, 76, 90) })
        val centered = Paint(currentPricePaint).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(8.5f) }
        canvas.drawText(priceLabel, box.centerX(), currentY + dp(3f), centered)

        canvas.drawText("VOL", left, volumeTop + dp(9f), smallText)

        selectedGlobalIndex?.let { globalIdx ->
            if (globalIdx in start until end) {
                val c = all[globalIdx]
                val x = left + cell * (globalIdx - start + 0.5f)
                val p = selectedPrice ?: c.close
                val cy = y(p.coerceIn(lo, hi))
                canvas.drawLine(x, top, x, volumeBottom, crosshair)
                canvas.drawLine(left, cy, right, cy, crosshair)
                drawTooltip(canvas, c, s.interval, x, top, right)
                val pText = format(p)
                val pBox = RectF(right + dp(2f), cy - dp(8f), width - dp(2f), cy + dp(8f))
                canvas.drawRoundRect(pBox, dp(3f), dp(3f), tooltipBg)
                canvas.drawText(pText, pBox.centerX(), cy + dp(3f), centered)
            }
        }
    }

    private fun drawHorizontalLevel(
        canvas: Canvas,
        value: Double,
        left: Float,
        right: Float,
        lo: Double,
        hi: Double,
        mapper: (Double) -> Float,
        paint: Paint,
        label: String
    ) {
        if (value <= lo || value >= hi || !value.isFinite()) return
        val yy = mapper(value)
        canvas.drawLine(left, yy, right, yy, paint)
        val labelPaint = Paint(smallText).apply { color = paint.color; textAlign = Paint.Align.RIGHT }
        canvas.drawText(label.take(22), right - dp(3f), yy - dp(2f), labelPaint)
    }

    private fun drawTooltip(canvas: Canvas, c: MarketCandle, interval: String, x: Float, top: Float, right: Float) {
        val lines = listOf(
            fullTimeLabel(c.time, interval),
            "O ${format(c.open)}  H ${format(c.high)}",
            "L ${format(c.low)}  C ${format(c.close)}",
            "Vol ${formatVolume(c.volume)}"
        )
        val width = lines.maxOf { smallText.measureText(it) } + dp(14f)
        val height = dp(48f)
        val left = if (x + width + dp(8f) < right) x + dp(8f) else (x - width - dp(8f)).coerceAtLeast(dp(8f))
        val rect = RectF(left, top + dp(5f), left + width, top + dp(5f) + height)
        canvas.drawRoundRect(rect, dp(6f), dp(6f), tooltipBg)
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, rect.left + dp(7f), rect.top + dp(11f) + i * dp(10f), smallText)
        }
    }

    private fun updateSelection(x: Float, y: Float) {
        val s = snapshot ?: return
        val all = s.candles
        if (all.isEmpty() || width <= 0 || height <= 0) return
        val end = (all.size - offsetFromEnd).coerceIn(1, all.size)
        val start = (end - visibleCount).coerceAtLeast(0)
        val count = end - start
        if (count <= 0) return
        val left = dp(10f)
        val right = width - dp(72f)
        val top = dp(14f)
        val priceBottom = height * 0.73f
        if (x !in left..right || y < top || y > priceBottom) return
        val cell = (right - left) / count
        val local = ((x - left) / cell).toInt().coerceIn(0, count - 1)
        selectedGlobalIndex = start + local
        val candles = all.subList(start, end)
        val maxPrice0 = candles.maxOf { it.high }
        val minPrice0 = candles.minOf { it.low }
        val range0 = (maxPrice0 - minPrice0).coerceAtLeast(maxPrice0 * 0.0005).coerceAtLeast(1e-12)
        val hi = maxPrice0 + range0 * 0.08
        val lo = minPrice0 - range0 * 0.08
        val ratio = ((priceBottom - y) / (priceBottom - top)).coerceIn(0f, 1f).toDouble()
        selectedPrice = lo + ratio * (hi - lo)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(event.actionMasked == MotionEvent.ACTION_MOVE || scaler.isInProgress)
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> lastTouchX = event.x
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && event.pointerCount == 1) {
                val dx = event.x - lastTouchX
                val threshold = (width - dp(82f)) / max(visibleCount, 1).toFloat()
                if (abs(dx) > threshold * 1.4f) {
                    val shift = (abs(dx) / threshold).toInt().coerceAtLeast(1)
                    offsetFromEnd = if (dx > 0) (offsetFromEnd + shift).coerceAtMost(maxOffset()) else (offsetFromEnd - shift).coerceAtLeast(0)
                    selectedGlobalIndex = null
                    selectedPrice = null
                    lastTouchX = event.x
                    invalidate()
                } else {
                    updateSelection(event.x, event.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
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

    private fun nearestIndexByTime(candles: List<MarketCandle>, time: Long): Int {
        if (candles.isEmpty()) return -1
        var best = 0
        var delta = Long.MAX_VALUE
        candles.forEachIndexed { i, c ->
            val d = kotlin.math.abs(c.time - time)
            if (d < delta) { delta = d; best = i }
        }
        return best
    }

    private fun timeLabel(time: Long, interval: String): String {
        val pattern = when (interval) {
            "1m", "5m", "15m", "1h", "4h" -> "HH:mm"
            "1d" -> "dd MMM"
            else -> "MMM yy"
        }
        return SimpleDateFormat(pattern, Locale.FRANCE).format(Date(time))
    }

    private fun fullTimeLabel(time: Long, interval: String): String {
        val pattern = if (interval == "1w") "dd MMM yyyy" else "dd MMM yyyy HH:mm"
        return SimpleDateFormat(pattern, Locale.FRANCE).format(Date(time))
    }

    private fun formatVolume(v: Double): String = when {
        v >= 1_000_000_000 -> String.format(Locale.US, "%.2fB", v / 1_000_000_000.0)
        v >= 1_000_000 -> String.format(Locale.US, "%.2fM", v / 1_000_000.0)
        v >= 1_000 -> String.format(Locale.US, "%.1fK", v / 1_000.0)
        else -> String.format(Locale.US, "%.2f", v)
    }

    private fun format(v: Double): String = when {
        v >= 1000 -> String.format(Locale.US, "%.2f", v)
        v >= 100 -> String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
        v >= 1 -> String.format(Locale.US, "%.5f", v).trimEnd('0').trimEnd('.')
        else -> String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
    }
}
