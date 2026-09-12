package com.chk.binancebybit

import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
class ChartTouchTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun snapshot(extra: Int = 0): IndicatorSnapshot {
        val candles = (0 until 300 + extra).map { MarketCandle(1_600_000_000_000L + it * 60_000,100.0,102.0,99.0,101.0,10.0) }
        return IndicatorSnapshot(exchange="BYBIT",requestedSymbol="RENDERUSDC",sourceSymbol="RENDERUSDC",interval="1m",candles=candles,
            lastPrice=101.0,changePct=0.0,rsi14=50.0,ema20=100.0,ema50=100.0,bbUpper=102.0,bbMiddle=100.0,bbLower=99.0,
            macd=0.0,macdSignal=0.0,macdHistogram=0.0,atr14=3.0,volumeRatio=1.0,support=99.0,resistance=102.0,
            trend="",divergence="",pattern="",score=50,summary="")
    }
    private fun chart() = CandlestickChartView(app).apply { layout(0,0,1080,720); setSnapshot(snapshot()) }
    private fun event(chart: CandlestickChartView, action: Int, time: Long, vararg points: Triple<Int,Float,Float>) {
        val props=points.map { MotionEvent.PointerProperties().apply { id=it.first;toolType=MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coords=points.map { MotionEvent.PointerCoords().apply { x=it.second;y=it.third;pressure=1f;size=1f } }.toTypedArray()
        val e=MotionEvent.obtain(1000L,time,action,points.size,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
        chart.onTouchEvent(e);e.recycle()
    }
    @Test fun selectingCrosshairDoesNotTrapTheNextDrag() {
        val c=chart()
        event(c,0,1000,Triple(0,300f,100f));event(c,1,1100,Triple(0,300f,100f))
        assertTrue(c.exportViewport().crosshair.active)
        event(c,0,2000,Triple(0,300f,100f));event(c,2,2200,Triple(0,500f,110f))
        assertTrue(c.exportViewport().offsetFromEnd>0)
        assertFalse(c.exportViewport().crosshair.active)
        event(c,3,2300,Triple(0,500f,110f))
    }
    @Test fun verticalSwipeReleasesThePage() {
        var disallowed=false
        val parent=object:FrameLayout(app){override fun requestDisallowInterceptTouchEvent(value:Boolean){disallowed=value;super.requestDisallowInterceptTouchEvent(value)}}
        val c=chart();parent.addView(c);c.layout(0,0,1080,720)
        event(c,0,1000,Triple(0,300f,100f));assertTrue(disallowed)
        event(c,2,1200,Triple(0,305f,350f));assertFalse(disallowed)
        assertEquals(0,c.exportViewport().offsetFromEnd)
    }
    @Test fun pinchTracksSmallChangesAndLiftingFirstFingerDoesNotJump() {
        val c=chart()
        event(c,0,1000,Triple(1,200f,100f))
        event(c,5 or (1 shl 8),1020,Triple(1,200f,100f),Triple(7,600f,100f))
        event(c,2,1040,Triple(1,150f,100f),Triple(7,650f,100f))
        for(i in 1..30)event(c,2,1040L+i*20,Triple(1,150f-i,100f),Triple(7,650f+i,100f))
        assertTrue(c.exportViewport().visibleCount<100)
        event(c,6,1800,Triple(1,120f,100f),Triple(7,680f,100f))
        val before=c.exportViewport()
        event(c,2,1820,Triple(7,680f,100f))
        assertEquals(before.offsetFromEnd,c.exportViewport().offsetFromEnd)
        event(c,1,1840,Triple(7,680f,100f))
    }
    @Test fun realtimeCandlePreservesHistoricalViewport() {
        val c=chart();c.panLeft(50);val before=c.visibleRange()
        c.setSnapshot(snapshot(1),preserveViewport=true)
        assertEquals(before,c.visibleRange())
    }
}
