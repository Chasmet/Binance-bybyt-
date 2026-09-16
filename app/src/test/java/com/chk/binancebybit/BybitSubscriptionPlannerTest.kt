package com.chk.binancebybit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BybitSubscriptionPlannerTest {
    @Test
    fun eightSymbolsAreSplitIntoRequestsOfAtMostTenArgs() {
        val symbols = setOf("BTCUSDT", "ETHUSDT", "RENDERUSDT", "ADAUSDT", "XRPUSDT", "SOLUSDT", "AVAXUSDT", "LINKUSDT")
        val batches = BybitSubscriptionPlanner.batches(symbols)

        assertEquals(24, batches.sumOf { it.size })
        assertEquals(3, batches.size)
        assertTrue(batches.all { it.size <= BybitSubscriptionPlanner.MAX_ARGS_PER_REQUEST })
        assertTrue(batches.flatten().contains("orderbook.50.BTCUSDT"))
        assertTrue(batches.flatten().contains("publicTrade.RENDERUSDT"))
        assertTrue(batches.flatten().contains("tickers.LINKUSDT"))
    }

    @Test
    fun duplicateAndInvalidSymbolsDoNotCreateExtraTopics() {
        val batches = BybitSubscriptionPlanner.batches(setOf("btcusdt", "BTCUSDT", "bad symbol"))
        assertEquals(3, batches.flatten().size)
        assertTrue(batches.flatten().all { it.endsWith("BTCUSDT") })
    }
}
