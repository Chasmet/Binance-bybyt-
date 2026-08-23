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
    val asset: String,
    val quoteAsset: String,
    val side: String,
    val qty: Double,
    val price: Double,
    val priceUsdt: Double,
    val quoteUsdt: Double,
    val time: Long
)

data class BinanceWorkspaceData(
    val portfolio: PortfolioSnapshot,
    val snapshotJson: JSONObject,
    val historyText: String,
    val buyCount: Int
)

class BinanceClient(
    private val apiKey: String,
    private val apiSecret: String,
    private val baseUrl: String = "https://api.binance.com"
) {
    private var serverOffsetMs: Long = 0L

    fun loadWorkspaceData(): BinanceWorkspaceData {
        syncServerTime()
        val prices = loadAllPrices()
        val account = JSONObject(signedGet("/api/v3/account", mapOf("omitZeroBalances" to "true")))
        val balances = account.optJSONArray("balances") ?: JSONArray()
        val eurUsdt = prices["EURUSDT"] ?: 1.17
        val holdings = mutableListOf<Holding>()
        var totalUsdt = 0.0

        for (i in 0 until balances.length()) {
            val b = balances.optJSONObject(i) ?: continue
            val asset = b.optString("asset")
            val amount = (b.optString("free").toDoubleOrNull() ?: 0.0) +
                (b.optString("locked").toDoubleOrNull() ?: 0.0)
            if (amount <= 0.0) continue
            val price = resolveUsdtPrice(asset, prices, eurUsdt)
            val value = amount * price
            totalUsdt += value
            holdings += Holding(asset, amount, price, value)
        }
        holdings.sortByDescending { it.valueUsdt }
        val capturedAt = System.currentTimeMillis()
        val portfolio = PortfolioSnapshot(
            capturedAt = capturedAt,
            totalUsdt = totalUsdt,
            totalEur = if (eurUsdt > 0) totalUsdt / eurUsdt else totalUsdt,
            eurUsdt = eurUsdt,
            holdings = holdings
        )

        val summaries = JSONArray()
        val allTrades = mutableListOf<TradeRow>()
        var buyCount = 0
        val historyDisplay = StringBuilder("PRU ESTIMÉ PAR ACTIF\n\n")
        var scanned = 0

        for (h in holdings) {
            if (scanned >= 15) break
            if (isCashLike(h.asset) || h.priceUsdt <= 0.0) continue
            val pair = findPair(h.asset, prices) ?: continue
            scanned++
            val quote = pair.removePrefix(h.asset)
            val quoteToUsdt = quoteToUsdt(quote, prices, eurUsdt)
            val trades = runCatching { loadTradesInternal(pair, h.asset, quote, quoteToUsdt, 1000) }.getOrDefault(emptyList())
            if (trades.isEmpty()) continue
            allTrades += trades

            val buys = trades.filter { it.side == "BUY" }
            val sells = trades.filter { it.side == "SELL" }
            buyCount += buys.size
            val buyQty = buys.sumOf { it.qty }
            val buyCost = buys.sumOf { it.quoteUsdt }
            val avgBuy = if (buyQty > 0.0) buyCost / buyQty else 0.0
            val sellQty = sells.sumOf { it.qty }
            val sellProceeds = sells.sumOf { it.quoteUsdt }
            val pnl = if (avgBuy > 0.0) (h.priceUsdt - avgBuy) * h.amount else 0.0
            val pnlPct = if (avgBuy > 0.0) (h.priceUsdt / avgBuy - 1.0) * 100.0 else 0.0

            summaries.put(JSONObject().apply {
                put("asset", h.asset)
                put("pair", pair)
                put("buyCount", buys.size)
                put("sellCount", sells.size)
                put("buyQty", buyQty)
                put("sellQty", sellQty)
                put("buyCostUsdt", buyCost)
                put("sellProceedsUsdt", sellProceeds)
                put("avgBuyPriceUsdt", avgBuy)
                put("currentPriceUsdt", h.priceUsdt)
                put("currentQty", h.amount)
                put("estimatedUnrealizedPnlUsdt", pnl)
                put("estimatedPnlPercent", pnlPct)
            })

            historyDisplay.append(h.asset).append('\n')
            historyDisplay.append("PRU ≈ ").append(if (avgBuy > 0) fmt(avgBuy) else "—")
                .append(" USDT • actuel ").append(fmt(h.priceUsdt)).append("\n")
            if (avgBuy > 0) {
                historyDisplay.append("Écart ≈ ")
                    .append(if (pnlPct >= 0) "+" else "")
                    .append(String.format(Locale.FRANCE, "%.1f", pnlPct)).append(" %")
                    .append(" • P/L ≈ ")
                    .append(if (pnl >= 0) "+" else "")
                    .append(String.format(Locale.FRANCE, "%.2f", pnl)).append(" USDT\n")
            }
            historyDisplay.append("${buys.size} achat(s) • ${sells.size} vente(s) Spot\n\n")
        }

        allTrades.sortByDescending { it.time }
        val recentJson = JSONArray()
        allTrades.take(100).forEach { t ->
            recentJson.put(JSONObject().apply {
                put("asset", t.asset)
                put("pair", t.symbol)
                put("side", t.side)
                put("qty", t.qty)
                put("priceQuote", t.price)
                put("quoteAsset", t.quoteAsset)
                put("priceUsdt", t.priceUsdt)
                put("quoteUsdt", t.quoteUsdt)
                put("time", t.time)
            })
        }

        historyDisplay.append("DERNIERS ACHATS SPOT\n\n")
        val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
        allTrades.filter { it.side == "BUY" }.take(20).forEach { t ->
            historyDisplay.append(df.format(Date(t.time))).append(" • ").append(t.asset).append('\n')
            historyDisplay.append(fmt(t.qty)).append(" à ").append(fmt(t.priceUsdt))
                .append(" USDT ≈ ").append(String.format(Locale.FRANCE, "%.2f", t.quoteUsdt)).append(" USDT\n\n")
        }
        if (buyCount == 0) {
            historyDisplay.append("Aucun achat Spot retrouvé. Les achats via Convert, carte, Earn ou transferts peuvent être absents.\n")
        }

        val assetsJson = JSONArray()
        holdings.forEach { h ->
            assetsJson.put(JSONObject().apply {
                put("asset", h.asset)
                put("amount", h.amount)
                put("free", h.amount)
                put("locked", 0)
                put("priceUsdt", h.priceUsdt)
                put("valueUsdt", h.valueUsdt)
                put("valueEur", if (eurUsdt > 0) h.valueUsdt / eurUsdt else 0.0)
            })
        }

        val snapshotJson = JSONObject().apply {
            put("capturedAt", capturedAt)
            put("binanceServerTime", capturedAt + serverOffsetMs)
            put("totalUsdt", portfolio.totalUsdt)
            put("totalEur", portfolio.totalEur)
            put("eurUsdt", portfolio.eurUsdt)
            put("source", "android_direct_read_only")
            put("assets", assetsJson)
            put("spotTradeSummaries", summaries)
            put("spotTradeHistory", recentJson)
            put("historyNote", "PRU estimé à partir des transactions Spot disponibles, hors achats carte/Convert/transferts éventuels et hors frais non reconvertis.")
        }

        return BinanceWorkspaceData(portfolio, snapshotJson, historyDisplay.toString(), buyCount)
    }

    fun loadPortfolio(): PortfolioSnapshot = loadWorkspaceData().portfolio

    fun loadTrades(symbol: String, limit: Int = 100): List<TradeRow> {
        syncServerTime()
        val clean = symbol.trim().uppercase(Locale.US)
        require(clean.isNotBlank()) { "Symbole vide" }
        val asset = quoteBase(clean).first
        val quote = quoteBase(clean).second
        val prices = loadAllPrices()
        val eurUsdt = prices["EURUSDT"] ?: 1.17
        return loadTradesInternal(clean, asset, quote, quoteToUsdt(quote, prices, eurUsdt), limit)
    }

    fun formatTradeSummary(symbol: String, trades: List<TradeRow>): String {
        if (trades.isEmpty()) return "Aucune transaction Spot trouvée pour $symbol."
        val buys = trades.filter { it.side == "BUY" }
        val sells = trades.filter { it.side == "SELL" }
        val buyQty = buys.sumOf { it.qty }
        val buyCost = buys.sumOf { it.quoteUsdt }
        val avgBuy = if (buyQty > 0.0) buyCost / buyQty else 0.0
        val sellQty = sells.sumOf { it.qty }
        val sellProceeds = sells.sumOf { it.quoteUsdt }
        val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
        return buildString {
            appendLine(symbol)
            appendLine("Achats : ${buys.size} • quantité ${fmt(buyQty)} • coût ${fmt(buyCost)} USDT")
            appendLine("PRU brut estimé : ${fmt(avgBuy)} USDT")
            appendLine("Ventes : ${sells.size} • quantité ${fmt(sellQty)} • produit ${fmt(sellProceeds)} USDT")
            appendLine()
            appendLine("Dernières opérations :")
            trades.take(30).forEach { appendLine("${df.format(Date(it.time))}  ${it.side}  ${fmt(it.qty)} @ ${fmt(it.priceUsdt)} USDT") }
        }
    }

    private fun loadTradesInternal(symbol: String, asset: String, quote: String, quoteToUsdt: Double, limit: Int): List<TradeRow> {
        val arr = JSONArray(signedGet("/api/v3/myTrades", mapOf("symbol" to symbol, "limit" to limit.coerceIn(1, 1000).toString())))
        val out = mutableListOf<TradeRow>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val qty = t.optString("qty").toDoubleOrNull() ?: 0.0
            val price = t.optString("price").toDoubleOrNull() ?: 0.0
            val quoteQty = t.optString("quoteQty").toDoubleOrNull() ?: (qty * price)
            out += TradeRow(
                symbol = symbol,
                asset = asset,
                quoteAsset = quote,
                side = if (t.optBoolean("isBuyer", false)) "BUY" else "SELL",
                qty = qty,
                price = price,
                priceUsdt = price * quoteToUsdt,
                quoteUsdt = quoteQty * quoteToUsdt,
                time = t.optLong("time", 0L)
            )
        }
        return out.sortedByDescending { it.time }
    }

    private fun syncServerTime() {
        val server = JSONObject(get("$baseUrl/api/v3/time", emptyMap())).optLong("serverTime", System.currentTimeMillis())
        serverOffsetMs = server - System.currentTimeMillis()
    }

    private fun loadAllPrices(): Map<String, Double> {
        val arr = JSONArray(get("$baseUrl/api/v3/ticker/price", emptyMap()))
        val result = HashMap<String, Double>(arr.length() * 2)
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            val symbol = x.optString("symbol")
            val price = x.optString("price").toDoubleOrNull() ?: 0.0
            if (symbol.isNotBlank() && price > 0) result[symbol] = price
        }
        return result
    }

    private fun resolveUsdtPrice(asset: String, prices: Map<String, Double>, eurUsdt: Double): Double {
        val a = asset.uppercase(Locale.US)
        if (a in setOf("USDT", "USDC", "FDUSD", "TUSD")) return 1.0
        if (a == "EUR") return eurUsdt
        prices["${a}USDT"]?.let { if (it > 0) return it }
        val btc = prices["BTCUSDT"] ?: 0.0
        prices["${a}BTC"]?.let { if (it > 0 && btc > 0) return it * btc }
        val eth = prices["ETHUSDT"] ?: 0.0
        prices["${a}ETH"]?.let { if (it > 0 && eth > 0) return it * eth }
        return 0.0
    }

    private fun findPair(asset: String, prices: Map<String, Double>): String? {
        for (q in listOf("USDT", "USDC", "FDUSD", "EUR", "BTC", "ETH")) {
            val pair = asset + q
            if ((prices[pair] ?: 0.0) > 0.0) return pair
        }
        return null
    }

    private fun quoteToUsdt(quote: String, prices: Map<String, Double>, eurUsdt: Double): Double = when (quote) {
        "USDT", "USDC", "FDUSD", "TUSD" -> 1.0
        "EUR" -> eurUsdt
        else -> prices["${quote}USDT"] ?: 0.0
    }

    private fun quoteBase(symbol: String): Pair<String, String> {
        for (q in listOf("USDT", "USDC", "FDUSD", "EUR", "BTC", "ETH")) {
            if (symbol.endsWith(q) && symbol.length > q.length) return symbol.dropLast(q.length) to q
        }
        return symbol to "USDT"
    }

    private fun isCashLike(asset: String) = asset in setOf("USDT", "USDC", "FDUSD", "TUSD", "EUR")

    private fun signedGet(path: String, params: Map<String, String>): String {
        val all = linkedMapOf<String, String>()
        all.putAll(params)
        all["recvWindow"] = "10000"
        all["timestamp"] = (System.currentTimeMillis() + serverOffsetMs).toString()
        val query = all.entries.joinToString("&") { (k, v) -> "${url(k)}=${url(v)}" }
        val signature = hmacSha256(query, apiSecret)
        return get("$baseUrl$path?$query&signature=$signature", mapOf("X-MBX-APIKEY" to apiKey))
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("Binance HTTP $code : ${body.take(500)}")
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
