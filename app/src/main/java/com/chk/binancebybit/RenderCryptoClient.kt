package com.chk.binancebybit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Passerelle privée CHK Crypto -> Render.
 * Les clés Binance/Bybit restent sur Render et ne sont jamais renvoyées au téléphone.
 */
class RenderCryptoClient(
    context: Context,
    secureStore: SecureStore
) {
    private val workspaceSync = WorkspaceSync(context.applicationContext, secureStore)

    data class RemoteWorkspaceData(
        val portfolio: PortfolioSnapshot,
        val historyText: String,
        val syncedAt: String,
        val apiInfoText: String
    )

    fun sync(exchange: String): RemoteWorkspaceData {
        val code = exchange.uppercase()
        require(code == "BINANCE" || code == "BYBIT") { "Exchange invalide" }
        val identity = workspaceSync.ensureIdentity()
        val root = post("/apk/sync", JSONObject().apply {
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("exchange", code)
        })
        val portfolio = parsePortfolio(root.optJSONObject("portfolio") ?: JSONObject())
        return RemoteWorkspaceData(
            portfolio = portfolio,
            historyText = root.optString("historyText"),
            syncedAt = root.optString("syncedAt", "OK"),
            apiInfoText = root.optString("apiInfoText")
        )
    }

    fun status(exchange: String): JSONObject {
        val identity = workspaceSync.ensureIdentity()
        return post("/apk/status", JSONObject().apply {
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("exchange", exchange.uppercase())
        })
    }

    /**
     * À appeler uniquement APRÈS TradeProposalClient.claim().
     * Render relit la proposition processing côté serveur avant tout /v5/order/create.
     */
    fun executeProposal(proposalId: String): TradeExecutionResult {
        val identity = workspaceSync.ensureIdentity()
        val root = try {
            post("/apk/bybit/execute", JSONObject().apply {
                put("deviceId", identity.deviceId)
                put("deviceSecret", identity.deviceSecret)
                put("proposalId", proposalId)
            })
        } catch (e: RenderHttpException) {
            if (e.errorCode == "bybit_state_uncertain" || e.httpCode == 503) {
                throw BybitExecutionUncertainException(
                    "État Bybit incertain. Render ne renverra pas automatiquement un second ordre. ${e.message}"
                )
            }
            throw e
        }
        return parseExecution(root.optJSONObject("result") ?: throw IllegalStateException("Résultat Bybit absent"))
    }

    fun reconcileProposal(proposalId: String): TradeExecutionResult? {
        val identity = workspaceSync.ensureIdentity()
        val root = post("/apk/bybit/reconcile", JSONObject().apply {
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("proposalId", proposalId)
        })
        if (!root.optBoolean("found", false)) return null
        return root.optJSONObject("result")?.let(::parseExecution)
    }

    private fun parsePortfolio(o: JSONObject): PortfolioSnapshot {
        val arr = o.optJSONArray("holdings") ?: JSONArray()
        val holdings = mutableListOf<Holding>()
        for (i in 0 until arr.length()) {
            val h = arr.optJSONObject(i) ?: continue
            holdings += Holding(
                asset = h.optString("asset"),
                amount = h.optDouble("amount", 0.0),
                priceUsdt = h.optDouble("priceUsdt", 0.0),
                valueUsdt = h.optDouble("valueUsdt", 0.0)
            )
        }
        return PortfolioSnapshot(
            capturedAt = o.optLong("capturedAt", System.currentTimeMillis()),
            totalUsdt = o.optDouble("totalUsdt", 0.0),
            totalEur = o.optDouble("totalEur", 0.0),
            eurUsdt = o.optDouble("eurUsdt", 1.17),
            holdings = holdings
        )
    }

    private fun parseExecution(o: JSONObject): TradeExecutionResult {
        val requestedQty = when (val raw = o.opt("requestedQty")) {
            is Number -> raw.toString()
            is String -> raw
            else -> ""
        }
        val requestedPrice = when {
            o.has("requestedPrice") && !o.isNull("requestedPrice") -> o.opt("requestedPrice")?.toString()
            o.optDouble("limitPrice", 0.0) > 0.0 -> o.optDouble("limitPrice").toString()
            else -> null
        }
        return TradeExecutionResult(
            orderId = o.optString("orderId"),
            orderLinkId = o.optString("orderLinkId"),
            orderStatus = o.optString("orderStatus", "SENT"),
            symbol = o.optString("symbol"),
            side = o.optString("side").uppercase(),
            orderType = o.optString("orderType").uppercase(),
            requestedQty = requestedQty,
            requestedPrice = requestedPrice,
            executedQty = o.optDouble("executedQty", 0.0),
            executedValueUsdc = o.optDouble("executedValueUsdc", 0.0),
            averagePrice = o.optDouble("averagePrice", 0.0)
        )
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 12_000
            readTimeout = 45_000
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
            val root = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("message", text.take(400)) }
            if (code !in 200..299) {
                throw RenderHttpException(
                    httpCode = code,
                    errorCode = root.optString("error"),
                    detail = root.optString("message").ifBlank { root.optString("error").ifBlank { "HTTP $code" } }
                )
            }
            root
        } finally {
            connection.disconnect()
        }
    }

    class RenderHttpException(
        val httpCode: Int,
        val errorCode: String,
        detail: String
    ) : IllegalStateException("Render $httpCode • $detail")

    companion object {
        const val BASE_URL = "https://chk-binance-workspace-mcp.onrender.com"
    }
}
