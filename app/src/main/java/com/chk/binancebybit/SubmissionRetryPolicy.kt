package com.chk.binancebybit

object SubmissionRetryPolicy {
    fun allowed(tracked: Boolean, attempts: Int, lastAttempt: Long?, expiresAt: Long?, now: Long): Boolean =
        tracked && attempts in 0..1 && expiresAt != null && expiresAt > now &&
            (lastAttempt == null || now - lastAttempt >= 60_000L)
}
