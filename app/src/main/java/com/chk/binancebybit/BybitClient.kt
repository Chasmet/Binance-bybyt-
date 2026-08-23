package com.chk.binancebybit

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class BybitWorkspaceData(
    val portfolio: PortfolioSnapshot,
    val snapshotJson: JSONObject,
    val historyText: String,
    val executionCount: Int,
    val apiInfoText: String
)

class BybitClient(
    private val apiKey: String,
    private val apiSecret: String,
    private val baseUrl: String = "https://api.bybit.eu"
) {
    private val recvWindow = "5000"
    private var serverOffsetMs = 0L

    fun loadWorkspaceData(): BybitWorkspaceData {
        syncServerTime()
        val apiInfo = loadApiInfo()
        val tickers = loadSpotTickers()
        val eurUsd = resolveEurUsd(tickers)
        val walletRoot = signedGet("/v5/account/wallet-balance", linkedMapOf("accountType" to "UNIFIED"))
        val account = walletRoot.optJSONObject("result")?.optJSONArray("list")?.optJSONObject(0)
            ?: throw IllegalStateException("Bybit n'a retourné aucun portefeuille UNIFIED.")
        val coins = account.optJSONArray("coin") ?: JSONArray()

        val holdings = mutableListOf<Holding>()
        var sumUsd = 0.0
        val assetsJson = JSONArray()
        for (i in 0 until coins.length()) {
            val c = coins.optJSONObject(i) ?: continue
            val asset = c.optString("coin").uppercase(Locale.US)
            val amount = d(c.optString("walletBalance"))
            if (asset.isBlank() || amount <= 0.0) continue
            var valueUsd = d(c.optString("usdValue"))
            var priceUsd = if (amount > 0.0 && valueUsd > 0.0) valueUsd / amount else resolveUsdPrice(asset, tickers, eurUsd)
            if (valueUsd <= 0.0 && priceUsd > 0.0) valueUsd = amount * priceUsd
            if (priceUsd <= 0.0 && valueUsd > 0.0) priceUsd = valueUsd / amount
            sumUsd += valueUsd
            holdings += Holding(asset, amount, priceUsd, valueUsd)
            assetsJson.put(JSONObject().apply {
                put("asset", asset)
                put("amount", amount)
                put("priceUsdt", priceUsd)
                put("valueUsdt", valueUsd)
                put("valueEur", if (eurUsd > 0.0) valueUsd / eurUsd else valueUsd)
            })
        }
        holdings.sortByDescending { it.valueUsdt }

        val totalUsd = d(account.optString("totalEquity")).takeIf { it > 0.0 } ?: sumUsd
        val capturedAt = System.currentTimeMillis()
        val portfolio = PortfolioSnapshot(
            capturedAt = capturedAt,
            totalUsdt = totalUsd,
            totalEur = if (eurUsd > 0.0) totalUsd / eurUsd else totalUsd,
            eurUsdt = eurUsd,
            holdings = holdings
        )

        val executions = loadExecutions(5)
        val summaries = JSONArray()
        val recentJson = JSONArray()
        val held = holdings.associateBy { it.asset }
        val grouped = executions.groupBy { it.asset }
        var buyCount = 0
        val display = StringBuilder("PRU ESTIMÉ BYBIT SPOT\n\n")

        for ((asset, rows) in grouped) {
            val h = held[asset] ?: continue
            val buys = rows.filter { it.side == "BUY" }
            val sells = rows.filter { it.side == "SELL" }
            if (buys.isEmpty() && sells.isEmpty()) continue
            buyCount += buys.size
            val buyQty = buys.sumOf { it.qty }
            val buyCost = buys.sumOf { it.quoteUsdt }
            val avg = if (buyQty > 0.0) buyCost / buyQty else 0.0
            val sellQty = sells.sumOf { it.qty }
            val sellProceeds = sells.sumOf { it.quoteUsdt }
            val pnl = if (avg > 0.0) (h.priceUsdt - avg) * h.amount else 0.0
            val pnlPct = if (avg > 0.0) (h.priceUsdt / avg - 1.0) * 100.0 else 0.0

            summaries.put(JSONObject().apply {
                put("asset", asset)
                put("buyCount", buys.size)
                put("sellCount", sells.size)
                put("buyQty", buyQty)
                put("sellQty", sellQty)
                put("buyCostUsdt", buyCost)
                put("sellProceedsUsdt", sellProceeds)
                put("avgBuyPriceUsdt", avg)
                put("currentPriceUsdt", h.priceUsdt)
                put("currentQty", h.amount)
                put("estimatedUnrealizedPnlUsdt", pnl)
                put("estimatedPnlPercent", pnlPct)
            })

            display.append(asset).append('\n')
            display.append("PRU ≈ ").append(if (avg > 0.0) fmt(avg) else "—").append(" USD • actuel ").append(fmt(h.priceUsdt)).append('\n')
            if (avg > 0.0) {
                display.append("Écart ≈ ").append(if (pnlPct >= 0) "+" else "").append(String.format(Locale.FRANCE, "%.1f", pnlPct)).append(" %")
                display.append(" • P/L ≈ ").append(if (pnl >= 0) "+" else "").append(String.format(Locale.FRANCE, "%.2f", pnl)).append(" USD\n")
            }
            display.append(buys.size).append(" achat(s) • ").append(sells.size).append(" vente(s) Spot\n\n")
        }

        display.append("DERNIÈRES EXÉCUTIONS SPOT\n\n")
        executions.sortedByDescending { it.time }.take(30).forEach { e ->
            display.append(dateFmt(e.time)).append(" • ").append(e.asset).append(" • ").append(e.side).append('\n')
            display.append(fmt(e.qty)).append(" à ").append(fmt(e.priceUsdt)).append(" USD ≈ ").append(String.format(Locale.FRANCE, "%.2f", e.quoteUsdt)).append(" USD\n\n")
        }
        if (executions.isEmpty()) display.append("Aucune exécution Spot retrouvée dans la fenêtre d'historique disponible.\n")

        executions.sortedByDescending { it.time }.take(100).forEach { e ->
            recentJson.put(JSONObject().apply {
                put("asset", e.asset)
                put("pair", e.symbol)
                put("side", e.side)
                put("qty", e.qty)
                put("priceUsdt", e.priceUsdt)
                put("quoteUsdt", e.quoteUsdt)
                put("time", e.time)
            })
        }

        val snapshot = JSONObject().apply {
            put("capturedAt", capturedAt)
            put("source", "android_direct_bybit_eu")
            put("totalUsdt", totalUsd)
            put("totalEur", portfolio.totalEur)
            put("eurUsdt", eurUsd)
            put("assets", assetsJson)
            put("spotTradeSummaries", summaries)
            put("spotTradeHistory", recentJson)
            put("historyNote", "PRU estimé depuis les exécutions Spot Bybit disponibles, hors transferts/Convert/produits Earn et hors certains frais.")
            put("apiKeyInfo", apiInfo.first)
        }

        return BybitWorkspaceData(portfolio, snapshot, display.toString().trim(), buyCount, apiInfo.second)
    }

    fun loadApiInfo(): Pair<JSONObject, String> {
        val root = signedGet("/v5/user/query-api", linkedMapOf())
        val result = root.optJSONObject("result") ?: JSONObject()
        val readOnly = result.optInt("readOnly", -1)
        val permissions = result.optJSONObject("permissions")
        val spot = permissions?.optJSONArray("Spot") ?: permissions?.optJSONArray("spot")
        val spotText = if (spot != null) (0 until spot.length()).mapNotNull { spot.optString(it).takeIf(String::isNotBlank) }.joinToString(", ") else "non indiqué"
        val text = "Clé Bybit détectée • ${if (readOnly == 1) "lecture seule" else if (readOnly == 0) "lecture-écriture" else "mode inconnu"}\nPermissions Spot : $spotText"
        return result to text
    }

    private fun loadExecutions(maxPages: Int): List<TradeRow> {
        val out = mutableListOf<TradeRow>()
        var cursor = ""
        repeat(maxPages) {
            val params = linkedMapOf("category" to "spot", "limit" to "100")
            if (cursor.isNotBlank()) params["cursor"] = cursor
            val root = signedGet("/v5/execution/list", params)
            val result = root.optJSONObject("result") ?: return@repeat
            val list = result.optJSONArray("list") ?: JSONArray()
            for (i in 0 until list.length()) {
                val x = list.optJSONObject(i) ?: continue
                val symbol = x.optString("symbol").uppercase(Locale.US)
                val split = splitSymbol(symbol) ?: continue
                val qty = d(x.optString("execQty"))
                val priceQuote = d(x.optString("execPrice"))
                if (qty <= 0.0 || priceQuote <= 0.0) continue
                val side = x.optString("side").uppercase(Locale.US)
                if (side != "BUY" && side != "SELL") continue
                val quoteToUsd = quoteToUsd(split.second, loadSpotTickersCached(), resolveEurUsd(loadSpotTickersCached()))
                val execValue = d(x.optString("execValue")).takeIf { it > 0.0 } ?: qty * priceQuote
                out += TradeRow(
                    symbol = symbol,
                    asset = split.first,
                    quoteAsset = split.second,
                    side = side,
                    qty = qty,
                    price = priceQuote,
                    priceUsdt = priceQuote * quoteToUsd,
                    quoteUsdt = execValue * quoteToUsd,
                    time = x.optString("execTime").toLongOrNull() ?: 0L
                )
            }
            val next = result.optString("nextPageCursor")
            if (next.isBlank() || next == cursor) return out
            cursor = next
        }
        return out
    }

    private var cachedTickers: Map<String, Double>? = null
    private fun loadSpotTickersCached(): Map<String, Double> = cachedTickers ?: loadSpotTickers().also { cachedTickers = it }

    private fun loadSpotTickers(): Map<String, Double> {
        val root = publicGet("/v5/market/tickers?category=spot")
        val arr = root.optJSONObject("result")?.optJSONArray("list") ?: JSONArray()
        val map = HashMap<String, Double>()
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            val symbol = x.optString("symbol").uppercase(Locale.US)
            val price = d(x.optString("lastPrice"))
            if (symbol.isNotBlank() && price > 0.0) map[symbol] = price
        }
        cachedTickers = map
        return map
    }

    private fun resolveUsdPrice(asset: String, tickers: Map<String, Double>, eurUsd: Double): Double {
        if (asset == "USDT" || asset == "USDC" || asset == "FDUSD" || asset == "TUSD") return 1.0
        if (asset == "EUR") return eurUsd
        tickers[asset + "USDC"]?.takeIf { it > 0.0 }?.let { return it }
        tickers[asset + "USDT"]?.takeIf { it > 0.0 }?.let { return it }
        val btc = tickers[asset + "BTC"]
        val btcUsd = tickers["BTCUSDT"] ?: tickers["BTCUSDC"]
        if (btc != null && btcUsd != null && btc > 0.0 && btcUsd > 0.0) return btc * btcUsd
        return 0.0
    }

    private fun resolveEurUsd(tickers: Map<String, Double>): Double {
        tickers["EURUSDC"]?.takeIf { it > 0.5 }?.let { return it }
        tickers["EURUSDT"]?.takeIf { it > 0.5 }?.let { return it }
        val btcUsd = tickers["BTCUSDT"] ?: tickers["BTCUSDC"]
        val btcEur = tickers["BTCEUR"]
        if (btcUsd != null && btcEur != null && btcUsd > 0.0 && btcEur > 0.0) return btcUsd / btcEur
        return 1.17
    }

    private fun quoteToUsd(quote: String, tickers: Map<String, Double>, eurUsd: Double): Double {
        if (quote == "USDT" || quote == "USDC" || quote == "FDUSD" || quote == "TUSD") return 1.0
        if (quote == "EUR") return eurUsd
        tickers[quote + "USDT"]?.takeIf { it > 0.0 }?.let { return it }
        tickers[quote + "USDC"]?.takeIf { it > 0.0 }?.let { return it }
        return 0.0
    }

    private fun splitSymbol(symbol: String): Pair<String, String>? {
        val quotes = listOf("USDC", "USDT", "FDUSD", "TUSD", "EUR", "BTC", "ETH")
        val quote = quotes.firstOrNull { symbol.endsWith(it) && symbol.length > it.length } ?: return null
        return symbol.removeSuffix(quote) to quote
    }

    private fun syncServerTime() {
        val root = publicGet("/v5/market/time")
        val server = root.optLong("time", 0L).takeIf { it > 0L }
            ?: root.optJSONObject("result")?.optString("timeSecond")?.toLongOrNull()?.times(1000L)
            ?: System.currentTimeMillis()
        serverOffsetMs = server - System.currentTimeMillis()
    }

    private fun signedGet(path: String, params: LinkedHashMap<String, String>): JSONObject {
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val timestamp = (System.currentTimeMillis() + serverOffsetMs).toString()
        val plain = timestamp + apiKey + recvWindow + query
        val signature = hmac(apiSecret, plain)
        val url = baseUrl + path + if (query.isBlank()) "" else "?$query"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-BAPI-API-KEY", apiKey)
            setRequestProperty("X-BAPI-TIMESTAMP", timestamp)
            setRequestProperty("X-BAPI-RECV-WINDOW", recvWindow)
            setRequestProperty("X-BAPI-SIGN", signature)
        }
        return readRoot(connection)
    }

    private fun publicGet(pathAndQuery: String): JSONObject {
        val connection = (URL(baseUrl + pathAndQuery).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
        }
        return readRoot(connection)
    }

    private fun readRoot(connection: HttpURLConnection): JSONObject {
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("Bybit HTTP $code • ${text.take(300)}")
            val root = JSONObject(text)
            val retCode = root.optInt("retCode", 0)
            if (retCode != 0) throw IllegalStateException("Bybit $retCode • ${root.optString("retMsg").take(250)}")
            root
        } finally {
            connection.disconnect()
        }
    }

    private fun hmac(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun d(value: String?): Double = value?.toDoubleOrNull() ?: 0.0

    private fun dateFmt(ms: Long): String {
        if (ms <= 0L) return "date inconnue"
        return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(ms))
    }

    companion object {
        fun fmt(v: Double): String {
            return when {
                v >= 1000 -> String.format(Locale.US, "%.2f", v)
                v >= 100 -> String.format(Locale.US, "%.3f", v)
                v >= 1 -> String.format(Locale.US, "%.5f", v)
                v >= 0.01 -> String.format(Locale.US, "%.6f", v)
                else -> String.format(Locale.US, "%.10f", v).trimEnd('0').trimEnd('.')
            }
        }
    }
}
