package com.chk.binancebybit

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object OrderBookHunterStore {
    private val statuses = ConcurrentHashMap<String, HunterStatus>()

    fun put(status: HunterStatus) {
        statuses[status.symbol] = status
    }

    fun get(symbolValue: String): HunterStatus? = statuses[normalizeSymbol(symbolValue)]

    fun all(): List<HunterStatus> = statuses.values.sortedByDescending { it.anomalyScore }

    fun remove(symbolValue: String) {
        statuses.remove(normalizeSymbol(symbolValue))
    }

    fun normalizeSymbol(value: String): String {
        val raw = value.trim().uppercase(Locale.US).replace("/", "").replace("-", "")
        require(raw.matches(Regex("^[A-Z0-9]{2,24}$"))) { "Symbole invalide" }
        return when {
            raw.endsWith("USDC") -> raw
            raw.endsWith("USDT") -> raw.removeSuffix("USDT") + "USDC"
            else -> raw + "USDC"
        }
    }

    fun statusJson(status: HunterStatus): JSONObject = JSONObject().apply {
        put("symbol", status.symbol)
        put("status", if (status.watching) "WATCHING" else "STOPPED")
        put("lastPrice", status.lastPrice)
        put("change24hPct", status.change24hPct)
        put("spreadPercent", status.spreadPercent)
        put("turnover24h", status.turnover24h)
        put("volume24h", status.volume24h)
        put("anomalyScore", status.anomalyScore)
        put("classification", status.classification)
        put("safeExplanation", HunterClassification.safeExplanation(status.anomalyScore))
        put("synchronized", status.synchronized)
        put("updatedAt", status.updatedAt)
        put("bidWalls", JSONArray(status.bidWalls.map(::wallJson)))
        put("askWalls", JSONArray(status.askWalls.map(::wallJson)))
        put("imbalances", JSONArray(status.imbalances.map { i -> JSONObject().apply {
            put("distancePercent", i.distancePercent)
            put("buyPressure", i.buyPressure)
            put("sellPressure", i.sellPressure)
            put("bidNotional", i.bidNotional)
            put("askNotional", i.askNotional)
        } }))
    }

    fun eventJson(e: HunterEvent): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("symbol", e.symbol)
        put("type", e.type.name)
        put("side", e.side?.name ?: "")
        put("price", e.price)
        put("qty", e.qty)
        put("oldPrice", e.oldPrice)
        put("newPrice", e.newPrice)
        put("score", e.score)
        put("detail", e.detail)
        put("createdAt", e.createdAt)
    }

    private fun wallJson(w: HunterWallView): JSONObject = JSONObject().apply {
        put("trackId", w.trackId)
        put("side", w.side.name)
        put("price", w.price)
        put("qty", w.qty)
        put("notionalUsdc", w.notionalUsdc)
        put("ageSeconds", w.ageSeconds)
        put("distanceFromMidPercent", w.distanceFromMidPercent)
        put("wallQtyRatio", w.wallQtyRatio)
        put("wallVsMedianDepth", w.wallVsMedianDepth)
        put("wallVsTurnover", w.wallVsTurnover)
        put("significanceScore", w.significanceScore)
    }
}
