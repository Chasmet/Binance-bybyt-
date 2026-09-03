package com.chk.binancebybit

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class HunterMarket(
    val symbol: String,
    val lastPrice: Double,
    val change24hPct: Double,
    val turnover24h: Double,
    val volume24h: Double,
    val bidPrice: Double,
    val askPrice: Double
)

class OrderBookHunterMarketScanner {
    fun scanAllUsdcMarkets(): List<HunterMarket> {
        val connection = URL("https://api.bybit.eu/v5/market/tickers?category=spot").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "CHK-Crypto-OrderBook-Hunter")
        return try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Bybit EU HTTP $code")
            val root = JSONObject(body)
            val retCode = root.optInt("retCode", 0)
            if (retCode != 0) throw IllegalStateException("Bybit $retCode • ${root.optString("retMsg")}")
            parse(root.optJSONObject("result")?.optJSONArray("list") ?: JSONArray())
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(arr: JSONArray): List<HunterMarket> {
        val out = ArrayList<HunterMarket>()
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            val symbol = x.optString("symbol").uppercase(Locale.US)
            if (!symbol.endsWith("USDC") || symbol.length <= 4) continue
            out += HunterMarket(
                symbol = symbol,
                lastPrice = x.optString("lastPrice").toDoubleOrNull() ?: 0.0,
                change24hPct = (x.optString("price24hPcnt").toDoubleOrNull() ?: 0.0) * 100.0,
                turnover24h = x.optString("turnover24h").toDoubleOrNull() ?: 0.0,
                volume24h = x.optString("volume24h").toDoubleOrNull() ?: 0.0,
                bidPrice = x.optString("bid1Price").toDoubleOrNull() ?: 0.0,
                askPrice = x.optString("ask1Price").toDoubleOrNull() ?: 0.0
            )
        }
        return out.sortedByDescending { it.turnover24h }
    }
}
