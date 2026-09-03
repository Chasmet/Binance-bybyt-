package com.chk.binancebybit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderBookHunterMathTest {
    @Test
    fun dynamicWallDependsOnRelativeMarketDepthNotFixedTokenCount() {
        val snapshot = HunterBookSnapshot(
            symbol = "SKRUSDC",
            bids = listOf(
                HunterBookLevel(0.01919, 714_000.0),
                HunterBookLevel(0.01918, 30_000.0),
                HunterBookLevel(0.01917, 25_000.0),
                HunterBookLevel(0.01916, 32_000.0),
                HunterBookLevel(0.01915, 28_000.0)
            ),
            asks = listOf(
                HunterBookLevel(0.01941, 26_000.0),
                HunterBookLevel(0.01942, 24_000.0),
                HunterBookLevel(0.01943, 31_000.0),
                HunterBookLevel(0.01944, 27_000.0)
            ),
            updateId = 1,
            sequence = 1,
            timestamp = 1,
            matchingTimestamp = 1,
            synchronized = true
        )
        val ticker = HunterTicker("SKRUSDC", 0.01940, -19.7, 20_000.0, 1_000_000.0, 1)
        val (bids, _) = OrderBookHunterMath.candidateWalls(snapshot, ticker, 1)
        assertTrue(bids.any { it.price == 0.01919 && it.qty == 714_000.0 })
        assertTrue(bids.first { it.price == 0.01919 }.wallVsTurnover > 0.5)
    }

    @Test
    fun skrRepeatedRetreatScenarioScoresAboveSeventy() {
        val now = System.currentTimeMillis()
        val events = listOf(
            HunterEvent("1", "SKRUSDC", HunterEventType.WALL_RETREAT, HunterWallSide.BUY, detail = "0.01919 -> 0.01912", createdAt = now),
            HunterEvent("2", "SKRUSDC", HunterEventType.WALL_RETREAT, HunterWallSide.BUY, detail = "0.01912 -> 0.01907", createdAt = now),
            HunterEvent("3", "SKRUSDC", HunterEventType.WALL_RETREAT, HunterWallSide.BUY, detail = "retreat", createdAt = now),
            HunterEvent("4", "SKRUSDC", HunterEventType.WALL_CANCELLED_NEAR_TOUCH, HunterWallSide.BUY, detail = "near touch", createdAt = now),
            HunterEvent("5", "SKRUSDC", HunterEventType.WALL_CANCELLED_NEAR_TOUCH, HunterWallSide.BUY, detail = "near touch", createdAt = now),
            HunterEvent("6", "SKRUSDC", HunterEventType.REPEATED_WALL_REPOSITIONING, HunterWallSide.BUY, detail = "repeated", createdAt = now)
        )
        val score = OrderBookHunterMath.anomalyScore(
            maxWallSignificance = 90.0,
            recentEvents = events,
            imbalances = listOf(HunterImbalance(1.0, 85_000.0, 15_000.0)),
            spreadPercent = 0.36,
            liquidityMismatch = true
        )
        assertTrue("SKR scenario must be suspicious, got $score", score > 70)
        assertTrue(HunterClassification.label(score) == "COMPORTEMENT FORTEMENT SUSPECT" || HunterClassification.label(score) == "ANOMALIE EXTRÊME")
    }

    @Test
    fun realAbsorptionReducesSpoofingSuspicion() {
        val suspicious = listOf(
            HunterEvent("1", "XUSDC", HunterEventType.WALL_RETREAT, detail = "x"),
            HunterEvent("2", "XUSDC", HunterEventType.WALL_CANCELLED_NEAR_TOUCH, detail = "x"),
            HunterEvent("3", "XUSDC", HunterEventType.REPEATED_WALL_REPOSITIONING, detail = "x")
        )
        val withoutAbsorption = OrderBookHunterMath.anomalyScore(90.0, suspicious, emptyList(), 0.2, true)
        val withAbsorption = OrderBookHunterMath.anomalyScore(
            90.0,
            suspicious + listOf(
                HunterEvent("4", "XUSDC", HunterEventType.WALL_ABSORPTION, detail = "executed"),
                HunterEvent("5", "XUSDC", HunterEventType.WALL_ABSORPTION, detail = "executed")
            ),
            emptyList(),
            0.2,
            true
        )
        assertTrue(withAbsorption < withoutAbsorption)
    }

    @Test
    fun imbalanceIsCalculatedFromVisibleDepthOnly() {
        val snapshot = HunterBookSnapshot(
            "AUSDC",
            bids = listOf(HunterBookLevel(0.999, 800.0)),
            asks = listOf(HunterBookLevel(1.001, 200.0)),
            updateId = 1,
            sequence = 1,
            timestamp = 1,
            matchingTimestamp = 1,
            synchronized = true
        )
        val imbalance = OrderBookHunterMath.imbalances(snapshot).first { it.distancePercent == 0.25 }
        assertEquals(80.0, imbalance.buyPressure, 0.01)
        assertEquals(20.0, imbalance.sellPressure, 0.01)
    }
}
