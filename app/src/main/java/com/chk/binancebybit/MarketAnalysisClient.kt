package com.chk.binancebybit

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class MarketCandle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

data class IndicatorSnapshot(
    val exchange: String,
    val requestedSymbol: String,
    val sourceSymbol: String,
    val interval: String,
    val candles: List<MarketCandle>,
    val lastPrice: Double,
    val changePct: Double,
    val rsi14: Double,
    val ema20: Double,
    val ema50: Double,
    val bbUpper: Double,
    val bbMiddle: Double,
    val bbLower: Double,
    val macd: Double,
    val macdSignal: Double,
    val macdHistogram: Double,
    val atr14: Double,
    val volumeRatio: Double,
    val support: Double,
    val resistance: Double,
    val trend: String,
    val divergence: String,
    val pattern: String,
    val score: Int,
    val summary: String,
    val capturedAt: Long = System.currentTimeMillis()
) {
    fun toSmartTraderNote(): String = buildString {
        append("ANALYSE TEMPS RÉEL — $requestedSymbol\n")
        append("Source : $exchange ($sourceSymbol) • Unité : $interval\n")
        append("Prix : ${f(lastPrice)} • Variation fenêtre : ${f(changePct)} %\n")
        append("Tendance : $trend • Score : $score/100\n")
        append("Support : ${f(support)} • Résistance : ${f(resistance)}\n")
        append("RSI14 : ${f(rsi14)}\n")
        append("EMA20 : ${f(ema20)} • EMA50 : ${f(ema50)}\n")
        append("Bollinger : ${f(bbLower)} / ${f(bbMiddle)} / ${f(bbUpper)}\n")
        append("MACD : ${f(macd)} • Signal : ${f(macdSignal)} • Hist : ${f(macdHistogram)}\n")
        append("ATR14 : ${f(atr14)} • Volume relatif : ${f(volumeRatio)}x\n")
        append("Divergence : $divergence\n")
        append("Pattern : $pattern\n")
        append("Synthèse : $summary\n")
        append("Cette analyse est informative. Toute proposition d'ordre reste soumise à confirmation manuelle dans CHK Crypto.")
    }

    private fun f(v: Double): String = String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
}

class MarketAnalysisClient {
    fun load(exchange: String, symbol: String, interval: String, limit: Int = 220): IndicatorSnapshot {
        val normalizedExchange = exchange.uppercase(Locale.US)
        val requested = normalizeSymbol(symbol)
        val (sourceSymbol, candles) = when (normalizedExchange) {
            "BINANCE" -> loadBinanceWithFallback(requested, interval, limit)
            else -> requested to loadBybit(requested, interval, limit)
        }
        if (candles.size < 60) throw IllegalStateException("Pas assez de bougies pour analyser $sourceSymbol.")
        return analyze(normalizedExchange, requested, sourceSymbol, interval, candles)
    }

    private fun loadBybit(symbol: String, interval: String, limit: Int): List<MarketCandle> {
        val bybitInterval = when (interval) {
            "1m" -> "1"; "5m" -> "5"; "15m" -> "15"; "1h" -> "60"; "4h" -> "240"; "1d" -> "D"; "1w" -> "W"
            else -> "60"
        }
        val url = "https://api.bybit.eu/v5/market/kline?category=spot&symbol=$symbol&interval=$bybitInterval&limit=${limit.coerceIn(60, 1000)}"
        val root = getJson(url)
        val code = root.optInt("retCode", 0)
        if (code != 0) throw IllegalStateException("Bybit $code • ${root.optString("retMsg")}")
        val arr = root.optJSONObject("result")?.optJSONArray("list") ?: JSONArray()
        val out = ArrayList<MarketCandle>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONArray(i) ?: continue
            if (a.length() < 6) continue
            out += MarketCandle(
                time = a.optString(0).toLongOrNull() ?: continue,
                open = a.optString(1).toDoubleOrNull() ?: continue,
                high = a.optString(2).toDoubleOrNull() ?: continue,
                low = a.optString(3).toDoubleOrNull() ?: continue,
                close = a.optString(4).toDoubleOrNull() ?: continue,
                volume = a.optString(5).toDoubleOrNull() ?: 0.0
            )
        }
        return out.sortedBy { it.time }
    }

    private fun loadBinanceWithFallback(symbol: String, interval: String, limit: Int): Pair<String, List<MarketCandle>> {
        val candidates = linkedSetOf(symbol)
        if (symbol.endsWith("USDC")) candidates += symbol.removeSuffix("USDC") + "USDT"
        var lastError: Exception? = null
        for (candidate in candidates) {
            try { return candidate to loadBinance(candidate, interval, limit) } catch (e: Exception) { lastError = e }
        }
        throw lastError ?: IllegalStateException("Paire Binance indisponible.")
    }

    private fun loadBinance(symbol: String, interval: String, limit: Int): List<MarketCandle> {
        val binanceInterval = when (interval) {
            "1m" -> "1m"; "5m" -> "5m"; "15m" -> "15m"; "1h" -> "1h"; "4h" -> "4h"; "1d" -> "1d"; "1w" -> "1w"
            else -> "1h"
        }
        val url = "https://api.binance.com/api/v3/klines?symbol=$symbol&interval=$binanceInterval&limit=${limit.coerceIn(60, 1000)}"
        val arr = getArray(url)
        val out = ArrayList<MarketCandle>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONArray(i) ?: continue
            if (a.length() < 6) continue
            out += MarketCandle(
                time = a.optLong(0),
                open = a.optString(1).toDoubleOrNull() ?: continue,
                high = a.optString(2).toDoubleOrNull() ?: continue,
                low = a.optString(3).toDoubleOrNull() ?: continue,
                close = a.optString(4).toDoubleOrNull() ?: continue,
                volume = a.optString(5).toDoubleOrNull() ?: 0.0
            )
        }
        return out
    }

    private fun analyze(exchange: String, requested: String, sourceSymbol: String, interval: String, c: List<MarketCandle>): IndicatorSnapshot {
        val closes = c.map { it.close }
        val rsiSeries = rsiSeries(closes, 14)
        val rsi = rsiSeries.last()
        val ema20Series = emaSeries(closes, 20)
        val ema50Series = emaSeries(closes, 50)
        val ema20 = ema20Series.last()
        val ema50 = ema50Series.last()
        val bb = bollinger(closes, 20)
        val macdTuple = macd(closes)
        val atr = atr(c, 14)
        val volAvg = c.takeLast(21).dropLast(1).map { it.volume }.average().takeIf { it > 0.0 } ?: 1.0
        val volumeRatio = c.last().volume / volAvg
        val swing = c.takeLast(min(60, c.size))
        val support = swing.minOf { it.low }
        val resistance = swing.maxOf { it.high }
        val last = closes.last()
        val first = closes[max(0, closes.size - 20)]
        val changePct = if (first > 0) (last / first - 1.0) * 100.0 else 0.0

        val trend = when {
            last > ema20 && ema20 > ema50 -> "HAUSSIÈRE"
            last < ema20 && ema20 < ema50 -> "BAISSIÈRE"
            else -> "NEUTRE / TRANSITION"
        }
        val div = divergence(closes, rsiSeries)
        val pattern = detectPattern(c)
        var score = 50
        if (trend == "HAUSSIÈRE") score += 12 else if (trend == "BAISSIÈRE") score -= 12
        if (rsi in 52.0..68.0) score += 8
        if (rsi < 30) score += 5
        if (rsi > 72) score -= 7
        if (macdTuple.third > 0) score += 8 else score -= 6
        if (volumeRatio > 1.25) score += 6
        if (div.contains("HAUSSIÈRE")) score += 8
        if (div.contains("BAISSIÈRE")) score -= 8
        if (last <= support * 1.025) score += 5
        if (last >= resistance * 0.985) score -= 4
        score = score.coerceIn(5, 95)

        val summary = buildString {
            append("$trend. ")
            when {
                rsi > 70 -> append("RSI en zone haute, attention au surachat. ")
                rsi < 30 -> append("RSI en zone basse, rebond possible mais confirmation nécessaire. ")
                else -> append("RSI équilibré. ")
            }
            append(if (macdTuple.third >= 0) "Momentum MACD positif. " else "Momentum MACD négatif. ")
            if (volumeRatio >= 1.3) append("Volume supérieur à la moyenne. ")
            if (div != "AUCUNE") append("$div. ")
            if (pattern != "AUCUN") append("Pattern récent : $pattern.")
        }.trim()

        return IndicatorSnapshot(
            exchange, requested, sourceSymbol, interval, c, last, changePct, rsi, ema20, ema50,
            bb.first, bb.second, bb.third, macdTuple.first, macdTuple.second, macdTuple.third,
            atr, volumeRatio, support, resistance, trend, div, pattern, score, summary
        )
    }

    private fun normalizeSymbol(input: String): String {
        val raw = input.uppercase(Locale.US).replace("/", "").replace("-", "").replace(" ", "")
        if (raw.endsWith("USDC") || raw.endsWith("USDT")) return raw
        return raw + "USDC"
    }

    private fun emaSeries(values: List<Double>, period: Int): List<Double> {
        val out = ArrayList<Double>(values.size)
        val k = 2.0 / (period + 1.0)
        var current = values.first()
        values.forEachIndexed { i, v ->
            current = if (i == 0) v else v * k + current * (1.0 - k)
            out += current
        }
        return out
    }

    private fun rsiSeries(values: List<Double>, period: Int): List<Double> {
        val out = MutableList(values.size) { 50.0 }
        if (values.size <= period) return out
        var gains = 0.0
        var losses = 0.0
        for (i in 1..period) {
            val d = values[i] - values[i - 1]
            if (d >= 0) gains += d else losses -= d
        }
        var avgGain = gains / period
        var avgLoss = losses / period
        out[period] = if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
        for (i in period + 1 until values.size) {
            val d = values[i] - values[i - 1]
            val g = max(d, 0.0)
            val l = max(-d, 0.0)
            avgGain = (avgGain * (period - 1) + g) / period
            avgLoss = (avgLoss * (period - 1) + l) / period
            out[i] = if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
        }
        for (i in 0 until period) out[i] = out[period]
        return out
    }

    private fun bollinger(values: List<Double>, period: Int): Triple<Double, Double, Double> {
        val w = values.takeLast(period)
        val mid = w.average()
        val sd = sqrt(w.sumOf { (it - mid).pow(2) } / w.size)
        return Triple(mid + 2 * sd, mid, mid - 2 * sd)
    }

    private fun macd(values: List<Double>): Triple<Double, Double, Double> {
        val fast = emaSeries(values, 12)
        val slow = emaSeries(values, 26)
        val line = values.indices.map { fast[it] - slow[it] }
        val signal = emaSeries(line, 9)
        return Triple(line.last(), signal.last(), line.last() - signal.last())
    }

    private fun atr(c: List<MarketCandle>, period: Int): Double {
        val tr = ArrayList<Double>()
        for (i in 1 until c.size) {
            tr += max(c[i].high - c[i].low, max(abs(c[i].high - c[i-1].close), abs(c[i].low - c[i-1].close)))
        }
        return tr.takeLast(period).average()
    }

    private fun divergence(closes: List<Double>, rsi: List<Double>): String {
        if (closes.size < 30) return "AUCUNE"
        val a = closes.size - 25
        val b = closes.size - 1
        val mid = (a + b) / 2
        val low1 = (a..mid).minByOrNull { closes[it] } ?: a
        val low2 = (mid + 1..b).minByOrNull { closes[it] } ?: b
        val high1 = (a..mid).maxByOrNull { closes[it] } ?: a
        val high2 = (mid + 1..b).maxByOrNull { closes[it] } ?: b
        return when {
            closes[low2] < closes[low1] && rsi[low2] > rsi[low1] + 2 -> "DIVERGENCE HAUSSIÈRE"
            closes[high2] > closes[high1] && rsi[high2] < rsi[high1] - 2 -> "DIVERGENCE BAISSIÈRE"
            else -> "AUCUNE"
        }
    }

    private fun detectPattern(c: List<MarketCandle>): String {
        if (c.size < 3) return "AUCUN"
        val p = c[c.size - 2]
        val x = c.last()
        val body = abs(x.close - x.open)
        val range = (x.high - x.low).coerceAtLeast(1e-12)
        val lowerWick = min(x.open, x.close) - x.low
        val upperWick = x.high - max(x.open, x.close)
        return when {
            body / range < 0.12 -> "DOJI"
            lowerWick > body * 2.2 && upperWick < body -> "MARTEAU / REJET BAS"
            upperWick > body * 2.2 && lowerWick < body -> "SHOOTING STAR / REJET HAUT"
            x.close > x.open && p.close < p.open && x.open <= p.close && x.close >= p.open -> "ENGULFING HAUSSIER"
            x.close < x.open && p.close > p.open && x.open >= p.close && x.close <= p.open -> "ENGULFING BAISSIER"
            else -> "AUCUN"
        }
    }

    private fun getJson(urlText: String): JSONObject = JSONObject(getText(urlText))

    private fun getArray(urlText: String): JSONArray {
        val text = getText(urlText)
        if (text.trim().startsWith("{")) {
            val o = JSONObject(text)
            val msg = o.optString("msg", o.optString("message", "Erreur API"))
            throw IllegalStateException(msg)
        }
        return JSONArray(text)
    }

    private fun getText(urlText: String): String {
        val c = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CHK-Crypto-Android")
        }
        return try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code • ${text.take(220)}")
            text
        } finally { c.disconnect() }
    }
}
