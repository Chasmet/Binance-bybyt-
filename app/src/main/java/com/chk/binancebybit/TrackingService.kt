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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated on-device foreground service for continuous Wall Tracking.
 * Raw depth never passes through Render. The service no longer holds an unbounded wake lock:
 * a short, timed bootstrap lock lets sockets/engine start reliably, then Android can sleep normally.
 * WebSocket callbacks remain event-driven and the foreground service keeps the tracking process alive.
 */
class TrackingService : Service() {
    @Volatile private var engine: WallTrackerEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val destroyed = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)

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
        acquireBootstrapWakeLock(store.powerMode())
        if (engine == null && starting.compareAndSet(false, true)) {
            Thread {
                try {
                    if (destroyed.get() || !TrackingStore(applicationContext).enabled()) return@Thread
                    val created = WallTrackerEngine(applicationContext)
                    if (destroyed.get()) return@Thread
                    engine = created
                    created.start()
                } finally {
                    starting.set(false)
                }
            }.apply { name = "CHK-Tracking-Start"; isDaemon = true; start() }
        }
        return START_STICKY
    }

    private fun acquireBootstrapWakeLock(mode: TrackingPowerMode) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CHKCrypto:WallTrackingBootstrap").apply {
            setReferenceCounted(false)
            val timeout = when (mode) {
                TrackingPowerMode.ULTRA_ECO -> 20_000L
                TrackingPowerMode.BALANCED -> 45_000L
                TrackingPowerMode.PERFORMANCE -> 90_000L
            }
            acquire(timeout)
        }
    }

    override fun onDestroy() {
        destroyed.set(true)
        engine?.stop()
        engine = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val store = TrackingStore(this)
        val rt = getSharedPreferences("chk_tracking_runtime", MODE_PRIVATE)
        val count = rt.getInt("tracked_asset_count", store.heldAssets().size)
        val bOk = rt.getBoolean("binance_feed_ready", false)
        val yOk = rt.getBoolean("bybit_feed_ready", false)
        val mode = store.powerMode()
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
            .setContentText("$count actif(s) • ${modeLabel(mode)} • Binance ${if (bOk) "✓" else "…"} • Bybit ${if (yOk) "✓" else "…"}")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private fun modeLabel(mode: TrackingPowerMode): String = when (mode) {
        TrackingPowerMode.ULTRA_ECO -> "ULTRA ÉCO"
        TrackingPowerMode.BALANCED -> "ÉQUILIBRÉ"
        TrackingPowerMode.PERFORMANCE -> "PERFORMANCE"
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
                description = "Wall Tracker local Binance + Bybit, optimisé batterie et actif en arrière-plan."
                setShowBadge(false)
            })
        }
    }
}
