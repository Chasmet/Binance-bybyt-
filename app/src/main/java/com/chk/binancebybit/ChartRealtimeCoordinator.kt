package com.chk.binancebybit

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the chart that is actually visible on screen connected to Bybit public WebSocket.
 * REST is used only to bootstrap/fallback the candle history. Live ticker/kline/book/trades then
 * update the same CandlestickChartView that the user manipulates and the same state exposed to MCP.
 */
object ChartRealtimeCoordinator {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val loading = AtomicBoolean(false)
    private val pushing = AtomicBoolean(false)

    private var installed = false
    private lateinit var app: Application
    private lateinit var stateStore: ChartStateStore
    private lateinit var remote: ChartRemoteClient
    private val marketClient = MarketAnalysisClient()

    private var activityRef = WeakReference<Activity>(null)
    private var chartRef = WeakReference<CandlestickChartView>(null)
    private var socket: BybitMarketWebSocket? = null
    private var liveSnapshot: IndicatorSnapshot? = null
    private var liveSymbol = ""
    private var liveTimeframe = ""
    private var liveBook: BybitLiveBook? = null
    private var lastTradePrice = 0.0
    private var lastTradeQuantity = 0.0
    private var lastTradeSide = ""
    private var lastTradeAt = 0L
    private var wsConnected = false
    private var lastStatePushAt = 0L
    private var lastSnapshotUploadAt = 0L

    private val scanner = object : Runnable {
        override fun run() {
            if (!installed) return
            val activity = activityRef.get()
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                val found = findChart(activity.window?.decorView)
                val old = chartRef.get()
                if (found !== old) {
                    chartRef = WeakReference(found)
                    if (found == null) stopSocket() else bindVisibleChart(found)
                } else if (found != null) {
                    ensureSubscription(found)
                }
            } else {
                chartRef = WeakReference(null)
                stopSocket()
            }
            main.postDelayed(this, 750L)
        }
    }

    @Synchronized
    fun install(application: Application) {
        if (installed) return
        installed = true
        app = application
        stateStore = ChartStateStore(app)
        remote = ChartRemoteClient(app)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                activityRef = WeakReference(activity)
                main.removeCallbacks(scanner)
                main.post(scanner)
            }

            override fun onActivityPaused(activity: Activity) {
                if (activityRef.get() === activity) {
                    main.postDelayed({
                        if (activityRef.get() === activity && !activity.hasWindowFocus()) {
                            chartRef = WeakReference(null)
                            stopSocket()
                        }
                    }, 400L)
                }
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (activityRef.get() === activity) {
                    activityRef = WeakReference(null)
                    chartRef = WeakReference(null)
                    stopSocket()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    private fun bindVisibleChart(chart: CandlestickChartView) {
        val session = stateStore.loadCurrent()
        liveSymbol = ChartSessionState.normalizeSymbol(session.symbol)
        liveTimeframe = ChartSessionState.normalizeTimeframe(session.timeframe)
        loadBootstrap(chart, liveSymbol, liveTimeframe)
        startSocket(liveSymbol, liveTimeframe)
    }

    private fun ensureSubscription(chart: CandlestickChartView) {
        val session = stateStore.loadCurrent()
        val symbol = ChartSessionState.normalizeSymbol(session.symbol)
        val timeframe = ChartSessionState.normalizeTimeframe(session.timeframe)
        if (symbol != liveSymbol || timeframe != liveTimeframe) {
            liveSymbol = symbol
            liveTimeframe = timeframe
            liveSnapshot = null
            liveBook = null
            loadBootstrap(chart, symbol, timeframe)
            startSocket(symbol, timeframe)
        } else if (socket == null) {
            startSocket(symbol, timeframe)
        }
    }

    private fun loadBootstrap(chart: CandlestickChartView, symbol: String, timeframe: String) {
        if (!loading.compareAndSet(false, true)) return
        io.execute {
            try {
                val snap = marketClient.load("BYBIT", symbol, timeframe, 600)
                main.post {
                    if (symbol != liveSymbol || timeframe != liveTimeframe) return@post
                    liveSnapshot = snap
                    val current = chartRef.get()
                    if (current === chart && current.isAttachedToWindow) {
                        current.setSnapshot(snap, preserveViewport = true)
                        scheduleRemotePush(force = true)
                    }
                }
            } catch (_: Throwable) {
                // ProAnalysisPanel REST refresh remains the fallback path.
            } finally {
                loading.set(false)
            }
        }
    }

    private fun startSocket(symbol: String, timeframe: String) {
        stopSocket()
        liveSymbol = symbol
        liveTimeframe = timeframe
        socket = BybitMarketWebSocket(symbol, timeframe, object : BybitMarketWebSocketListener {
            override fun onConnected(endpoint: String) {
                main.post {
                    wsConnected = true
                    scheduleRemotePush(force = true)
                }
            }

            override fun onDisconnected(reason: String) {
                main.post {
                    wsConnected = false
                    scheduleRemotePush(force = false)
                }
            }

            override fun onTicker(value: BybitLiveTicker) {
                if (value.symbol != liveSymbol || value.lastPrice <= 0.0) return
                main.post {
                    val current = liveSnapshot ?: return@post
                    if (current.requestedSymbol != liveSymbol || current.interval != liveTimeframe) return@post
                    val updated = current.copy(
                        lastPrice = value.lastPrice,
                        capturedAt = System.currentTimeMillis()
                    )
                    liveSnapshot = updated
                    chartRef.get()?.takeIf { it.isAttachedToWindow }?.setSnapshot(updated, preserveViewport = true)
                    scheduleRemotePush(force = false)
                }
            }

            override fun onKline(candle: MarketCandle, confirmed: Boolean) {
                // Bybit has no native 3D stream. 3D keeps REST candles while ticker stays live.
                if (liveTimeframe == "3d") return
                main.post {
                    val current = liveSnapshot ?: return@post
                    if (current.requestedSymbol != liveSymbol || current.interval != liveTimeframe) return@post
                    val rows = current.candles.toMutableList()
                    val index = rows.indexOfLast { it.time == candle.time }
                    when {
                        index >= 0 -> rows[index] = candle
                        rows.isEmpty() || candle.time > rows.last().time -> rows.add(candle)
                        else -> {
                            rows.add(candle)
                            rows.sortBy { it.time }
                        }
                    }
                    val trimmed = if (rows.size > 1000) rows.takeLast(1000) else rows
                    val updated = current.copy(
                        candles = trimmed,
                        lastPrice = candle.close,
                        capturedAt = System.currentTimeMillis()
                    )
                    liveSnapshot = updated
                    chartRef.get()?.takeIf { it.isAttachedToWindow }?.setSnapshot(updated, preserveViewport = true)
                    scheduleRemotePush(force = confirmed)
                }
            }

            override fun onTrade(symbol: String, price: Double, quantity: Double, side: String, timestamp: Long) {
                if (symbol != liveSymbol) return
                main.post {
                    lastTradePrice = price
                    lastTradeQuantity = quantity
                    lastTradeSide = side
                    lastTradeAt = timestamp
                    scheduleRemotePush(force = false)
                }
            }

            override fun onOrderBook(value: BybitLiveBook) {
                if (value.symbol != liveSymbol) return
                main.post {
                    liveBook = value
                    scheduleRemotePush(force = false)
                }
            }
        }).also { it.start() }
    }

    private fun stopSocket() {
        socket?.stop()
        socket = null
        wsConnected = false
    }

    private fun scheduleRemotePush(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastStatePushAt < 2_500L) return
        if (!pushing.compareAndSet(false, true)) return

        val chart = chartRef.get()
        val snap = liveSnapshot
        if (chart == null || snap == null || !chart.isAttachedToWindow) {
            pushing.set(false)
            return
        }

        val viewport = chart.exportViewport()
        val session = stateStore.loadCurrent().copy(
            symbol = liveSymbol,
            timeframe = liveTimeframe,
            viewport = viewport,
            indicators = chart.currentIndicators(),
            drawings = chart.currentDrawings()
        )
        stateStore.save(session)
        val market = remote.marketJson(snap, viewport).apply {
            put("transport", if (wsConnected) "BYBIT_WEBSOCKET" else "REST_FALLBACK")
            liveBook?.let {
                put("bestBid", it.bestBid)
                put("bestAsk", it.bestAsk)
                put("orderBookAt", it.timestamp)
            }
            if (lastTradePrice > 0.0) {
                put("lastTradePrice", lastTradePrice)
                put("lastTradeQuantity", lastTradeQuantity)
                put("lastTradeSide", lastTradeSide)
                put("lastTradeAt", lastTradeAt)
            }
        }
        val shouldCapture = now - lastSnapshotUploadAt >= 15_000L
        val png = if (shouldCapture && chart.width > 0 && chart.height > 0) chart.capturePng() else ByteArray(0)
        lastStatePushAt = now
        if (png.isNotEmpty()) lastSnapshotUploadAt = now

        io.execute {
            try {
                runCatching { remote.pushState(session, market) }
                if (png.isNotEmpty()) runCatching { remote.uploadSnapshot(png) }
            } finally {
                pushing.set(false)
            }
        }
    }

    private fun findChart(view: View?): CandlestickChartView? {
        if (view == null || !view.isShown) return null
        if (view is CandlestickChartView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findChart(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
