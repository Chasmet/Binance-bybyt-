package com.chk.binancebybit

import java.util.Locale

enum class CanonicalOrderState {
    PLACED,
    OPEN,
    PARTIAL,
    FILLED,
    CANCELLED,
    REJECTED,
    UNKNOWN
}

object OrderLifecycle {
    fun fromBybit(rawStatus: String?, orderId: String? = null, executedQty: Double = 0.0): CanonicalOrderState {
        val raw = rawStatus?.trim()?.uppercase(Locale.US).orEmpty()
        return when (raw) {
            "NEW", "UNTRIGGERED", "TRIGGERED", "ACTIVE" -> CanonicalOrderState.OPEN
            "PARTIALLYFILLED", "PARTIALLY_FILLED", "PARTIAL" -> CanonicalOrderState.PARTIAL
            "FILLED" -> CanonicalOrderState.FILLED
            "CANCELLED", "CANCELED", "PARTIALLYFILLEDCANCELED", "PARTIALLY_FILLED_CANCELED", "DEACTIVATED" -> CanonicalOrderState.CANCELLED
            "REJECTED" -> CanonicalOrderState.REJECTED
            "SENT", "CREATED", "PLACED", "PROCESSING" -> CanonicalOrderState.PLACED
            else -> when {
                executedQty > 0.0 -> CanonicalOrderState.PARTIAL
                !orderId.isNullOrBlank() -> CanonicalOrderState.PLACED
                else -> CanonicalOrderState.UNKNOWN
            }
        }
    }

    fun isOpen(state: CanonicalOrderState): Boolean = state == CanonicalOrderState.OPEN || state == CanonicalOrderState.PARTIAL
    fun countsForPnlOrTransactions(state: CanonicalOrderState): Boolean = state == CanonicalOrderState.FILLED
}
