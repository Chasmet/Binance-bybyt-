package com.chk.binancebybit

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

data class TrackingBookLevel(val price: Double, val quantity: Double)

interface TrackingMarketListener {
    fun onConnection(exchange: String, connected: Boolean, detail: String) {}
    fun onBook(exchange: String, symbol: String, bids: List<TrackingBookLevel>, asks: List<TrackingBookLevel>, timestamp: Long) {}
    fun onTrade(exchange: String, symbol: String, price: Double, quantity: Double, side: String, timestamp: Long) {}
    fun onTicker(exchange: String, symbol: String, lastPrice: Double, timestamp: Long) {}
}

/**
 * Two public WebSockets only: one Binance multiplexed connection and one Bybit connection.
 * No private API key is required and raw packets remain on-device.
 */
class TrackingSocketManager(
    private val listener: TrackingMarketListener
) {
    private val stopped = AtomicBoolean(true)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var binanceSymbols: Set<String> = emptySet()
    @Volatile private var bybitSymbols: Set<String> = emptySet()
    private var binanceSocket: WebSocket? = null
    private var bybitSocket: WebSocket? = null
    private var binanceReconnect: ScheduledFuture<*>? = null
    private var bybitReconnect: ScheduledFuture<*>? = null
    private var binanceFailures = 0
    private var bybitFailures = 0
    private var binanceEndpoint = 0
    private var bybitEndpoint = 0

    private val bybitBooks = ConcurrentHashMap<String, MutableBook>()

    @Synchronized fun start(binance: Set<String>, bybit: Set<String>) {
        binanceSymbols = binance.filter { validSymbol(it) }.map { it.uppercase(Locale.US) }.toSet()
        bybitSymbols = bybit.filter { validSymbol(it) }.map { it.uppercase(Locale.US) }.toSet()
        if (!stopped.compareAndSet(true, false)) {
            reconnectAll("subscription refresh")
            return
        }
        connectBinance()
        connectBybit()
    }

    @Synchronized fun update(binance: Set<String>, bybit: Set<String>) {
        val b = binance.filter { validSymbol(it) }.map { it.uppercase(Locale.US) }.toSet()
        val y = bybit.filter { validSymbol(it) }.map { it.uppercase(Locale.US) }.toSet()
        if (b == binanceSymbols && y == bybitSymbols) return
        binanceSymbols = b
        bybitSymbols = y
        bybitBooks.keys.retainAll(y)
        if (!stopped.get()) reconnectAll("held assets changed")
    }

    @Synchronized fun stop() {
        if (stopped.getAndSet(true)) return
        binanceReconnect?.cancel(false); binanceReconnect = null
        bybitReconnect?.cancel(false); bybitReconnect = null
        binanceSocket?.close(1000, "CHK Tracking stopped"); binanceSocket = null
        bybitSocket?.close(1000, "CHK Tracking stopped"); bybitSocket = null
        bybitBooks.clear()
        scheduler.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun reconnectAll(reason: String) {
        binanceReconnect?.cancel(false); bybitReconnect?.cancel(false)
        binanceSocket?.close(1000, reason); bybitSocket?.close(1000, reason)
        binanceSocket = null; bybitSocket = null
        scheduler.schedule({ if (!stopped.get()) connectBinance() }, 250, TimeUnit.MILLISECONDS)
        scheduler.schedule({ if (!stopped.get()) connectBybit() }, 350, TimeUnit.MILLISECONDS)
    }

    private fun connectBinance() {
        if (stopped.get()) return
        if (binanceSymbols.isEmpty()) {
            listener.onConnection("BINANCE", false, "Aucune paire détenue disponible")
            return
        }
        val endpoint = BINANCE_ENDPOINTS[binanceEndpoint.coerceIn(0, BINANCE_ENDPOINTS.lastIndex)]
        binanceSocket = client.newWebSocket(Request.Builder().url(endpoint).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                binanceFailures = 0
                listener.onConnection("BINANCE", true, endpoint)
                val params = JSONArray()
                binanceSymbols.sorted().forEach { symbol ->
                    val s = symbol.lowercase(Locale.US)
                    params.put("$s@depth20@100ms")
                    params.put("$s@aggTrade")
                    params.put("$s@ticker")
                }
                webSocket.send(JSONObject().apply {
                    put("method", "SUBSCRIBE")
                    put("params", params)
                    put("id", 1979)
                }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleBinance(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onConnection("BINANCE", false, "$code • $reason")
                scheduleBinanceReconnect()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onConnection("BINANCE", false, t.message ?: "WebSocket Binance indisponible")
                if (binanceEndpoint < BINANCE_ENDPOINTS.lastIndex) binanceEndpoint++
                scheduleBinanceReconnect()
            }
        })
    }

    private fun handleBinance(text: String) {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (root.has("result")) return
        val stream = root.optString("stream")
        val data = root.optJSONObject("data") ?: root
        val symbol = when {
            data.optString("s").isNotBlank() -> data.optString("s").uppercase(Locale.US)
            stream.contains('@') -> stream.substringBefore('@').uppercase(Locale.US)
            else -> ""
        }
        if (!validSymbol(symbol)) return
        val event = data.optString("e")
        when {
            stream.contains("@depth") || (data.has("bids") && data.has("asks")) -> {
                val bids = levels(data.optJSONArray("bids") ?: data.optJSONArray("b") ?: JSONArray(), true)
                val asks = levels(data.optJSONArray("asks") ?: data.optJSONArray("a") ?: JSONArray(), false)
                listener.onBook("BINANCE", symbol, bids, asks, System.currentTimeMillis())
            }
            stream.contains("@aggTrade") || event == "aggTrade" -> {
                val price = data.optString("p").toDoubleOrNull() ?: return
                val qty = data.optString("q").toDoubleOrNull() ?: return
                // m=true => buyer is maker => taker sold. Otherwise taker bought.
                val side = if (data.optBoolean("m", false)) "SELL" else "BUY"
                listener.onTrade("BINANCE", symbol, price, qty, side, data.optLong("T", System.currentTimeMillis()))
            }
            stream.contains("@ticker") || event == "24hrTicker" -> {
                val last = data.optString("c").toDoubleOrNull() ?: return
                listener.onTicker("BINANCE", symbol, last, data.optLong("E", System.currentTimeMillis()))
            }
        }
    }

    private fun connectBybit() {
        if (stopped.get()) return
        if (bybitSymbols.isEmpty()) {
            listener.onConnection("BYBIT", false, "Aucune paire détenue disponible")
            return
        }
        val endpoint = BYBIT_ENDPOINTS[bybitEndpoint.coerceIn(0, BYBIT_ENDPOINTS.lastIndex)]
        bybitSocket = client.newWebSocket(Request.Builder().url(endpoint).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                bybitFailures = 0
                listener.onConnection("BYBIT", true, endpoint)
                val args = JSONArray()
                bybitSymbols.sorted().forEach { symbol ->
                    args.put("orderbook.50.$symbol")
                    args.put("publicTrade.$symbol")
                    args.put("tickers.$symbol")
                }
                webSocket.send(JSONObject().apply { put("op", "subscribe"); put("args", args) }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleBybit(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onConnection("BYBIT", false, "$code • $reason")
                scheduleBybitReconnect()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onConnection("BYBIT", false, t.message ?: "WebSocket Bybit indisponible")
                if (bybitEndpoint < BYBIT_ENDPOINTS.lastIndex) bybitEndpoint++
                scheduleBybitReconnect()
            }
        })
    }

    private fun handleBybit(text: String) {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        val topic = root.optString("topic")
        if (topic.isBlank()) return
        when {
            topic.startsWith("orderbook.") -> {
                val data = root.optJSONObject("data") ?: return
                val symbol = data.optString("s").uppercase(Locale.US)
                if (!validSymbol(symbol)) return
                val book = bybitBooks.getOrPut(symbol) { MutableBook() }
                synchronized(book) {
                    if (root.optString("type").equals("snapshot", true)) {
                        book.bids.clear(); book.asks.clear()
                    }
                    applyDelta(book.bids, data.optJSONArray("b") ?: JSONArray())
                    applyDelta(book.asks, data.optJSONArray("a") ?: JSONArray())
                    listener.onBook(
                        "BYBIT",
                        symbol,
                        book.bids.entries.asSequence().filter { it.value > 0.0 }.sortedByDescending { it.key }.take(50).map { TrackingBookLevel(it.key, it.value) }.toList(),
                        book.asks.entries.asSequence().filter { it.value > 0.0 }.sortedBy { it.key }.take(50).map { TrackingBookLevel(it.key, it.value) }.toList(),
                        root.optLong("ts", System.currentTimeMillis())
                    )
                }
            }
            topic.startsWith("publicTrade.") -> {
                val rows = root.optJSONArray("data") ?: return
                for (i in 0 until rows.length()) {
                    val d = rows.optJSONObject(i) ?: continue
                    val symbol = d.optString("s").uppercase(Locale.US)
                    val price = d.optString("p").toDoubleOrNull() ?: continue
                    val qty = d.optString("v").toDoubleOrNull() ?: continue
                    listener.onTrade("BYBIT", symbol, price, qty, d.optString("S").uppercase(Locale.US), d.optLong("T", System.currentTimeMillis()))
                }
            }
            topic.startsWith("tickers.") -> {
                val raw = root.opt("data")
                val d = when (raw) { is JSONObject -> raw; is JSONArray -> raw.optJSONObject(0); else -> null } ?: return
                val symbol = d.optString("symbol", topic.substringAfterLast('.')).uppercase(Locale.US)
                val last = d.optString("lastPrice").toDoubleOrNull() ?: return
                listener.onTicker("BYBIT", symbol, last, root.optLong("ts", System.currentTimeMillis()))
            }
        }
    }

    @Synchronized private fun scheduleBinanceReconnect() {
        if (stopped.get() || scheduler.isShutdown || binanceReconnect?.isDone == false) return
        binanceFailures++
        val delay = min(30L, 1L shl min(binanceFailures, 5))
        binanceReconnect = scheduler.schedule({ if (!stopped.get()) connectBinance() }, delay, TimeUnit.SECONDS)
    }

    @Synchronized private fun scheduleBybitReconnect() {
        if (stopped.get() || scheduler.isShutdown || bybitReconnect?.isDone == false) return
        bybitFailures++
        val delay = min(30L, 1L shl min(bybitFailures, 5))
        bybitReconnect = scheduler.schedule({ if (!stopped.get()) connectBybit() }, delay, TimeUnit.SECONDS)
    }

    private fun levels(rows: JSONArray, bids: Boolean): List<TrackingBookLevel> {
        val out = ArrayList<TrackingBookLevel>(rows.length())
        for (i in 0 until rows.length()) {
            val r = rows.optJSONArray(i) ?: continue
            val p = r.optString(0).toDoubleOrNull() ?: continue
            val q = r.optString(1).toDoubleOrNull() ?: continue
            if (p > 0.0 && q > 0.0) out += TrackingBookLevel(p, q)
        }
        return if (bids) out.sortedByDescending { it.price } else out.sortedBy { it.price }
    }

    private fun applyDelta(target: MutableMap<Double, Double>, rows: JSONArray) {
        for (i in 0 until rows.length()) {
            val r = rows.optJSONArray(i) ?: continue
            val p = r.optString(0).toDoubleOrNull() ?: continue
            val q = r.optString(1).toDoubleOrNull() ?: continue
            if (q <= 0.0) target.remove(p) else target[p] = q
        }
    }

    private fun validSymbol(value: String): Boolean = value.matches(Regex("^[A-Z0-9]{4,28}$"))

    private class MutableBook {
        val bids = HashMap<Double, Double>()
        val asks = HashMap<Double, Double>()
    }

    companion object {
        private val BINANCE_ENDPOINTS = listOf(
            "wss://stream.binance.com:9443/stream",
            "wss://stream.binance.com:443/stream"
        )
        private val BYBIT_ENDPOINTS = listOf(
            "wss://stream.bybit.eu/v5/public/spot",
            "wss://stream.bybit.com/v5/public/spot"
        )
    }
}
