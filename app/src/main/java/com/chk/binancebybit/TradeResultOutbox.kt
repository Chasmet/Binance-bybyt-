package com.chk.binancebybit

import android.content.Context
import org.json.JSONObject

/** Durable acknowledgements. Retries only the receipt, never the Bybit order. */
class TradeResultOutbox(context: Context, private val client: TradeProposalClient) {
    private val prefs = context.applicationContext.getSharedPreferences("chk_trade_receipts_v1", Context.MODE_PRIVATE)

    fun contains(id: String): Boolean = prefs.contains(id)

    fun enqueue(id: String, status: String, orderId: String, result: JSONObject) {
        val receipt = JSONObject().put("status", status).put("orderId", orderId).put("result", result)
        check(prefs.edit().putString(id, receipt.toString()).commit()) { "Confirmation à synchroniser : stockage indisponible" }
    }

    fun flush() = synchronized(lock) {
        for ((id, raw) in prefs.all) {
            runCatching {
                val receipt = JSONObject(raw as String)
                val response = JSONObject(client.markResult(id, receipt.getString("status"),
                    receipt.getString("orderId"), receipt.getJSONObject("result")))
                check(response.optBoolean("ok")) { "Confirmation serveur absente" }
                prefs.edit().remove(id).commit()
            }
        }
    }

    companion object { private val lock = Any() }
}
