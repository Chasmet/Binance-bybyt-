package com.chk.binancebybit

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Lightweight relay only. It sends compact derived state and MCP commands through Supabase.
 * Raw order-book/trade streams never use this endpoint and never pass through Render.
 */
class TrackingRemoteClient(context: Context) {
    private val app = context.applicationContext
    private val secureStore = SecureStore(app)
    private val workspace = WorkspaceSync(app, secureStore)

    data class PendingCommand(val seq: Long, val command: JSONObject)

    fun pushState(state: JSONObject): JSONObject {
        val id = workspace.ensureIdentity()
        return post(JSONObject().apply {
            put("action", "push_state")
            put("deviceId", id.deviceId)
            put("deviceSecret", id.deviceSecret)
            put("state", state)
        })
    }

    fun pullCommand(): PendingCommand? {
        val id = workspace.ensureIdentity()
        val out = post(JSONObject().apply {
            put("action", "pull_command")
            put("deviceId", id.deviceId)
            put("deviceSecret", id.deviceSecret)
        })
        if (!out.optBoolean("pending", false)) return null
        val seq = out.optLong("seq", 0L)
        val command = out.optJSONObject("command") ?: return null
        if (seq <= 0L) return null
        return PendingCommand(seq, command)
    }

    fun ackCommand(seq: Long, result: JSONObject) {
        val id = workspace.ensureIdentity()
        post(JSONObject().apply {
            put("action", "ack_command")
            put("deviceId", id.deviceId)
            put("deviceSecret", id.deviceSecret)
            put("seq", seq)
            put("result", result)
        })
    }

    private fun post(body: JSONObject): JSONObject {
        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 8_000
        c.readTimeout = 12_000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "CHK-Crypto-Tracking/0.10")
        return try {
            val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            if (code !in 200..299) throw IllegalStateException("Tracking relay HTTP $code • ${text.take(180)}")
            JSONObject(text)
        } finally { c.disconnect() }
    }

    companion object {
        const val ENDPOINT = "https://gflnvlolwqnvzxyqsrir.supabase.co/functions/v1/chk-tracking-control"
    }
}
