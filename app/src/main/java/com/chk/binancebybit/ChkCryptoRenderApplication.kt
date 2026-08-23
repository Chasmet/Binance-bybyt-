package com.chk.binancebybit

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * CHK Crypto 0.6+ : les clés Binance/Bybit vivent uniquement sur Render.
 * Les anciennes copies locales sont effacées au démarrage et au retour vers l'écran principal.
 */
class ChkCryptoRenderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        purgeLegacyExchangeKeys()
        OrdersRoute.install(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivityV4) purgeLegacyExchangeKeys()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun purgeLegacyExchangeKeys() {
        val store = SecureStore(this)
        listOf(
            "binance_api_key",
            "binance_api_secret",
            "bybit_api_key",
            "bybit_api_secret"
        ).forEach { key ->
            if (store.get(key).isNotBlank()) store.put(key, "")
        }
    }
}
