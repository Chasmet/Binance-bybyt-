package com.chk.binancebybit

/** Drains arrivals in order, once per pass; failures do not abandon the rest of a batch. */
object PendingOrderQueue {
    fun <T> drain(first: List<T>, fetch: () -> List<T>, enabled: () -> Boolean,
                  id: (T) -> String, process: (T) -> Unit, failed: (T, Exception) -> Unit) {
        val attempted = mutableSetOf<String>()
        var pending = first
        for (round in 0 until 20) {
            val fresh = pending.filter { attempted.add(id(it)) }
            if (fresh.isEmpty() || !enabled()) return
            for (item in fresh) {
                if (!enabled()) return
                try { process(item) } catch (error: Exception) { failed(item, error) }
            }
            pending = fetch()
        }
    }
}
