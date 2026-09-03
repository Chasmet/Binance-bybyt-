package com.chk.binancebybit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class OrderBookHunterBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val db = OrderBookHunterDb(app)
        try {
            val hasRestorableWatches = db.watches(restoreOnly = true).isNotEmpty()
            val mcpEnabled = OrderBookHunterService.isMcpControlEnabled(app)
            if (hasRestorableWatches || mcpEnabled) {
                runCatching { OrderBookHunterService.ensureRunning(app) }
            }
        } finally {
            db.close()
        }
    }
}
