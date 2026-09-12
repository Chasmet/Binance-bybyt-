package com.chk.binancebybit

import org.junit.Assert.*
import org.junit.Test

class SubmissionRetryPolicyTest {
    @Test fun retryRequiresTrackedSubmissionCooldownAndLiveAuthorizationWindow() {
        assertFalse(SubmissionRetryPolicy.allowed(false, 0, null, 200_000, 100_000))
        assertFalse(SubmissionRetryPolicy.allowed(true, 1, 90_000, 200_000, 100_000))
        assertFalse(SubmissionRetryPolicy.allowed(true, 1, 10_000, 80_000, 100_000))
        assertTrue(SubmissionRetryPolicy.allowed(true, 1, 10_000, 200_000, 100_000))
    }
    @Test fun attemptsRemainBoundedAcrossProcessRestarts() {
        assertTrue(SubmissionRetryPolicy.allowed(true, 0, null, 200_000, 100_000))
        assertFalse(SubmissionRetryPolicy.allowed(true, 2, 10_000, 200_000, 100_000))
        assertFalse(SubmissionRetryPolicy.allowed(true, -1, null, 200_000, 100_000))
    }
}
