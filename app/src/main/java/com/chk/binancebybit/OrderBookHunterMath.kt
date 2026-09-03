package com.chk.binancebybit

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

object OrderBookHunterMath {
    fun candidateWalls(snapshot: HunterBookSnapshot, ticker: HunterTicker?, now: Long): Pair<List<HunterWallView>, List<HunterWallView>> {
        val mid = snapshot.midPrice
        if (mid <= 0.0) return emptyList<HunterWallView>() to emptyList()
        val all = (snapshot.bids + snapshot.asks).filter { distancePct(it.price, mid) <= 2.0 && it.qty > 0.0 }
        if (all.isEmpty()) return emptyList<HunterWallView>() to emptyList()
        val medianQty = median(all.map { it.qty }).coerceAtLeast(1e-12)
        val medianNotional = median(all.map { it.notionalUsdc }).coerceAtLeast(1e-12)
        val turnover = ticker?.turnover24h ?: 0.0
        fun build(side: HunterWallSide, levels: List<HunterBookLevel>): List<HunterWallView> = levels.mapNotNull { level ->
            val qtyRatio = level.qty / medianQty
            val depthRatio = level.notionalUsdc / medianNotional
            val turnoverRatio = if (turnover > 0.0) level.notionalUsdc / turnover else 0.0
            val distance = distancePct(level.price, mid)
            val significance = (
                ln(1.0 + depthRatio) * 24.0 +
                    ln(1.0 + qtyRatio) * 18.0 +
                    min(36.0, turnoverRatio * 240.0) -
                    min(10.0, distance * 3.0)
                ).coerceIn(0.0, 100.0)
            if (significance < 48.0) null else HunterWallView(
                trackId = "",
                side = side,
                price = level.price,
                qty = level.qty,
                notionalUsdc = level.notionalUsdc,
                ageSeconds = 0,
                distanceFromMidPercent = distance,
                wallQtyRatio = qtyRatio,
                wallVsMedianDepth = depthRatio,
                wallVsTurnover = turnoverRatio,
                significanceScore = significance
            )
        }.sortedByDescending { it.significanceScore }.take(12)
        return build(HunterWallSide.BUY, snapshot.bids) to build(HunterWallSide.SELL, snapshot.asks)
    }

    fun imbalances(snapshot: HunterBookSnapshot): List<HunterImbalance> {
        val mid = snapshot.midPrice
        if (mid <= 0.0) return emptyList()
        return listOf(0.25, 0.5, 1.0, 2.0).map { distance ->
            val bids = snapshot.bids.filter { distancePct(it.price, mid) <= distance }.sumOf { it.notionalUsdc }
            val asks = snapshot.asks.filter { distancePct(it.price, mid) <= distance }.sumOf { it.notionalUsdc }
            HunterImbalance(distance, bids, asks)
        }
    }

    fun anomalyScore(
        maxWallSignificance: Double,
        recentEvents: List<HunterEvent>,
        imbalances: List<HunterImbalance>,
        spreadPercent: Double,
        liquidityMismatch: Boolean
    ): Int {
        val retreat = recentEvents.count { it.type == HunterEventType.WALL_RETREAT }
        val cancelTouch = recentEvents.count { it.type == HunterEventType.WALL_CANCELLED_NEAR_TOUCH }
        val repeated = recentEvents.count { it.type == HunterEventType.REPEATED_WALL_REPOSITIONING }
        val refill = recentEvents.count { it.type == HunterEventType.WALL_REFILL }
        val sweep = recentEvents.count { it.type == HunterEventType.ORDERBOOK_SWEEP }
        val absorption = recentEvents.count { it.type == HunterEventType.WALL_ABSORPTION }
        val extremeImbalance = imbalances.any { it.buyPressure >= 85.0 || it.buyPressure <= 15.0 }
        var score = min(22.0, maxWallSignificance * 0.22)
        score += min(28.0, cancelTouch * 9.0)
        score += min(24.0, retreat * 7.0)
        score += min(18.0, repeated * 10.0)
        score += min(10.0, refill * 3.0)
        score += min(10.0, sweep * 5.0)
        if (liquidityMismatch) score += 14.0
        if (extremeImbalance) score += 7.0
        if (spreadPercent >= 1.0) score += 4.0 else if (spreadPercent >= 0.5) score += 2.0
        score -= min(22.0, absorption * 6.0)
        return score.toInt().coerceIn(0, 100)
    }

    fun tradeVolumeAtWall(trades: List<HunterTrade>, side: HunterWallSide, price: Double, since: Long): Double {
        if (price <= 0.0) return 0.0
        val taker = if (side == HunterWallSide.BUY) "SELL" else "BUY"
        return trades.asSequence()
            .filter { it.timestamp >= since && it.side.uppercase() == taker }
            .filter { abs(it.price - price) / price <= 0.0015 }
            .sumOf { it.qty }
    }

    fun distancePct(price: Double, mid: Double): Double = if (price > 0.0 && mid > 0.0) abs(price - mid) / mid * 100.0 else 999.0

    fun similarQty(a: Double, b: Double): Double {
        val max = maxOf(a, b)
        return if (max <= 0.0) 0.0 else 1.0 - abs(a - b) / max
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 0) (s[m - 1] + s[m]) / 2.0 else s[m]
    }
}
