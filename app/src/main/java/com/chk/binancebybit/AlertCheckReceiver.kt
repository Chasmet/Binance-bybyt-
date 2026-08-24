package com.chk.binancebybit

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import java.util.Locale
import kotlin.math.abs

class AlertCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        Thread {
            try { checkAlerts(context.applicationContext) } catch (_: Exception) { } finally { pending.finish() }
        }.start()
    }

    companion object {
        private const val CHANNEL_ID = "chk_crypto_price_alerts"
        private const val INTERVAL_MS = 15L * 60L * 1000L

        fun schedule(context: Context) {
            createChannel(context)
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, AlertCheckReceiver::class.java).setAction("com.chk.binancebybit.CHECK_LOCAL_ALERTS")
            val pi = PendingIntent.getBroadcast(context, 4101, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 60_000L,
                INTERVAL_MS,
                pi
            )
        }

        fun checkNow(context: Context) {
            context.sendBroadcast(Intent(context, AlertCheckReceiver::class.java).setAction("com.chk.binancebybit.CHECK_LOCAL_ALERTS_NOW"))
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID,
                    "Alertes prix CHK Crypto",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alertes locales calculées sur le téléphone depuis les prix publics Bybit."
                    enableVibration(true)
                })
            }
        }

        private fun checkAlerts(context: Context) {
            val store = LocalAlertStore(context)
            val client = BybitPublicMarketClient()
            val alerts = store.list().filter { it.enabled }
            context.getSharedPreferences("chk_workspace", Context.MODE_PRIVATE).edit().putInt("alert_count", alerts.size).apply()
            alerts.groupBy { it.symbol }.forEach { (symbol, rows) ->
                val ticker = runCatching { client.ticker(symbol) }.getOrNull() ?: return@forEach
                rows.forEach { alert ->
                    val hit = if (alert.condition == "above") ticker.lastPrice >= alert.targetPrice else ticker.lastPrice <= alert.targetPrice
                    if (hit) {
                        notifyAlert(context, alert, ticker.lastPrice)
                        store.markTriggered(alert.id, disableAfterTrigger = true)
                    }
                }
            }
        }

        private fun notifyAlert(context: Context, alert: LocalMarketAlert, price: Double) {
            createChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val open = Intent(context, MainActivityV4::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_analysis", true)
            }
            val content = PendingIntent.getActivity(context, abs(alert.id.hashCode()), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val title = alert.label.ifBlank { "${alert.symbol} • prix cible atteint" }
            val relation = if (alert.condition == "above") "≥" else "≤"
            val msg = "${alert.symbol} = ${fmt(price)} USDC • seuil $relation ${fmt(alert.targetPrice)}"
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(context)
            val notification = builder.setSmallIcon(R.drawable.app_icon)
                .setContentTitle(title)
                .setContentText(msg)
                .setStyle(Notification.BigTextStyle().bigText("$msg\nAlerte calculée localement sur ce téléphone. Ouvre CHK Crypto pour revoir le marché."))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
            nm.notify(abs(alert.id.hashCode()), notification)
        }

        private fun fmt(v: Double): String = when {
            v >= 1000 -> String.format(Locale.US, "%.2f", v)
            v >= 100 -> String.format(Locale.US, "%.3f", v)
            v >= 1 -> String.format(Locale.US, "%.5f", v).trimEnd('0').trimEnd('.')
            else -> String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
        }
    }
}
