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
        BotEngine.createChannels(app)
        val botStore = BotRuleStore(app)
        val autoTrade = AutoTradePolicyStore(app)
        if (LocalAlertStore(app).monitoringEnabled() || botStore.enabled() || autoTrade.enabled()) {
            runCatching { MarketWatchService.start(app) }
        }
        if (botStore.enabled()) botStore.syncJournalNow()
    }
}
