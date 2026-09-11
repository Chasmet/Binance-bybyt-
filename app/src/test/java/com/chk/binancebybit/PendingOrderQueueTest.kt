package com.chk.binancebybit

import org.junit.Assert.*
import org.junit.Test

class PendingOrderQueueTest {
    @Test fun processesFiveIncludingArrivalsDuringExecution() {
        val seen = mutableListOf<Int>()
        PendingOrderQueue.drain(listOf(1), { (1..5).toList() }, { true }, { it.toString() },
            { seen += it }, { _, error -> throw error })
        assertEquals((1..5).toList(), seen)
    }

    @Test fun oneFailureDoesNotAbandonTheRemainingOrders() {
        val seen = mutableListOf<Int>()
        val errors = mutableListOf<Int>()
        PendingOrderQueue.drain((1..5).toList(), { emptyList() }, { true }, { it.toString() },
            { if (it == 2) throw IllegalStateException("rejected") else seen += it }, { id, _ -> errors += id })
        assertEquals(listOf(1,3,4,5), seen)
        assertEquals(listOf(2), errors)
    }

    @Test fun repeatedPendingRowsAreNotResubmittedInOnePass() {
        var calls = 0
        PendingOrderQueue.drain(listOf(1,1), { listOf(1) }, { true }, { it.toString() },
            { calls++ }, { _, error -> throw error })
        assertEquals(1, calls)
    }

    @Test fun disablingStopsBeforeTheNextOrder() {
        var active = true
        val seen = mutableListOf<Int>()
        PendingOrderQueue.drain((1..5).toList(), { emptyList() }, { active }, { it.toString() },
            { seen += it; active = false }, { _, error -> throw error })
        assertEquals(listOf(1), seen)
    }
}
