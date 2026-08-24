package com.chk.binancebybit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant

class RemoteAlertClient(context: Context) {
    private val appContext = context.applicationContext
    private val secureStore = SecureStore(appContext)
    private val workspaceSync = WorkspaceSync(appContext, secureStore)

    fun syncIntoLocal(): Boolean {
        val identity = workspaceSync.ensureIdentity()
        val root = post(JSONObject().apply {
            put("action", "list")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
        })
        val arr = root.optJSONArray("alerts") ?: JSONArray()
        val rows = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pair = normalizePair(o.optString("pair", o.optString("symbol")))
                val target = o.optDouble("target_price", 0.0)
                if (pair.isBlank() || target <= 0.0) continue
                add(
                    LocalMarketAlert(
                        id = o.optString("id"),
                        symbol = pair,
                        condition = if (o.optString("condition") == "above") "above" else "below",
                        targetPrice = target,
                        label = o.optString("label").take(120),
                        enabled = o.optBoolean("enabled", true),
                        createdAt = instantMs(o.optString("created_at")),
                        lastTriggeredAt = instantMs(o.optString("triggered_at"))
                    )
                )
            }
        }
        return LocalAlertStore(appContext).replaceRemote(rows)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val identity = workspaceSync.ensureIdentity()
        post(JSONObject().apply {
            put("action", "set_enabled")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("id", id)
            put("enabled", enabled)
        })
    }

    fun delete(id: String) {
        val identity = workspaceSync.ensureIdentity()
        post(JSONObject().apply {
            put("action", "delete")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("id", id)
        })
    }

    fun trigger(id: String, lastPrice: Double) {
        val identity = workspaceSync.ensureIdentity()
        post(JSONObject().apply {
            put("action", "trigger")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("id", id)
            put("lastPrice", lastPrice)
        })
    }

    private fun post(body: JSONObject): JSONObject {
        val c = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val error = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
                throw IllegalStateException("Alarmes HTTP $code${if (error.isNotBlank()) " • $error" else ""}")
            }
            JSONObject(text.ifBlank { "{}" })
        } finally {
            c.disconnect()
        }
    }

    private fun normalizePair(value: String): String {
        val raw = value.trim().uppercase().replace("/", "").replace("-", "")
        return when {
            raw.isBlank() -> ""
            raw.endsWith("USDC") -> raw
            raw.endsWith("USDT") -> raw.removeSuffix("USDT") + "USDC"
            else -> raw + "USDC"
        }
    }

    private fun instantMs(value: String): Long = runCatching {
        if (value.isBlank()) 0L else Instant.parse(value).toEpochMilli()
    }.getOrDefault(0L)

    companion object {
        const val ENDPOINT = "https://gflnvlolwqnvzxyqsrir.supabase.co/functions/v1/chk-binance-alerts"
    }
}
