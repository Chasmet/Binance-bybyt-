package com.chk.binancebybit

import android.content.Context
import android.graphics.Bitmap
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
import android.widget.OverScroller
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    private var drawings: List<ChartDrawing> = emptyList()
    private var indicators = ChartIndicatorConfig()

    private var visibleCount = 100
    private var offsetFromEnd = 0
    private var autoScale = true
    private var priceScale = 1.0
    private var selectedGlobalIndex: Int? = null
    private var selectedPrice: Double? = null
    private var crosshairActive = false
    private var alertRequest: ((String, Double) -> Unit)? = null
    private var stateChanged: ((ChartViewportState) -> Unit)? = null

    private val maSeries = linkedMapOf<Int, List<Double>>()
    private val emaSeriesCache = linkedMapOf<Int, List<Double>>()
    private var bollingerSeries: List<Triple<Double, Double, Double>> = emptyList()
    private var rsiSeriesCache: List<Double> = emptyList()
    private var macdLine: List<Double> = emptyList()
    private var macdSignal: List<Double> = emptyList()
    private var macdHistogram: List<Double> = emptyList()
    private var atrCurrent = 0.0

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(37, 42, 49); strokeWidth = dp(0.6f) }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 160, 172); textSize = dp(9f) }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(184, 191, 202); textSize = dp(8.2f) }
    private val up = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 200, 137); strokeWidth = dp(1.2f) }
    private val down = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 83, 80); strokeWidth = dp(1.2f) }
    private val currentPricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 235, 240); strokeWidth = dp(0.8f) }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 207, 218); strokeWidth = dp(0.8f); pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f) }
    private val supportPaint = levelPaint(Color.rgb(93, 148, 255))
    private val resistancePaint = levelPaint(Color.rgb(245, 142, 30))
    private val orderBuyPaint = levelPaint(Color.rgb(57, 197, 128), 3f)
    private val orderSellPaint = levelPaint(Color.rgb(238, 91, 91), 3f)
    private val tooltipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(238, 23, 27, 33) }
    private val paneBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(12, 15, 19) }

    private val linePalette = intArrayOf(
        Color.rgb(255, 213, 79), Color.rgb(0, 188, 212), Color.rgb(186, 104, 200),
        Color.rgb(245, 142, 30), Color.rgb(93, 148, 255), Color.rgb(76, 175, 80), Color.rgb(239, 83, 80)
    )

    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var panAccumulator = 0f
    private var priceAxisScaling = false
    private var moved = false

    private val scroller = OverScroller(context)
    private var lastScrollerX = 0

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var anchorRatio = 0.5f
        private var anchorGlobal = 0.0

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            stopFling()
            parent?.requestDisallowInterceptTouchEvent(true)
            val all = snapshot?.candles ?: return false
            if (all.isEmpty()) return false
            val left = plotLeft()
            val right = plotRight()
            anchorRatio = ((detector.focusX - left) / (right - left).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val end = visibleEnd(all.size)
            val start = visibleStart(end)
            anchorGlobal = start + anchorRatio * (end - start).coerceAtLeast(1)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val all = snapshot?.candles ?: return false
            if (all.isEmpty()) return false
            val newCount = (visibleCount / detector.scaleFactor).roundToInt().coerceIn(MIN_VISIBLE, min(MAX_VISIBLE, all.size.coerceAtLeast(MIN_VISIBLE)))
            if (newCount == visibleCount) return true
            visibleCount = newCount
            val maxStart = (all.size - visibleCount).coerceAtLeast(0)
            val newStart = (anchorGlobal - anchorRatio * visibleCount).roundToInt().coerceIn(0, maxStart)
            offsetFromEnd = (all.size - (newStart + visibleCount)).coerceIn(0, maxOffset())
            clearCrosshair()
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            emitStateChanged()
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            stopFling()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!moved && e.x < plotRight()) {
                crosshairActive = true
                updateSelection(e.x, e.y)
                emitStateChanged()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetView()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (e.x <= plotRight()) {
                crosshairActive = true
                updateSelection(e.x, e.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                emitStateChanged()
            }
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (crosshairActive || priceAxisScaling || scaler.isInProgress || abs(velocityX) <= abs(velocityY) * 1.15f) return false
            lastScrollerX = 0
            scroller.fling(0, 0, velocityX.toInt(), 0, -width * 8, width * 8, 0, 0)
            postInvalidateOnAnimation()
            return true
        }
    })

    init {
        isClickable = true
        isFocusable = true
    }

    fun setSnapshot(value: IndicatorSnapshot, preserveViewport: Boolean = true) {
        val sameChart = snapshot?.requestedSymbol == value.requestedSymbol && snapshot?.interval == value.interval
        snapshot = value
        if (!preserveViewport || !sameChart) {
            offsetFromEnd = 0
            visibleCount = min(100, value.candles.size).coerceAtLeast(MIN_VISIBLE.coerceAtMost(value.candles.size))
            clearCrosshair()
        } else {
            visibleCount = visibleCount.coerceIn(MIN_VISIBLE.coerceAtMost(value.candles.size), min(MAX_VISIBLE, value.candles.size).coerceAtLeast(1))
            offsetFromEnd = offsetFromEnd.coerceIn(0, maxOffset())
        }
        prepareIndicators()
        invalidate()
    }

    fun setTradeMarkers(value: List<ChartTradeMarker>) { tradeMarkers = value; invalidate() }
    fun setOrderLevels(value: List<ChartOrderLevel>) { orderLevels = value; invalidate() }
    fun setDrawings(value: List<ChartDrawing>) { drawings = value.take(100); invalidate(); emitStateChanged() }
    fun currentDrawings(): List<ChartDrawing> = drawings.toList()

    fun addDrawing(value: ChartDrawing) {
        drawings = (drawings.filterNot { it.id == value.id } + value).takeLast(100)
        invalidate(); emitStateChanged()
    }

    fun updateDrawing(value: ChartDrawing) {
        drawings = drawings.map { if (it.id == value.id) value else it }
        invalidate(); emitStateChanged()
    }

    fun removeDrawing(id: String) {
        drawings = drawings.filterNot { it.id == id }
        invalidate(); emitStateChanged()
    }

    fun clearDrawings() { drawings = emptyList(); invalidate(); emitStateChanged() }

    fun setIndicators(value: ChartIndicatorConfig) {
        indicators = value
        prepareIndicators()
        invalidate()
        emitStateChanged()
    }

    fun currentIndicators(): ChartIndicatorConfig = indicators
    fun setOnAlertRequestListener(listener: ((String, Double) -> Unit)?) { alertRequest = listener }
    fun setOnStateChangedListener(listener: ((ChartViewportState) -> Unit)?) { stateChanged = listener }
    fun selectedTargetPrice(): Double? = selectedPrice

    fun exportViewport(): ChartViewportState {
        val selectedTime = selectedGlobalIndex?.let { snapshot?.candles?.getOrNull(it)?.time }
        return ChartViewportState(
            visibleCount = visibleCount,
            offsetFromEnd = offsetFromEnd,
            autoScale = autoScale,
            priceScale = priceScale,
            crosshair = ChartCrosshairState(crosshairActive, selectedTime, selectedPrice)
        )
    }

    fun applyViewport(value: ChartViewportState) {
        visibleCount = value.visibleCount.coerceIn(MIN_VISIBLE, MAX_VISIBLE)
        offsetFromEnd = value.offsetFromEnd.coerceIn(0, maxOffset())
        autoScale = value.autoScale
        priceScale = value.priceScale.coerceIn(0.2, 8.0)
        val ch = value.crosshair
        if (ch.active) setCrosshair(ch.timestamp, ch.price, notify = false) else clearCrosshair()
        invalidate()
    }

    fun zoomIn() = zoomBy(1.35f)
    fun zoomOut() = zoomBy(0.74f)

    private fun zoomBy(factor: Float) {
        val all = snapshot?.candles ?: return
        if (all.isEmpty()) return
        val end = visibleEnd(all.size)
        val start = visibleStart(end)
        val center = (start + end) / 2.0
        visibleCount = (visibleCount / factor).roundToInt().coerceIn(MIN_VISIBLE.coerceAtMost(all.size), min(MAX_VISIBLE, all.size).coerceAtLeast(1))
        val maxStart = (all.size - visibleCount).coerceAtLeast(0)
        val newStart = (center - visibleCount / 2.0).roundToInt().coerceIn(0, maxStart)
        offsetFromEnd = (all.size - newStart - visibleCount).coerceIn(0, maxOffset())
        clearCrosshair(); invalidate(); emitStateChanged()
    }

    fun panLeft(candles: Int = max(1, visibleCount / 5)) {
        offsetFromEnd = (offsetFromEnd + candles).coerceAtMost(maxOffset())
        clearCrosshair(); invalidate(); emitStateChanged()
    }

    fun panRight(candles: Int = max(1, visibleCount / 5)) {
        offsetFromEnd = (offsetFromEnd - candles).coerceAtLeast(0)
        clearCrosshair(); invalidate(); emitStateChanged()
    }

    fun goToLatest() {
        offsetFromEnd = 0
        clearCrosshair()
        invalidate(); emitStateChanged()
    }

    fun resetView() {
        visibleCount = min(100, snapshot?.candles?.size ?: 100).coerceAtLeast(MIN_VISIBLE.coerceAtMost(snapshot?.candles?.size ?: MIN_VISIBLE))
        offsetFromEnd = 0
        autoScale = true
        priceScale = 1.0
        clearCrosshair()
        stopFling()
        invalidate(); emitStateChanged()
    }

    fun setAutoScale(enabled: Boolean) {
        autoScale = enabled
        if (enabled) priceScale = 1.0
        invalidate(); emitStateChanged()
    }

    fun setCrosshair(timestamp: Long?, price: Double?, notify: Boolean = true) {
        val all = snapshot?.candles ?: return
        if (all.isEmpty()) return
        crosshairActive = true
        val idx = if (timestamp != null && timestamp > 0L) nearestIndexByTime(all, timestamp) else visibleEnd(all.size) - 1
        selectedGlobalIndex = idx.coerceIn(0, all.lastIndex)
        selectedPrice = price?.takeIf { it.isFinite() && it > 0.0 } ?: all[selectedGlobalIndex!!].close
        centerIndexIfNeeded(selectedGlobalIndex!!)
        invalidate()
        if (notify) emitStateChanged()
    }

    fun visibleRange(): Pair<Long, Long>? {
        val all = snapshot?.candles ?: return null
        if (all.isEmpty()) return null
        val end = visibleEnd(all.size)
        val start = visibleStart(end)
        return (all[start].time to all[end - 1].time)
    }

    fun capturePng(): ByteArray {
        if (width <= 0 || height <= 0) return ByteArray(0)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        draw(canvas)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(9, 11, 14))
        val s = snapshot ?: run {
            canvas.drawText("Chargement du graphique…", dp(16f), dp(34f), axisText)
            return
        }
        val all = s.candles
        if (all.size < 2) return

        val end = visibleEnd(all.size)
        val start = visibleStart(end)
        val candles = all.subList(start, end)
        if (candles.isEmpty()) return

        val left = plotLeft()
        val right = plotRight()
        val top = dp(12f)
        val timeBottom = height - dp(22f)
        val priceBottom = height * 0.58f
        val volumeTop = priceBottom + dp(6f)
        val volumeBottom = height * 0.70f
        val rsiTop = volumeBottom + dp(5f)
        val rsiBottom = height * 0.83f
        val macdTop = rsiBottom + dp(5f)
        val macdBottom = timeBottom

        val baseMax = candles.maxOf { it.high }
        val baseMin = candles.minOf { it.low }
        val rawRange = (baseMax - baseMin).coerceAtLeast(baseMax * 0.0005).coerceAtLeast(1e-12)
        val center = (baseMax + baseMin) / 2.0
        val appliedRange = if (autoScale) rawRange * 1.16 else rawRange * 1.16 * priceScale
        val hi = center + appliedRange / 2.0
        val lo = center - appliedRange / 2.0
        val priceRange = (hi - lo).coerceAtLeast(1e-12)
        fun y(price: Double): Float = (priceBottom - ((price - lo) / priceRange * (priceBottom - top))).toFloat()
        val cell = (right - left) / candles.size.coerceAtLeast(1)

        canvas.drawRect(left, volumeTop, right, volumeBottom, paneBg)
        canvas.drawRect(left, rsiTop, right, rsiBottom, paneBg)
        canvas.drawRect(left, macdTop, right, macdBottom, paneBg)

        drawGridAndAxes(canvas, all, s.interval, start, end, left, right, top, priceBottom, volumeBottom, timeBottom, hi, lo)

        drawHorizontalLevel(canvas, s.support, left, right, lo, hi, ::y, supportPaint, "S ${format(s.support)}")
        drawHorizontalLevel(canvas, s.resistance, left, right, lo, hi, ::y, resistancePaint, "R ${format(s.resistance)}")
        orderLevels.forEach { level ->
            val buy = level.side.uppercase(Locale.US) == "BUY"
            drawHorizontalLevel(canvas, level.price, left, right, lo, hi, ::y, if (buy) orderBuyPaint else orderSellPaint, level.label.ifBlank { "${if (buy) "BUY" else "SELL"} ${format(level.price)}" })
        }
        drawTechnicalDrawings(canvas, all, start, end, left, right, lo, hi, ::y)

        val maxVol = candles.maxOf { it.volume }.coerceAtLeast(1e-12)
        candles.forEachIndexed { i, c ->
            val x = left + cell * (i + 0.5f)
            val paint = if (c.close >= c.open) up else down
            canvas.drawLine(x, y(c.high), x, y(c.low), paint)
            val half = max(dp(1.15f), min(cell * 0.34f, dp(8f)))
            val y1 = y(c.open)
            val y2 = y(c.close)
            canvas.drawRect(x - half, min(y1, y2), x + half, max(y1, y2).coerceAtLeast(min(y1, y2) + dp(1f)), paint)
            if (indicators.volume) {
                val vh = ((c.volume / maxVol) * (volumeBottom - volumeTop - dp(4f))).toFloat()
                val vp = Paint(paint).apply { alpha = 110 }
                canvas.drawRect(x - half, volumeBottom - vh, x + half, volumeBottom, vp)
            }
        }

        var paletteIndex = 0
        indicators.maPeriods.forEach { period ->
            maSeries[period]?.let { values ->
                val paint = linePaint(linePalette[paletteIndex++ % linePalette.size], 1.1f)
                drawSeries(canvas, values, start, end, left, cell, ::y, paint)
            }
        }
        indicators.emaPeriods.forEach { period ->
            emaSeriesCache[period]?.let { values ->
                val paint = linePaint(linePalette[paletteIndex++ % linePalette.size], 1.35f)
                drawSeries(canvas, values, start, end, left, cell, ::y, paint)
            }
        }
        if (indicators.bollinger && bollingerSeries.isNotEmpty()) {
            val p = linePaint(Color.rgb(176, 126, 255), 0.85f).apply { alpha = 175 }
            drawSeries(canvas, bollingerSeries.map { it.first }, start, end, left, cell, ::y, p)
            drawSeries(canvas, bollingerSeries.map { it.third }, start, end, left, cell, ::y, p)
        }

        drawTradeMarkers(canvas, all, start, end, left, cell, ::y)
        drawCurrentPrice(canvas, s.lastPrice, left, right, lo, hi, ::y)
        if (indicators.volume) canvas.drawText("VOL", left + dp(3f), volumeTop + dp(10f), smallText)
        drawRsi(canvas, start, end, left, right, rsiTop, rsiBottom)
        drawMacd(canvas, start, end, left, right, macdTop, macdBottom)
        canvas.drawText("ATR ${indicators.atrPeriod}: ${format(atrCurrent)}", right - dp(3f), top + dp(10f), Paint(smallText).apply { textAlign = Paint.Align.RIGHT })

        drawCrosshair(canvas, all, start, end, left, right, top, macdBottom, lo, hi, ::y, cell, s.interval)
    }

    private fun drawGridAndAxes(
        canvas: Canvas, all: List<MarketCandle>, interval: String, start: Int, end: Int,
        left: Float, right: Float, top: Float, priceBottom: Float, volumeBottom: Float, timeBottom: Float,
        hi: Double, lo: Double
    ) {
        val priceRange = (hi - lo).coerceAtLeast(1e-12)
        for (i in 0..5) {
            val yy = top + (priceBottom - top) * i / 5f
            canvas.drawLine(left, yy, right, yy, grid)
            val p = hi - priceRange * i / 5.0
            canvas.drawText(format(p), right + dp(5f), yy + dp(3f), axisText)
        }
        for (i in 0..4) {
            val xx = left + (right - left) * i / 4f
            canvas.drawLine(xx, top, xx, volumeBottom, grid)
            val idx = (start + ((end - start - 1).coerceAtLeast(0) * i / 4.0).toInt()).coerceIn(start, end - 1)
            val label = timeLabel(all[idx].time, interval)
            val w = axisText.measureText(label)
            canvas.drawText(label, (xx - w / 2f).coerceAtLeast(left), timeBottom + dp(15f), axisText)
        }
    }

    private fun drawCurrentPrice(canvas: Canvas, price: Double, left: Float, right: Float, lo: Double, hi: Double, mapper: (Double) -> Float) {
        if (price !in lo..hi) return
        val py = mapper(price)
        canvas.drawLine(left, py, right, py, currentPricePaint)
        val box = RectF(right + dp(2f), py - dp(9f), width - dp(2f), py + dp(8f))
        canvas.drawRoundRect(box, dp(3f), dp(3f), Paint().apply { color = Color.rgb(67, 76, 90) })
        canvas.drawText(format(price), box.centerX(), py + dp(3f), Paint(axisText).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(8.3f) })
    }

    private fun drawTradeMarkers(canvas: Canvas, all: List<MarketCandle>, start: Int, end: Int, left: Float, cell: Float, mapper: (Double) -> Float) {
        tradeMarkers.forEach { marker ->
            val idx = nearestIndexByTime(all, marker.time)
            if (idx !in start until end) return@forEach
            val c = all[idx]
            val x = left + cell * (idx - start + 0.5f)
            val py = mapper(marker.price.takeIf { it > 0.0 } ?: c.close)
            val markerPaint = if (marker.side.uppercase(Locale.US) == "BUY") up else down
            canvas.drawCircle(x, py, dp(4.5f), markerPaint)
            canvas.drawText(if (marker.side.uppercase(Locale.US) == "BUY") "A" else "V", x, py + dp(2.4f), Paint(smallText).apply { color = Color.WHITE; textSize = dp(7f); textAlign = Paint.Align.CENTER })
        }
    }

    private fun drawRsi(canvas: Canvas, start: Int, end: Int, left: Float, right: Float, top: Float, bottom: Float) {
        if (rsiSeriesCache.isEmpty()) return
        val map: (Double) -> Float = { value -> (bottom - (value.coerceIn(0.0, 100.0) / 100.0 * (bottom - top))).toFloat() }
        val p70 = map(70.0); val p30 = map(30.0)
        canvas.drawLine(left, p70, right, p70, Paint(grid).apply { pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f) })
        canvas.drawLine(left, p30, right, p30, Paint(grid).apply { pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f) })
        canvas.drawText("RSI ${indicators.rsiPeriod}", left + dp(3f), top + dp(10f), smallText)
        drawSeries(canvas, rsiSeriesCache, start, end, left, (right-left)/(end-start).coerceAtLeast(1), map, linePaint(Color.rgb(93,148,255),1.1f))
    }

    private fun drawMacd(canvas: Canvas, start: Int, end: Int, left: Float, right: Float, top: Float, bottom: Float) {
        if (!indicators.macd || macdHistogram.isEmpty()) return
        val visible = macdHistogram.subList(start.coerceAtLeast(0), end.coerceAtMost(macdHistogram.size))
        val maxAbs = visible.maxOfOrNull { abs(it) }?.coerceAtLeast(1e-12) ?: return
        val mid = (top + bottom) / 2f
        canvas.drawLine(left, mid, right, mid, grid)
        canvas.drawText("MACD", left + dp(3f), top + dp(10f), smallText)
        val cell = (right-left)/(end-start).coerceAtLeast(1)
        for (idx in start until end) {
            if (idx !in macdHistogram.indices) continue
            val x = left + cell * (idx-start+0.5f)
            val h = (macdHistogram[idx] / maxAbs * (bottom-top) * 0.42).toFloat()
            val p = if (macdHistogram[idx] >= 0) Paint(up).apply { alpha=150 } else Paint(down).apply { alpha=150 }
            canvas.drawRect(x-max(dp(0.8f),cell*0.28f), mid-h, x+max(dp(0.8f),cell*0.28f), mid, p)
        }
        fun map(v: Double): Float = (mid - (v/maxAbs * (bottom-top)*0.42)).toFloat()
        drawSeries(canvas, macdLine, start, end, left, cell, ::map, linePaint(Color.rgb(245,142,30),1f))
        drawSeries(canvas, macdSignal, start, end, left, cell, ::map, linePaint(Color.rgb(176,126,255),1f))
    }

    private fun drawCrosshair(
        canvas: Canvas, all: List<MarketCandle>, start: Int, end: Int, left: Float, right: Float,
        top: Float, bottom: Float, lo: Double, hi: Double, mapper: (Double)->Float, cell: Float, interval: String
    ) {
        if (!crosshairActive) return
        val idx = selectedGlobalIndex ?: return
        if (idx !in start until end) return
        val c = all[idx]
        val x = left + cell * (idx - start + 0.5f)
        val p = (selectedPrice ?: c.close).coerceIn(lo, hi)
        val cy = mapper(p)
        canvas.drawLine(x, top, x, bottom, crosshairPaint)
        canvas.drawLine(left, cy, right, cy, crosshairPaint)
        drawTooltip(canvas, c, interval, x, top, right)
        val pBox = RectF(right + dp(2f), cy - dp(8f), width - dp(2f), cy + dp(8f))
        canvas.drawRoundRect(pBox, dp(3f), dp(3f), tooltipBg)
        canvas.drawText(format(p), pBox.centerX(), cy + dp(3f), Paint(axisText).apply { color=Color.WHITE; textAlign=Paint.Align.CENTER; textSize=dp(8.2f) })
    }

    private fun drawTechnicalDrawings(canvas: Canvas, all: List<MarketCandle>, start: Int, end: Int, left: Float, right: Float, lo: Double, hi: Double, mapper: (Double)->Float) {
        drawings.forEach { d ->
            val color = drawingColor(d.type)
            val paint = levelPaint(color, 5f)
            val label = d.label.ifBlank { d.type.uppercase(Locale.US) }
            when (d.type.lowercase()) {
                "rectangle", "zone", "buy_zone", "sell_zone" -> {
                    val p2 = d.price2 ?: d.price1
                    val y1 = mapper(d.price1.coerceIn(lo, hi)); val y2 = mapper(p2.coerceIn(lo, hi))
                    val fill = Paint().apply { this.color = color; alpha = 35 }
                    canvas.drawRect(left, min(y1,y2), right, max(y1,y2), fill)
                    canvas.drawRect(left, min(y1,y2), right, max(y1,y2), paint)
                    canvas.drawText(label.take(24), left + dp(4f), min(y1,y2)+dp(11f), Paint(smallText).apply { this.color=color })
                }
                "trendline" -> {
                    val t1 = d.time1 ?: return@forEach; val t2 = d.time2 ?: return@forEach; val p2=d.price2 ?: return@forEach
                    val i1=nearestIndexByTime(all,t1); val i2=nearestIndexByTime(all,t2)
                    if ((i1 < start && i2 < start) || (i1 >= end && i2 >= end)) return@forEach
                    val cell=(right-left)/(end-start).coerceAtLeast(1)
                    val x1=left+cell*(i1-start+0.5f); val x2=left+cell*(i2-start+0.5f)
                    canvas.drawLine(x1,mapper(d.price1),x2,mapper(p2),paint)
                }
                else -> drawHorizontalLevel(canvas,d.price1,left,right,lo,hi,mapper,paint,label)
            }
        }
    }

    private fun drawingColor(type: String): Int = when (type.lowercase()) {
        "support", "rebuy", "re-buy", "buy", "buy_zone" -> Color.rgb(57,197,128)
        "resistance", "sell", "sell_zone", "tp", "target" -> Color.rgb(238,91,91)
        "invalidation" -> Color.rgb(255,82,82)
        "trendline" -> Color.rgb(93,148,255)
        else -> Color.rgb(176,126,255)
    }

    private fun drawHorizontalLevel(canvas: Canvas, value: Double, left: Float, right: Float, lo: Double, hi: Double, mapper: (Double)->Float, paint: Paint, label: String) {
        if (!value.isFinite() || value < lo || value > hi) return
        val yy=mapper(value)
        canvas.drawLine(left,yy,right,yy,paint)
        canvas.drawText(label.take(24),right-dp(3f),yy-dp(2f),Paint(smallText).apply { color=paint.color; textAlign=Paint.Align.RIGHT })
    }

    private fun drawTooltip(canvas: Canvas, c: MarketCandle, interval: String, x: Float, top: Float, right: Float) {
        val pct = if (c.open > 0.0) (c.close / c.open - 1.0) * 100.0 else 0.0
        val lines = listOf(
            fullTimeLabel(c.time, interval),
            "O ${format(c.open)}  H ${format(c.high)}",
            "L ${format(c.low)}  C ${format(c.close)}  ${if (pct>=0) "+" else ""}${String.format(Locale.FRANCE,"%.2f",pct)}%",
            "Vol ${formatVolume(c.volume)}"
        )
        val w=lines.maxOf { smallText.measureText(it) }+dp(14f); val h=dp(50f)
        val l=if(x+w+dp(8f)<right)x+dp(8f) else (x-w-dp(8f)).coerceAtLeast(dp(8f))
        val rect=RectF(l,top+dp(5f),l+w,top+dp(5f)+h)
        canvas.drawRoundRect(rect,dp(6f),dp(6f),tooltipBg)
        lines.forEachIndexed { i,line -> canvas.drawText(line,rect.left+dp(7f),rect.top+dp(11f)+i*dp(10f),smallText) }
    }

    private fun updateSelection(x: Float, yPos: Float) {
        val s=snapshot ?: return; val all=s.candles
        if(all.isEmpty()||width<=0||height<=0)return
        val end=visibleEnd(all.size); val start=visibleStart(end); val count=end-start
        val left=plotLeft(); val right=plotRight(); val top=dp(12f); val priceBottom=height*0.58f
        if(x !in left..right || yPos<top || yPos>priceBottom)return
        val cell=(right-left)/count.coerceAtLeast(1)
        selectedGlobalIndex=start+((x-left)/cell).toInt().coerceIn(0,count-1)
        val candles=all.subList(start,end); val maxP=candles.maxOf{it.high}; val minP=candles.minOf{it.low}
        val raw=(maxP-minP).coerceAtLeast(maxP*0.0005).coerceAtLeast(1e-12); val center=(maxP+minP)/2.0
        val range=if(autoScale)raw*1.16 else raw*1.16*priceScale; val hi=center+range/2; val lo=center-range/2
        val ratio=((priceBottom-yPos)/(priceBottom-top)).coerceIn(0f,1f).toDouble()
        selectedPrice=lo+ratio*(hi-lo); invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN -> {
                downX=event.x; downY=event.y; lastTouchX=event.x; lastTouchY=event.y; moved=false; panAccumulator=0f
                priceAxisScaling=event.x>plotRight(); parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if(event.pointerCount>1 || scaler.isInProgress){ parent?.requestDisallowInterceptTouchEvent(true); return true }
                val dx=event.x-lastTouchX; val dy=event.y-lastTouchY
                if(abs(event.x-downX)>dp(5f)||abs(event.y-downY)>dp(5f))moved=true
                when {
                    priceAxisScaling -> {
                        autoScale=false
                        val factor=(1.0 + dy/height.coerceAtLeast(1)*2.0).coerceIn(0.85,1.15)
                        priceScale=(priceScale*factor).coerceIn(0.2,8.0); invalidate()
                    }
                    crosshairActive -> updateSelection(event.x,event.y)
                    abs(event.x-downX)>abs(event.y-downY)*0.75f -> panPixels(dx)
                }
                lastTouchX=event.x; lastTouchY=event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                priceAxisScaling=false; parent?.requestDisallowInterceptTouchEvent(false); emitStateChanged()
            }
        }
        return true
    }

    override fun computeScroll() {
        if(scroller.computeScrollOffset()){
            val curr=scroller.currX; val dx=curr-lastScrollerX; lastScrollerX=curr
            panPixels(dx.toFloat()); postInvalidateOnAnimation()
        } else if(lastScrollerX!=0){ lastScrollerX=0; emitStateChanged() }
    }

    private fun panPixels(dx: Float) {
        val all=snapshot?.candles ?: return
        if(all.isEmpty())return
        val cell=(plotRight()-plotLeft())/visibleCount.coerceAtLeast(1)
        panAccumulator+=dx
        val shift=(panAccumulator/cell.coerceAtLeast(1f)).toInt()
        if(shift!=0){
            offsetFromEnd=(offsetFromEnd+shift).coerceIn(0,maxOffset())
            panAccumulator-=shift*cell; clearCrosshair(); invalidate()
        }
    }

    private fun prepareIndicators() {
        val c=snapshot?.candles ?: emptyList(); val closes=c.map{it.close}
        maSeries.clear(); emaSeriesCache.clear()
        indicators.maPeriods.forEach { maSeries[it]=sma(closes,it) }
        indicators.emaPeriods.forEach { emaSeriesCache[it]=ema(closes,it) }
        bollingerSeries=if(indicators.bollinger)rollingBollinger(closes,20)else emptyList()
        rsiSeriesCache=rsi(closes,indicators.rsiPeriod)
        if(indicators.macd){
            val fast=ema(closes,12); val slow=ema(closes,26); macdLine=closes.indices.map{fast[it]-slow[it]}; macdSignal=ema(macdLine,9); macdHistogram=closes.indices.map{macdLine[it]-macdSignal[it]}
        }else{macdLine=emptyList();macdSignal=emptyList();macdHistogram=emptyList()}
        atrCurrent=atr(c,indicators.atrPeriod)
    }

    private fun drawSeries(canvas: Canvas, values: List<Double>, start: Int, end: Int, left: Float, cell: Float, mapper: (Double)->Float, paint: Paint) {
        if(values.isEmpty())return; val path=Path(); var moved=false
        for(idx in start until end){if(idx !in values.indices)continue;val v=values[idx];if(!v.isFinite())continue;val x=left+cell*(idx-start+0.5f);val yy=mapper(v);if(!moved){path.moveTo(x,yy);moved=true}else path.lineTo(x,yy)}
        if(moved)canvas.drawPath(path,paint)
    }

    private fun sma(values: List<Double>, period: Int): List<Double> {
        if(values.isEmpty())return emptyList();val out=DoubleArray(values.size);var sum=0.0
        values.forEachIndexed{i,v->sum+=v;if(i>=period)sum-=values[i-period];out[i]=sum/min(i+1,period)}
        return out.toList()
    }

    private fun ema(values: List<Double>, period: Int): List<Double> {
        if(values.isEmpty())return emptyList();val out=ArrayList<Double>(values.size);val k=2.0/(period+1.0);var e=values.first()
        values.forEachIndexed{i,v->e=if(i==0)v else v*k+e*(1-k);out+=e};return out
    }

    private fun rsi(values: List<Double>, period: Int): List<Double> {
        if(values.isEmpty())return emptyList();val out=MutableList(values.size){50.0};if(values.size<=period)return out
        var gains=0.0;var losses=0.0
        for(i in 1..period){val d=values[i]-values[i-1];if(d>=0)gains+=d else losses-=d}
        var avgG=gains/period;var avgL=losses/period
        fun value()=if(avgL==0.0)100.0 else 100.0-100.0/(1.0+avgG/avgL)
        out[period]=value();for(i in period+1 until values.size){val d=values[i]-values[i-1];avgG=(avgG*(period-1)+max(d,0.0))/period;avgL=(avgL*(period-1)+max(-d,0.0))/period;out[i]=value()}
        for(i in 0 until period)out[i]=out[period];return out
    }

    private fun rollingBollinger(values: List<Double>, period: Int): List<Triple<Double,Double,Double>> {
        val out=ArrayList<Triple<Double,Double,Double>>(values.size);for(i in values.indices){val from=(i-period+1).coerceAtLeast(0);val w=values.subList(from,i+1);val mid=w.average();val sd=sqrt(w.sumOf{(it-mid)*(it-mid)}/w.size);out+=Triple(mid+2*sd,mid,mid-2*sd)};return out
    }

    private fun atr(c: List<MarketCandle>, period: Int): Double {
        if(c.size<2)return 0.0;val tr=ArrayList<Double>(c.size-1);for(i in 1 until c.size)tr+=max(c[i].high-c[i].low,max(abs(c[i].high-c[i-1].close),abs(c[i].low-c[i-1].close)));return tr.takeLast(period).average()
    }

    private fun centerIndexIfNeeded(index:Int){
        val n=snapshot?.candles?.size ?: return;val end=visibleEnd(n);val start=visibleStart(end)
        if(index in start until end)return
        val maxStart=(n-visibleCount).coerceAtLeast(0);val newStart=(index-visibleCount/2).coerceIn(0,maxStart);offsetFromEnd=(n-newStart-visibleCount).coerceIn(0,maxOffset())
    }

    private fun clearCrosshair(){crosshairActive=false;selectedGlobalIndex=null;selectedPrice=null}
    private fun visibleEnd(size:Int)=(size-offsetFromEnd).coerceIn(1,size)
    private fun visibleStart(end:Int)=(end-visibleCount).coerceAtLeast(0)
    private fun maxOffset():Int{val n=snapshot?.candles?.size ?: 0;return(n-visibleCount).coerceAtLeast(0)}
    private fun plotLeft()=dp(9f)
    private fun plotRight()=(width-dp(72f)).coerceAtLeast(plotLeft()+dp(40f))
    private fun stopFling(){if(!scroller.isFinished)scroller.forceFinished(true);lastScrollerX=0}
    private fun emitStateChanged(){stateChanged?.invoke(exportViewport())}

    private fun nearestIndexByTime(candles:List<MarketCandle>,time:Long):Int{if(candles.isEmpty())return-1;var best=0;var delta=Long.MAX_VALUE;candles.forEachIndexed{i,c->val d=abs(c.time-time);if(d<delta){delta=d;best=i}};return best}

    private fun timeLabel(time:Long,interval:String):String{val pattern=when(interval){"1m","3m","5m","15m","30m","1h","2h","4h","6h","12h"->"HH:mm";"1d","3d"->"dd MMM";else->"MMM yy"};return SimpleDateFormat(pattern,Locale.FRANCE).format(Date(time))}
    private fun fullTimeLabel(time:Long,interval:String):String{val pattern=if(interval=="1w")"dd MMM yyyy" else "dd MMM yyyy HH:mm";return SimpleDateFormat(pattern,Locale.FRANCE).format(Date(time))}
    private fun formatVolume(v:Double):String=when{v>=1_000_000_000->String.format(Locale.US,"%.2fB",v/1_000_000_000.0);v>=1_000_000->String.format(Locale.US,"%.2fM",v/1_000_000.0);v>=1_000->String.format(Locale.US,"%.1fK",v/1_000.0);else->String.format(Locale.US,"%.2f",v)}
    private fun format(v:Double):String=when{v>=1000->String.format(Locale.US,"%.2f",v);v>=100->String.format(Locale.US,"%.3f",v).trimEnd('0').trimEnd('.');v>=1->String.format(Locale.US,"%.5f",v).trimEnd('0').trimEnd('.');else->String.format(Locale.US,"%.8f",v).trimEnd('0').trimEnd('.')}
    private fun linePaint(color:Int,widthDp:Float)=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;strokeWidth=dp(widthDp);style=Paint.Style.STROKE}
    private fun levelPaint(color:Int,dash:Float=6f)=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;strokeWidth=dp(0.95f);pathEffect=DashPathEffect(floatArrayOf(dp(dash),dp(4f)),0f);alpha=200}

    companion object{private const val MIN_VISIBLE=12;private const val MAX_VISIBLE=600}
}
