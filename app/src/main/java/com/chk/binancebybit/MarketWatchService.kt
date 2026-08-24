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
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class MarketWatchService : Service() {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private val publicClient = BybitPublicMarketClient()
    private val samples = ConcurrentHashMap<String, ArrayDeque<Pair<Long, Double>>>()
    private val smartCooldown = ConcurrentHashMap<String, Long>()
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastRemoteSyncAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = LocalAlertStore(this)
        store.setMonitoringEnabled(true)
        startForeground(FOREGROUND_ID, buildForegroundNotification(store.activeCount()))
        if (running.compareAndSet(false, true)) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CHKCrypto:MarketWatch")?.apply {
                setReferenceCounted(false)
                acquire(10 * 60_000L)
            }
            worker = Thread { runLoop() }.apply {
                name = "CHK-MarketWatch"
                isDaemon = true
                start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        worker?.interrupt()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runLoop() {
        while (running.get()) {
            try {
                checkMarketOnce()
                Thread.sleep(20_000L)
            } catch (_: InterruptedException) {
                break
            } catch (_: Throwable) {
                try { Thread.sleep(30_000L) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun checkMarketOnce() {
        val now = System.currentTimeMillis()
        if (now - lastRemoteSyncAt >= 60_000L) {
            runCatching { RemoteAlertClient(this).syncIntoLocal() }
            lastRemoteSyncAt = now
        }

        val store = LocalAlertStore(this)
        val alerts = store.list().filter { it.enabled }
        val symbols = linkedSetOf<String>()
        alerts.forEach { symbols += it.symbol }
        if (store.smartWatchEnabled()) {
            symbols += "BTCUSDC"
            symbols += "ETHUSDC"
            symbols += "RENDERUSDC"
        }

        alerts.groupBy { it.symbol }.forEach { (symbol, rows) ->
            runCatching {
                val ticker = publicClient.ticker(symbol)
                rows.forEach { alert ->
                    val hit = when (alert.condition) {
                        "above" -> ticker.lastPrice >= alert.targetPrice
                        else -> ticker.lastPrice <= alert.targetPrice
                    }
                    if (hit) {
                        notifyTarget(alert, ticker.lastPrice)
                        store.markTriggered(alert.id, disableAfterTrigger = true, lastPrice = ticker.lastPrice)
                    }
                }
            }
        }

        if (store.smartWatchEnabled()) {
            val threshold = store.smartMoveThresholdPct()
            symbols.forEach { symbol ->
                runCatching {
                    val ticker = publicClient.ticker(symbol)
                    recordSample(symbol, ticker.lastPrice)
                    val move = fiveMinuteMovePct(symbol)
                    if (abs(move) >= threshold && canNotifySmart(symbol)) {
                        notifySmartMove(symbol, ticker.lastPrice, move)
                        smartCooldown[symbol] = System.currentTimeMillis()
                    }
                }
            }
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(FOREGROUND_ID, buildForegroundNotification(store.activeCount()))
    }

    private fun recordSample(symbol: String, price: Double) {
        if (price <= 0.0) return
        val now = System.currentTimeMillis()
        val q = samples.getOrPut(symbol) { ArrayDeque() }
        synchronized(q) {
            q.addLast(now to price)
            while (q.isNotEmpty() && now - q.first().first > 7 * 60_000L) q.removeFirst()
        }
    }

    private fun fiveMinuteMovePct(symbol: String): Double {
        val q = samples[symbol] ?: return 0.0
        synchronized(q) {
            if (q.size < 2) return 0.0
            val now = System.currentTimeMillis()
            val current = q.last().second
            val base = q.firstOrNull { now - it.first >= 4 * 60_000L }?.second ?: return 0.0
            if (base <= 0.0) return 0.0
            return (current / base - 1.0) * 100.0
        }
    }

    private fun canNotifySmart(symbol: String): Boolean {
        val last = smartCooldown[symbol] ?: 0L
        return System.currentTimeMillis() - last >= 15 * 60_000L
    }

    private fun notifyTarget(alert: LocalMarketAlert, price: Double) {
        val relation = if (alert.condition == "above") "≥" else "≤"
        val title = if (alert.label.isBlank()) "${alert.symbol} • prix cible atteint" else alert.label
        val body = "${alert.symbol} = ${fmt(price)} USDC • seuil $relation ${fmt(alert.targetPrice)}"
        notifyHigh(alert.id.hashCode(), title, body)
    }

    private fun notifySmartMove(symbol: String, price: Double, movePct: Double) {
        val dir = if (movePct >= 0) "hausse rapide" else "baisse rapide"
        val body = "$symbol • ${if (movePct >= 0) "+" else ""}${String.format(Locale.FRANCE, "%.2f", movePct)} % en ~5 min • prix ${fmt(price)}"
        notifyHigh(("smart_$symbol").hashCode(), "CHK Crypto • $dir", body)
    }

    private fun notifyHigh(id: Int, title: String, body: String) {
        createChannels(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val open = Intent(this, MainActivityV4::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_analysis", true)
        }
        val pi = PendingIntent.getActivity(this, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, ALERT_CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setSmallIcon(R.drawable.app_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText("$body\nOuvre CHK Crypto pour revoir le marché."))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        nm.notify(abs(id), n)
    }

    private fun buildForegroundNotification(active: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            9001,
            Intent(this, MainActivityV4::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, SERVICE_CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.app_icon)
            .setContentTitle("CHK Crypto • surveillance active")
            .setContentText("$active alarme(s) CHK • synchronisation MCP + prix Bybit public")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val SERVICE_CHANNEL = "chk_local_market_watch_service"
        private const val ALERT_CHANNEL = "chk_local_market_watch_alerts"
        private const val FOREGROUND_ID = 9321

        fun start(context: Context) {
            val app = context.applicationContext
            LocalAlertStore(app).setMonitoringEnabled(true)
            val intent = Intent(app, MarketWatchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            LocalAlertStore(app).setMonitoringEnabled(false)
            app.stopService(Intent(app, MarketWatchService::class.java))
        }

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, "Surveillance marché CHK Crypto", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notification permanente lorsque la surveillance CHK Crypto du marché est active."
            })
            nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Alertes marché CHK Crypto", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alarmes synchronisées via le MCP et surveillées directement sur le téléphone."
                enableVibration(true)
            })
        }

        private fun fmt(v: Double): String = when {
            v >= 1000 -> String.format(Locale.US, "%.2f", v)
            v >= 100 -> String.format(Locale.US, "%.3f", v)
            v >= 1 -> String.format(Locale.US, "%.5f", v).trimEnd('0').trimEnd('.')
            else -> String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
        }
    }
}
