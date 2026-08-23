package com.chk.binancebybit

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

class TradeProposalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        Thread {
            try {
                val app = context.applicationContext
                val store = SecureStore(app)
                val proposals = TradeProposalClient(app, store).list().pending
                if (proposals.isNotEmpty()) notifyNew(app, proposals)
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun notifyNew(context: Context, proposals: List<TradeProposal>) {
        val prefs = context.getSharedPreferences("chk_trade_notifications", Context.MODE_PRIVATE)
        val notified = prefs.getStringSet("notified_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        val fresh = proposals.filter { !notified.contains(it.id) }
        if (fresh.isEmpty()) return

        createChannel(context)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(context, TradeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            8813,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val first = fresh.first()
        val action = if (first.side == "BUY") "ACHAT" else "VENTE"
        val extra = if (fresh.size > 1) " • +${fresh.size - 1} autre(s)" else ""
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("CHK Crypto • Ordre à confirmer")
            .setContentText("$action ${first.symbol} • ${first.orderType} • ${format(first.quoteAmountUsdc)} USDC$extra")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
        notified.addAll(fresh.map { it.id })
        prefs.edit().putStringSet("notified_ids", notified.toList().takeLast(100).toSet()).apply()
    }

    companion object {
        private const val CHANNEL_ID = "chk_trade_proposals"
        private const val NOTIFICATION_ID = 8814
        private const val REQUEST_CODE = 8812
        private const val INTERVAL_MS = 60L * 1000L

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Ordres CHK Crypto à confirmer",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Propositions d'achat/vente préparées et en attente de confirmation"
                    }
                )
            }
        }

        fun schedule(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, TradeProposalReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val first = System.currentTimeMillis() + 5_000L
            alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP, first, INTERVAL_MS, pending)
            checkNow(context)
        }

        fun checkNow(context: Context) {
            context.applicationContext.sendBroadcast(Intent(context.applicationContext, TradeProposalReceiver::class.java))
        }

        private fun format(v: Double): String = String.format(java.util.Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
    }
}
