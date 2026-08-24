package com.chk.binancebybit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        AlertCheckReceiver.schedule(app)
        TradeProposalReceiver.createChannel(app)
        TradeProposalReceiver.schedule(app)
        MarketWatchService.createChannels(app)
        if (LocalAlertStore(app).monitoringEnabled()) {
            runCatching { MarketWatchService.start(app) }
        }
    }
}
