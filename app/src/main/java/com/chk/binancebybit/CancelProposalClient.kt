package com.chk.binancebybit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class CancelProposalClient(
    context: Context,
    private val secureStore: SecureStore
) {
    private val workspaceSync = WorkspaceSync(context.applicationContext, secureStore)

    data class Bundle(val pending: List<CancelProposal>, val recent: JSONArray)

    fun list(): Bundle {
        val identity = workspaceSync.ensureIdentity()
        val root = JSONObject(post(JSONObject().apply {
            put("action", "list")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
        }))
        val arr = root.optJSONArray("pending") ?: JSONArray()
        val pending = mutableListOf<CancelProposal>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { pending += CancelProposal.fromJson(it) }
        return Bundle(pending, root.optJSONArray("recent") ?: JSONArray())
    }

    fun claim(id: String): CancelProposal {
        val identity = workspaceSync.ensureIdentity()
        val root = JSONObject(post(JSONObject().apply {
            put("action", "claim")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("id", id)
        }))
        return CancelProposal.fromJson(root.optJSONObject("proposal")
            ?: throw IllegalStateException("La proposition d'annulation n'est plus disponible"))
    }

    fun markResult(id: String, status: String, result: JSONObject = JSONObject()): String {
        val identity = workspaceSync.ensureIdentity()
        return post(JSONObject().apply {
            put("action", "mark_result")
            put("deviceId", identity.deviceId)
            put("deviceSecret", identity.deviceSecret)
            put("id", id)
            put("status", status)
            put("result", result)
        })
    }

    private fun post(body: JSONObject): String {
        val c = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val root = runCatching { JSONObject(text) }.getOrNull()
                val detail = listOf(root?.optString("error").orEmpty(), root?.optString("message").orEmpty())
                    .filter { it.isNotBlank() }.joinToString(" • ")
                throw IllegalStateException("Annulations HTTP $code${if (detail.isNotBlank()) " • $detail" else ""}")
            }
            text
        } finally { c.disconnect() }
    }

    companion object {
        const val ENDPOINT = "https://gflnvlolwqnvzxyqsrir.supabase.co/functions/v1/chk-cancel-proposals"
    }
}
