package com.chk.binancebybit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingAssetPolicyTest {
    @Test
    fun `btc and eth are always tracked and dust is excluded`() {
        val selected = TrackingAssetPolicy.select(
            mapOf(
                "RENDER" to 42.0,
                "ADA" to 10.0,
                "DOGE" to 9.99,
                "USDC" to 500.0
            ),
            maxTrackedAssets = 8
        )

        assertEquals(listOf("BTC", "ETH", "RENDER"), selected.toList())
        assertFalse("ADA at exactly 10 is not over the threshold", "ADA" in selected)
        assertFalse("stablecoins are never auto-tracked", "USDC" in selected)
    }

    @Test
    fun `largest holdings win when balanced cap is reached`() {
        val selected = TrackingAssetPolicy.select(
            mapOf(
                "AAVE" to 11.0,
                "SOL" to 120.0,
                "RENDER" to 80.0,
                "XRP" to 70.0,
                "LINK" to 60.0,
                "AVAX" to 50.0,
                "ADA" to 40.0,
                "DOGE" to 30.0,
                "SUI" to 20.0
            ),
            maxTrackedAssets = 8
        )

        assertEquals(8, selected.size)
        assertEquals(listOf("BTC", "ETH", "SOL", "RENDER", "XRP", "LINK", "AVAX", "ADA"), selected.toList())
        assertFalse("smaller qualifying holdings are dropped once the cap is reached", "DOGE" in selected)
    }

    @Test
    fun `eligibility is sorted by combined portfolio value`() {
        val eligible = TrackingAssetPolicy.eligibleHoldings(
            mapOf("RENDER" to 25.0, "SOL" to 100.0, "ADA" to 10.01, "BTC" to 1.0)
        )
        assertEquals(listOf("SOL", "RENDER", "ADA"), eligible.map { it.first })
        assertTrue(eligible.all { it.second > TrackingAssetPolicy.MIN_HOLDING_USDC })
    }
}
