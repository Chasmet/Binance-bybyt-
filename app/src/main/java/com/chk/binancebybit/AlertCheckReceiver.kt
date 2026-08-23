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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

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
        private const val BINANCE = "https://api.binance.com"

        fun schedule(context: Context) {
            createChannel(context)
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, AlertCheckReceiver::class.java).setAction("com.chk.binancebybit.CHECK_ALERTS")
            val pi = PendingIntent.getBroadcast(context, 4101, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 60_000L,
                INTERVAL_MS,
                pi
            )
        }

        fun checkNow(context: Context) {
            context.sendBroadcast(Intent(context, AlertCheckReceiver::class.java).setAction("com.chk.binancebybit.CHECK_ALERTS_NOW"))
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID,
                    "Alertes prix CHK Crypto",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Avertit lorsqu'une crypto atteint un seuil CHK."
                    enableVibration(true)
                })
            }
        }

        private fun checkAlerts(context: Context) {
            val store = SecureStore(context)
            val workspace = WorkspaceSync(context, store)
            val identity = workspace.ensureIdentity()
            val response = JSONObject(workspace.listAlerts())
            val alerts = response.optJSONArray("alerts") ?: JSONArray()
            var active = 0
            val prices = mutableMapOf<String, Double>()

            for (i in 0 until alerts.length()) {
                val a = alerts.optJSONObject(i) ?: continue
                if (!a.optBoolean("enabled", false)) continue
                active++
                val pair = a.optString("pair").trim().uppercase(Locale.US)
                if (pair.isBlank() || prices.containsKey(pair)) continue
                runCatching {
                    val ticker = JSONObject(getJson("$BINANCE/api/v3/ticker/price?symbol=$pair"))
                    val px = ticker.optString("price").toDoubleOrNull() ?: ticker.optDouble("price", 0.0)
                    if (px > 0) prices[pair] = px
                }
            }
            context.getSharedPreferences("chk_workspace", Context.MODE_PRIVATE).edit().putInt("alert_count", active).apply()

            for (i in 0 until alerts.length()) {
                val a = alerts.optJSONObject(i) ?: continue
                if (!a.optBoolean("enabled", false)) continue
                val pair = a.optString("pair").trim().uppercase(Locale.US)
                val price = prices[pair] ?: continue
                val target = a.optDouble("target_price", 0.0)
                val condition = a.optString("condition")
                val hit = (condition == "above" && price >= target) || (condition == "below" && price <= target)
                if (!hit) continue
                notifyAlert(context, a, price, target)
                val trigger = JSONObject().apply {
                    put("action", "trigger")
                    put("deviceId", identity.deviceId)
                    put("deviceSecret", identity.deviceSecret)
                    put("id", a.optString("id"))
                    put("lastPrice", price)
                }
                runCatching { postJson(WorkspaceSync.ALERTS_URL, trigger) }
            }
        }

        private fun notifyAlert(context: Context, alert: JSONObject, price: Double, target: Double) {
            createChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val content = PendingIntent.getActivity(context, 5101, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val symbol = alert.optString("symbol", "Crypto")
            val condition = alert.optString("condition", "above")
            val title = alert.optString("label", "$symbol • alerte prix")
            val msg = "$symbol = ${fmt(price)} USDT • seuil ${if (condition == "above") "≥" else "≤"} ${fmt(target)}"
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(context)
            }.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(msg)
                .setStyle(Notification.BigTextStyle().bigText("$msg\nOuvre CHK Crypto Workspace pour revoir la situation avant de décider."))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
            runCatching { nm.notify(kotlin.math.abs(alert.optString("id", symbol).hashCode()), notification) }
        }

        private fun getJson(urlText: String): String {
            val c = URL(urlText).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/json")
            return try {
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) error("HTTP $code")
                body
            } finally { c.disconnect() }
        }

        private fun postJson(urlText: String, body: JSONObject): String {
            val c = URL(urlText).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10000
            c.readTimeout = 12000
            c.setRequestProperty("Content-Type", "application/json")
            return try {
                val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                c.outputStream.use { it.write(bytes) }
                val code = c.responseCode
                val response = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) error("HTTP $code")
                response
            } finally { c.disconnect() }
        }

        private fun fmt(v: Double): String = when {
            v >= 1000 -> String.format(Locale.US, "%.0f", v)
            v >= 100 -> String.format(Locale.US, "%.1f", v)
            v >= 10 -> String.format(Locale.US, "%.2f", v)
            v >= 1 -> String.format(Locale.US, "%.3f", v)
            v >= .01 -> String.format(Locale.US, "%.5f", v)
            else -> String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
        }
    }
}
