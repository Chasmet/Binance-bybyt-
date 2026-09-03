package com.chk.binancebybit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class OrderBookHunterTimelineView(context: Context) : View(context) {
    private var events: List<HunterEvent> = emptyList()
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(72, 79, 90); strokeWidth = 1f }
    private val buyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(57, 197, 128); strokeWidth = 3f }
    private val sellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(242, 96, 96); strokeWidth = 3f }
    private val neutralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 185, 11); strokeWidth = 2f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(167, 175, 187); textSize = 28f }

    fun setEvents(value: List<HunterEvent>) {
        events = value.filter { it.price > 0.0 || it.newPrice > 0.0 }.sortedBy { it.createdAt }.takeLast(100)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 34f
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(pad, h - pad, w - pad, h - pad, axisPaint)
        canvas.drawLine(pad, pad, pad, h - pad, axisPaint)
        if (events.isEmpty()) {
            canvas.drawText("Timeline des murs en attente…", pad + 12f, h / 2f, textPaint)
            return
        }
        val prices = events.map { if (it.newPrice > 0.0) it.newPrice else it.price }.filter { it > 0.0 }
        if (prices.isEmpty()) return
        val minP = prices.minOrNull() ?: return
        val maxP = prices.maxOrNull() ?: return
        val rangeP = max(maxP - minP, maxP * 0.0001)
        val minT = events.first().createdAt
        val maxT = events.last().createdAt
        val rangeT = max(1L, maxT - minT)
        var previousX = 0f
        var previousY = 0f
        var previousSide: HunterWallSide? = null
        events.forEachIndexed { index, e ->
            val p = if (e.newPrice > 0.0) e.newPrice else e.price
            if (p <= 0.0) return@forEachIndexed
            val x = pad + ((e.createdAt - minT).toDouble() / rangeT.toDouble() * (w - 2 * pad)).toFloat()
            val y = h - pad - (((p - minP) / rangeP) * (h - 2 * pad)).toFloat()
            val paint = when (e.side) {
                HunterWallSide.BUY -> buyPaint
                HunterWallSide.SELL -> sellPaint
                null -> neutralPaint
            }
            if (index > 0 && previousX > 0f && previousSide == e.side) canvas.drawLine(previousX, previousY, x, y, paint)
            val r = min(13f, max(4f, sqrt(max(0.0, e.qty)).toFloat() / 90f))
            canvas.drawCircle(x, y, r, paint)
            previousX = x
            previousY = y
            previousSide = e.side
        }
        canvas.drawText(String.format(java.util.Locale.US, "%.8f", maxP).trimEnd('0').trimEnd('.'), pad + 6f, pad + 22f, textPaint)
        canvas.drawText(String.format(java.util.Locale.US, "%.8f", minP).trimEnd('0').trimEnd('.'), pad + 6f, h - pad - 8f, textPaint)
    }
}
