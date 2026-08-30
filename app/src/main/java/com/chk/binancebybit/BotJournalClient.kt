package com.chk.binancebybit

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class BotJournalClient(context: Context) {
    private val app = context.applicationContext
    private val secureStore = SecureStore(app)
    private val workspace = WorkspaceSync(app, secureStore)

    fun append(entry: BotRuleStore.LogEntry) {
        val identity = workspace.ensureIdentity()
        val body = JSONObject().apply {
            put("action", "append")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("eventId", entry.id)
            put("eventAt", entry.at)
            put("level", entry.level)
            put("category", entry.category)
            put("title", entry.title)
            put("detail", entry.detail)
            if (entry.symbol.isNotBlank()) put("symbol", entry.symbol)
            if (entry.ruleId.isNotBlank()) put("ruleId", entry.ruleId)
        }
        post(body)
    }

    fun syncRecent(entries: List<BotRuleStore.LogEntry>) {
        entries.take(120).asReversed().forEach { append(it) }
    }

    private fun post(body: JSONObject): String {
        val c = URL(URL_TEXT).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 8_000
        c.readTimeout = 12_000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")
        return try {
            val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val response = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Bot journal HTTP $code ${response.take(160)}")
            response
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val URL_TEXT = "https://gflnvlolwqnvzxyqsrir.supabase.co/functions/v1/chk-bot-journal"
    }
}
