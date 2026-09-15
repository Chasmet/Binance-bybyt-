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
import kotlin.math.abs
import kotlin.math.max

class TrackingWallNotifier(context: Context) {
    private val app = context.applicationContext
    private val lastSent = HashMap<String, Long>()

    fun maybeNotify(wall: TrackingWall, event: String, detail: String, minNotional: Double, minStrength: Double) {
        if (event !in IMPORTANT_EVENTS) return
        val notional = max(wall.initialPrice * wall.initialQty, wall.currentPrice * wall.currentQty)
        val important = wall.strength >= max(6.0, minStrength * 1.35) || notional >= max(10_000.0, minNotional * 2.5)
        if (!important) return
        val now = System.currentTimeMillis()
        val key = "${wall.fingerprintId.ifBlank { wall.id }}:$event"
        synchronized(lastSent) {
            val previous = lastSent[key] ?: 0L
            if (now - previous < 45_000L) return
            lastSent[key] = now
        }
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        createChannel(manager)
        val open = PendingIntent.getActivity(
            app,
            abs(key.hashCode()),
            Intent(app, TrackingActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = "CHK Wall • ${wall.asset} ${wall.side} • ${label(event)}"
        val body = buildString {
            append(wall.exchange).append(" • ").append(format(wall.currentPrice)).append(" • ")
            append(String.format(java.util.Locale.FRANCE, "%.1f", wall.strength)).append("×")
            if (detail.isNotBlank()) append(" • ").append(detail.take(140))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(app)
        manager.notify(abs((key + now / 60_000L).hashCode()), builder
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .build())
    }

    private fun createChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL,
            "Alertes Wall Tracker",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Déplacements, disparitions, absorptions et replenishments importants détectés par CHK Crypto."
            setShowBadge(false)
        })
    }

    private fun label(event: String): String = when (event) {
        "MOVED" -> "mur déplacé"
        "REPLENISHED" -> "replenishment"
        "ABSORBED" -> "mur absorbé"
        "CANCELLED", "DISAPPEARED" -> "mur disparu"
        "REAPPEARED" -> "fingerprint retrouvé"
        "SPOOFING_PROBABLE" -> "spoofing probable"
        else -> event
    }

    private fun format(value: Double): String = when {
        value >= 1_000.0 -> String.format(java.util.Locale.FRANCE, "%.1f", value)
        value >= 1.0 -> String.format(java.util.Locale.FRANCE, "%.4f", value)
        else -> String.format(java.util.Locale.FRANCE, "%.8f", value)
    }

    companion object {
        private const val CHANNEL = "chk_wall_events"
        private val IMPORTANT_EVENTS = setOf("MOVED", "REPLENISHED", "ABSORBED", "CANCELLED", "DISAPPEARED", "REAPPEARED", "SPOOFING_PROBABLE")
    }
}
