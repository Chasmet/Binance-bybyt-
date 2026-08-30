package com.chk.binancebybit

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class AutoTradeExecutor(context: Context) {
    private val app = context.applicationContext
    private val secureStore = SecureStore(app)
    private val proposalClient = TradeProposalClient(app, secureStore)
    private val policy = AutoTradePolicyStore(app)
    private val journal = BotRuleStore(app)
    private val running = AtomicBoolean(false)

    fun processEligiblePending(): Summary {
        if (!policy.enabled()) return Summary(0, 0, 0)
        if (!running.compareAndSet(false, true)) return Summary(0, 0, 0)
        var checked = 0
        var executed = 0
        var failed = 0
        try {
            val pending = proposalClient.list().pending
            for (proposal in pending) {
                val decision = policy.canExecute(proposal)
                if (!decision.allowed) continue
                checked++
                try {
                    executeOne(proposal)
                    executed++
                } catch (e: Exception) {
                    failed++
                    journal.addLog(
                        level = "ERROR",
                        category = "AUTO_TRADE",
                        title = "Auto-Trade non exécuté",
                        detail = "${proposal.side} ${proposal.symbol} • ${e.message ?: e.javaClass.simpleName}",
                        symbol = proposal.symbol
                    )
                }
            }
        } finally {
            running.set(false)
        }
        return Summary(checked, executed, failed)
    }

    fun executeOne(original: TradeProposal): TradeExecutionResult {
        val decision = policy.canExecute(original)
        if (!decision.allowed) throw IllegalStateException(decision.reason)

        val key = secureStore.get("bybit_api_key")
        val secret = secureStore.get("bybit_api_secret")
        if (key.isBlank() || secret.isBlank()) throw IllegalStateException("Clés Bybit absentes")

        val claimed = proposalClient.claim(original.id)
        return try {
            val result = BybitTradeClient(key, secret).execute(claimed)
            runCatching { proposalClient.markResult(claimed.id, "executed", result.orderId, result.toJson()) }
            policy.recordExecuted(claimed)
            journal.addLog(
                level = "AUTO",
                category = "AUTO_TRADE",
                title = "Ordre exécuté automatiquement",
                detail = buildString {
                    append("${claimed.side} ${claimed.symbol} • ${claimed.quoteAmountUsdc} USDC")
                    claimed.limitPrice?.let { append(" • LIMIT $it") }
                    append(" • Bybit ${result.orderStatus}")
                    if (result.orderId.isNotBlank()) append(" • ${result.orderId}")
                },
                symbol = claimed.symbol
            )
            result
        } catch (uncertain: BybitExecutionUncertainException) {
            journal.addLog(
                level = "ERROR",
                category = "AUTO_TRADE",
                title = "Auto-Trade à vérifier",
                detail = "${claimed.side} ${claimed.symbol} • ${uncertain.message}",
                symbol = claimed.symbol
            )
            throw uncertain
        } catch (error: Exception) {
            runCatching {
                proposalClient.markResult(
                    claimed.id,
                    "error",
                    null,
                    JSONObject().put("error", error.message ?: error.toString()).put("autoTrade", true)
                )
            }
            throw error
        }
    }

    data class Summary(val checked: Int, val executed: Int, val failed: Int)
}
