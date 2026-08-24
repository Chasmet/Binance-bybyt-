package com.chk.binancebybit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class LocalMarketAlert(
    val id: String,
    val symbol: String,
    val condition: String,
    val targetPrice: Double,
    val label: String,
    val enabled: Boolean,
    val createdAt: Long,
    val lastTriggeredAt: Long
)

class LocalAlertStore(context: Context) {
    private val ownerContext = context
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        maybeRefreshRemote()
    }

    private fun maybeRefreshRemote() {
        val now = System.currentTimeMillis()
        if (now - remoteSyncLastAt < 20_000L || !remoteSyncRunning.compareAndSet(false, true)) return
        Thread {
            var changed = false
            try {
                changed = RemoteAlertClient(appContext).syncIntoLocal()
            } catch (_: Exception) {
            } finally {
                remoteSyncLastAt = System.currentTimeMillis()
                remoteSyncRunning.set(false)
            }
            if (changed && ownerContext is MainActivityV4) {
                ownerContext.runOnUiThread {
                    val refreshed = LocalAlertStore(ownerContext)
                    if (refreshed.activeCount() > 0 && !refreshed.monitoringEnabled()) {
                        runCatching { MarketWatchService.start(ownerContext) }
                    }
                    ExperienceRoute.refreshCurrent(ownerContext)
                }
            }
        }.apply { name = "CHK-AlertSync"; isDaemon = true; start() }
    }

    @Synchronized
    fun list(): List<LocalMarketAlert> {
        val raw = prefs.getString(KEY_ALERTS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val symbol = normalizeSymbol(o.optString("symbol"))
                    val target = o.optDouble("targetPrice", 0.0)
                    if (symbol.isBlank() || target <= 0.0) continue
                    add(
                        LocalMarketAlert(
                            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                            symbol = symbol,
                            condition = if (o.optString("condition") == "above") "above" else "below",
                            targetPrice = target,
                            label = o.optString("label").take(120),
                            enabled = o.optBoolean("enabled", true),
                            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                            lastTriggeredAt = o.optLong("lastTriggeredAt", 0L)
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun add(symbol: String, condition: String, targetPrice: Double, label: String = ""): LocalMarketAlert {
        require(targetPrice > 0.0) { "Prix invalide" }
        val alert = LocalMarketAlert(
            id = UUID.randomUUID().toString(),
            symbol = normalizeSymbol(symbol),
            condition = if (condition == "above") "above" else "below",
            targetPrice = targetPrice,
            label = label.trim().take(120),
            enabled = true,
            createdAt = System.currentTimeMillis(),
            lastTriggeredAt = 0L
        )
        save(list() + alert)
        return alert
    }

    @Synchronized
    fun replaceRemote(items: List<LocalMarketAlert>): Boolean {
        val before = list()
        val oldRemoteIds = remoteIds()
        val newRemoteIds = items.map { it.id }.filter { it.isNotBlank() }.toSet()
        val localOnly = before.filterNot { oldRemoteIds.contains(it.id) }
        val merged = (localOnly + items).distinctBy { it.id }
        val changed = before.sortedBy { it.id } != merged.sortedBy { it.id } || oldRemoteIds != newRemoteIds
        if (changed) save(merged)
        prefs.edit().putStringSet(KEY_REMOTE_IDS, newRemoteIds).apply()
        return changed
    }

    fun isRemote(id: String): Boolean = remoteIds().contains(id)

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        val remote = isRemote(id)
        save(list().map { if (it.id == id) it.copy(enabled = enabled) else it })
        if (remote) Thread { runCatching { RemoteAlertClient(appContext).setEnabled(id, enabled) } }.start()
    }

    @Synchronized
    fun delete(id: String) {
        val remote = isRemote(id)
        save(list().filterNot { it.id == id })
        if (remote) {
            prefs.edit().putStringSet(KEY_REMOTE_IDS, remoteIds() - id).apply()
            Thread { runCatching { RemoteAlertClient(appContext).delete(id) } }.start()
        }
    }

    @Synchronized
    fun markTriggered(id: String, disableAfterTrigger: Boolean = true, lastPrice: Double? = null) {
        val remote = isRemote(id)
        val now = System.currentTimeMillis()
        save(list().map {
            if (it.id == id) it.copy(lastTriggeredAt = now, enabled = if (disableAfterTrigger) false else it.enabled) else it
        })
        if (remote && lastPrice != null && lastPrice > 0.0) {
            Thread { runCatching { RemoteAlertClient(appContext).trigger(id, lastPrice) } }.start()
        }
    }

    fun activeCount(): Int = list().count { it.enabled }

    fun monitoringEnabled(): Boolean = prefs.getBoolean(KEY_MONITORING, false)

    fun setMonitoringEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MONITORING, enabled).apply()
    }

    fun smartWatchEnabled(): Boolean = prefs.getBoolean(KEY_SMART_WATCH, true)

    fun setSmartWatchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMART_WATCH, enabled).apply()
    }

    fun smartMoveThresholdPct(): Double = prefs.getFloat(KEY_SMART_MOVE_PCT, 1.5f).toDouble().coerceIn(0.5, 20.0)

    fun setSmartMoveThresholdPct(value: Double) {
        prefs.edit().putFloat(KEY_SMART_MOVE_PCT, value.coerceIn(0.5, 20.0).toFloat()).apply()
    }

    private fun remoteIds(): Set<String> = prefs.getStringSet(KEY_REMOTE_IDS, emptySet())?.toSet() ?: emptySet()

    @Synchronized
    private fun save(items: List<LocalMarketAlert>) {
        val arr = JSONArray()
        items.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("symbol", a.symbol)
                put("condition", a.condition)
                put("targetPrice", a.targetPrice)
                put("label", a.label)
                put("enabled", a.enabled)
                put("createdAt", a.createdAt)
                put("lastTriggeredAt", a.lastTriggeredAt)
            })
        }
        prefs.edit().putString(KEY_ALERTS, arr.toString()).apply()
        contextAlertCount(items.count { it.enabled })
    }

    private fun contextAlertCount(count: Int) {
        prefs.edit().putInt("alert_count", count).apply()
    }

    private fun normalizeSymbol(value: String): String {
        val raw = value.trim().uppercase().replace("/", "").replace("-", "")
        return when {
            raw.isBlank() -> ""
            raw.endsWith("USDC") -> raw
            raw.endsWith("USDT") -> raw.removeSuffix("USDT") + "USDC"
            else -> raw + "USDC"
        }
    }

    companion object {
        private const val PREFS = "chk_workspace"
        private const val KEY_ALERTS = "local_market_alerts_v1"
        private const val KEY_REMOTE_IDS = "remote_market_alert_ids_v1"
        private const val KEY_MONITORING = "local_market_monitoring_enabled"
        private const val KEY_SMART_WATCH = "local_smart_watch_enabled"
        private const val KEY_SMART_MOVE_PCT = "local_smart_move_pct"
        private val remoteSyncRunning = AtomicBoolean(false)
        @Volatile private var remoteSyncLastAt = 0L
    }
}
