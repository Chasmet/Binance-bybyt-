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
        val root = JSONObject(postJson(JSONObject().apply {
            put("action", "list")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
        }))
        val pendingJson = root.optJSONArray("pending") ?: JSONArray()
        val pending = mutableListOf<TradeProposal>()
        for (i in 0 until pendingJson.length()) {
            val o = pendingJson.optJSONObject(i) ?: continue
            pending += TradeProposal.fromJson(o)
        }
        return Bundle(pending, root.optJSONArray("recent") ?: JSONArray())
    }

    /**
     * Réserve atomiquement une proposition avant tout appel réel à Bybit.
     * Le serveur n'accepte le claim que si elle est encore pending et non expirée.
     */
    fun claim(proposalId: String): TradeProposal {
        val identity = workspaceSync.ensureIdentity()
        val root = JSONObject(postJson(JSONObject().apply {
            put("action", "claim")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("id", proposalId)
        }))
        val proposal = root.optJSONObject("proposal")
            ?: throw IllegalStateException("La proposition n'est plus disponible pour exécution")
        return TradeProposal.fromJson(proposal)
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

    fun deleteHistory(proposalId: String): String {
        val identity = workspaceSync.ensureIdentity()
        return postJson(JSONObject().apply {
            put("action", "delete_history")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("id", proposalId)
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
                val root = runCatching { JSONObject(text) }.getOrNull()
                val message = root?.optString("error").orEmpty()
                val detail = root?.optString("message").orEmpty()
                val suffix = listOf(message, detail).filter { it.isNotBlank() }.joinToString(" • ")
                throw IllegalStateException("Propositions HTTP $code${if (suffix.isNotBlank()) " • $suffix" else ""}")
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
