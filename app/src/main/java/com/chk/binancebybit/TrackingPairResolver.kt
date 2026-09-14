package com.chk.binancebybit

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Resolves one public Spot pair per held asset and exchange. */
class TrackingPairResolver(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("chk_tracking_pairs", Context.MODE_PRIVATE)

    data class Result(val binance: Map<String, String>, val bybit: Map<String, String>)

    fun resolve(assets: Set<String>): Result {
        val b = linkedMapOf<String, String>()
        val y = linkedMapOf<String, String>()
        assets.sorted().forEach { raw ->
            val asset = raw.uppercase(Locale.US)
            resolveCached("BINANCE", asset)?.let { b[asset] = it } ?: resolveBinance(asset)?.let { save("BINANCE", asset, it); b[asset] = it }
            resolveCached("BYBIT", asset)?.let { y[asset] = it } ?: resolveBybit(asset)?.let { save("BYBIT", asset, it); y[asset] = it }
        }
        return Result(b, y)
    }

    private fun resolveBinance(asset: String): String? {
        for (quote in listOf("USDT", "USDC")) {
            val symbol = asset + quote
            if (existsBinance(symbol)) return symbol
        }
        return null
    }

    private fun resolveBybit(asset: String): String? {
        // Existing CHK Crypto orders use USDC when available; USDT is the broad public fallback.
        for (quote in listOf("USDC", "USDT")) {
            val symbol = asset + quote
            if (existsBybit(symbol)) return symbol
        }
        return null
    }

    private fun existsBinance(symbol: String): Boolean = runCatching {
        val root = JSONObject(get("https://api.binance.com/api/v3/exchangeInfo?symbol=$symbol"))
        val rows = root.optJSONArray("symbols")
        rows != null && rows.length() > 0 && rows.optJSONObject(0)?.optString("status") == "TRADING"
    }.getOrDefault(false)

    private fun existsBybit(symbol: String): Boolean {
        val urls = listOf(
            "https://api.bybit.eu/v5/market/instruments-info?category=spot&symbol=$symbol",
            "https://api.bybit.com/v5/market/instruments-info?category=spot&symbol=$symbol"
        )
        for (url in urls) {
            val ok = runCatching {
                val root = JSONObject(get(url))
                root.optLong("retCode", -1L) == 0L && (root.optJSONObject("result")?.optJSONArray("list")?.length() ?: 0) > 0
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun get(urlText: String): String {
        val c = URL(urlText).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 7000
        c.readTimeout = 9000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "CHK-Crypto-Tracking/0.10")
        return try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            text
        } finally { c.disconnect() }
    }

    private fun resolveCached(exchange: String, asset: String): String? {
        val key = "${exchange}_${asset}"
        val at = prefs.getLong("${key}_at", 0L)
        if (System.currentTimeMillis() - at > 24 * 60 * 60_000L) return null
        return prefs.getString(key, null)?.takeIf { it.matches(Regex("^[A-Z0-9]{4,28}$")) }
    }

    private fun save(exchange: String, asset: String, symbol: String) {
        val key = "${exchange}_${asset}"
        prefs.edit().putString(key, symbol).putLong("${key}_at", System.currentTimeMillis()).apply()
    }
}
