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
     * Creates a proposal from Bot CHK after authenticating the installed app/device.
     * This endpoint cannot execute an order. The returned proposal follows the exact
     * same CONFIRMER / ANNULER flow as proposals prepared by ChatGPT.
     */
    fun createBotProposal(
        symbol: String,
        side: String,
        quoteAmountUsdc: Double,
        baseQuantity: Double?,
        limitPrice: Double,
        rationale: String,
        expiresInMinutes: Int = 120
    ): TradeProposal {
        val identity = workspaceSync.ensureIdentity()
        val root = JSONObject(postJson(JSONObject().apply {
            put("action", "create")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("symbol", symbol)
            put("side", side)
            put("quoteAmountUsdc", quoteAmountUsdc)
            if (baseQuantity != null) put("baseQuantity", baseQuantity)
            put("limitPrice", limitPrice)
            put("rationale", rationale)
            put("expiresInMinutes", expiresInMinutes)
        }, BOT_ENDPOINT))
        val proposal = root.optJSONObject("proposal")
            ?: throw IllegalStateException("Bot CHK n'a pas reçu la proposition préparée")
        return TradeProposal.fromJson(proposal)
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

    private fun postJson(body: JSONObject, endpoint: String = ENDPOINT): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
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
        const val BOT_ENDPOINT = "https://gflnvlolwqnvzxyqsrir.supabase.co/functions/v1/chk-bot-proposals"
    }
}
