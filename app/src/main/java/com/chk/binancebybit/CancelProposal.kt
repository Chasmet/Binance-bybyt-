package com.chk.binancebybit

import org.json.JSONObject

data class CancelProposal(
    val id: String,
    val symbol: String,
    val targetOrderId: String,
    val targetOrderLinkId: String?,
    val rationale: String,
    val confidence: Int?,
    val status: String,
    val expiresAt: String?,
    val createdAt: String?
) {
    companion object {
        fun fromJson(o: JSONObject): CancelProposal = CancelProposal(
            id = o.optString("id"),
            symbol = o.optString("symbol").uppercase(),
            targetOrderId = o.optString("target_order_id"),
            targetOrderLinkId = o.optString("target_order_link_id").takeIf { it.isNotBlank() },
            rationale = o.optString("rationale"),
            confidence = if (o.isNull("confidence")) null else o.optInt("confidence"),
            status = o.optString("status", "pending"),
            expiresAt = o.optString("expires_at").takeIf { it.isNotBlank() },
            createdAt = o.optString("created_at").takeIf { it.isNotBlank() }
        )
    }
}

data class CancelExecutionResult(
    val orderId: String,
    val orderLinkId: String,
    val orderStatus: String,
    val symbol: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("orderId", orderId)
        put("orderLinkId", orderLinkId)
        put("orderStatus", orderStatus)
        put("symbol", symbol)
    }
}
