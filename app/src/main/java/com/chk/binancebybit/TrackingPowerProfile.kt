package com.chk.binancebybit

import java.util.Locale

enum class TrackingPowerMode {
    ULTRA_ECO,
    BALANCED,
    PERFORMANCE;

    companion object {
        fun parse(raw: String?): TrackingPowerMode = when (raw?.trim()?.uppercase(Locale.US)) {
            "ULTRA_ECO", "ULTRA ECO", "ECO" -> ULTRA_ECO
            "PERFORMANCE", "PERF" -> PERFORMANCE
            else -> BALANCED
        }
    }
}

data class TrackingPowerProfile(
    val mode: TrackingPowerMode,
    val bookDispatchMs: Long,
    val quoteDispatchMs: Long,
    val tradeBucketMs: Long,
    val activePersistOnMs: Long,
    val activePersistOffMs: Long,
    val tradePersistOnMs: Long,
    val tradePersistOffMs: Long,
    val remoteDirtyPushMs: Long,
    val remoteHeartbeatMs: Long,
    val remotePullMs: Long,
    val assetRefreshMs: Long,
    val orderRefreshMs: Long,
    val maintenanceSleepMs: Long,
    val freshnessMs: Long,
    val wakeLockTimeoutMs: Long,
    val maxTrackedAssets: Int
) {
    companion object {
        fun of(mode: TrackingPowerMode): TrackingPowerProfile = when (mode) {
            TrackingPowerMode.ULTRA_ECO -> TrackingPowerProfile(
                mode = mode,
                bookDispatchMs = 1_250L,
                quoteDispatchMs = 2_000L,
                tradeBucketMs = 1_500L,
                activePersistOnMs = 45_000L,
                activePersistOffMs = 120_000L,
                tradePersistOnMs = 15_000L,
                tradePersistOffMs = 45_000L,
                remoteDirtyPushMs = 60_000L,
                remoteHeartbeatMs = 180_000L,
                remotePullMs = 45_000L,
                assetRefreshMs = 180_000L,
                orderRefreshMs = 600_000L,
                maintenanceSleepMs = 5_000L,
                freshnessMs = 12_000L,
                wakeLockTimeoutMs = 10 * 60_000L,
                maxTrackedAssets = 5
            )
            TrackingPowerMode.BALANCED -> TrackingPowerProfile(
                mode = mode,
                bookDispatchMs = 350L,
                quoteDispatchMs = 800L,
                tradeBucketMs = 650L,
                activePersistOnMs = 15_000L,
                activePersistOffMs = 45_000L,
                tradePersistOnMs = 6_000L,
                tradePersistOffMs = 18_000L,
                remoteDirtyPushMs = 20_000L,
                remoteHeartbeatMs = 90_000L,
                remotePullMs = 20_000L,
                assetRefreshMs = 60_000L,
                orderRefreshMs = 180_000L,
                maintenanceSleepMs = 3_000L,
                freshnessMs = 6_000L,
                wakeLockTimeoutMs = 10 * 60_000L,
                maxTrackedAssets = 8
            )
            TrackingPowerMode.PERFORMANCE -> TrackingPowerProfile(
                mode = mode,
                bookDispatchMs = 120L,
                quoteDispatchMs = 250L,
                tradeBucketMs = 250L,
                activePersistOnMs = 5_000L,
                activePersistOffMs = 15_000L,
                tradePersistOnMs = 2_500L,
                tradePersistOffMs = 7_500L,
                remoteDirtyPushMs = 8_000L,
                remoteHeartbeatMs = 45_000L,
                remotePullMs = 10_000L,
                assetRefreshMs = 30_000L,
                orderRefreshMs = 60_000L,
                maintenanceSleepMs = 1_500L,
                freshnessMs = 3_500L,
                wakeLockTimeoutMs = 10 * 60_000L,
                maxTrackedAssets = 12
            )
        }
    }
}
