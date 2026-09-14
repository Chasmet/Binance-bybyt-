package com.chk.binancebybit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Dedicated on-device foreground service for continuous Wall Tracking.
 * It never proxies exchange depth through Render. A partial wake lock is held only while the user
 * has Tracking enabled so the two public WebSockets can continue with the screen off.
 */
class TrackingService : Service() {
    private var engine: WallTrackerEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = TrackingStore(this)
        if (!store.enabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CHKCrypto:WallTracking").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (engine == null) engine = WallTrackerEngine(this).also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val rt = getSharedPreferences("chk_tracking_runtime", MODE_PRIVATE)
        val count = rt.getInt("tracked_asset_count", TrackingStore(this).heldAssets().size)
        val bOk = rt.getBoolean("binance_connected", false)
        val yOk = rt.getBoolean("bybit_connected", false)
        val open = PendingIntent.getActivity(
            this,
            9821,
            Intent(this, TrackingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("CHK Crypto • Tracking actif")
            .setContentText("$count actif(s) • Binance ${if (bOk) "✓" else "…"} • Bybit ${if (yOk) "✓" else "…"}")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL = "chk_wall_tracking_service"
        private const val NOTIFICATION_ID = 9821

        fun start(context: Context) {
            val app = context.applicationContext
            if (!TrackingStore(app).enabled()) return
            val i = Intent(app, TrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i) else app.startService(i)
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context.applicationContext, TrackingService::class.java))
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL,
                "Tracking CHK Crypto",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Wall Tracker local Binance + Bybit actif en arrière-plan et écran éteint."
                setShowBadge(false)
            })
        }
    }
}
