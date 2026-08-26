package com.chk.binancebybit

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

data class BybitLiveTicker(
    val symbol: String,
    val lastPrice: Double,
    val change24hPct: Double,
    val high24h: Double,
    val low24h: Double
)

data class BybitLiveBook(
    val symbol: String,
    val bestBid: Double,
    val bestAsk: Double,
    val timestamp: Long
)

interface BybitMarketWebSocketListener {
    fun onConnected(endpoint: String) {}
    fun onDisconnected(reason: String) {}
    fun onTicker(value: BybitLiveTicker) {}
    fun onKline(candle: MarketCandle, confirmed: Boolean) {}
    fun onTrade(symbol: String, price: Double, quantity: Double, side: String, timestamp: Long) {}
    fun onOrderBook(value: BybitLiveBook) {}
}

class BybitMarketWebSocket(
    private var symbol: String,
    private var timeframe: String,
    private val listener: BybitMarketWebSocketListener
) {
    private val stopped = AtomicBoolean(true)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null
    private var reconnect: ScheduledFuture<*>? = null
    private var failures = 0
    private var endpointIndex = 0

    @Synchronized
    fun start() {
        if (!stopped.compareAndSet(true, false)) return
        failures = 0
        endpointIndex = 0
        connect()
    }

    @Synchronized
    fun stop() {
        stopped.set(true)
        reconnect?.cancel(false)
        reconnect = null
        socket?.close(1000, "CHK chart closed")
        socket = null
    }

    @Synchronized
    fun changeSubscription(newSymbol: String, newTimeframe: String) {
        symbol = ChartSessionState.normalizeSymbol(newSymbol)
        timeframe = ChartSessionState.normalizeTimeframe(newTimeframe)
        if (!stopped.get()) {
            reconnect?.cancel(false)
            socket?.close(1000, "CHK chart subscription changed")
            socket = null
            failures = 0
            endpointIndex = 0
            scheduler.schedule({ if (!stopped.get()) connect() }, 150, TimeUnit.MILLISECONDS)
        }
    }

    private fun connect() {
        if (stopped.get()) return
        val endpoint = ENDPOINTS[endpointIndex.coerceIn(0, ENDPOINTS.lastIndex)]
        val request = Request.Builder().url(endpoint).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                failures = 0
                listener.onConnected(endpoint)
                val tf = wsInterval(timeframe)
                val args = JSONArray().apply {
                    put("tickers.$symbol")
                    if (tf != null) put("kline.$tf.$symbol")
                    put("publicTrade.$symbol")
                    put("orderbook.1.$symbol")
                }
                webSocket.send(JSONObject().apply { put("op", "subscribe"); put("args", args) }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onDisconnected("$code • $reason")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onDisconnected(t.message ?: "WebSocket indisponible")
                if (endpointIndex == 0) endpointIndex = 1
                scheduleReconnect()
            }
        })
    }

    private fun handleMessage(text: String) {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        val topic = root.optString("topic")
        when {
            topic.startsWith("tickers.") -> {
                val d = root.optJSONArray("data")?.optJSONObject(0) ?: return
                val last = d.optString("lastPrice").toDoubleOrNull() ?: return
                listener.onTicker(BybitLiveTicker(
                    symbol = d.optString("symbol", symbol),
                    lastPrice = last,
                    change24hPct = (d.optString("price24hPcnt").toDoubleOrNull() ?: 0.0) * 100.0,
                    high24h = d.optString("highPrice24h").toDoubleOrNull() ?: 0.0,
                    low24h = d.optString("lowPrice24h").toDoubleOrNull() ?: 0.0
                ))
            }
            topic.startsWith("kline.") -> {
                val d = root.optJSONArray("data")?.optJSONObject(0) ?: return
                val candle = MarketCandle(
                    time = d.optLong("start"),
                    open = d.optString("open").toDoubleOrNull() ?: return,
                    high = d.optString("high").toDoubleOrNull() ?: return,
                    low = d.optString("low").toDoubleOrNull() ?: return,
                    close = d.optString("close").toDoubleOrNull() ?: return,
                    volume = d.optString("volume").toDoubleOrNull() ?: 0.0
                )
                listener.onKline(candle, d.optBoolean("confirm", false))
            }
            topic.startsWith("publicTrade.") -> {
                val arr = root.optJSONArray("data") ?: return
                val d = arr.optJSONObject(arr.length() - 1) ?: return
                listener.onTrade(
                    symbol = d.optString("s", symbol),
                    price = d.optString("p").toDoubleOrNull() ?: return,
                    quantity = d.optString("v").toDoubleOrNull() ?: 0.0,
                    side = d.optString("S"),
                    timestamp = d.optLong("T")
                )
            }
            topic.startsWith("orderbook.") -> {
                val d = root.optJSONObject("data") ?: return
                val bids = d.optJSONArray("b") ?: JSONArray()
                val asks = d.optJSONArray("a") ?: JSONArray()
                val bid = bids.optJSONArray(0)?.optString(0)?.toDoubleOrNull() ?: 0.0
                val ask = asks.optJSONArray(0)?.optString(0)?.toDoubleOrNull() ?: 0.0
                if (bid > 0.0 || ask > 0.0) listener.onOrderBook(BybitLiveBook(d.optString("s", symbol), bid, ask, root.optLong("ts")))
            }
        }
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (stopped.get() || reconnect?.isDone == false) return
        failures += 1
        if (failures >= 2) endpointIndex = min(ENDPOINTS.lastIndex, 1)
        val delay = min(30L, 1L shl min(failures, 5))
        reconnect = scheduler.schedule({ if (!stopped.get()) connect() }, delay, TimeUnit.SECONDS)
    }

    private fun wsInterval(tf: String): String? = when (tf.lowercase(Locale.US)) {
        "1m" -> "1"; "3m" -> "3"; "5m" -> "5"; "15m" -> "15"; "30m" -> "30"
        "1h" -> "60"; "2h" -> "120"; "4h" -> "240"; "6h" -> "360"; "12h" -> "720"
        "1d" -> "D"; "1w" -> "W"
        "3d" -> "D"
        else -> null
    }

    companion object {
        private val ENDPOINTS = listOf(
            "wss://stream.bybit.eu/v5/public/spot",
            "wss://stream.bybit.com/v5/public/spot"
        )
    }
}
