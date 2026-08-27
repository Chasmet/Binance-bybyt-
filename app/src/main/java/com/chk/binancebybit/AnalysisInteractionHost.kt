package com.chk.binancebybit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ScrollView
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Remote navigation bridge used by MCP commands. It always targets the ScrollView
 * that is currently attached to the Analyse tab, so ChatGPT manipulates the same
 * vertical viewport and the same orange wheel as the user.
 */
object AnalysisRemoteNavigation {
    @Volatile private var activeScroll: WeakReference<ScrollView>? = null

    fun bind(scrollView: ScrollView) {
        activeScroll = WeakReference(scrollView)
    }

    fun unbind(scrollView: ScrollView) {
        if (activeScroll?.get() === scrollView) activeScroll = null
    }

    fun scroll(direction: String, pixels: Int = 0, position: Double? = null): Boolean {
        val scroll = activeScroll?.get() ?: return false
        scroll.post {
            val childHeight = scroll.getChildAt(0)?.height ?: 0
            val range = (childHeight - scroll.height + scroll.paddingTop + scroll.paddingBottom).coerceAtLeast(0)
            val targetRatio = position?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
            if (targetRatio != null) {
                scroll.smoothScrollTo(0, (range * targetRatio).toInt())
                return@post
            }
            val step = pixels.takeIf { it > 0 } ?: (scroll.height * 0.72f).toInt().coerceAtLeast(1)
            when (direction.trim().lowercase()) {
                "top" -> scroll.smoothScrollTo(0, 0)
                "bottom" -> scroll.smoothScrollTo(0, range)
                "up" -> scroll.smoothScrollBy(0, -step)
                "down" -> scroll.smoothScrollBy(0, step)
                else -> Unit
            }
        }
        return true
    }
}

/**
 * Touch coordinator for the Analyse tab.
 *
 * Goals:
 * - one-finger vertical gestures scroll the whole Analyse page, even when they start on the chart;
 * - horizontal gestures stay owned by the candlestick chart for history pan;
 * - two-finger gestures are never intercepted so pinch-to-zoom remains native to the chart;
 * - a compact draggable wheel gives precise top/bottom navigation without stealing screen space.
 */
class AnalysisInteractionHost(
    context: Context,
    private val scrollView: ScrollView
) : FrameLayout(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var pointerCount = 0
    private var verticalDrag = false
    private var horizontalLock = false
    private var velocityTracker: VelocityTracker? = null

    init {
        clipChildren = false
        clipToPadding = false
        addView(
            scrollView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        addView(
            AnalysisScrollWheel(context, scrollView),
            LayoutParams(dp(30), LayoutParams.MATCH_PARENT, Gravity.END).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
                marginEnd = dp(3)
            }
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        AnalysisRemoteNavigation.bind(scrollView)
    }

    override fun onDetachedFromWindow() {
        AnalysisRemoteNavigation.unbind(scrollView)
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        pointerCount = ev.pointerCount
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = ev.x
            downY = ev.y
            lastY = ev.y
            verticalDrag = false
            horizontalLock = false
            velocityTracker?.recycle()
            velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
        } else {
            velocityTracker?.addMovement(ev)
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            pointerCount = 0
            horizontalLock = false
        }
        return handled
    }

    /**
     * The chart historically disallowed interception immediately on ACTION_DOWN.
     * Ignore that early one-finger lock until the gesture direction is known.
     * Two-finger pinch and confirmed horizontal chart gestures keep full ownership.
     */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        val reallyDisallow = disallowIntercept && (pointerCount > 1 || horizontalLock)
        super.requestDisallowInterceptTouchEvent(reallyDisallow)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount > 1) {
            verticalDrag = false
            horizontalLock = true
            super.requestDisallowInterceptTouchEvent(true)
            return false
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                verticalDrag = false
                horizontalLock = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                val ax = abs(dx)
                val ay = abs(dy)
                if (!verticalDrag && !horizontalLock && max(ax, ay) >= touchSlop) {
                    if (ay > ax * 1.12f) {
                        verticalDrag = true
                        super.requestDisallowInterceptTouchEvent(false)
                        lastY = ev.y
                        return true
                    }
                    if (ax > ay * 1.08f) {
                        horizontalLock = true
                        super.requestDisallowInterceptTouchEvent(true)
                        return false
                    }
                }
                return verticalDrag
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val result = verticalDrag
                verticalDrag = false
                horizontalLock = false
                return result
            }
        }
        return verticalDrag
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        velocityTracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!verticalDrag || event.pointerCount > 1) return false
                val dy = lastY - event.y
                if (dy != 0f) scrollView.scrollBy(0, dy.toInt())
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (verticalDrag) {
                    velocityTracker?.computeCurrentVelocity(1000, maxFling.toFloat())
                    val fingerVelocity = velocityTracker?.yVelocity ?: 0f
                    if (abs(fingerVelocity) >= minFling) {
                        scrollView.fling((-fingerVelocity).toInt())
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
                verticalDrag = false
                horizontalLock = false
                super.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
                verticalDrag = false
                horizontalLock = false
                super.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return verticalDrag
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** Small vertical wheel/rail for fast Analyse page navigation. */
private class AnalysisScrollWheel(
    context: Context,
    private val scrollView: ScrollView
) : View(context) {

    private val density = resources.displayMetrics.density
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 55, 62, 72) }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 245, 142, 30) }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(224, 229, 236)
        strokeWidth = dpF(1.6f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val hitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(105, 20, 23, 28) }

    private var dragging = false
    private var dragOffset = 0f
    private var lastHapticBucket = -1

    init {
        isClickable = true
        isFocusable = true
        scrollView.setOnScrollChangeListener { _, _, _, _, _ -> invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height <= 0 || width <= 0) return

        val topButton = dpF(32f)
        val bottomButton = height - dpF(32f)
        val cx = width / 2f

        canvas.drawRoundRect(
            RectF(dpF(3f), 0f, width - dpF(3f), height.toFloat()),
            dpF(13f), dpF(13f), hitPaint
        )

        drawChevron(canvas, cx, dpF(15f), up = true)
        drawChevron(canvas, cx, height - dpF(15f), up = false)

        val rail = RectF(cx - dpF(2.2f), topButton, cx + dpF(2.2f), bottomButton)
        canvas.drawRoundRect(rail, dpF(3f), dpF(3f), railPaint)

        val thumb = thumbRect(topButton, bottomButton)
        canvas.drawRoundRect(thumb, dpF(7f), dpF(7f), thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val topButton = dpF(32f)
        val bottomButton = height - dpF(32f)
        val thumb = thumbRect(topButton, bottomButton)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                when {
                    event.y < topButton -> {
                        scrollView.smoothScrollBy(0, -(scrollView.height * 0.72f).toInt())
                        return true
                    }
                    event.y > bottomButton -> {
                        scrollView.smoothScrollBy(0, (scrollView.height * 0.72f).toInt())
                        return true
                    }
                    event.y in thumb.top..thumb.bottom -> {
                        dragging = true
                        dragOffset = event.y - thumb.top
                    }
                    else -> {
                        dragging = true
                        dragOffset = thumb.height() / 2f
                        moveTo(event.y, topButton, bottomButton)
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    moveTo(event.y - dragOffset + thumb.height() / 2f, topButton, bottomButton)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                lastHapticBucket = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun moveTo(centerY: Float, top: Float, bottom: Float) {
        val range = scrollRange()
        if (range <= 0) return
        val thumbH = thumbHeight(top, bottom)
        val usable = (bottom - top - thumbH).coerceAtLeast(1f)
        val thumbTop = (centerY - thumbH / 2f).coerceIn(top, bottom - thumbH)
        val ratio = ((thumbTop - top) / usable).coerceIn(0f, 1f)
        scrollView.scrollTo(0, (range * ratio).toInt())

        val bucket = (ratio * 10f).toInt()
        if (bucket != lastHapticBucket) {
            lastHapticBucket = bucket
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        invalidate()
    }

    private fun thumbRect(top: Float, bottom: Float): RectF {
        val thumbH = thumbHeight(top, bottom)
        val range = scrollRange()
        val ratio = if (range <= 0) 0f else (scrollView.scrollY.toFloat() / range.toFloat()).coerceIn(0f, 1f)
        val usable = (bottom - top - thumbH).coerceAtLeast(0f)
        val y = top + usable * ratio
        return RectF(dpF(6f), y, width - dpF(6f), y + thumbH)
    }

    private fun thumbHeight(top: Float, bottom: Float): Float {
        val track = (bottom - top).coerceAtLeast(dpF(40f))
        val child = scrollView.getChildAt(0)?.height ?: 0
        val viewport = scrollView.height
        if (child <= 0 || viewport <= 0) return min(track, dpF(48f))
        val proportional = track * (viewport.toFloat() / child.toFloat()).coerceIn(0f, 1f)
        return proportional.coerceIn(dpF(38f), min(track, dpF(92f)))
    }

    private fun scrollRange(): Int {
        val childHeight = scrollView.getChildAt(0)?.height ?: 0
        return (childHeight - scrollView.height + scrollView.paddingTop + scrollView.paddingBottom).coerceAtLeast(0)
    }

    private fun drawChevron(canvas: Canvas, cx: Float, cy: Float, up: Boolean) {
        val d = dpF(4.5f)
        if (up) {
            canvas.drawLine(cx - d, cy + d / 2f, cx, cy - d / 2f, iconPaint)
            canvas.drawLine(cx, cy - d / 2f, cx + d, cy + d / 2f, iconPaint)
        } else {
            canvas.drawLine(cx - d, cy - d / 2f, cx, cy + d / 2f, iconPaint)
            canvas.drawLine(cx, cy + d / 2f, cx + d, cy - d / 2f, iconPaint)
        }
    }

    private fun dpF(value: Float): Float = value * density
}
