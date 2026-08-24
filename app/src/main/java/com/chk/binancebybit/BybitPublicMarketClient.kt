package com.chk.binancebybit

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class BybitPublicTicker(
    val symbol: String,
    val lastPrice: Double,
    val bidPrice: Double,
    val askPrice: Double,
    val price24hPct: Double,
    val turnover24h: Double,
    val volume24h: Double
)

class BybitPublicMarketClient(
    private val baseUrl: String = "https://api.bybit.eu"
) {
    fun ticker(symbol: String): BybitPublicTicker {
        val s = normalizeSymbol(symbol)
        val root = get("$baseUrl/v5/market/tickers?category=spot&symbol=$s")
        ensureOk(root)
        val x = root.optJSONObject("result")?.optJSONArray("list")?.optJSONObject(0)
            ?: throw IllegalStateException("Ticker Bybit indisponible pour $s")
        return BybitPublicTicker(
            symbol = s,
            lastPrice = d(x.optString("lastPrice")),
            bidPrice = d(x.optString("bid1Price")),
            askPrice = d(x.optString("ask1Price")),
            price24hPct = d(x.optString("price24hPcnt")) * 100.0,
            turnover24h = d(x.optString("turnover24h")),
            volume24h = d(x.optString("volume24h"))
        )
    }

    fun recentCandles(symbol: String, interval: String = "1", limit: Int = 20): List<MarketCandle> {
        val s = normalizeSymbol(symbol)
        val root = get("$baseUrl/v5/market/kline?category=spot&symbol=$s&interval=$interval&limit=${limit.coerceIn(3, 200)}")
        ensureOk(root)
        val arr = root.optJSONObject("result")?.optJSONArray("list") ?: JSONArray()
        val out = ArrayList<MarketCandle>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONArray(i) ?: continue
            if (a.length() < 6) continue
            out += MarketCandle(
                time = a.optString(0).toLongOrNull() ?: continue,
                open = d(a.optString(1)),
                high = d(a.optString(2)),
                low = d(a.optString(3)),
                close = d(a.optString(4)),
                volume = d(a.optString(5))
            )
        }
        return out.sortedBy { it.time }
    }

    private fun ensureOk(root: JSONObject) {
        val code = root.optInt("retCode", 0)
        if (code != 0) throw IllegalStateException("Bybit $code • ${root.optString("retMsg")}")
    }

    private fun get(urlText: String): JSONObject {
        val c = URL(urlText).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "CHK-Crypto-Android")
        return try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            JSONObject(body)
        } finally {
            c.disconnect()
        }
    }

    private fun normalizeSymbol(value: String): String {
        val raw = value.trim().uppercase(Locale.US).replace("/", "").replace("-", "")
        return when {
            raw.endsWith("USDC") -> raw
            raw.endsWith("USDT") -> raw.removeSuffix("USDT") + "USDC"
            else -> raw + "USDC"
        }
    }

    private fun d(v: String?): Double = v?.toDoubleOrNull() ?: 0.0
}
