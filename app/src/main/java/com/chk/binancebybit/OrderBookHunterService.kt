package com.chk.binancebybit

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap

class OrderBookHunterService : Service() {
    private lateinit var db: OrderBookHunterDb
    private var engine: OrderBookHunterEngine? = null
    private var mcpBridge: OrderBookHunterMcpBridge? = null
    private val alertCooldown = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        db = OrderBookHunterDb(this)
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_RELOAD) {
            ACTION_START_WATCH -> {
                val symbol = intent?.getStringExtra(EXTRA_SYMBOL).orEmpty()
                runCatching { db.watch(symbol, true, alerts = true, restore = true) }
            }
            ACTION_STOP_WATCH -> {
                val symbol = intent?.getStringExtra(EXTRA_SYMBOL).orEmpty()
                runCatching { db.watch(symbol, false) }
            }
            ACTION_ALERTS -> {
                val symbol = intent?.getStringExtra(EXTRA_SYMBOL).orEmpty()
                val enabled = intent?.getBooleanExtra(EXTRA_ENABLED, true) ?: true
                runCatching { db.setAlerts(symbol, enabled) }
            }
            ACTION_CLEAR -> {
                val symbol = intent?.getStringExtra(EXTRA_SYMBOL).orEmpty()
                runCatching { db.clear(symbol, includeNotes = false) }
            }
            ACTION_NOTE -> {
                val symbol = intent?.getStringExtra(EXTRA_SYMBOL).orEmpty()
                val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
                val author = intent?.getStringExtra(EXTRA_AUTHOR).orEmpty().ifBlank { "USER" }
                if (text.isNotBlank()) runCatching { db.note(symbol, text, author) }
            }
        }
        val watches = db.watches()
        if (watches.isEmpty()) {
            engine?.shutdown(); engine = null
            mcpBridge?.stop(); mcpBridge = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, foregroundNotification(watches.size))
        if (engine == null) {
            engine = OrderBookHunterEngine(this, ::onHunterEvent).also { it.start() }
        } else {
            engine?.reload()
        }
        if (mcpBridge == null) {
            mcpBridge = OrderBookHunterMcpBridge(this).also { it.start() }
        }
        updateForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        mcpBridge?.stop(); mcpBridge = null
        engine?.shutdown(); engine = null
        db.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onHunterEvent(event: HunterEvent) {
        updateForeground()
        if (!db.alertsEnabled(event.symbol)) return
        val important = when (event.type) {
            HunterEventType.WALL_CANCELLED_NEAR_TOUCH,
            HunterEventType.REPEATED_WALL_REPOSITIONING,
            HunterEventType.WALL_ABSORPTION,
            HunterEventType.ORDERBOOK_LIQUIDITY_MISMATCH,
            HunterEventType.ORDERBOOK_SWEEP -> true
            HunterEventType.SCORE_CHANGED -> event.score >= 60
            else -> false
        }
        if (!important) return
        val key = "${event.symbol}:${event.type.name}"
        val now = System.currentTimeMillis()
        val cooldown = if (event.type == HunterEventType.SCORE_CHANGED) 60_000L else 90_000L
        if (now - (alertCooldown[key] ?: 0L) < cooldown) return
        alertCooldown[key] = now
        notifyEvent(event)
    }

    private fun updateForeground() {
        val count = runCatching { db.watches().size }.getOrDefault(0)
        if (count <= 0) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, foregroundNotification(count))
    }

    private fun foregroundNotification(count: Int): Notification {
        val open = Intent(this, OrderBookHunterActivity::class.java)
        val pending = PendingIntent.getActivity(this, 5300, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("CHK OrderBook Hunter actif")
            .setContentText("$count marché${if (count > 1) "s" else ""} surveillé${if (count > 1) "s" else ""} • Bybit EU Spot")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pending)
            .build()
    }

    private fun notifyEvent(event: HunterEvent) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = Intent(this, OrderBookHunterActivity::class.java).putExtra(EXTRA_SYMBOL, event.symbol)
        val pending = PendingIntent.getActivity(
            this,
            5400 + (event.symbol.hashCode() and 0x3ff),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = when (event.type) {
            HunterEventType.WALL_ABSORPTION -> "${event.symbol} • support/résistance réellement exécuté"
            HunterEventType.WALL_CANCELLED_NEAR_TOUCH -> "${event.symbol} • mur disparu près du prix"
            HunterEventType.REPEATED_WALL_REPOSITIONING -> "${event.symbol} • repositionnements répétés"
            HunterEventType.ORDERBOOK_LIQUIDITY_MISMATCH -> "${event.symbol} • carnet/volume incohérents"
            HunterEventType.ORDERBOOK_SWEEP -> "${event.symbol} • sweep du carnet"
            HunterEventType.SCORE_CHANGED -> "${event.symbol} • score ${event.score}/100"
            else -> "${event.symbol} • OrderBook Hunter"
        }
        val text = event.detail.take(220)
        val notification = Notification.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify((event.id.hashCode() and 0x7fffffff), notification)
    }

    companion object {
        const val ACTION_RELOAD = "com.chk.binancebybit.hunter.RELOAD"
        const val ACTION_START_WATCH = "com.chk.binancebybit.hunter.START"
        const val ACTION_STOP_WATCH = "com.chk.binancebybit.hunter.STOP"
        const val ACTION_ALERTS = "com.chk.binancebybit.hunter.ALERTS"
        const val ACTION_CLEAR = "com.chk.binancebybit.hunter.CLEAR"
        const val ACTION_NOTE = "com.chk.binancebybit.hunter.NOTE"
        const val EXTRA_SYMBOL = "hunter_symbol"
        const val EXTRA_ENABLED = "hunter_enabled"
        const val EXTRA_TEXT = "hunter_text"
        const val EXTRA_AUTHOR = "hunter_author"
        private const val CHANNEL_SERVICE = "chk_orderbook_hunter_service"
        private const val CHANNEL_ALERTS = "chk_orderbook_hunter_alerts"
        private const val NOTIFICATION_ID = 5299

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_SERVICE,
                "OrderBook Hunter actif",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Notification permanente lorsque le suivi temporel des carnets Bybit est actif." })
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ALERTS,
                "Alertes OrderBook Hunter",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Murs déplacés, annulations proches, absorption, sweep et anomalies significatives." })
        }

        fun ensureRunning(context: Context) {
            val intent = Intent(context, OrderBookHunterService::class.java).setAction(ACTION_RELOAD)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun startWatch(context: Context, symbol: String) = send(context, ACTION_START_WATCH, symbol)
        fun stopWatch(context: Context, symbol: String) = send(context, ACTION_STOP_WATCH, symbol)
        fun clearHistory(context: Context, symbol: String) = send(context, ACTION_CLEAR, symbol)
        fun setAlerts(context: Context, symbol: String, enabled: Boolean) {
            val intent = Intent(context, OrderBookHunterService::class.java)
                .setAction(ACTION_ALERTS)
                .putExtra(EXTRA_SYMBOL, symbol)
                .putExtra(EXTRA_ENABLED, enabled)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
        fun addNote(context: Context, symbol: String, text: String, author: String = "USER") {
            val intent = Intent(context, OrderBookHunterService::class.java)
                .setAction(ACTION_NOTE)
                .putExtra(EXTRA_SYMBOL, symbol)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_AUTHOR, author)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        private fun send(context: Context, action: String, symbol: String) {
            val intent = Intent(context, OrderBookHunterService::class.java).setAction(action).putExtra(EXTRA_SYMBOL, symbol)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
