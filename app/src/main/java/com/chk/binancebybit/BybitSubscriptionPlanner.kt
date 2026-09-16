package com.chk.binancebybit

import java.util.Locale

/**
 * Builds Bybit Spot public subscription requests without exceeding Bybit's hard limit of
 * 10 args per subscribe message. One symbol uses three topics: orderbook, public trades and ticker.
 */
object BybitSubscriptionPlanner {
    const val MAX_ARGS_PER_REQUEST = 10

    fun topics(symbols: Set<String>): List<String> = symbols.asSequence()
        .map { it.trim().uppercase(Locale.US) }
        .filter { it.matches(Regex("^[A-Z0-9]{4,28}$")) }
        .distinct()
        .sorted()
        .flatMap { symbol ->
            sequenceOf(
                "orderbook.50.$symbol",
                "publicTrade.$symbol",
                "tickers.$symbol"
            )
        }
        .toList()

    fun batches(symbols: Set<String>): List<List<String>> = topics(symbols)
        .chunked(MAX_ARGS_PER_REQUEST)
}
