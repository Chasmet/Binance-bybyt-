package com.chk.binancebybit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class OrderBookHunterBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val db = OrderBookHunterDb(context.applicationContext)
        try {
            if (db.watches(restoreOnly = true).isNotEmpty()) {
                OrderBookHunterService.ensureRunning(context.applicationContext)
            }
        } finally {
            db.close()
        }
    }
}
