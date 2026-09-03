package com.chk.binancebybit

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

interface OrderBookHunterSocketListener {
    fun onConnected(endpoint: String) {}
    fun onDisconnected(reason: String) {}
    fun onTicker(ticker: HunterTicker) {}
    fun onTrade(trade: HunterTrade) {}
    fun onBookUpdated(symbol: String, timestamp: Long) {}
    fun onDesync(symbol: String, reason: String) {}
}

class OrderBookHunterWebSocket(
    symbols: Collection<String>,
    private val listener: OrderBookHunterSocketListener
) {
    private data class MutableBook(
        val bids: TreeMap<Double, Double> = TreeMap(compareByDescending { it }),
        val asks: TreeMap<Double, Double> = TreeMap(),
        var updateId: Long = 0L,
        var sequence: Long = 0L,
        var timestamp: Long = 0L,
        var matchingTimestamp: Long = 0L,
        var synchronized: Boolean = false
    )

    private val stopped = AtomicBoolean(true)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val books = ConcurrentHashMap<String, MutableBook>()
    private var watched = normalizeSymbols(symbols)
    private var socket: WebSocket? = null
    private var reconnect: ScheduledFuture<*>? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var failures = 0
    private var endpointIndex = 0

    @Synchronized
    fun start() {
        if (watched.isEmpty() || !stopped.compareAndSet(true, false)) return
        connect()
    }

    @Synchronized
    fun stop() {
        if (stopped.getAndSet(true)) return
        reconnect?.cancel(false)
        reconnect = null
        heartbeat?.cancel(false)
        heartbeat = null
        socket?.close(1000, "CHK OrderBook Hunter stopped")
        socket = null
        books.clear()
        scheduler.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Synchronized
    fun replaceSymbols(symbols: Collection<String>) {
        watched = normalizeSymbols(symbols)
        books.keys.retainAll(watched.toSet())
        if (stopped.get()) return
        reconnect?.cancel(false)
        reconnect = null
        heartbeat?.cancel(false)
        heartbeat = null
        socket?.close(1000, "CHK OrderBook Hunter subscriptions changed")
        socket = null
        failures = 0
        endpointIndex = 0
        scheduler.schedule({
            if (!stopped.get() && watched.isNotEmpty()) connect()
        }, 250, TimeUnit.MILLISECONDS)
    }

    fun snapshot(symbolValue: String): HunterBookSnapshot? {
        val symbol = OrderBookHunterStore.normalizeSymbol(symbolValue)
        val book = books[symbol] ?: return null
        synchronized(book) {
            return HunterBookSnapshot(
                symbol = symbol,
                bids = book.bids.entries.take(DEPTH).map { HunterBookLevel(it.key, it.value) },
                asks = book.asks.entries.take(DEPTH).map { HunterBookLevel(it.key, it.value) },
                updateId = book.updateId,
                sequence = book.sequence,
                timestamp = book.timestamp,
                matchingTimestamp = book.matchingTimestamp,
                synchronized = book.synchronized
            )
        }
    }

    private fun connect() {
        if (stopped.get() || watched.isEmpty()) return
        val endpoint = ENDPOINTS[endpointIndex.coerceIn(0, ENDPOINTS.lastIndex)]
        socket = client.newWebSocket(Request.Builder().url(endpoint).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                failures = 0
                books.clear()
                listener.onConnected(endpoint)

                subscriptionBatches(watched).forEachIndexed { index, topics ->
                    val args = JSONArray()
                    topics.forEach(args::put)
                    val request = JSONObject()
                        .put("req_id", "hunter-${index + 1}")
                        .put("op", "subscribe")
                        .put("args", args)
                    webSocket.send(request.toString())
                }

                heartbeat?.cancel(false)
                heartbeat = scheduler.scheduleAtFixedRate({
                    if (!stopped.get()) socket?.send(JSONObject().put("op", "ping").toString())
                }, 20, 20, TimeUnit.SECONDS)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

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
        if (root.optString("op") == "subscribe" && root.has("success") && !root.optBoolean("success")) {
            listener.onDisconnected("Abonnement Bybit refusé : ${root.optString("ret_msg")}")
            return
        }
        val topic = root.optString("topic")
        when {
            topic.startsWith("tickers.") -> handleTicker(root)
            topic.startsWith("publicTrade.") -> handleTrades(root)
            topic.startsWith("orderbook.") -> handleBook(root)
        }
    }

    private fun handleTicker(root: JSONObject) {
        val data = when (val raw = root.opt("data")) {
            is JSONObject -> raw
            is JSONArray -> raw.optJSONObject(0)
            else -> null
        } ?: return
        val symbol = data.optString("symbol").ifBlank { root.optString("topic").substringAfterLast('.') }
        val last = data.optString("lastPrice").toDoubleOrNull() ?: return
        listener.onTicker(
            HunterTicker(
                symbol = symbol,
                lastPrice = last,
                change24hPct = (data.optString("price24hPcnt").toDoubleOrNull() ?: 0.0) * 100.0,
                turnover24h = data.optString("turnover24h").toDoubleOrNull() ?: 0.0,
                volume24h = data.optString("volume24h").toDoubleOrNull() ?: 0.0,
                timestamp = root.optLong("ts", System.currentTimeMillis())
            )
        )
    }

    private fun handleTrades(root: JSONObject) {
        val arr = root.optJSONArray("data") ?: return
        for (i in 0 until arr.length()) {
            val data = arr.optJSONObject(i) ?: continue
            val price = data.optString("p").toDoubleOrNull() ?: continue
            val qty = data.optString("v").toDoubleOrNull() ?: continue
            listener.onTrade(
                HunterTrade(
                    symbol = data.optString("s"),
                    price = price,
                    qty = qty,
                    side = data.optString("S"),
                    timestamp = data.optLong("T", root.optLong("ts")),
                    sequence = data.optLong("seq")
                )
            )
        }
    }

    private fun handleBook(root: JSONObject) {
        val data = root.optJSONObject("data") ?: return
        val symbol = data.optString("s").ifBlank { root.optString("topic").substringAfterLast('.') }
        val type = root.optString("type")
        val updateId = data.optLong("u")
        val sequence = data.optLong("seq")
        val book = books.getOrPut(symbol) { MutableBook() }
        var desync: String? = null

        synchronized(book) {
            val reset = type == "snapshot" || updateId == 1L
            if (reset) {
                book.bids.clear()
                book.asks.clear()
                book.synchronized = true
            } else if (!book.synchronized) {
                desync = "delta reçu avant snapshot"
            } else if (book.updateId > 0L && updateId > 0L && updateId <= book.updateId) {
                desync = "updateId non monotone ${book.updateId}→$updateId"
            } else if (book.sequence > 0L && sequence > 0L && sequence < book.sequence) {
                desync = "sequence non monotone ${book.sequence}→$sequence"
            }

            if (desync == null) {
                applyLevels(book.bids, data.optJSONArray("b"))
                applyLevels(book.asks, data.optJSONArray("a"))
                trim(book.bids)
                trim(book.asks)
                book.updateId = updateId
                book.sequence = sequence
                book.timestamp = root.optLong("ts", System.currentTimeMillis())
                book.matchingTimestamp = root.optLong("cts", book.timestamp)
            } else {
                book.synchronized = false
            }
        }

        if (desync != null) listener.onDesync(symbol, desync!!) else listener.onBookUpdated(symbol, root.optLong("ts"))
    }

    private fun applyLevels(target: TreeMap<Double, Double>, arr: JSONArray?) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val level = arr.optJSONArray(i) ?: continue
            val price = level.optString(0).toDoubleOrNull() ?: continue
            val qty = level.optString(1).toDoubleOrNull() ?: continue
            if (qty <= 0.0) target.remove(price) else target[price] = qty
        }
    }

    private fun trim(map: TreeMap<Double, Double>) {
        while (map.size > DEPTH) map.pollLastEntry()
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (stopped.get() || scheduler.isShutdown || reconnect?.isDone == false) return
        failures++
        if (failures >= 2) endpointIndex = min(ENDPOINTS.lastIndex, 1)
        val delay = min(30L, 1L shl min(failures, 5))
        reconnect = scheduler.schedule({
            if (!stopped.get()) connect()
        }, delay, TimeUnit.SECONDS)
    }

    private fun normalizeSymbols(values: Collection<String>): List<String> = values
        .mapNotNull { runCatching { OrderBookHunterStore.normalizeSymbol(it) }.getOrNull() }
        .distinct()
        .take(MAX_SYMBOLS)

    companion object {
        const val DEPTH = 50
        const val MAX_SYMBOLS = 20
        const val MAX_SPOT_TOPICS_PER_SUBSCRIBE = 10
        private val ENDPOINTS = listOf(
            "wss://stream.bybit.eu/v5/public/spot",
            "wss://stream.bybit.com/v5/public/spot"
        )

        fun subscriptionBatches(symbols: Collection<String>): List<List<String>> {
            val topics = symbols.distinct().take(MAX_SYMBOLS).flatMap { symbol ->
                listOf("tickers.$symbol", "publicTrade.$symbol", "orderbook.$DEPTH.$symbol")
            }
            return topics.chunked(MAX_SPOT_TOPICS_PER_SUBSCRIBE)
        }
    }
}
