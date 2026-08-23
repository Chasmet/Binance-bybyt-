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
import java.util.concurrent.atomic.AtomicBoolean

class TradePushService : Service() {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, serviceNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) {
            worker = Thread {
                while (running.get()) {
                    try {
                        TradeProposalReceiver.checkNow(applicationContext)
                    } catch (_: Exception) {
                    }
                    try {
                        Thread.sleep(POLL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }.apply {
                name = "CHK-TradePush"
                isDaemon = true
                start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun serviceNotification(): Notification {
        val openIntent = Intent(this, TradeActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            8822,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("CHK Crypto")
            .setContentText("Surveillance instantanée des ordres active")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Surveillance CHK Crypto",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Maintient la réception rapide des propositions d'ordre"
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "chk_trade_push_service"
        private const val NOTIFICATION_ID = 8821
        private const val POLL_MS = 5_000L

        fun start(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, TradePushService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent)
                else app.startService(intent)
            } catch (_: Exception) {
                // AlarmManager remains the fallback if the OS temporarily blocks foreground start.
                TradeProposalReceiver.schedule(app)
            }
        }
    }
}
