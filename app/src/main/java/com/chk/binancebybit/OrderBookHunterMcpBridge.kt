package com.chk.binancebybit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Compatibility bridge between the Android-only temporal engine and the existing CHK Crypto MCP.
 * ChatGPT writes ORDERBOOK_CONTROL notes through the canonical MCP; the APK consumes them and
 * writes ORDERBOOK_RESULT / ORDERBOOK_HUNTER_STATE notes back through the same authenticated
 * device identity. This keeps Bot 2 independent from trading and gives ChatGPT control of its
 * watch list, history and dedicated notebook without exposing Bybit trading credentials.
 */
class OrderBookHunterMcpBridge(context: Context) {
    private val app = context.applicationContext
    private val db = OrderBookHunterDb(app)
    private val workspace = WorkspaceSync(app, SecureStore(app))
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val running = AtomicBoolean(false)
    private var pollFuture: ScheduledFuture<*>? = null
    private var mirrorFuture: ScheduledFuture<*>? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        pollFuture = executor.scheduleWithFixedDelay({ safePoll() }, 3, 12, TimeUnit.SECONDS)
        mirrorFuture = executor.scheduleWithFixedDelay({ safeMirror() }, 30, 120, TimeUnit.SECONDS)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        pollFuture?.cancel(true); mirrorFuture?.cancel(true)
        executor.shutdownNow()
        db.close()
    }

    private fun safePoll() {
        if (!running.get()) return
        runCatching {
            val root = JSONObject(workspace.listNotes())
            val notes = root.optJSONArray("notes") ?: JSONArray()
            for (i in 0 until notes.length()) {
                val note = notes.optJSONObject(i) ?: continue
                if (!note.optString("kind").equals(KIND_CONTROL, ignoreCase = true)) continue
                val source = note.optString("source")
                if (source.isNotBlank() && !source.equals("chatgpt", ignoreCase = true)) continue
                val content = note.optString("content").trim()
                if (content.isBlank()) continue
                val key = note.optString("id").ifBlank {
                    sha256(note.optString("created_at") + "|" + content)
                }
                if (wasProcessed(key)) continue
                processControl(key, content)
            }
        }
    }

    private fun processControl(key: String, content: String) {
        val command = parseControl(content)
        val action = command.optString("action").trim().lowercase()
        val symbolRaw = command.optString("symbol")
        val requestId = command.optString("requestId").ifBlank { key.take(16) }
        val result = JSONObject().apply {
            put("requestId", requestId)
            put("action", action)
            put("handledAt", System.currentTimeMillis())
        }
        try {
            when (action) {
                "start_orderbook_watch", "start", "watch" -> {
                    val symbol = OrderBookHunterStore.normalizeSymbol(symbolRaw)
                    db.watch(symbol, true, alerts = command.optBoolean("alerts", true), restore = command.optBoolean("restore", true))
                    OrderBookHunterService.ensureRunning(app)
                    result.put("ok", true).put("symbol", symbol).put("status", "WATCHING")
                }
                "stop_orderbook_watch", "stop" -> {
                    val symbol = OrderBookHunterStore.normalizeSymbol(symbolRaw)
                    db.watch(symbol, false)
                    OrderBookHunterService.ensureRunning(app)
                    result.put("ok", true).put("symbol", symbol).put("status", "STOPPED")
                }
                "list_orderbook_watches", "list" -> {
                    result.put("ok", true)
                    result.put("watches", JSONArray(db.watches().map { w -> JSONObject().apply {
                        put("symbol", w.symbol); put("alerts", w.alerts); put("restoreAfterBoot", w.restore); put("updatedAt", w.updatedAt)
                    } }))
                }
                "get_orderbook_hunter_status", "status" -> {
                    val symbol = OrderBookHunterStore.normalizeSymbol(symbolRaw)
                    val status = OrderBookHunterStore.get(symbol)
                    result.put("ok", status != null)
                    result.put("symbol", symbol)
                    if (status != null) result.put("data", OrderBookHunterStore.statusJson(status)) else result.put("message", "Pas encore de snapshot synchronisé")
                }
                "get_orderbook_walls", "walls" -> {
                    val symbol = OrderBookHunterStore.normalizeSymbol(symbolRaw)
                    val status = OrderBookHunterStore.get(symbol)
                    result.put("ok", status != null).put("symbol", symbol)
                    if (status != null) {
                        val state = OrderBookHunterStore.statusJson(status)
                        result.put("bidWalls", state.optJSONArray("bidWalls"))
                        result.put("askWalls", state.optJSONArray("askWalls"))
                    }
                }
                "get_orderbook_events", "events" -> {
                    val symbol = OrderBookHunterStore.normalizeSymbol(symbolRaw)
                    val limit = command.optInt("limit", 100).coerceIn(1, 500)
                    val minutes = command.optInt("minutes", 0).coerceIn(0, 24 * 60)
                    val since = if (minutes > 0) System.currentTimeMillis() - minutes * 60_000L else 0L
                    result.put("ok", true).put("symbol", symbol)
                    result.put("events", JSONArray(db.events(symbol, limit, since).map(OrderBookHunterStore::eventJson)))
                }
                "get_orderbook_anomaly_score", "score" -> {
                    val symbol = OrderBookHunterStore.normalizeSymbol(symbolRaw)
                    val status = OrderBookHunterStore.get(symbol)
                    result.put("ok", status != null).put("symbol", symbol)
                    if (status != null) {
                        result.put("anomalyScore", status.anomalyScore)
                        result.put("classification", status.classification)
                        result.put("explanation", HunterClassification.safeExplanation(status.anomalyScore))
                    }
                }
                "get_orderbook_absorption", "absorption" -> {
                    val symbol = OrderBookHunterStore.normalizeSymbol(symbolRaw)
                    val minutes = command.optInt("minutes", 30).coerceIn(1, 24 * 60)
                    val since = System.currentTimeMillis() - minutes * 60_000L
                    val events = db.events(symbol, 500, since).filter { it.type == HunterEventType.WALL_ABSORPTION }
                    result.put("ok", true).put("symbol", symbol).put("minutes", minutes)
                    result.put("count", events.size)
                    result.put("events", JSONArray(events.map(OrderBookHunterStore::eventJson)))
                }
                "clear_orderbook_history", "clear" -> {
                    val symbol = OrderBookHunterStore.normalizeSymbol(symbolRaw)
                    db.clear(symbol, includeNotes = command.optBoolean("includeNotes", false))
                    result.put("ok", true).put("symbol", symbol).put("cleared", true)
                }
                "add_orderbook_note", "note" -> {
                    val symbol = OrderBookHunterStore.normalizeSymbol(symbolRaw)
                    val text = command.optString("text").trim()
                    require(text.isNotBlank()) { "Note vide" }
                    val note = db.note(symbol, text, "CHATGPT")
                    result.put("ok", true).put("symbol", symbol).put("noteId", note.id)
                }
                "set_orderbook_alerts", "alerts" -> {
                    val symbol = OrderBookHunterStore.normalizeSymbol(symbolRaw)
                    val enabled = command.optBoolean("enabled", true)
                    db.setAlerts(symbol, enabled)
                    result.put("ok", true).put("symbol", symbol).put("alerts", enabled)
                }
                else -> throw IllegalArgumentException("Action OrderBook inconnue : $action")
            }
        } catch (e: Exception) {
            result.put("ok", false)
            result.put("error", e.message ?: e.javaClass.simpleName)
        }
        workspace.createNote("BYBIT", KIND_RESULT, result.toString())
        markProcessed(key)
    }

    private fun safeMirror() {
        if (!running.get()) return
        runCatching {
            val watches = db.watches()
            for (watch in watches) {
                val status = OrderBookHunterStore.get(watch.symbol) ?: continue
                val recent = db.events(watch.symbol, 120, System.currentTimeMillis() - 30L * 60L * 1000L)
                val counts = JSONObject()
                HunterEventType.entries.forEach { type ->
                    val n = recent.count { it.type == type }
                    if (n > 0) counts.put(type.name, n)
                }
                val state = OrderBookHunterStore.statusJson(status).apply {
                    put("windowMinutes", 30)
                    put("eventCounts", counts)
                    put("recentEventCount", recent.size)
                }
                val signature = sha256(state.toString().replace(Regex("\"updatedAt\":\d+"), "\"updatedAt\":0"))
                val lastKey = "mirror_${watch.symbol}"
                if (prefs.getString(lastKey, "") == signature) continue
                workspace.createNote("BYBIT", KIND_STATE, state.toString())
                prefs.edit().putString(lastKey, signature).apply()
            }
        }
    }

    private fun parseControl(content: String): JSONObject {
        return runCatching { JSONObject(content) }.getOrElse {
            val parts = content.trim().split(Regex("\\s+"))
            JSONObject().apply {
                put("action", parts.firstOrNull().orEmpty())
                if (parts.size > 1) put("symbol", parts[1])
                if (parts.size > 2) put("text", parts.drop(2).joinToString(" "))
            }
        }
    }

    private fun wasProcessed(key: String): Boolean = processed().contains(key)

    private fun markProcessed(key: String) {
        val next = (processed() + key).takeLast(120)
        prefs.edit().putString(PROCESSED, next.joinToString("\n")).apply()
    }

    private fun processed(): List<String> = prefs.getString(PROCESSED, "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val KIND_CONTROL = "ORDERBOOK_CONTROL"
        const val KIND_RESULT = "ORDERBOOK_RESULT"
        const val KIND_STATE = "ORDERBOOK_HUNTER_STATE"
        private const val PREFS = "chk_orderbook_hunter_mcp"
        private const val PROCESSED = "processed_control_ids"
    }
}
