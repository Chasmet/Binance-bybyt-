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

class AutoCancelExecutor(context: Context) {
    private val app = context.applicationContext
    private val secureStore = SecureStore(app)
    private val cancelClient = CancelProposalClient(app, secureStore)
    private val policy = AutoTradePolicyStore(app)
    private val journal = BotRuleStore(app)
    private val running = AtomicBoolean(false)

    fun processEligiblePending(): Summary {
        if (!policy.enabled() || !policy.allowCancelReplace()) return Summary(0, 0, 0)
        if (!running.compareAndSet(false, true)) return Summary(0, 0, 0)
        var checked = 0
        var executed = 0
        var failed = 0
        try {
            val pending = cancelClient.list().pending
            for (proposal in pending) {
                val decision = policy.canAutoCancel(proposal)
                if (!decision.allowed) continue
                checked++
                try {
                    executeOne(proposal)
                    executed++
                } catch (e: Exception) {
                    failed++
                    journal.addLog(
                        level = "ERROR",
                        category = "AUTO_CANCEL",
                        title = "Annulation auto non exécutée",
                        detail = "${proposal.symbol} • ${proposal.targetOrderId} • ${e.message ?: e.javaClass.simpleName}",
                        symbol = proposal.symbol
                    )
                }
            }
        } finally {
            running.set(false)
        }
        return Summary(checked, executed, failed)
    }

    fun executeOne(original: CancelProposal): CancelExecutionResult {
        val decision = policy.canAutoCancel(original)
        if (!decision.allowed) throw IllegalStateException(decision.reason)

        val key = secureStore.get("bybit_api_key")
        val secret = secureStore.get("bybit_api_secret")
        if (key.isBlank() || secret.isBlank()) throw IllegalStateException("Clés Bybit absentes")

        val claimed = cancelClient.claim(original.id)
        return try {
            val result = BybitCancelClient(key, secret).cancel(claimed)
            val markResponse = cancelClient.markResult(claimed.id, "executed", result.toJson())
            val replacement = runCatching {
                JSONObject(markResponse).optJSONObject("replacementProposal")
            }.getOrNull()

            journal.addLog(
                level = "AUTO",
                category = "AUTO_CANCEL",
                title = if (replacement != null) "Ordre annulé • remplacement préparé" else "Ordre annulé automatiquement",
                detail = buildString {
                    append("${claimed.symbol} • Order ID ${result.orderId} • Bybit ${result.orderStatus}")
                    replacement?.let {
                        append(" • remplacement ${it.optString("side")} ${it.optString("order_type")}")
                        val amount = it.optDouble("quote_amount_usdc", 0.0)
                        if (amount > 0.0) append(" ${amount} USDC")
                        val price = it.optDouble("limit_price", 0.0)
                        if (price > 0.0) append(" @ $price")
                    }
                },
                symbol = claimed.symbol
            )

            notify(
                urgent = false,
                title = "Auto-Trade • ordre annulé",
                body = if (replacement != null) {
                    "${claimed.symbol} • annulation confirmée • remplacement transmis à Auto-Trade"
                } else {
                    "${claimed.symbol} • annulation confirmée par Bybit"
                }
            )
            result
        } catch (error: Exception) {
            runCatching {
                cancelClient.markResult(
                    claimed.id,
                    "error",
                    JSONObject().put("error", error.message ?: error.toString()).put("autoCancel", true)
                )
            }
            journal.addLog(
                level = "ERROR",
                category = "AUTO_CANCEL",
                title = "Annulation automatique à vérifier",
                detail = "${claimed.symbol} • ${claimed.targetOrderId} • ${error.message ?: error.javaClass.simpleName}",
                symbol = claimed.symbol
            )
            notify(
                urgent = true,
                title = "Auto-Trade • annulation à vérifier",
                body = "${claimed.symbol} • ${error.message ?: "état Bybit incertain"} • aucun nouvel essai automatique"
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
                    description = "Exécutions, annulations et incidents Auto-Trade CHK"
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
