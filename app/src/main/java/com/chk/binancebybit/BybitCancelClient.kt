package com.chk.binancebybit

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BybitCancelClient(
    private val apiKey: String,
    private val apiSecret: String,
    private val baseUrl: String = "https://api.bybit.eu"
) {
    private val recvWindow = "5000"
    private var serverOffsetMs = 0L

    fun cancel(proposal: CancelProposal): CancelExecutionResult {
        require(apiKey.isNotBlank() && apiSecret.isNotBlank()) { "Clés Bybit manquantes" }
        require(proposal.status == "processing") { "L'annulation n'est pas réservée pour exécution" }
        require(proposal.symbol.matches(Regex("^[A-Z0-9]{2,20}USDC$"))) { "Seules les paires Spot */USDC sont autorisées" }
        require(proposal.targetOrderId.isNotBlank()) { "Order ID cible manquant" }

        syncServerTime()
        verifySpotTradePermission()
        val before = loadOrderState(proposal.symbol, proposal.targetOrderId)
        if (before == null) throw IllegalStateException("Ordre Bybit ${proposal.targetOrderId} introuvable")
        val beforeStatus = before.optString("orderStatus")
        if (beforeStatus.equals("Filled", true)) throw IllegalStateException("Ordre déjà exécuté : aucune annulation possible")
        if (beforeStatus.equals("Cancelled", true)) {
            return CancelExecutionResult(
                orderId = proposal.targetOrderId,
                orderLinkId = before.optString("orderLinkId"),
                orderStatus = "Cancelled",
                symbol = proposal.symbol
            )
        }
        if (!listOf("New", "PartiallyFilled", "Untriggered", "Created").any { beforeStatus.equals(it, true) }) {
            throw IllegalStateException("Ordre non annulable dans son état actuel : $beforeStatus")
        }

        signedPost("/v5/order/cancel", JSONObject().apply {
            put("category", "spot")
            put("symbol", proposal.symbol)
            put("orderId", proposal.targetOrderId)
        })

        val state = pollCancelled(proposal.symbol, proposal.targetOrderId)
            ?: throw IllegalStateException("Annulation envoyée mais statut Bybit non confirmé. Vérifie avant toute autre action.")
        val status = state.optString("orderStatus")
        if (!status.equals("Cancelled", true)) {
            throw IllegalStateException("Annulation non confirmée par Bybit : statut $status")
        }
        return CancelExecutionResult(
            orderId = state.optString("orderId").ifBlank { proposal.targetOrderId },
            orderLinkId = state.optString("orderLinkId"),
            orderStatus = status,
            symbol = proposal.symbol
        )
    }

    private fun verifySpotTradePermission() {
        val root = signedGet("/v5/user/query-api", linkedMapOf())
        val result = root.optJSONObject("result") ?: throw IllegalStateException("Permissions Bybit indisponibles")
        val readOnly = result.optInt("readOnly", -1)
        val spot = result.optJSONObject("permissions")?.optJSONArray("Spot") ?: JSONArray()
        val permissions = (0 until spot.length()).map { spot.optString(it) }
        if (readOnly != 0 || !permissions.contains("SpotTrade")) {
            throw IllegalStateException("La clé Bybit doit être lecture-écriture avec permission SpotTrade")
        }
    }

    private fun pollCancelled(symbol: String, orderId: String): JSONObject? {
        var last: JSONObject? = null
        for (delay in longArrayOf(250L, 700L, 1500L, 3000L)) {
            Thread.sleep(delay)
            val state = runCatching { loadOrderState(symbol, orderId) }.getOrNull()
            if (state != null) {
                last = state
                if (state.optString("orderStatus").equals("Cancelled", true)) return state
            }
        }
        return last
    }

    private fun loadOrderState(symbol: String, orderId: String): JSONObject? {
        fun find(path: String): JSONObject? {
            val root = signedGet(path, linkedMapOf(
                "category" to "spot",
                "symbol" to symbol,
                "orderId" to orderId,
                "limit" to "20"
            ))
            return root.optJSONObject("result")?.optJSONArray("list")?.optJSONObject(0)
        }
        return find("/v5/order/realtime") ?: find("/v5/order/history")
    }

    private fun syncServerTime() {
        val root = publicGet("/v5/market/time")
        val server = root.optLong("time", 0L).takeIf { it > 0L }
            ?: root.optJSONObject("result")?.optString("timeSecond")?.toLongOrNull()?.times(1000L)
            ?: System.currentTimeMillis()
        serverOffsetMs = server - System.currentTimeMillis()
    }

    private fun signedGet(path: String, params: LinkedHashMap<String, String>): JSONObject {
        val query = params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val timestamp = (System.currentTimeMillis() + serverOffsetMs).toString()
        val signature = hmac(apiSecret, timestamp + apiKey + recvWindow + query)
        val c = (URL(baseUrl + path + if (query.isBlank()) "" else "?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-BAPI-API-KEY", apiKey)
            setRequestProperty("X-BAPI-TIMESTAMP", timestamp)
            setRequestProperty("X-BAPI-RECV-WINDOW", recvWindow)
            setRequestProperty("X-BAPI-SIGN", signature)
        }
        return readRoot(c)
    }

    private fun signedPost(path: String, body: JSONObject): JSONObject {
        val payload = body.toString()
        val timestamp = (System.currentTimeMillis() + serverOffsetMs).toString()
        val signature = hmac(apiSecret, timestamp + apiKey + recvWindow + payload)
        val c = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-BAPI-API-KEY", apiKey)
            setRequestProperty("X-BAPI-TIMESTAMP", timestamp)
            setRequestProperty("X-BAPI-RECV-WINDOW", recvWindow)
            setRequestProperty("X-BAPI-SIGN", signature)
        }
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        return readRoot(c)
    }

    private fun publicGet(path: String): JSONObject {
        val c = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
        }
        return readRoot(c)
    }

    private fun readRoot(c: HttpURLConnection): JSONObject {
        return try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("Bybit HTTP $code • ${text.take(300)}")
            val root = JSONObject(text)
            val retCode = root.optInt("retCode", 0)
            if (retCode != 0) throw IllegalStateException("Bybit $retCode • ${root.optString("retMsg")}")
            root
        } finally { c.disconnect() }
    }

    private fun hmac(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun encode(v: String): String = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
}
