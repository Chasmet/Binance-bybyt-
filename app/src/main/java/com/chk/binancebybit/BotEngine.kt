package com.chk.binancebybit

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Autonomous local rule engine.
 * - no OpenAI call
 * - no Notes writes
 * - independent Bot journal
 * - BUY/SELL remain proposals requiring explicit confirmation in CHK Crypto
 */
class BotEngine(
    context: Context,
    private val publicClient: BybitPublicMarketClient = BybitPublicMarketClient()
) {
    private val app = context.applicationContext
    private val store = BotRuleStore(app)
    private val secureStore = SecureStore(app)
    private val observationPrefs = app.getSharedPreferences("chk_bot_observations_v1", Context.MODE_PRIVATE)
    private val evaluating = AtomicBoolean(false)

    fun evaluateOnce(): Summary {
        if (!store.enabled()) return Summary(0, 0, 0)
        if (!evaluating.compareAndSet(false, true)) return Summary(0, 0, 0)
        return try {
            val rules = store.list().filter { it.enabled && it.targetPrice > 0.0 }

            // The Bot keeps reading market/chart data even when no CHK Crypto screen is open.
            val watched = linkedSetOf("BTCUSDC", "ETHUSDC", "RENDERUSDC")
            rules.forEach { watched += it.symbol }
            watched.forEach { symbol -> runCatching { recordMarketObservationIfDue(symbol) } }

            if (rules.isEmpty()) return Summary(0, 0, 0)

            val tickerCache = hashMapOf<String, BybitPublicTicker>()
            val rsiCache = hashMapOf<String, Double>()
            var checked = 0
            var triggered = 0
            var proposals = 0

            rules.forEach { rule ->
                if (!store.canTrigger(rule)) return@forEach
                checked++
                runCatching {
                    val ticker = tickerCache.getOrPut(rule.symbol) { publicClient.ticker(rule.symbol) }
                    val priceHit = when (rule.priceCondition) {
                        "above" -> ticker.lastPrice >= rule.targetPrice
                        else -> ticker.lastPrice <= rule.targetPrice
                    }
                    if (!priceHit) return@runCatching

                    val rsiValue = if (rule.rsiEnabled) {
                        val key = "${rule.symbol}:${rule.rsiTimeframe}"
                        rsiCache.getOrPut(key) { loadRsi(rule.symbol, rule.rsiTimeframe) }
                    } else null
                    val rsiHit = if (!rule.rsiEnabled) true else when (rule.rsiCondition) {
                        "above" -> (rsiValue ?: 0.0) >= rule.rsiThreshold
                        else -> (rsiValue ?: 100.0) <= rule.rsiThreshold
                    }
                    if (!rsiHit) return@runCatching

                    val detail = triggerDescription(rule, ticker.lastPrice, rsiValue)
                    when (rule.action) {
                        BotRuleStore.ACTION_PREPARE_BUY,
                        BotRuleStore.ACTION_PREPARE_SELL -> {
                            val side = if (rule.action == BotRuleStore.ACTION_PREPARE_BUY) "BUY" else "SELL"
                            val quote = rule.amountUsdc.coerceIn(1.0, 10.0)
                            val base = if (side == "SELL") quote / max(ticker.lastPrice, 0.00000001) else null
                            val proposal = TradeProposalClient(app, secureStore).createBotProposal(
                                symbol = rule.symbol,
                                side = side,
                                quoteAmountUsdc = quote,
                                baseQuantity = base,
                                limitPrice = rule.targetPrice,
                                rationale = "Bot CHK • ${rule.name} • $detail",
                                expiresInMinutes = 120
                            )
                            proposals++
                            val actionText = if (side == "BUY") "ACHAT" else "VENTE"
                            store.addLog(
                                level = "PROPOSAL",
                                title = "${rule.name} • proposition $actionText",
                                detail = "$detail • ${format(quote)} USDC • ID ${proposal.id} • confirmation utilisateur obligatoire",
                                category = "PROPOSAL",
                                symbol = rule.symbol,
                                ruleId = rule.id
                            )
                            notifyHigh(
                                title = "Bot CHK • ordre à confirmer",
                                body = "$actionText ${rule.symbol} préparé • ${format(quote)} USDC • aucune exécution automatique",
                                openOrders = true
                            )
                            TradeProposalReceiver.checkNow(app)
                        }
                        else -> {
                            store.addLog(
                                level = "ALERT",
                                title = rule.name,
                                detail = detail,
                                category = "ALERT",
                                symbol = rule.symbol,
                                ruleId = rule.id
                            )
                            notifyHigh("Bot CHK • ${rule.name}", detail, openOrders = false)
                        }
                    }
                    triggered++
                    store.markTriggered(rule.id, disable = rule.oneShot)
                }.onFailure { error ->
                    val message = error.message ?: error.javaClass.simpleName
                    store.addLog(
                        level = "ERROR",
                        title = rule.name,
                        detail = message,
                        category = "ERROR",
                        symbol = rule.symbol,
                        ruleId = rule.id
                    )
                }
            }
            Summary(checked, triggered, proposals)
        } finally {
            evaluating.set(false)
        }
    }

    private fun recordMarketObservationIfDue(symbol: String) {
        val key = "last_${symbol.uppercase(Locale.US)}"
        val now = System.currentTimeMillis()
        if (now - observationPrefs.getLong(key, 0L) < OBSERVATION_INTERVAL_MS) return

        val ticker = publicClient.ticker(symbol)
        val candles = publicClient.recentCandles(symbol, "15", 40)
        if (candles.size < 16) return
        val closes = candles.map { it.close }
        val currentRsi = rsi(closes, 14)
        val baseIndex = (candles.lastIndex - 4).coerceAtLeast(0)
        val base = candles[baseIndex].close
        val move1h = if (base > 0.0) (candles.last().close / base - 1.0) * 100.0 else 0.0
        val recent = candles.takeLast(16)
        val high = recent.maxOf { it.high }
        val low = recent.minOf { it.low }
        val avgVolume = recent.dropLast(1).map { it.volume }.average().takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val volumeRatio = if (avgVolume > 0.0) recent.last().volume / avgVolume else 0.0

        val detail = buildString {
            append("$symbol • prix ${format(ticker.lastPrice)} USDC")
            append(" • RSI14 15m ${String.format(Locale.FRANCE, "%.1f", currentRsi)}")
            append(" • ~1h ${if (move1h >= 0) "+" else ""}${String.format(Locale.FRANCE, "%.2f", move1h)} %")
            append(" • zone 4h ${format(low)}–${format(high)}")
            if (volumeRatio > 0.0) append(" • volume x${String.format(Locale.FRANCE, "%.2f", volumeRatio)}")
            append(" • 24h ${if (ticker.price24hPct >= 0) "+" else ""}${String.format(Locale.FRANCE, "%.2f", ticker.price24hPct)} %")
        }
        store.addLog(
            level = "INFO",
            title = "Observation marché 15m",
            detail = detail,
            category = "MARKET",
            symbol = symbol
        )
        observationPrefs.edit().putLong(key, now).apply()
    }

    private fun loadRsi(symbol: String, timeframe: String): Double {
        val candles = publicClient.recentCandles(
            symbol = symbol,
            interval = BotRuleStore.bybitInterval(timeframe),
            limit = 80
        )
        val closes = candles.map { it.close }.filter { it > 0.0 }
        if (closes.size < 16) throw IllegalStateException("RSI indisponible pour $symbol $timeframe")
        return rsi(closes, 14)
    }

    private fun rsi(values: List<Double>, period: Int): Double {
        if (values.size <= period) return 50.0
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = values[i] - values[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        for (i in period + 1 until values.size) {
            val d = values[i] - values[i - 1]
            avgGain = (avgGain * (period - 1) + max(d, 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + max(-d, 0.0)) / period
        }
        if (avgLoss <= 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    private fun triggerDescription(rule: BotRuleStore.Rule, price: Double, rsi: Double?): String {
        val relation = if (rule.priceCondition == "above") "≥" else "≤"
        return buildString {
            append("${rule.symbol} ${format(price)} USDC • seuil $relation ${format(rule.targetPrice)}")
            if (rsi != null) {
                val r = if (rule.rsiCondition == "above") "≥" else "≤"
                append(" • RSI14 ${rule.rsiTimeframe} ${String.format(Locale.FRANCE, "%.1f", rsi)} $r ${String.format(Locale.FRANCE, "%.1f", rule.rsiThreshold)}")
            }
        }
    }

    private fun notifyHigh(title: String, body: String, openOrders: Boolean) {
        createChannels(app)
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = if (openOrders) Intent(app, TradeActivity::class.java) else Intent(app, BotActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pending = PendingIntent.getActivity(
            app,
            (title + body).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            kotlin.math.abs((title + body).hashCode()),
            Notification.Builder(app, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
        )
    }

    data class Summary(val checked: Int, val triggered: Int, val proposals: Int)

    companion object {
        private const val ALERT_CHANNEL = "chk_bot_alerts"
        private const val INFO_CHANNEL = "chk_bot_info"
        private const val INTRO_NOTIFICATION_ID = 9480
        private const val OBSERVATION_INTERVAL_MS = 15L * 60L * 1000L

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, "Bot CHK • actions et alertes", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Règles locales Bot CHK et propositions d'ordres à confirmer"
                    enableVibration(true)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(INFO_CHANNEL, "Bot CHK • informations", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "État et accès rapide au Bot CHK"
                }
            )
        }

        fun install(context: Context) {
            val app = context.applicationContext
            createChannels(app)
            installShortcut(app)
            val store = BotRuleStore(app)
            if (store.enabled()) runCatching { MarketWatchService.start(app) }
            store.syncJournalNow()
            val prefs = app.getSharedPreferences("chk_bot_v1", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("intro_v1_shown", false)) {
                prefs.edit().putBoolean("intro_v1_shown", true).apply()
                postIntro(app)
            }
        }

        private fun installShortcut(context: Context) {
            if (Build.VERSION.SDK_INT < 25) return
            runCatching {
                val manager = context.getSystemService(ShortcutManager::class.java) ?: return@runCatching
                val shortcut = ShortcutInfo.Builder(context, "bot_chk")
                    .setShortLabel("Bot CHK")
                    .setLongLabel("Ouvrir Bot CHK")
                    .setIcon(Icon.createWithResource(context, R.drawable.app_icon))
                    .setIntent(Intent(context, BotActivity::class.java).apply { action = Intent.ACTION_VIEW })
                    .build()
                manager.dynamicShortcuts = listOf(shortcut)
            }
        }

        private fun postIntro(context: Context) {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val pending = PendingIntent.getActivity(
                context,
                INTRO_NOTIFICATION_ID,
                Intent(context, BotActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            nm.notify(
                INTRO_NOTIFICATION_ID,
                Notification.Builder(context, INFO_CHANNEL)
                    .setSmallIcon(R.drawable.app_icon)
                    .setContentTitle("Nouveau • Bot CHK")
                    .setContentText("Surveillance autonome + journal indépendant.")
                    .setStyle(Notification.BigTextStyle().bigText("Bot CHK surveille le marché même quand l'écran CHK Crypto est fermé, tient son propre journal et peut préparer des ordres. Aucun BUY/SELL n'est exécuté sans ta confirmation."))
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build()
            )
        }

        private fun format(v: Double): String = when {
            v >= 1000 -> String.format(Locale.US, "%.2f", v)
            v >= 100 -> String.format(Locale.US, "%.3f", v)
            v >= 1 -> String.format(Locale.US, "%.5f", v).trimEnd('0').trimEnd('.')
            else -> String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
        }
    }
}
