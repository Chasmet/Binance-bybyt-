package com.chk.binancebybit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderLifecycleTest {
    @Test fun bybitStatesStayStrictlySeparated() {
        assertEquals(CanonicalOrderState.OPEN, OrderLifecycle.fromBybit("New", "123"))
        assertEquals(CanonicalOrderState.PARTIAL, OrderLifecycle.fromBybit("PartiallyFilled", "123", 0.5))
        assertEquals(CanonicalOrderState.FILLED, OrderLifecycle.fromBybit("Filled", "123", 1.0))
        assertEquals(CanonicalOrderState.PLACED, OrderLifecycle.fromBybit("Placed", "123"))
    }

    @Test fun onlyFilledCountsForPnlAndTransactions() {
        assertFalse(OrderLifecycle.countsForPnlOrTransactions(CanonicalOrderState.PLACED))
        assertFalse(OrderLifecycle.countsForPnlOrTransactions(CanonicalOrderState.OPEN))
        assertFalse(OrderLifecycle.countsForPnlOrTransactions(CanonicalOrderState.PARTIAL))
        assertTrue(OrderLifecycle.countsForPnlOrTransactions(CanonicalOrderState.FILLED))
    }
}
