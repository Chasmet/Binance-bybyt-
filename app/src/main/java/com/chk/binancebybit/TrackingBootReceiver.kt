package com.chk.binancebybit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TrackingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        TrackingService.createChannel(app)
        if (TrackingStore(app).enabled()) runCatching { TrackingService.start(app) }
    }
}
