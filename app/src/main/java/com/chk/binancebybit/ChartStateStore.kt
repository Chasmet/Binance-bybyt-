package com.chk.binancebybit

import android.content.Context
import org.json.JSONObject

class ChartStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun loadCurrent(): ChartSessionState {
        val raw = prefs.getString(KEY_CURRENT, null) ?: return ChartSessionState()
        return runCatching { ChartSessionState.fromJson(JSONObject(raw)) }.getOrDefault(ChartSessionState())
    }

    @Synchronized
    fun loadFor(symbol: String, timeframe: String): ChartSessionState {
        val s = ChartSessionState.normalizeSymbol(symbol)
        val tf = ChartSessionState.normalizeTimeframe(timeframe)
        val raw = prefs.getString(key(s, tf), null)
        if (raw != null) {
            return runCatching { ChartSessionState.fromJson(JSONObject(raw)) }.getOrNull()
                ?.copy(symbol = s, timeframe = tf)
                ?: ChartSessionState(symbol = s, timeframe = tf)
        }
        val current = loadCurrent()
        return ChartSessionState(
            symbol = s,
            timeframe = tf,
            viewport = if (current.symbol == s) current.viewport else ChartViewportState(),
            indicators = current.indicators,
            drawings = current.drawings.filter { current.symbol == s },
            profile = current.profile
        )
    }

    @Synchronized
    fun save(state: ChartSessionState) {
        val normalized = state.copy(
            symbol = ChartSessionState.normalizeSymbol(state.symbol),
            timeframe = ChartSessionState.normalizeTimeframe(state.timeframe),
            drawings = state.drawings.take(100)
        )
        val raw = normalized.toJson().toString()
        prefs.edit()
            .putString(KEY_CURRENT, raw)
            .putString(key(normalized.symbol, normalized.timeframe), raw)
            .apply()
    }

    fun profile(name: String, symbol: String = loadCurrent().symbol): ChartSessionState {
        val current = loadCurrent()
        val upper = name.uppercase()
        val tf = when (upper) {
            "SCALP" -> "5m"
            "SWING" -> "4h"
            else -> "15m"
        }
        return current.copy(
            symbol = ChartSessionState.normalizeSymbol(symbol),
            timeframe = tf,
            viewport = ChartViewportState(visibleCount = if (upper == "SCALP") 120 else 100),
            profile = when (upper) { "SCALP", "SWING" -> upper; else -> "INTRADAY" }
        )
    }

    private fun key(symbol: String, timeframe: String) = "chart_${symbol}_${timeframe}"

    companion object {
        private const val PREFS = "chk_chart_preferences_v2"
        private const val KEY_CURRENT = "current_chart_state"
    }
}
