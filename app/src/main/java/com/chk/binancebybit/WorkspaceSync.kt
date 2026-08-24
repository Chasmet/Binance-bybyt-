package com.chk.binancebybit

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

class WorkspaceSync(private val context: Context, private val secureStore: SecureStore) {
    private val prefs = context.getSharedPreferences("chk_workspace", Context.MODE_PRIVATE)

    data class Identity(val deviceId: String, val deviceSecret: String)

    fun ensureIdentity(): Identity {
        var id = prefs.getString("sync_id", null)
        var secret = secureStore.get("sync_secret")
        if (id.isNullOrBlank() || secret.isBlank()) {
            id = "${randomHex(16)}-${randomHex(8)}"
            secret = randomHex(32)
            prefs.edit().putString("sync_id", id).apply()
            secureStore.put("sync_secret", secret)
        }
        return Identity(id, secret)
    }

    fun syncBinance(apiKey: String, snapshot: JSONObject): String {
        val identity = ensureIdentity()
        val body = JSONObject().apply {
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("accountFingerprint", sha256(apiKey))
            put("appVersion", BuildConfig.VERSION_NAME)
            put("snapshot", snapshot)
        }
        val response = postJson(BINANCE_SYNC_URL, body)
        runCatching { syncGeneric("BINANCE", apiKey, snapshot) }
        return JSONObject(response).optString("syncedAt", "OK")
    }

    fun syncBybit(apiKey: String, snapshot: JSONObject): String {
        return syncGeneric("BYBIT", apiKey, snapshot)
    }

    private fun syncGeneric(exchange: String, apiKey: String, snapshot: JSONObject): String {
        val identity = ensureIdentity()
        val body = JSONObject().apply {
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("exchange", exchange)
            put("accountFingerprint", sha256(apiKey))
            put("appVersion", BuildConfig.VERSION_NAME)
            put("snapshot", snapshot)
        }
        val response = postJson(CRYPTO_SYNC_URL, body)
        return JSONObject(response).optString("syncedAt", "OK")
    }

    fun listNotes(): String {
        val identity = ensureIdentity()
        val body = JSONObject().apply {
            put("action", "list")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
        }
        return postJson(NOTES_URL, body)
    }

    fun createNote(exchange: String, kind: String, content: String): String {
        val identity = ensureIdentity()
        val body = JSONObject().apply {
            put("action", "create")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("exchange", exchange)
            put("kind", kind)
            put("content", content)
        }
        return postJson(NOTES_URL, body)
    }

    fun listAlerts(): String {
        val identity = ensureIdentity()
        val body = JSONObject().apply {
            put("action", "list")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
        }
        return postJson(ALERTS_URL, body)
    }

    private fun postJson(urlText: String, body: JSONObject): String {
        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 20000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code : ${response.take(500)}")
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun randomHex(size: Int): String {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val BINANCE_SYNC_URL = "https://gflnvlolwqnvzxyqsrir.supabase.co/functions/v1/chk-binance-sync"
        const val CRYPTO_SYNC_URL = "https://gflnvlolwqnvzxyqsrir.supabase.co/functions/v1/chk-crypto-sync"
        const val ALERTS_URL = "https://gflnvlolwqnvzxyqsrir.supabase.co/functions/v1/chk-binance-alerts"
        const val NOTES_URL = "https://gflnvlolwqnvzxyqsrir.supabase.co/functions/v1/chk-crypto-notes"
    }
}
