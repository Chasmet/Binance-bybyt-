package com.chk.binancebybit

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Routeur de compatibilité pour les anciens boutons / notifications.
 * Aucun ordre n'est exécuté ici : l'interface réelle est TradeOrdersPanel,
 * affichée dans MainActivityV4 avec la navigation CHK Crypto.
 */
class TradeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrdersRoute.requestOpen()
        startActivity(Intent(this, MainActivityV4::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        overridePendingTransition(0, 0)
        finish()
        overridePendingTransition(0, 0)
    }
}
