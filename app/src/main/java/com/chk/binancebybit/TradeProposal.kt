package com.chk.binancebybit

import org.json.JSONObject

data class TradeProposal(
    val id: String,
    val symbol: String,
    val side: String,
    val orderType: String,
    val quoteAmountUsdc: Double,
    val baseQuantity: Double?,
    val limitPrice: Double?,
    val rationale: String,
    val confidence: Int?,
    val source: String,
    val status: String,
    val expiresAt: String?,
    val createdAt: String?
) {
    val baseAsset: String
        get() = symbol.uppercase().removeSuffix("USDC")

    companion object {
        fun fromJson(o: JSONObject): TradeProposal = TradeProposal(
            id = o.optString("id"),
            symbol = o.optString("symbol").uppercase(),
            side = o.optString("side").uppercase(),
            orderType = o.optString("order_type").uppercase(),
            quoteAmountUsdc = o.optDouble("quote_amount_usdc", 0.0),
            baseQuantity = if (o.isNull("base_quantity")) null else o.optDouble("base_quantity"),
            limitPrice = if (o.isNull("limit_price")) null else o.optDouble("limit_price"),
            rationale = o.optString("rationale"),
            confidence = if (o.isNull("confidence")) null else o.optInt("confidence"),
            source = o.optString("source", "chatgpt"),
            status = o.optString("status", "pending"),
            expiresAt = o.optString("expires_at").takeIf { it.isNotBlank() },
            createdAt = o.optString("created_at").takeIf { it.isNotBlank() }
        )
    }
}

data class TradeExecutionResult(
    val orderId: String,
    val orderLinkId: String,
    val orderStatus: String,
    val symbol: String,
    val side: String,
    val orderType: String,
    val requestedQty: String,
    val requestedPrice: String?,
    val executedQty: Double,
    val executedValueUsdc: Double,
    val averagePrice: Double
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("orderId", orderId)
        put("orderLinkId", orderLinkId)
        put("orderStatus", orderStatus)
        put("symbol", symbol)
        put("side", side)
        put("orderType", orderType)
        put("requestedQty", requestedQty)
        put("requestedPrice", requestedPrice)
        put("executedQty", executedQty)
        put("executedValueUsdc", executedValueUsdc)
        put("averagePrice", averagePrice)
    }
}
