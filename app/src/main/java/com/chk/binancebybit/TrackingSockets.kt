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

data class TrackingQuote(
    val lastPrice: Double,
    val bid: Double,
    val ask: Double,
    val timestamp: Long,
    val source: String
)

interface TrackingMarketListener {
    fun onConnection(exchange: String, connected: Boolean, detail: String) {}
    fun onBook(exchange: String, symbol: String, bids: List<TrackingBookLevel>, asks: List<TrackingBookLevel>, timestamp: Long) {}
    fun onTrade(exchange: String, symbol: String, price: Double, quantity: Double, side: String, timestamp: Long) {}
    fun onTicker(exchange: String, symbol: String, lastPrice: Double, timestamp: Long) {}
    fun onQuote(exchange: String, symbol: String, quote: TrackingQuote) {}
}

/**
 * Public, event-driven market feed. Raw packets stay on-device.
 *
 * The sockets may receive many events, especially Bybit depth deltas. We merge those deltas in
 * memory and dispatch only the latest useful snapshot at the cadence selected by the battery mode.
 * There is no market polling loop.
 */
class TrackingSocketManager(
    private val listener: TrackingMarketListener,
    initialProfile: TrackingPowerProfile = TrackingPowerProfile.of(TrackingPowerMode.BALANCED)
) {
    private val stopped = AtomicBoolean(true)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "CHK-Tracking-WS").apply { isDaemon = true } }
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var profile = initialProfile
    @Volatile private var binanceSymbols: Set<String> = emptySet()
    @Volatile private var bybitSymbols: Set<String> = emptySet()
    private var binanceSocket: WebSocket? = null
    private var bybitSocket: WebSocket? = null
    private var binanceReconnect: ScheduledFuture<*>? = null
    private var bybitReconnect: ScheduledFuture<*>? = null
    private var bybitHeartbeat: ScheduledFuture<*>? = null
    private var binanceFailures = 0
    private var bybitFailures = 0
    private var binanceEndpoint = 0
    private var bybitEndpoint = 0
    private var bybitSubscribeExpected = 0
    private var bybitSubscribeAccepted = 0

    private val bybitBooks = ConcurrentHashMap<String, MutableBook>()
    private val quotes = ConcurrentHashMap<String, MutableQuote>()
    private val pendingBooks = ConcurrentHashMap<String, PendingBook>()
    private val bookTasks = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val quoteTasks = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val tradeBuckets = ConcurrentHashMap<String, TradeBucket>()
    private val tradeTasks = ConcurrentHashMap<String, ScheduledFuture<*>>()

    @Synchronized fun start(binance: Set<String>, bybit: Set<String>) {
        binanceSymbols = normalize(binance)
        bybitSymbols = normalize(bybit)
        if (!stopped.compareAndSet(true, false)) {
            reconnectAll("subscription refresh")
            return
        }
        connectBinance()
        connectBybit()
    }

    @Synchronized fun update(binance: Set<String>, bybit: Set<String>, newProfile: TrackingPowerProfile = profile) {
        val b = normalize(binance)
        val y = normalize(bybit)
        val symbolsChanged = b != binanceSymbols || y != bybitSymbols
        val depthCadenceChanged = profile.mode == TrackingPowerMode.PERFORMANCE != (newProfile.mode == TrackingPowerMode.PERFORMANCE)
        profile = newProfile
        if (!symbolsChanged && !depthCadenceChanged) return
        binanceSymbols = b
        bybitSymbols = y
        bybitBooks.keys.retainAll(y)
        quotes.keys.removeAll { key ->
            val symbol = key.substringAfter(':')
            (key.startsWith("BINANCE:") && symbol !in b) || (key.startsWith("BYBIT:") && symbol !in y)
        }
        if (!stopped.get()) reconnectAll(if (symbolsChanged) "tracked assets changed" else "power mode changed")
    }

    @Synchronized fun setProfile(newProfile: TrackingPowerProfile) {
        update(binanceSymbols, bybitSymbols, newProfile)
    }

    @Synchronized fun stop() {
        if (stopped.getAndSet(true)) return
        binanceReconnect?.cancel(false); binanceReconnect = null
        bybitReconnect?.cancel(false); bybitReconnect = null
        bybitHeartbeat?.cancel(false); bybitHeartbeat = null
        bookTasks.values.forEach { it.cancel(false) }; bookTasks.clear()
        quoteTasks.values.forEach { it.cancel(false) }; quoteTasks.clear()
        tradeTasks.values.forEach { it.cancel(false) }; tradeTasks.clear()
        binanceSocket?.close(1000, "CHK Tracking stopped"); binanceSocket = null
        bybitSocket?.close(1000, "CHK Tracking stopped"); bybitSocket = null
        bybitBooks.clear(); pendingBooks.clear(); tradeBuckets.clear(); quotes.clear()
        scheduler.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun reconnectAll(reason: String) {
        binanceReconnect?.cancel(false); bybitReconnect?.cancel(false)
        bybitHeartbeat?.cancel(false); bybitHeartbeat = null
        binanceSocket?.close(1000, reason); bybitSocket?.close(1000, reason)
        binanceSocket = null; bybitSocket = null
        scheduler.schedule({ if (!stopped.get()) connectBinance() }, 350, TimeUnit.MILLISECONDS)
        scheduler.schedule({ if (!stopped.get()) connectBybit() }, 500, TimeUnit.MILLISECONDS)
    }

    private fun connectBinance() {
        if (stopped.get()) return
        if (binanceSymbols.isEmpty()) {
            listener.onConnection("BINANCE", false, "Aucune paire prioritaire disponible")
            return
        }
        val endpoint = BINANCE_ENDPOINTS[binanceEndpoint.coerceIn(0, BINANCE_ENDPOINTS.lastIndex)]
        binanceSocket = client.newWebSocket(Request.Builder().url(endpoint).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                binanceFailures = 0
                listener.onConnection("BINANCE", true, "$endpoint • transport connecté • feed en chauffe")
                val params = JSONArray()
                val depthSuffix = if (profile.mode == TrackingPowerMode.PERFORMANCE) "@depth20@100ms" else "@depth20@1000ms"
                binanceSymbols.sorted().forEach { symbol ->
                    val s = symbol.lowercase(Locale.US)
                    params.put(s + depthSuffix)
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
                if (bids.isNotEmpty() || asks.isNotEmpty()) {
                    val ts = data.optLong("E", System.currentTimeMillis())
                    scheduleBook("BINANCE", symbol, bids, asks, ts)
                    updateQuoteFromBook("BINANCE", symbol, bids, asks, ts)
                }
            }
            stream.contains("@aggTrade") || event == "aggTrade" -> {
                val price = data.optString("p").toDoubleOrNull() ?: return
                val qty = data.optString("q").toDoubleOrNull() ?: return
                val side = if (data.optBoolean("m", false)) "SELL" else "BUY"
                val ts = data.optLong("T", System.currentTimeMillis())
                updateQuote("BINANCE", symbol, last = price, timestamp = ts, source = "trade")
                bucketTrade("BINANCE", symbol, price, qty, side, ts)
            }
            stream.contains("@ticker") || event == "24hrTicker" -> {
                val ts = data.optLong("E", System.currentTimeMillis())
                val last = data.optString("c").toDoubleOrNull()
                val bid = data.optString("b").toDoubleOrNull()
                val ask = data.optString("a").toDoubleOrNull()
                updateQuote("BINANCE", symbol, last, bid, ask, ts, "ticker")
            }
        }
    }

    private fun connectBybit() {
        if (stopped.get()) return
        if (bybitSymbols.isEmpty()) {
            listener.onConnection("BYBIT", false, "Aucune paire prioritaire disponible")
            return
        }
        val endpoint = BYBIT_ENDPOINTS[bybitEndpoint.coerceIn(0, BYBIT_ENDPOINTS.lastIndex)]
        bybitSocket = client.newWebSocket(Request.Builder().url(endpoint).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                bybitFailures = 0
                bybitSubscribeAccepted = 0
                val batches = BybitSubscriptionPlanner.batches(bybitSymbols)
                bybitSubscribeExpected = batches.size
                listener.onConnection(
                    "BYBIT",
                    true,
                    "$endpoint • transport connecté • ${bybitSymbols.size} paire(s) • ${batches.size} lot(s) de souscription • feed en chauffe"
                )
                batches.forEachIndexed { index, topics ->
                    val args = JSONArray().apply { topics.forEach { put(it) } }
                    val sent = webSocket.send(JSONObject().apply {
                        put("req_id", "chk-track-${System.currentTimeMillis()}-${index + 1}")
                        put("op", "subscribe")
                        put("args", args)
                    }.toString())
                    if (!sent) {
                        listener.onConnection("BYBIT", true, "transport connecté • échec envoi souscription lot ${index + 1}/${batches.size}")
                    }
                }
                startBybitHeartbeat(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleBybit(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                bybitHeartbeat?.cancel(false); bybitHeartbeat = null
                listener.onConnection("BYBIT", false, "$code • $reason")
                scheduleBybitReconnect()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                bybitHeartbeat?.cancel(false); bybitHeartbeat = null
                listener.onConnection("BYBIT", false, t.message ?: "WebSocket Bybit indisponible")
                if (bybitEndpoint < BYBIT_ENDPOINTS.lastIndex) bybitEndpoint++
                scheduleBybitReconnect()
            }
        })
    }

    private fun startBybitHeartbeat(webSocket: WebSocket) {
        bybitHeartbeat?.cancel(false)
        if (scheduler.isShutdown) return
        bybitHeartbeat = scheduler.scheduleAtFixedRate({
            if (!stopped.get() && webSocket === bybitSocket) {
                webSocket.send(JSONObject().apply {
                    put("req_id", "chk-heartbeat")
                    put("op", "ping")
                }.toString())
            }
        }, 20, 20, TimeUnit.SECONDS)
    }

    private fun handleBybit(text: String) {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        val op = root.optString("op")
        if (op.equals("pong", true) || (op.equals("ping", true) && root.optString("ret_msg").equals("pong", true))) return
        if (op.equals("subscribe", true)) {
            val success = root.optBoolean("success", false)
            val reqId = root.optString("req_id")
            val msg = root.optString("ret_msg")
            if (success) {
                bybitSubscribeAccepted = (bybitSubscribeAccepted + 1).coerceAtMost(bybitSubscribeExpected)
                listener.onConnection(
                    "BYBIT",
                    true,
                    "transport connecté • souscription ${bybitSubscribeAccepted}/${bybitSubscribeExpected} acceptée${if (reqId.isNotBlank()) " • $reqId" else ""} • feed en chauffe"
                )
            } else {
                listener.onConnection(
                    "BYBIT",
                    true,
                    "transport connecté • souscription refusée${if (reqId.isNotBlank()) " • $reqId" else ""}${if (msg.isNotBlank()) " • $msg" else ""}"
                )
            }
            return
        }
        val topic = root.optString("topic")
        if (topic.isBlank()) return
        when {
            topic.startsWith("orderbook.") -> {
                val data = root.optJSONObject("data") ?: return
                val symbol = data.optString("s", topic.substringAfterLast('.')).uppercase(Locale.US)
                if (!validSymbol(symbol)) return
                val book = bybitBooks.getOrPut(symbol) { MutableBook() }
                val ts = root.optLong("ts", System.currentTimeMillis())
                synchronized(book) {
                    if (root.optString("type").equals("snapshot", true)) {
                        book.bids.clear(); book.asks.clear()
                    }
                    applyDelta(book.bids, data.optJSONArray("b") ?: JSONArray())
                    applyDelta(book.asks, data.optJSONArray("a") ?: JSONArray())
                    book.updatedAt = ts
                }
                scheduleBybitBook(symbol, book)
            }
            topic.startsWith("publicTrade.") -> {
                val rows = root.optJSONArray("data") ?: return
                for (i in 0 until rows.length()) {
                    val d = rows.optJSONObject(i) ?: continue
                    val symbol = d.optString("s", topic.substringAfterLast('.')).uppercase(Locale.US)
                    val price = d.optString("p").toDoubleOrNull() ?: continue
                    val qty = d.optString("v").toDoubleOrNull() ?: continue
                    val ts = d.optLong("T", root.optLong("ts", System.currentTimeMillis()))
                    updateQuote("BYBIT", symbol, last = price, timestamp = ts, source = "trade")
                    bucketTrade("BYBIT", symbol, price, qty, d.optString("S").uppercase(Locale.US), ts)
                }
            }
            topic.startsWith("tickers.") -> {
                val raw = root.opt("data")
                val d = when (raw) { is JSONObject -> raw; is JSONArray -> raw.optJSONObject(0); else -> null } ?: return
                val symbol = d.optString("symbol", topic.substringAfterLast('.')).uppercase(Locale.US)
                val ts = root.optLong("ts", System.currentTimeMillis())
                // Bybit can send ticker deltas. Missing fields keep their previous values so that
                // last/bid/ask never fall back to null while the feed is connected.
                updateQuote(
                    "BYBIT",
                    symbol,
                    last = d.optString("lastPrice").toDoubleOrNull(),
                    bid = d.optString("bid1Price").toDoubleOrNull(),
                    ask = d.optString("ask1Price").toDoubleOrNull(),
                    timestamp = ts,
                    source = "ticker"
                )
            }
        }
    }

    private fun scheduleBybitBook(symbol: String, book: MutableBook) {
        val key = "BYBIT:$symbol"
        if (bookTasks[key]?.isDone == false) return
        bookTasks[key] = scheduler.schedule({
            if (stopped.get()) return@schedule
            val bids: List<TrackingBookLevel>
            val asks: List<TrackingBookLevel>
            val ts: Long
            synchronized(book) {
                bids = book.bids.entries.asSequence().filter { it.value > 0.0 }.sortedByDescending { it.key }.take(50)
                    .map { TrackingBookLevel(it.key, it.value) }.toList()
                asks = book.asks.entries.asSequence().filter { it.value > 0.0 }.sortedBy { it.key }.take(50)
                    .map { TrackingBookLevel(it.key, it.value) }.toList()
                ts = book.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            }
            if (bids.isNotEmpty() || asks.isNotEmpty()) {
                listener.onBook("BYBIT", symbol, bids, asks, ts)
                updateQuoteFromBook("BYBIT", symbol, bids, asks, ts)
            }
            bookTasks.remove(key)
        }, profile.bookDispatchMs, TimeUnit.MILLISECONDS)
    }

    private fun scheduleBook(exchange: String, symbol: String, bids: List<TrackingBookLevel>, asks: List<TrackingBookLevel>, ts: Long) {
        val key = "$exchange:$symbol"
        pendingBooks[key] = PendingBook(bids, asks, ts)
        if (bookTasks[key]?.isDone == false) return
        bookTasks[key] = scheduler.schedule({
            pendingBooks.remove(key)?.let { listener.onBook(exchange, symbol, it.bids, it.asks, it.timestamp) }
            bookTasks.remove(key)
        }, profile.bookDispatchMs, TimeUnit.MILLISECONDS)
    }

    private fun updateQuoteFromBook(exchange: String, symbol: String, bids: List<TrackingBookLevel>, asks: List<TrackingBookLevel>, ts: Long) {
        val bid = bids.firstOrNull()?.price
        val ask = asks.firstOrNull()?.price
        updateQuote(exchange, symbol, bid = bid, ask = ask, timestamp = ts, source = "orderbook")
    }

    private fun updateQuote(
        exchange: String,
        symbol: String,
        last: Double? = null,
        bid: Double? = null,
        ask: Double? = null,
        timestamp: Long = System.currentTimeMillis(),
        source: String
    ) {
        if (!validSymbol(symbol)) return
        val key = "$exchange:$symbol"
        val q = quotes.getOrPut(key) { MutableQuote() }
        synchronized(q) {
            if (last != null && last > 0.0) q.last = last
            if (bid != null && bid > 0.0) q.bid = bid
            if (ask != null && ask > 0.0) q.ask = ask
            if (q.last <= 0.0) {
                q.last = when {
                    q.bid > 0.0 && q.ask > 0.0 -> (q.bid + q.ask) / 2.0
                    q.bid > 0.0 -> q.bid
                    q.ask > 0.0 -> q.ask
                    else -> 0.0
                }
            }
            if (q.bid <= 0.0 && q.last > 0.0) q.bid = q.last
            if (q.ask <= 0.0 && q.last > 0.0) q.ask = q.last
            q.timestamp = maxOf(q.timestamp, timestamp)
            q.source = source
        }
        scheduleQuote(exchange, symbol, key, q)
    }

    private fun scheduleQuote(exchange: String, symbol: String, key: String, q: MutableQuote) {
        if (quoteTasks[key]?.isDone == false) return
        quoteTasks[key] = scheduler.schedule({
            val snapshot = synchronized(q) {
                if (q.last <= 0.0 && q.bid <= 0.0 && q.ask <= 0.0) null
                else TrackingQuote(q.last, q.bid, q.ask, q.timestamp, q.source)
            }
            if (snapshot != null) {
                listener.onQuote(exchange, symbol, snapshot)
                listener.onTicker(exchange, symbol, snapshot.lastPrice, snapshot.timestamp)
            }
            quoteTasks.remove(key)
        }, profile.quoteDispatchMs, TimeUnit.MILLISECONDS)
    }

    private fun bucketTrade(exchange: String, symbol: String, price: Double, quantity: Double, side: String, ts: Long) {
        if (price <= 0.0 || quantity <= 0.0) return
        val normalizedSide = side.uppercase(Locale.US).let { if (it == "BUY") "BUY" else "SELL" }
        val key = "$exchange:$symbol:$normalizedSide"
        val bucket = tradeBuckets.getOrPut(key) { TradeBucket() }
        synchronized(bucket) {
            bucket.qty += quantity
            bucket.notional += price * quantity
            bucket.lastPrice = price
            bucket.timestamp = maxOf(bucket.timestamp, ts)
        }
        if (tradeTasks[key]?.isDone == false) return
        tradeTasks[key] = scheduler.schedule({
            val sample = synchronized(bucket) {
                val qty = bucket.qty
                val avg = if (qty > 0.0) bucket.notional / qty else bucket.lastPrice
                val at = bucket.timestamp
                bucket.qty = 0.0; bucket.notional = 0.0; bucket.lastPrice = 0.0; bucket.timestamp = 0L
                Triple(avg, qty, at)
            }
            if (sample.second > 0.0) listener.onTrade(exchange, symbol, sample.first, sample.second, normalizedSide, sample.third)
            tradeTasks.remove(key)
        }, profile.tradeBucketMs, TimeUnit.MILLISECONDS)
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

    private fun normalize(values: Set<String>): Set<String> = values.asSequence()
        .map { it.uppercase(Locale.US) }.filter(::validSymbol).toSet()

    private fun validSymbol(value: String): Boolean = value.matches(Regex("^[A-Z0-9]{4,28}$"))

    private data class PendingBook(val bids: List<TrackingBookLevel>, val asks: List<TrackingBookLevel>, val timestamp: Long)

    private class MutableBook {
        val bids = HashMap<Double, Double>()
        val asks = HashMap<Double, Double>()
        var updatedAt: Long = 0L
    }

    private class MutableQuote {
        var last = 0.0
        var bid = 0.0
        var ask = 0.0
        var timestamp = 0L
        var source = ""
    }

    private class TradeBucket {
        var qty = 0.0
        var notional = 0.0
        var lastPrice = 0.0
        var timestamp = 0L
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
