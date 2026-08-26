package com.chk.binancebybit

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChartIndicatorConfig(
    val maPeriods: List<Int> = listOf(7, 14, 28),
    val emaPeriods: List<Int> = listOf(9, 20, 50, 200),
    val volume: Boolean = true,
    val bollinger: Boolean = true,
    val rsiPeriod: Int = 14,
    val macd: Boolean = true,
    val atrPeriod: Int = 14
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ma", JSONArray(maPeriods))
        put("ema", JSONArray(emaPeriods))
        put("volume", volume)
        put("bollinger", bollinger)
        put("rsi", rsiPeriod)
        put("macd", macd)
        put("atr", atrPeriod)
    }

    companion object {
        fun fromJson(o: JSONObject?): ChartIndicatorConfig {
            if (o == null) return ChartIndicatorConfig()
            fun ints(name: String, fallback: List<Int>): List<Int> {
                val a = o.optJSONArray(name) ?: return fallback
                return buildList {
                    for (i in 0 until a.length()) {
                        val v = a.optInt(i, 0)
                        if (v in 1..500) add(v)
                    }
                }.distinct().ifEmpty { fallback }
            }
            return ChartIndicatorConfig(
                maPeriods = ints("ma", listOf(7, 14, 28)),
                emaPeriods = ints("ema", listOf(9, 20, 50, 200)),
                volume = o.optBoolean("volume", true),
                bollinger = o.optBoolean("bollinger", true),
                rsiPeriod = o.optInt("rsi", 14).coerceIn(2, 100),
                macd = o.optBoolean("macd", true),
                atrPeriod = o.optInt("atr", 14).coerceIn(2, 100)
            )
        }
    }
}

data class ChartCrosshairState(
    val active: Boolean = false,
    val timestamp: Long? = null,
    val price: Double? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("active", active)
        if (timestamp != null) put("timestamp", timestamp)
        if (price != null && price.isFinite()) put("price", price)
    }

    companion object {
        fun fromJson(o: JSONObject?): ChartCrosshairState {
            if (o == null) return ChartCrosshairState()
            return ChartCrosshairState(
                active = o.optBoolean("active", false),
                timestamp = o.optLong("timestamp", 0L).takeIf { it > 0L },
                price = o.optDouble("price", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
            )
        }
    }
}

data class ChartViewportState(
    val visibleCount: Int = 100,
    val offsetFromEnd: Int = 0,
    val autoScale: Boolean = true,
    val priceScale: Double = 1.0,
    val crosshair: ChartCrosshairState = ChartCrosshairState()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("visibleCount", visibleCount)
        put("offsetFromEnd", offsetFromEnd)
        put("autoScale", autoScale)
        put("priceScale", priceScale)
        put("crosshair", crosshair.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject?): ChartViewportState {
            if (o == null) return ChartViewportState()
            return ChartViewportState(
                visibleCount = o.optInt("visibleCount", 100).coerceIn(12, 600),
                offsetFromEnd = o.optInt("offsetFromEnd", 0).coerceAtLeast(0),
                autoScale = o.optBoolean("autoScale", true),
                priceScale = o.optDouble("priceScale", 1.0).takeIf { it.isFinite() }?.coerceIn(0.2, 8.0) ?: 1.0,
                crosshair = ChartCrosshairState.fromJson(o.optJSONObject("crosshair"))
            )
        }
    }
}

data class ChartDrawing(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val label: String = "",
    val price1: Double,
    val price2: Double? = null,
    val time1: Long? = null,
    val time2: Long? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.lowercase())
        put("label", label.take(120))
        put("price1", price1)
        if (price2 != null && price2.isFinite()) put("price2", price2)
        if (time1 != null && time1 > 0L) put("time1", time1)
        if (time2 != null && time2 > 0L) put("time2", time2)
    }

    companion object {
        fun fromJson(o: JSONObject?): ChartDrawing? {
            if (o == null) return null
            val p1 = o.optDouble("price1", Double.NaN)
            if (!p1.isFinite() || p1 <= 0.0) return null
            return ChartDrawing(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                type = o.optString("type", "horizontal").lowercase(),
                label = o.optString("label").take(120),
                price1 = p1,
                price2 = o.optDouble("price2", Double.NaN).takeIf { it.isFinite() && it > 0.0 },
                time1 = o.optLong("time1", 0L).takeIf { it > 0L },
                time2 = o.optLong("time2", 0L).takeIf { it > 0L }
            )
        }
    }
}

data class ChartSessionState(
    val symbol: String = "RENDERUSDC",
    val timeframe: String = "1h",
    val viewport: ChartViewportState = ChartViewportState(),
    val indicators: ChartIndicatorConfig = ChartIndicatorConfig(),
    val drawings: List<ChartDrawing> = emptyList(),
    val profile: String = "INTRADAY"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("symbol", symbol)
        put("timeframe", timeframe)
        put("viewport", viewport.toJson())
        put("indicators", indicators.toJson())
        put("profile", profile)
        put("drawings", JSONArray().apply { drawings.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject?): ChartSessionState {
            if (o == null) return ChartSessionState()
            val drawings = buildList {
                val a = o.optJSONArray("drawings") ?: JSONArray()
                for (i in 0 until a.length()) ChartDrawing.fromJson(a.optJSONObject(i))?.let(::add)
            }
            return ChartSessionState(
                symbol = normalizeSymbol(o.optString("symbol", "RENDERUSDC")),
                timeframe = normalizeTimeframe(o.optString("timeframe", "1h")),
                viewport = ChartViewportState.fromJson(o.optJSONObject("viewport")),
                indicators = ChartIndicatorConfig.fromJson(o.optJSONObject("indicators")),
                drawings = drawings.take(100),
                profile = o.optString("profile", "INTRADAY").uppercase().take(24)
            )
        }

        fun normalizeSymbol(input: String): String {
            val raw = input.trim().uppercase().replace("/", "").replace("-", "").replace(" ", "")
            return when {
                raw.isBlank() -> "RENDERUSDC"
                raw.endsWith("USDC") -> raw
                raw.endsWith("USDT") -> raw.removeSuffix("USDT") + "USDC"
                else -> raw + "USDC"
            }
        }

        fun normalizeTimeframe(input: String): String {
            val v = input.trim().lowercase()
            return when (v) {
                "1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d", "3d", "1w" -> v
                else -> "1h"
            }
        }
    }
}
