package com.chk.binancebybit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Local persistent configuration + independent journal for Bot CHK.
 * The journal is not stored in CHK Crypto Notes. It is mirrored to the dedicated
 * Bot journal endpoint so ChatGPT/MCP can inspect it during later analyses.
 */
class BotRuleStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    data class Rule(
        val id: String,
        val name: String,
        val symbol: String,
        val priceCondition: String,
        val targetPrice: Double,
        val rsiEnabled: Boolean,
        val rsiTimeframe: String,
        val rsiCondition: String,
        val rsiThreshold: Double,
        val action: String,
        val amountUsdc: Double,
        val oneShot: Boolean,
        val enabled: Boolean,
        val lastTriggeredAt: Long
    )

    data class LogEntry(
        val id: String,
        val at: Long,
        val level: String,
        val category: String,
        val title: String,
        val detail: String,
        val symbol: String = "",
        val ruleId: String = ""
    )

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun list(): List<Rule> = synchronized(lock) {
        decodeRules(prefs.getString(KEY_RULES, "[]") ?: "[]")
    }

    fun activeCount(): Int = list().count { it.enabled }

    fun upsert(rule: Rule) = synchronized(lock) {
        val rows = list().toMutableList()
        val index = rows.indexOfFirst { it.id == rule.id }
        if (index >= 0) rows[index] = normalize(rule) else rows += normalize(rule)
        save(rows)
    }

    fun create(
        name: String,
        symbol: String,
        priceCondition: String,
        targetPrice: Double,
        rsiEnabled: Boolean,
        rsiTimeframe: String,
        rsiCondition: String,
        rsiThreshold: Double,
        action: String,
        amountUsdc: Double,
        oneShot: Boolean
    ): Rule {
        val rule = normalize(
            Rule(
                id = UUID.randomUUID().toString(),
                name = name.trim().ifBlank { "Règle ${normalizeSymbol(symbol)}" }.take(80),
                symbol = symbol,
                priceCondition = priceCondition,
                targetPrice = targetPrice,
                rsiEnabled = rsiEnabled,
                rsiTimeframe = rsiTimeframe,
                rsiCondition = rsiCondition,
                rsiThreshold = rsiThreshold,
                action = action,
                amountUsdc = amountUsdc,
                oneShot = oneShot,
                enabled = true,
                lastTriggeredAt = 0L
            )
        )
        upsert(rule)
        return rule
    }

    fun setRuleEnabled(id: String, value: Boolean) = synchronized(lock) {
        val rows = list().map { if (it.id == id) it.copy(enabled = value) else it }
        save(rows)
    }

    fun delete(id: String) = synchronized(lock) {
        save(list().filterNot { it.id == id })
    }

    fun markTriggered(id: String, disable: Boolean) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val rows = list().map {
            if (it.id == id) it.copy(lastTriggeredAt = now, enabled = if (disable) false else it.enabled) else it
        }
        save(rows)
    }

    fun canTrigger(rule: Rule, now: Long = System.currentTimeMillis()): Boolean {
        if (!rule.enabled) return false
        if (rule.lastTriggeredAt <= 0L) return true
        return now - rule.lastTriggeredAt >= COOLDOWN_MS
    }

    fun addLog(
        level: String,
        title: String,
        detail: String,
        category: String = "BOT",
        symbol: String = "",
        ruleId: String = ""
    ): LogEntry = synchronized(lock) {
        val entry = LogEntry(
            id = UUID.randomUUID().toString(),
            at = System.currentTimeMillis(),
            level = level.take(16).uppercase(Locale.US),
            category = category.take(32).uppercase(Locale.US),
            title = title.take(100),
            detail = detail.take(700),
            symbol = symbol.take(24).uppercase(Locale.US),
            ruleId = ruleId.take(100)
        )
        val current = logs().toMutableList()
        current.add(0, entry)
        saveLogs(current)
        Thread {
            runCatching { BotJournalClient(app).append(entry) }
        }.apply { isDaemon = true; name = "CHK-BotJournal"; start() }
        entry
    }

    fun logs(): List<LogEntry> = synchronized(lock) {
        val arr = runCatching { JSONArray(prefs.getString(KEY_LOGS, "[]") ?: "[]") }.getOrDefault(JSONArray())
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    LogEntry(
                        id = o.optString("id").ifBlank { "legacy-${o.optLong("at")}-${i}" },
                        at = o.optLong("at"),
                        level = o.optString("level", "INFO"),
                        category = o.optString("category", "BOT"),
                        title = o.optString("title"),
                        detail = o.optString("detail"),
                        symbol = o.optString("symbol"),
                        ruleId = o.optString("ruleId")
                    )
                )
            }
        }
    }

    fun syncJournalNow() {
        val snapshot = logs()
        Thread {
            runCatching { BotJournalClient(app).syncRecent(snapshot) }
        }.apply { isDaemon = true; name = "CHK-BotJournalSync"; start() }
    }

    fun clearLogs() {
        prefs.edit().remove(KEY_LOGS).apply()
    }

    private fun saveLogs(rows: List<LogEntry>) {
        val arr = JSONArray()
        rows.take(MAX_LOGS).forEach { row ->
            arr.put(JSONObject().apply {
                put("id", row.id)
                put("at", row.at)
                put("level", row.level)
                put("category", row.category)
                put("title", row.title)
                put("detail", row.detail)
                put("symbol", row.symbol)
                put("ruleId", row.ruleId)
            })
        }
        prefs.edit().putString(KEY_LOGS, arr.toString()).apply()
    }

    private fun save(rows: List<Rule>) {
        val arr = JSONArray()
        rows.take(MAX_RULES).forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("symbol", r.symbol)
                put("priceCondition", r.priceCondition)
                put("targetPrice", r.targetPrice)
                put("rsiEnabled", r.rsiEnabled)
                put("rsiTimeframe", r.rsiTimeframe)
                put("rsiCondition", r.rsiCondition)
                put("rsiThreshold", r.rsiThreshold)
                put("action", r.action)
                put("amountUsdc", r.amountUsdc)
                put("oneShot", r.oneShot)
                put("enabled", r.enabled)
                put("lastTriggeredAt", r.lastTriggeredAt)
            })
        }
        prefs.edit().putString(KEY_RULES, arr.toString()).apply()
    }

    private fun decodeRules(raw: String): List<Rule> {
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching {
                    normalize(
                        Rule(
                            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = o.optString("name"),
                            symbol = o.optString("symbol"),
                            priceCondition = o.optString("priceCondition", "below"),
                            targetPrice = o.optDouble("targetPrice"),
                            rsiEnabled = o.optBoolean("rsiEnabled", false),
                            rsiTimeframe = o.optString("rsiTimeframe", "15m"),
                            rsiCondition = o.optString("rsiCondition", "below"),
                            rsiThreshold = o.optDouble("rsiThreshold", 35.0),
                            action = o.optString("action", ACTION_NOTIFY),
                            amountUsdc = o.optDouble("amountUsdc", 10.0),
                            oneShot = o.optBoolean("oneShot", true),
                            enabled = o.optBoolean("enabled", true),
                            lastTriggeredAt = o.optLong("lastTriggeredAt", 0L)
                        )
                    )
                }.getOrNull()?.let { add(it) }
            }
        }
    }

    private fun normalize(rule: Rule): Rule {
        val action = when (rule.action.uppercase(Locale.US)) {
            ACTION_PREPARE_BUY -> ACTION_PREPARE_BUY
            ACTION_PREPARE_SELL -> ACTION_PREPARE_SELL
            else -> ACTION_NOTIFY
        }
        return rule.copy(
            name = rule.name.trim().ifBlank { "Règle CHK" }.take(80),
            symbol = normalizeSymbol(rule.symbol),
            priceCondition = if (rule.priceCondition.lowercase(Locale.US) == "above") "above" else "below",
            targetPrice = rule.targetPrice.coerceAtLeast(0.0),
            rsiTimeframe = normalizeTimeframe(rule.rsiTimeframe),
            rsiCondition = if (rule.rsiCondition.lowercase(Locale.US) == "above") "above" else "below",
            rsiThreshold = rule.rsiThreshold.coerceIn(1.0, 99.0),
            action = action,
            amountUsdc = rule.amountUsdc.coerceIn(1.0, 10.0)
        )
    }

    companion object {
        const val ACTION_NOTIFY = "NOTIFY"
        const val ACTION_PREPARE_BUY = "PREPARE_BUY"
        const val ACTION_PREPARE_SELL = "PREPARE_SELL"

        private const val PREFS = "chk_bot_v1"
        private const val KEY_ENABLED = "bot_enabled"
        private const val KEY_RULES = "rules_json"
        private const val KEY_LOGS = "logs_json"
        private const val MAX_RULES = 50
        private const val MAX_LOGS = 240
        private const val COOLDOWN_MS = 60L * 60_000L

        fun normalizeSymbol(value: String): String {
            val raw = value.trim().uppercase(Locale.US).replace("/", "").replace("-", "").replace(" ", "")
            return when {
                raw.endsWith("USDC") -> raw
                raw.endsWith("USDT") -> raw.removeSuffix("USDT") + "USDC"
                raw.isBlank() -> "BTCUSDC"
                else -> raw + "USDC"
            }
        }

        fun normalizeTimeframe(value: String): String = when (value.trim().lowercase(Locale.US)) {
            "1m", "5m", "15m", "1h", "4h", "1d" -> value.trim().lowercase(Locale.US)
            else -> "15m"
        }

        fun bybitInterval(timeframe: String): String = when (normalizeTimeframe(timeframe)) {
            "1m" -> "1"
            "5m" -> "5"
            "15m" -> "15"
            "1h" -> "60"
            "4h" -> "240"
            "1d" -> "D"
            else -> "15"
        }
    }
}
