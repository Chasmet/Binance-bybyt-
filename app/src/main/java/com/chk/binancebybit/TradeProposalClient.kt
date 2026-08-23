package com.chk.binancebybit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class TradeProposalClient(
    context: Context,
    private val secureStore: SecureStore
) {
    private val appContext = context.applicationContext
    private val workspaceSync = WorkspaceSync(appContext, secureStore)

    data class Bundle(
        val pending: List<TradeProposal>,
        val recent: JSONArray
    )

    fun list(): Bundle {
        val identity = workspaceSync.ensureIdentity()
        val raw = postJson(JSONObject().apply {
            put("action", "list")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
        })
        val root = JSONObject(raw)
        val pendingJson = root.optJSONArray("pending") ?: JSONArray()
        val pending = mutableListOf<TradeProposal>()
        for (i in 0 until pendingJson.length()) {
            val o = pendingJson.optJSONObject(i) ?: continue
            pending += TradeProposal.fromJson(o)
        }
        return Bundle(pending, root.optJSONArray("recent") ?: JSONArray())
    }

    fun markResult(
        proposalId: String,
        status: String,
        bybitOrderId: String? = null,
        result: JSONObject = JSONObject()
    ): String {
        val identity = workspaceSync.ensureIdentity()
        return postJson(JSONObject().apply {
            put("action", "mark_result")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("id", proposalId)
            put("status", status)
            put("bybitOrderId", bybitOrderId ?: "")
            put("result", result)
        })
    }

    private fun postJson(body: JSONObject): String {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                throw IllegalStateException("Propositions HTTP $code${if (message.isNotBlank()) " • $message" else ""}")
            }
            text
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val ENDPOINT = "https://gflnvlolwqnvzxyqsrir.supabase.co/functions/v1/chk-trade-proposals"
    }
}
