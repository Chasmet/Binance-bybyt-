package com.chk.binancebybit

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class ChartRemoteCommand(val seq: Long, val command: JSONObject)

class ChartRemoteClient(context: Context) {
    private val appContext = context.applicationContext
    private val secureStore = SecureStore(appContext)
    private val workspace = WorkspaceSync(appContext, secureStore)

    fun pushState(state: ChartSessionState, market: JSONObject = JSONObject()): JSONObject {
        val identity = workspace.ensureIdentity()
        return post(JSONObject().apply {
            put("action", "push_state")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("state", state.toJson())
            put("market", market)
        })
    }

    fun getServerState(): JSONObject {
        val identity = workspace.ensureIdentity()
        return post(JSONObject().apply {
            put("action", "get_state")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
        })
    }

    fun pullCommand(): ChartRemoteCommand? {
        val identity = workspace.ensureIdentity()
        val root = post(JSONObject().apply {
            put("action", "pull_command")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
        })
        if (!root.optBoolean("pending", false)) return null
        val seq = root.optLong("seq", 0L)
        val command = root.optJSONObject("command") ?: return null
        if (seq <= 0L) return null
        return ChartRemoteCommand(seq, command)
    }

    fun ack(seq: Long) {
        val identity = workspace.ensureIdentity()
        post(JSONObject().apply {
            put("action", "ack_command")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("seq", seq)
        })
    }

    fun uploadSnapshot(png: ByteArray): JSONObject {
        require(png.isNotEmpty()) { "Snapshot vide" }
        require(png.size <= 3_145_728) { "Snapshot trop volumineux" }
        val identity = workspace.ensureIdentity()
        return post(JSONObject().apply {
            put("action", "upload_snapshot")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("pngBase64", Base64.encodeToString(png, Base64.NO_WRAP))
        })
    }

    fun marketJson(snapshot: IndicatorSnapshot, viewport: ChartViewportState): JSONObject {
        val candles = JSONArray()
        snapshot.candles.takeLast(1000).forEach { c ->
            candles.put(JSONArray().apply {
                put(c.time); put(c.open); put(c.high); put(c.low); put(c.close); put(c.volume)
            })
        }
        val end = (snapshot.candles.size - viewport.offsetFromEnd).coerceIn(1, snapshot.candles.size)
        val start = (end - viewport.visibleCount).coerceAtLeast(0)
        val visibleStart = snapshot.candles.getOrNull(start)?.time
        val visibleEnd = snapshot.candles.getOrNull(end - 1)?.time
        return JSONObject().apply {
            put("exchange", snapshot.exchange)
            put("symbol", snapshot.requestedSymbol)
            put("sourceSymbol", snapshot.sourceSymbol)
            put("timeframe", snapshot.interval)
            put("capturedAt", snapshot.capturedAt)
            put("lastPrice", snapshot.lastPrice)
            put("changePct", snapshot.changePct)
            put("visibleStart", visibleStart ?: 0L)
            put("visibleEnd", visibleEnd ?: 0L)
            put("candles", candles)
            put("indicators", JSONObject().apply {
                put("rsi14", snapshot.rsi14)
                put("ema20", snapshot.ema20)
                put("ema50", snapshot.ema50)
                put("macd", snapshot.macd)
                put("macdSignal", snapshot.macdSignal)
                put("macdHistogram", snapshot.macdHistogram)
                put("atr14", snapshot.atr14)
                put("bbUpper", snapshot.bbUpper)
                put("bbMiddle", snapshot.bbMiddle)
                put("bbLower", snapshot.bbLower)
                put("support", snapshot.support)
                put("resistance", snapshot.resistance)
                put("volumeRatio", snapshot.volumeRatio)
                put("trend", snapshot.trend)
                put("score", snapshot.score)
            })
        }
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
                val detail = runCatching { JSONObject(text).optString("message", JSONObject(text).optString("error")) }.getOrDefault(text.take(240))
                throw IllegalStateException("Graph MCP HTTP $code${if (detail.isNotBlank()) " • $detail" else ""}")
            }
            JSONObject(text.ifBlank { "{}" })
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val ENDPOINT = "https://gflnvlolwqnvzxyqsrir.supabase.co/functions/v1/chk-chart-control"
    }
}
