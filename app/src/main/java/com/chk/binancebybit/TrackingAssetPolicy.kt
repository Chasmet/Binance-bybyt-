package com.chk.binancebybit

/**
 * Battery-first automatic asset selection for Wall Tracker.
 * BTC and ETH are always kept. Other assets must represent more than 10 USDC
 * across the cached Binance + Bybit portfolios, then the largest positions win.
 */
object TrackingAssetPolicy {
    const val MIN_HOLDING_USDC = 10.0
    val ALWAYS_TRACKED = listOf("BTC", "ETH")

    fun select(portfolioValuesUsdc: Map<String, Double>, maxTrackedAssets: Int): LinkedHashSet<String> {
        val limit = maxTrackedAssets.coerceAtLeast(ALWAYS_TRACKED.size)
        val selected = linkedSetOf<String>()
        ALWAYS_TRACKED.forEach { selected += it }

        portfolioValuesUsdc.entries
            .asSequence()
            .filter { (asset, value) ->
                asset !in ALWAYS_TRACKED &&
                    asset !in STABLES &&
                    value.isFinite() &&
                    value > MIN_HOLDING_USDC
            }
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take((limit - selected.size).coerceAtLeast(0))
            .forEach { selected += it.key }

        return selected
    }

    fun eligibleHoldings(portfolioValuesUsdc: Map<String, Double>): List<Pair<String, Double>> =
        portfolioValuesUsdc.entries
            .asSequence()
            .filter { (asset, value) ->
                asset !in ALWAYS_TRACKED &&
                    asset !in STABLES &&
                    value.isFinite() &&
                    value > MIN_HOLDING_USDC
            }
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
            .toList()

    private val STABLES = setOf("USDT", "USDC", "FDUSD", "TUSD", "DAI", "EUR")
}
