package com.chk.binancebybit

import kotlin.math.abs

data class HunterBookLevel(
    val price: Double,
    val qty: Double
) {
    val notionalUsdc: Double get() = price * qty
}

data class HunterBookSnapshot(
    val symbol: String,
    val bids: List<HunterBookLevel>,
    val asks: List<HunterBookLevel>,
    val updateId: Long,
    val sequence: Long,
    val timestamp: Long,
    val matchingTimestamp: Long,
    val synchronized: Boolean
) {
    val bestBid: Double get() = bids.firstOrNull()?.price ?: 0.0
    val bestAsk: Double get() = asks.firstOrNull()?.price ?: 0.0
    val midPrice: Double
        get() = when {
            bestBid > 0.0 && bestAsk > 0.0 -> (bestBid + bestAsk) / 2.0
            bestBid > 0.0 -> bestBid
            else -> bestAsk
        }
    val spreadPercent: Double
        get() = if (midPrice > 0.0 && bestAsk >= bestBid) ((bestAsk - bestBid) / midPrice) * 100.0 else 0.0
}

data class HunterTrade(
    val symbol: String,
    val price: Double,
    val qty: Double,
    val side: String,
    val timestamp: Long,
    val sequence: Long = 0L
) {
    val notionalUsdc: Double get() = price * qty
}

data class HunterTicker(
    val symbol: String,
    val lastPrice: Double,
    val change24hPct: Double,
    val turnover24h: Double,
    val volume24h: Double,
    val timestamp: Long
)

enum class HunterWallSide { BUY, SELL }

enum class HunterWallStatus { ACTIVE, DISAPPEARED, ABSORBED, CANCELLED, MOVED }

data class HunterWallTrack(
    val id: String,
    val symbol: String,
    val side: HunterWallSide,
    val initialPrice: Double,
    var currentPrice: Double,
    val initialQty: Double,
    var currentQty: Double,
    val firstSeen: Long,
    var lastSeen: Long,
    var moveCount: Int = 0,
    var cancelCount: Int = 0,
    var executedEstimate: Double = 0.0,
    var minObservedQty: Double = currentQty,
    var refillCount: Int = 0,
    var lastMissingAt: Long = 0L,
    var status: HunterWallStatus = HunterWallStatus.ACTIVE
) {
    fun quantitySimilarity(otherQty: Double): Double {
        val max = maxOf(currentQty, otherQty)
        return if (max <= 0.0) 0.0 else 1.0 - (abs(currentQty - otherQty) / max)
    }
}

enum class HunterEventType {
    LARGE_WALL,
    WALL_RETREAT,
    WALL_CHASING_PRICE,
    WALL_CANCELLED_NEAR_TOUCH,
    WALL_ABSORPTION,
    WALL_REFILL,
    WALL_DISAPPEARED,
    REPEATED_WALL_REPOSITIONING,
    ORDERBOOK_LIQUIDITY_MISMATCH,
    ORDERBOOK_IMBALANCE,
    ORDERBOOK_SWEEP,
    BOOK_DESYNC,
    BOOK_RESYNC,
    SCORE_CHANGED,
    USER_NOTE,
    WATCH_STARTED,
    WATCH_STOPPED
}

data class HunterEvent(
    val id: String,
    val symbol: String,
    val type: HunterEventType,
    val side: HunterWallSide? = null,
    val price: Double = 0.0,
    val qty: Double = 0.0,
    val oldPrice: Double = 0.0,
    val newPrice: Double = 0.0,
    val score: Int = 0,
    val detail: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class HunterImbalance(
    val distancePercent: Double,
    val bidNotional: Double,
    val askNotional: Double
) {
    val buyPressure: Double
        get() {
            val total = bidNotional + askNotional
            return if (total <= 0.0) 50.0 else (bidNotional / total) * 100.0
        }
    val sellPressure: Double get() = 100.0 - buyPressure
}

data class HunterWallView(
    val trackId: String,
    val side: HunterWallSide,
    val price: Double,
    val qty: Double,
    val notionalUsdc: Double,
    val ageSeconds: Long,
    val distanceFromMidPercent: Double,
    val wallQtyRatio: Double,
    val wallVsMedianDepth: Double,
    val wallVsTurnover: Double,
    val significanceScore: Double
)

data class HunterStatus(
    val symbol: String,
    val watching: Boolean,
    val lastPrice: Double,
    val change24hPct: Double,
    val spreadPercent: Double,
    val turnover24h: Double,
    val volume24h: Double,
    val anomalyScore: Int,
    val classification: String,
    val synchronized: Boolean,
    val bidWalls: List<HunterWallView>,
    val askWalls: List<HunterWallView>,
    val imbalances: List<HunterImbalance>,
    val updatedAt: Long
)

object HunterClassification {
    fun label(score: Int): String = when (score.coerceIn(0, 100)) {
        in 0..39 -> "NORMAL"
        in 40..59 -> "ACTIVITÉ INHABITUELLE"
        in 60..74 -> "SPOOFING POTENTIEL"
        in 75..89 -> "COMPORTEMENT FORTEMENT SUSPECT"
        else -> "ANOMALIE EXTRÊME"
    }

    fun safeExplanation(score: Int): String = when {
        score >= 75 -> "Comportement fortement suspect. Spoofing potentiel ou market-making agressif. Manipulation non confirmée."
        score >= 60 -> "Spoofing potentiel. Les signaux du carnet sont insuffisants pour confirmer une manipulation."
        score >= 40 -> "Activité inhabituelle dans les ordres visibles. À confirmer avec les trades et le marché global."
        else -> "Comportement du carnet compatible avec une activité normale à cet instant."
    }
}
