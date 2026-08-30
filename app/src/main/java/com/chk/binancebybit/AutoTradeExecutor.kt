package com.chk.binancebybit

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

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
            notify(
                urgent = false,
                title = "Auto-Trade • ${claimed.side} ${claimed.symbol}",
                body = "${claimed.quoteAmountUsdc} USDC • LIMIT ${claimed.limitPrice ?: "-"} • Bybit ${result.orderStatus}"
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
            notify(
                urgent = true,
                title = "Auto-Trade • ordre à vérifier",
                body = "${claimed.side} ${claimed.symbol} • état Bybit incertain. Aucun renvoi automatique."
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
            notify(
                urgent = true,
                title = "Auto-Trade • ordre refusé",
                body = "${claimed.side} ${claimed.symbol} • ${error.message ?: "erreur inconnue"}"
            )
            throw error
        }
    }

    private fun notify(urgent: Boolean, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Auto-Trade CHK",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Exécutions automatiques et incidents Auto-Trade CHK"
                    enableVibration(true)
                }
            )
        }
        val intent = Intent(app, AutoTradeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            app,
            abs((title + body).hashCode()),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(app)
        manager.notify(
            abs((title + body + System.currentTimeMillis()).hashCode()),
            builder
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(if (urgent) Notification.PRIORITY_MAX else Notification.PRIORITY_HIGH)
                .build()
        )
    }

    data class Summary(val checked: Int, val executed: Int, val failed: Int)

    companion object {
        private const val CHANNEL_ID = "chk_auto_trade"
    }
}
