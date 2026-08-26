package com.chk.binancebybit

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ChartFullscreenActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val running = AtomicBoolean(true)
    private lateinit var chart: CandlestickChartView
    private lateinit var status: TextView
    private lateinit var timeframes: HorizontalScrollView
    private lateinit var autoScale: Button
    private lateinit var stateStore: ChartStateStore
    private lateinit var remote: ChartRemoteClient
    private var state = ChartSessionState()
    private var lastSnapshot: IndicatorSnapshot? = null
    private var exchange = "BYBIT"
    private var pushPending = false
    private var lastPngAt = 0L

    private val bg = Color.rgb(8, 10, 13)
    private val surface = Color.rgb(20, 23, 28)
    private val border = Color.rgb(48, 54, 64)
    private val text = Color.rgb(246, 247, 249)
    private val muted = Color.rgb(153, 162, 174)
    private val orange = Color.rgb(245, 142, 30)
    private val green = Color.rgb(57, 197, 128)

    private val refreshLoop = object : Runnable {
        override fun run() {
            if (!running.get()) return
            refresh(false)
            main.postDelayed(this, 12_000L)
        }
    }

    private val commandLoop = object : Runnable {
        override fun run() {
            if (!running.get()) return
            executor.execute {
                val cmd = runCatching { remote.pullCommand() }.getOrNull()
                if (cmd != null) main.post { applyCommand(cmd) }
            }
            main.postDelayed(this, 1_500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        window.statusBarColor = bg
        window.navigationBarColor = bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        stateStore = ChartStateStore(this)
        remote = ChartRemoteClient(this)
        val stored = stateStore.loadCurrent()
        val symbol = intent.getStringExtra("symbol") ?: stored.symbol
        val timeframe = intent.getStringExtra("timeframe") ?: stored.timeframe
        exchange = intent.getStringExtra("exchange")?.uppercase(Locale.US) ?: "BYBIT"
        state = stateStore.loadFor(symbol, timeframe).copy(symbol = ChartSessionState.normalizeSymbol(symbol), timeframe = ChartSessionState.normalizeTimeframe(timeframe))
        setContentView(build())
        main.post { refresh(true) }
    }

    override fun onResume() {
        super.onResume()
        running.set(true)
        main.removeCallbacks(refreshLoop)
        main.removeCallbacks(commandLoop)
        main.postDelayed(refreshLoop, 12_000L)
        main.post(commandLoop)
    }

    override fun onPause() {
        running.set(false)
        main.removeCallbacks(refreshLoop)
        main.removeCallbacks(commandLoop)
        saveState()
        super.onPause()
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(bg)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        status = TextView(this).apply {
            text = "${state.symbol} • ${state.timeframe.uppercase(Locale.US)}"
            textSize = 17f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(text)
        }
        top.addView(status, LinearLayout.LayoutParams(0, dp(42), 1f))
        top.addView(action("FERMER") { finish() }, LinearLayout.LayoutParams(dp(92), dp(40)))
        root.addView(top)

        timeframes = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        rebuildTimeframes()
        root.addView(timeframes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        chart = CandlestickChartView(this).apply {
            setIndicators(state.indicators)
            setDrawings(state.drawings)
            applyViewport(state.viewport)
            setOnStateChangedListener { viewport ->
                state = state.copy(viewport = viewport, indicators = currentIndicators(), drawings = currentDrawings())
                stateStore.save(state)
                updateAutoScale()
                schedulePush(false)
            }
        }
        root.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, 0) }
        controls.addView(action("DERNIER") { chart.goToLatest() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(3) })
        controls.addView(action("RESET") { chart.resetView() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        autoScale = action("AUTO SCALE") { chart.setAutoScale(!chart.exportViewport().autoScale); updateAutoScale() }
        controls.addView(autoScale, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(3) })
        root.addView(controls)
        updateAutoScale()
        return root
    }

    private fun rebuildTimeframes() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
        listOf("1m","3m","5m","15m","30m","1h","2h","4h","6h","12h","1d","3d","1w").forEach { tf ->
            row.addView(TextView(this).apply {
                text = tf.uppercase(Locale.US)
                gravity = Gravity.CENTER
                textSize = 10f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (tf == state.timeframe) Color.BLACK else text)
                background = rounded(if (tf == state.timeframe) orange else surface, if (tf == state.timeframe) orange else border, 999)
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener {
                    state = stateStore.loadFor(state.symbol, tf).copy(symbol = state.symbol, timeframe = tf, indicators = state.indicators, drawings = state.drawings)
                    stateStore.save(state)
                    rebuildTimeframes()
                    refresh(true)
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { rightMargin = dp(4) }
            })
        }
        timeframes.removeAllViews(); timeframes.addView(row)
    }

    private fun refresh(manual: Boolean) {
        if (!running.get()) return
        status.text = "${state.symbol} • ${state.timeframe.uppercase(Locale.US)} • ${if (manual) "chargement" else "live"}"
        executor.execute {
            try {
                val snap = MarketAnalysisClient().load(exchange, state.symbol, state.timeframe, 420)
                val overlays = if (exchange == "BYBIT") {
                    val secure = SecureStore(this)
                    runCatching { BybitChartOverlayClient(secure.get("bybit_api_key"), secure.get("bybit_api_secret")).load(snap.requestedSymbol) }.getOrDefault(emptyList<ChartTradeMarker>() to emptyList())
                } else emptyList<ChartTradeMarker>() to emptyList()
                main.post {
                    if (!running.get()) return@post
                    val same = lastSnapshot?.requestedSymbol == snap.requestedSymbol && lastSnapshot?.interval == snap.interval
                    lastSnapshot = snap
                    state = state.copy(symbol = snap.requestedSymbol, timeframe = snap.interval)
                    chart.setSnapshot(snap, preserveViewport = same)
                    if (!same) chart.applyViewport(state.viewport)
                    chart.setIndicators(state.indicators); chart.setDrawings(state.drawings)
                    chart.setTradeMarkers(overlays.first); chart.setOrderLevels(overlays.second)
                    status.text = "${snap.requestedSymbol} • ${snap.interval.uppercase(Locale.US)} • ${fmt(snap.lastPrice)} • ${if (snap.changePct >= 0) "+" else ""}${String.format(Locale.FRANCE,"%.2f",snap.changePct)}%"
                    saveState(); pushState(snap, System.currentTimeMillis() - lastPngAt >= 30_000L)
                }
            } catch (e: Throwable) {
                main.post { status.text = "Erreur graphique • ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    private fun applyCommand(rc: ChartRemoteCommand) {
        val args = rc.command.optJSONObject("args") ?: JSONObject()
        var reload = false
        when (rc.command.optString("op").lowercase(Locale.US)) {
            "set_symbol" -> { state = state.copy(symbol = ChartSessionState.normalizeSymbol(args.optString("symbol",state.symbol))); reload = true }
            "set_timeframe" -> { state = state.copy(timeframe = ChartSessionState.normalizeTimeframe(args.optString("timeframe",state.timeframe))); reload = true; rebuildTimeframes() }
            "set_indicators" -> { val cfg=ChartIndicatorConfig.fromJson(args.optJSONObject("indicators") ?: args); state=state.copy(indicators=cfg);chart.setIndicators(cfg) }
            "set_visible_range" -> chart.applyViewport(chart.exportViewport().copy(visibleCount=args.optInt("visible_count",chart.exportViewport().visibleCount).coerceIn(12,600),offsetFromEnd=args.optInt("offset_from_end",chart.exportViewport().offsetFromEnd).coerceAtLeast(0)))
            "zoom_in" -> chart.zoomIn(); "zoom_out" -> chart.zoomOut()
            "pan_left" -> chart.panLeft(args.optInt("candles",maxOf(1,chart.exportViewport().visibleCount/5)))
            "pan_right" -> chart.panRight(args.optInt("candles",maxOf(1,chart.exportViewport().visibleCount/5)))
            "go_to_latest" -> chart.goToLatest(); "reset_view" -> chart.resetView()
            "set_auto_scale" -> chart.setAutoScale(args.optBoolean("enabled",true))
            "set_crosshair" -> chart.setCrosshair(args.optLong("timestamp",0L).takeIf{it>0L},args.optDouble("price",Double.NaN).takeIf{it.isFinite()&&it>0.0})
            "add_drawing" -> ChartDrawing.fromJson(args.optJSONObject("drawing") ?: args)?.let{chart.addDrawing(it)}
            "update_drawing" -> ChartDrawing.fromJson(args.optJSONObject("drawing") ?: args)?.let{chart.updateDrawing(it)}
            "remove_drawing" -> chart.removeDrawing(args.optString("id")); "clear_drawings" -> chart.clearDrawings()
            "set_profile" -> { state=stateStore.profile(args.optString("profile","INTRADAY"),state.symbol);chart.setIndicators(state.indicators);chart.applyViewport(state.viewport);rebuildTimeframes();reload=true }
        }
        saveState()
        executor.execute { runCatching { remote.ack(rc.seq) } }
        if (reload) refresh(true) else schedulePush(true)
    }

    private fun saveState() {
        if (::chart.isInitialized) state = state.copy(viewport = chart.exportViewport(), indicators = chart.currentIndicators(), drawings = chart.currentDrawings())
        stateStore.save(state)
    }

    private fun schedulePush(snapshot: Boolean) {
        if (pushPending) return
        pushPending = true
        main.postDelayed({ pushPending=false; lastSnapshot?.let { pushState(it,snapshot) } },400L)
    }

    private fun pushState(snap: IndicatorSnapshot, includePng: Boolean) {
        saveState()
        val market=remote.marketJson(snap,state.viewport)
        val png=if(includePng)chart.capturePng()else ByteArray(0)
        if(png.isNotEmpty())lastPngAt=System.currentTimeMillis()
        executor.execute { runCatching { remote.pushState(state,market) };if(png.isNotEmpty())runCatching{remote.uploadSnapshot(png)} }
    }

    private fun updateAutoScale(){if(::autoScale.isInitialized&&::chart.isInitialized){val on=chart.exportViewport().autoScale;autoScale.text=if(on)"AUTO ✓" else "AUTO OFF";autoScale.setTextColor(if(on)green else muted)}}
    private fun action(label:String,click:()->Unit)=Button(this).apply{text=label;isAllCaps=false;textSize=10f;setTypeface(Typeface.DEFAULT,Typeface.BOLD);setTextColor(text);background=rounded(surface,border,12);setOnClickListener{click()}}
    private fun rounded(fill:Int,stroke:Int,radius:Int)=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;setColor(fill);cornerRadius=dp(radius).toFloat();if(stroke!=Color.TRANSPARENT)setStroke(dp(1),stroke)}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun fmt(v:Double):String=when{v>=1000->String.format(Locale.US,"%.2f",v);v>=1->String.format(Locale.US,"%.5f",v).trimEnd('0').trimEnd('.');else->String.format(Locale.US,"%.8f",v).trimEnd('0').trimEnd('.')}
}
