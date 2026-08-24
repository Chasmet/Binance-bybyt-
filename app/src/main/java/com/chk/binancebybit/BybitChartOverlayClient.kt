package com.chk.binancebybit

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BybitChartOverlayClient(
    private val apiKey: String,
    private val apiSecret: String,
    private val baseUrl: String = "https://api.bybit.eu"
) {
    private val recvWindow = "5000"
    private var serverOffset = 0L

    fun load(symbol: String): Pair<List<ChartTradeMarker>, List<ChartOrderLevel>> {
        if (apiKey.isBlank() || apiSecret.isBlank()) return emptyList<ChartTradeMarker>() to emptyList()
        syncTime()
        val s = normalize(symbol)
        val trades = mutableListOf<ChartTradeMarker>()
        val executionRoot = signedGet("/v5/execution/list", linkedMapOf("category" to "spot", "symbol" to s, "limit" to "100"))
        val executions = executionRoot.optJSONObject("result")?.optJSONArray("list") ?: JSONArray()
        for (i in 0 until executions.length()) {
            val x = executions.optJSONObject(i) ?: continue
            val price = x.optString("execPrice").toDoubleOrNull() ?: continue
            val time = x.optString("execTime").toLongOrNull() ?: continue
            val side = x.optString("side").uppercase(Locale.US)
            if (side != "BUY" && side != "SELL") continue
            val qty = x.optString("execQty")
            trades += ChartTradeMarker(time, price, side, "$side $qty")
        }

        val orders = mutableListOf<ChartOrderLevel>()
        val orderRoot = signedGet("/v5/order/realtime", linkedMapOf("category" to "spot", "symbol" to s, "openOnly" to "0", "limit" to "50"))
        val orderRows = orderRoot.optJSONObject("result")?.optJSONArray("list") ?: JSONArray()
        for (i in 0 until orderRows.length()) {
            val x = orderRows.optJSONObject(i) ?: continue
            val status = x.optString("orderStatus")
            if (status != "New" && status != "PartiallyFilled" && status != "Untriggered") continue
            val price = x.optString("price").toDoubleOrNull() ?: continue
            if (price <= 0.0) continue
            val side = x.optString("side").uppercase(Locale.US)
            val qty = x.optString("qty")
            orders += ChartOrderLevel(price, side, "$side LIMIT $qty")
        }
        return trades.sortedBy { it.time } to orders
    }

    private fun syncTime() {
        val c = URL("$baseUrl/v5/market/time").openConnection() as HttpURLConnection
        c.connectTimeout = 6_000
        c.readTimeout = 6_000
        try {
            val root = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val server = root.optLong("time", 0L).takeIf { it > 0L }
                ?: root.optJSONObject("result")?.optString("timeSecond")?.toLongOrNull()?.times(1000L)
                ?: System.currentTimeMillis()
            serverOffset = server - System.currentTimeMillis()
        } finally { c.disconnect() }
    }

    private fun signedGet(path: String, params: LinkedHashMap<String, String>): JSONObject {
        val query = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val ts = (System.currentTimeMillis() + serverOffset).toString()
        val payload = ts + apiKey + recvWindow + query
        val sign = hmac(payload)
        val url = "$baseUrl$path${if (query.isBlank()) "" else "?$query"}"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        c.setRequestProperty("X-BAPI-API-KEY", apiKey)
        c.setRequestProperty("X-BAPI-TIMESTAMP", ts)
        c.setRequestProperty("X-BAPI-RECV-WINDOW", recvWindow)
        c.setRequestProperty("X-BAPI-SIGN", sign)
        c.setRequestProperty("Accept", "application/json")
        return try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("Bybit HTTP $code")
            val root = JSONObject(body)
            val retCode = root.optInt("retCode", 0)
            if (retCode != 0) throw IllegalStateException("Bybit $retCode • ${root.optString("retMsg")}")
            root
        } finally { c.disconnect() }
    }

    private fun hmac(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun enc(v: String) = URLEncoder.encode(v, StandardCharsets.UTF_8.name())
    private fun normalize(value: String): String = value.trim().uppercase(Locale.US).replace("/", "").replace("-", "")
}
