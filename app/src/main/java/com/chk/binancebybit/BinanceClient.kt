package com.chk.binancebybit

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class Holding(
    val asset: String,
    val amount: Double,
    val priceUsdt: Double,
    val valueUsdt: Double
)

data class PortfolioSnapshot(
    val capturedAt: Long,
    val totalUsdt: Double,
    val totalEur: Double,
    val eurUsdt: Double,
    val holdings: List<Holding>
)

data class TradeRow(
    val symbol: String,
    val side: String,
    val qty: Double,
    val price: Double,
    val quoteQty: Double,
    val time: Long
)

class BinanceClient(
    private val apiKey: String,
    private val apiSecret: String,
    private val baseUrl: String = "https://api.binance.com"
) {
    fun loadPortfolio(): PortfolioSnapshot {
        val account = JSONObject(signedGet("/api/v3/account", emptyMap()))
        val balances = account.getJSONArray("balances")
        val holdings = mutableListOf<Holding>()
        var totalUsdt = 0.0

        for (i in 0 until balances.length()) {
            val b = balances.getJSONObject(i)
            val asset = b.getString("asset")
            val free = b.getString("free").toDoubleOrNull() ?: 0.0
            val locked = b.getString("locked").toDoubleOrNull() ?: 0.0
            val amount = free + locked
            if (amount <= 0.0) continue

            val price = resolveUsdtPrice(asset)
            val value = if (price > 0.0) amount * price else 0.0
            totalUsdt += value
            holdings += Holding(asset, amount, price, value)
        }

        val eurUsdt = runCatching { publicTicker("EURUSDT") }.getOrDefault(1.17)
        val totalEur = if (eurUsdt > 0.0) totalUsdt / eurUsdt else totalUsdt

        return PortfolioSnapshot(
            capturedAt = System.currentTimeMillis(),
            totalUsdt = totalUsdt,
            totalEur = totalEur,
            eurUsdt = eurUsdt,
            holdings = holdings.sortedByDescending { it.valueUsdt }
        )
    }

    fun loadTrades(symbol: String, limit: Int = 100): List<TradeRow> {
        val clean = symbol.trim().uppercase(Locale.US)
        require(clean.isNotBlank()) { "Symbole vide" }
        val body = signedGet(
            "/api/v3/myTrades",
            mapOf("symbol" to clean, "limit" to limit.coerceIn(1, 1000).toString())
        )
        val arr = JSONArray(body)
        val out = mutableListOf<TradeRow>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            val qty = t.optString("qty").toDoubleOrNull() ?: 0.0
            val price = t.optString("price").toDoubleOrNull() ?: 0.0
            val quoteQty = t.optString("quoteQty").toDoubleOrNull() ?: (qty * price)
            out += TradeRow(
                symbol = clean,
                side = if (t.optBoolean("isBuyer", false)) "BUY" else "SELL",
                qty = qty,
                price = price,
                quoteQty = quoteQty,
                time = t.optLong("time", 0L)
            )
        }
        return out.sortedByDescending { it.time }
    }

    fun formatTradeSummary(symbol: String, trades: List<TradeRow>): String {
        if (trades.isEmpty()) return "Aucune transaction Spot trouvée pour $symbol."
        val buys = trades.filter { it.side == "BUY" }
        val sells = trades.filter { it.side == "SELL" }
        val buyQty = buys.sumOf { it.qty }
        val buyCost = buys.sumOf { it.quoteQty }
        val avgBuy = if (buyQty > 0.0) buyCost / buyQty else 0.0
        val sellQty = sells.sumOf { it.qty }
        val sellProceeds = sells.sumOf { it.quoteQty }
        val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)

        return buildString {
            appendLine("$symbol")
            appendLine("Achats : ${buys.size} • quantité ${fmt(buyQty)} • coût ${fmt(buyCost)}")
            appendLine("Prix moyen d'achat (brut) : ${fmt(avgBuy)}")
            appendLine("Ventes : ${sells.size} • quantité ${fmt(sellQty)} • produit ${fmt(sellProceeds)}")
            appendLine()
            appendLine("Dernières opérations :")
            trades.take(30).forEach {
                appendLine("${df.format(Date(it.time))}  ${it.side}  ${fmt(it.qty)} @ ${fmt(it.price)}")
            }
        }
    }

    private fun resolveUsdtPrice(asset: String): Double {
        return when (asset.uppercase(Locale.US)) {
            "USDT", "FDUSD", "USDC" -> 1.0
            "EUR" -> publicTicker("EURUSDT")
            else -> runCatching { publicTicker("${asset.uppercase(Locale.US)}USDT") }.getOrDefault(0.0)
        }
    }

    private fun publicTicker(symbol: String): Double {
        val encoded = URLEncoder.encode(symbol, StandardCharsets.UTF_8.name())
        val body = get("$baseUrl/api/v3/ticker/price?symbol=$encoded", emptyMap())
        return JSONObject(body).getString("price").toDouble()
    }

    private fun signedGet(path: String, params: Map<String, String>): String {
        val all = linkedMapOf<String, String>()
        all.putAll(params)
        all["recvWindow"] = "10000"
        all["timestamp"] = System.currentTimeMillis().toString()
        val query = all.entries.joinToString("&") { (k, v) ->
            "${url(k)}=${url(v)}"
        }
        val signature = hmacSha256(query, apiSecret)
        return get("$baseUrl$path?$query&signature=$signature", mapOf("X-MBX-APIKEY" to apiKey))
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("Binance HTTP $code : $body")
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun hmacSha256(data: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun url(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    companion object {
        fun fmt(value: Double): String = when {
            value >= 1000 -> String.format(Locale.FRANCE, "%,.2f", value)
            value >= 1 -> String.format(Locale.FRANCE, "%.4f", value)
            else -> String.format(Locale.FRANCE, "%.8f", value)
        }
    }
}
