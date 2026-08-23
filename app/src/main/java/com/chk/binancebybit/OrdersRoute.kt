package com.chk.binancebybit

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object OrdersRoute {
    @Volatile private var requested = false
    @Volatile private var syncing = false

    fun requestOpen() { requested = true }

    private fun consumeRequest(): Boolean {
        val value = requested
        requested = false
        return value
    }

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is MainActivityV4) {
                    activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
                        applyRemoteMode(activity)
                    }
                }
            }

            override fun onActivityResumed(activity: Activity) {
                if (activity !is MainActivityV4) return
                val section = readStringField(activity, "section")
                if (consumeRequest() || section == "ORDERS") {
                    activity.window.decorView.post { showIntegratedOrders(activity) }
                } else {
                    activity.window.decorView.post { applyRemoteMode(activity) }
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun applyRemoteMode(activity: MainActivityV4) {
        val section = readStringField(activity, "section") ?: return
        if (section == "ORDERS") {
            showIntegratedOrders(activity)
            return
        }
        if (section == "SETTINGS") {
            renderRemoteSettings(activity)
            return
        }
        rewireSyncButtons(activity)
    }

    private fun showIntegratedOrders(activity: MainActivityV4) {
        runCatching {
            writeStringField(activity, "section", "ORDERS")
            writeStringField(activity, "exchange", "BYBIT")
            val contentField = MainActivityV4::class.java.getDeclaredField("content").apply { isAccessible = true }
            val content = contentField.get(activity) as FrameLayout
            if (content.getChildAt(0)?.tag == ORDERS_TAG) return
            val root = content.parent as? LinearLayout
            val secureStore = SecureStore(activity)
            val panel = RenderTradeOrdersPanel(activity, secureStore, WorkspaceSync(activity, secureStore)).build().apply { tag = ORDERS_TAG }
            content.removeAllViews()
            content.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            if (root != null && root.childCount >= 4) {
                replaceMainChild(activity, root, 0, "buildHeader")
                replaceExchangeSelector(activity, root, 1)
                replaceMainChild(activity, root, root.childCount - 1, "buildBottomNav")
            }
        }
    }

    private fun renderRemoteSettings(activity: MainActivityV4) {
        runCatching {
            val contentField = MainActivityV4::class.java.getDeclaredField("content").apply { isAccessible = true }
            val content = contentField.get(activity) as? FrameLayout ?: return
            if (content.getChildAt(0)?.tag == SETTINGS_TAG) return
            val exchange = readStringField(activity, "exchange") ?: "BINANCE"
            val prefs = activity.getSharedPreferences("chk_workspace", Context.MODE_PRIVATE)
            val density = activity.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            fun rounded(fill: Int, stroke: Int, radius: Int = 16) = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(fill)
                cornerRadius = dp(radius).toFloat()
                if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
            }
            val bg = Color.rgb(10, 12, 15)
            val surface = Color.rgb(20, 23, 28)
            val surface2 = Color.rgb(28, 32, 38)
            val border = Color.rgb(48, 54, 64)
            val text = Color.rgb(246, 247, 249)
            val muted = Color.rgb(153, 162, 174)
            val orange = Color.rgb(245, 142, 30)
            val yellow = Color.rgb(240, 185, 11)
            val green = Color.rgb(57, 197, 128)
            val accent = if (exchange == "BINANCE") yellow else orange

            fun info(title: String, detail: String, color: Int): View = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(surface2, color)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(10)) }
                addView(TextView(activity).apply { this.text = title; textSize = 13f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(color) })
                addView(TextView(activity).apply { this.text = detail; textSize = 11f; setTextColor(muted); setPadding(0, dp(4), 0, 0) })
            }

            fun action(label: String, primary: Boolean, run: () -> Unit) = Button(activity).apply {
                this.text = label
                isAllCaps = false
                textSize = 13f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (primary) Color.BLACK else text)
                background = rounded(if (primary) accent else surface2, if (primary) Color.TRANSPARENT else border)
                setOnClickListener { run() }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { setMargins(0, dp(4), 0, dp(6)) }
            }

            fun row(title: String, desc: String, status: String): View = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(surface, border)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) }
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(activity).apply { text = title; textSize = 14f; setTextColor(text); setTypeface(Typeface.DEFAULT, Typeface.BOLD) })
                    addView(TextView(activity).apply { text = desc; textSize = 11f; setTextColor(muted) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(activity).apply { text = status; textSize = 10f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(green) })
            }

            val page = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(28))
                setBackgroundColor(bg)
            }
            page.addView(TextView(activity).apply {
                text = "Réglages ${if (exchange == "BINANCE") "Binance" else "Bybit"}"
                textSize = 24f
                setTextColor(text)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })
            page.addView(TextView(activity).apply {
                text = "Connexion permanente via Render"
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(3), 0, dp(14))
            })
            page.addView(info(
                "Clés gérées par Render",
                "Tu n'as plus aucune clé API à saisir dans CHK Crypto. Les secrets Binance et Bybit restent dans les variables d'environnement Render et ne sont jamais renvoyés au téléphone.",
                green
            ))
            page.addView(action("Tester + synchroniser ${if (exchange == "BINANCE") "Binance" else "Bybit"}", true) {
                syncRemote(activity, exchange)
            })
            page.addView(action("Synchroniser Binance + Bybit", false) { syncAllRemote(activity) })

            val stateKey = if (exchange == "BINANCE") "binance_sync_state" else "bybit_sync_state"
            val state = prefs.getString(stateKey, "Pas encore testé avec Render") ?: "Pas encore testé avec Render"
            page.addView(info("État de connexion", state, if (state.startsWith("OK")) green else muted))
            if (exchange == "BYBIT") {
                val apiInfo = prefs.getString("bybit_api_info", "") ?: ""
                if (apiInfo.isNotBlank()) page.addView(info("Permissions Bybit", apiInfo, orange))
            }

            page.addView(row("Stockage des clés", "Variables d'environnement privées Render", "RENDER"))
            page.addView(row("Clés sur le téléphone", "Aucune clé nécessaire pour les nouvelles synchronisations", "INUTILE"))
            page.addView(row("Retraits", "Aucune fonction de retrait dans CHK Crypto", "BLOQUÉ"))
            page.addView(row("Transferts", "Aucune fonction de transfert dans CHK Crypto", "BLOQUÉ"))
            page.addView(row("Ordres Bybit", "Spot USDC • MARKET/LIMIT • proposition + CONFIRMER obligatoire", "PROTÉGÉ"))

            page.addView(info(
                "Sauvegarde de secours",
                "La sauvegarde chiffrée sert maintenant surtout à conserver l'identité CHK Crypto, les réglages et les anciennes données locales. Les clés API restent sur Render.",
                accent
            ))
            page.addView(action("Sauvegarde / restauration chiffrée", false) {
                activity.startActivity(Intent(activity, BackupActivity::class.java))
            })

            val scroll = ScrollView(activity).apply {
                tag = SETTINGS_TAG
                isFillViewport = true
                addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            content.removeAllViews()
            content.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun rewireSyncButtons(activity: MainActivityV4) {
        val root = activity.window.decorView as? ViewGroup ?: return
        val buttons = mutableListOf<Button>()
        collectButtons(root, buttons)
        buttons.forEach { button ->
            val label = button.text.toString()
            when {
                label.contains("Tout synchroniser", ignoreCase = true) -> {
                    if (button.tag != REMOTE_BUTTON_TAG) {
                        button.tag = REMOTE_BUTTON_TAG
                        button.setOnClickListener { syncAllRemote(activity) }
                    }
                }
                label.contains("Actualiser portefeuille", ignoreCase = true) ||
                    label.contains("Charger l'historique", ignoreCase = true) ||
                    label.startsWith("Synchroniser Binance", ignoreCase = true) ||
                    label.startsWith("Synchroniser Bybit", ignoreCase = true) -> {
                    if (button.tag != REMOTE_BUTTON_TAG) {
                        button.tag = REMOTE_BUTTON_TAG
                        button.setOnClickListener {
                            syncRemote(activity, readStringField(activity, "exchange") ?: "BINANCE")
                        }
                    }
                }
            }
        }
    }

    private fun syncRemote(activity: MainActivityV4, exchange: String) {
        if (syncing) {
            Toast.makeText(activity, "Synchronisation déjà en cours", Toast.LENGTH_SHORT).show()
            return
        }
        syncing = true
        Toast.makeText(activity, "$exchange : connexion Render…", Toast.LENGTH_SHORT).show()
        val secureStore = SecureStore(activity)
        Thread {
            try {
                val data = RenderCryptoClient(activity, secureStore).sync(exchange)
                activity.runOnUiThread {
                    saveRemoteData(activity, exchange, data)
                    Toast.makeText(activity, "$exchange synchronisé via Render", Toast.LENGTH_SHORT).show()
                    syncing = false
                    invokeNoArg(activity, "rebuildUi")
                }
            } catch (e: Throwable) {
                activity.runOnUiThread {
                    val prefs = activity.getSharedPreferences("chk_workspace", Context.MODE_PRIVATE)
                    val stateKey = if (exchange == "BINANCE") "binance_sync_state" else "bybit_sync_state"
                    prefs.edit().putString(stateKey, "Échec Render • ${(e.message ?: e.toString()).take(120)}").apply()
                    Toast.makeText(activity, "Erreur $exchange : ${e.message}", Toast.LENGTH_LONG).show()
                    syncing = false
                    invokeNoArg(activity, "rebuildUi")
                }
            }
        }.start()
    }

    private fun syncAllRemote(activity: MainActivityV4) {
        if (syncing) {
            Toast.makeText(activity, "Synchronisation déjà en cours", Toast.LENGTH_SHORT).show()
            return
        }
        syncing = true
        Toast.makeText(activity, "Binance + Bybit via Render…", Toast.LENGTH_SHORT).show()
        val secureStore = SecureStore(activity)
        Thread {
            val client = RenderCryptoClient(activity, secureStore)
            val results = mutableListOf<String>()
            val data = mutableMapOf<String, RenderCryptoClient.RemoteWorkspaceData>()
            for (exchange in listOf("BINANCE", "BYBIT")) {
                runCatching { client.sync(exchange) }
                    .onSuccess { data[exchange] = it; results += "$exchange OK" }
                    .onFailure { results += "$exchange : ${it.message}" }
            }
            activity.runOnUiThread {
                data.forEach { (exchange, value) -> saveRemoteData(activity, exchange, value) }
                Toast.makeText(activity, results.joinToString("\n"), Toast.LENGTH_LONG).show()
                syncing = false
                invokeNoArg(activity, "rebuildUi")
            }
        }.start()
    }

    private fun saveRemoteData(activity: MainActivityV4, exchange: String, data: RenderCryptoClient.RemoteWorkspaceData) {
        runCatching {
            val method = MainActivityV4::class.java.getDeclaredMethod("saveSnapshot", String::class.java, PortfolioSnapshot::class.java).apply { isAccessible = true }
            method.invoke(activity, exchange, data.portfolio)
        }
        val prefs = activity.getSharedPreferences("chk_workspace", Context.MODE_PRIVATE)
        val historyKey = if (exchange == "BINANCE") "binance_history" else "bybit_history"
        val stateKey = if (exchange == "BINANCE") "binance_sync_state" else "bybit_sync_state"
        val edit = prefs.edit()
            .putString(historyKey, data.historyText)
            .putString(stateKey, "OK • Render • ${data.syncedAt}")
        if (exchange == "BYBIT") edit.putString("bybit_api_info", data.apiInfoText)
        edit.apply()
    }

    private fun replaceMainChild(activity: MainActivityV4, root: LinearLayout, index: Int, methodName: String) {
        val old = root.getChildAt(index)
        val params = old.layoutParams
        val method = MainActivityV4::class.java.getDeclaredMethod(methodName).apply { isAccessible = true }
        val replacement = method.invoke(activity) as View
        root.removeViewAt(index)
        root.addView(replacement, index, params)
    }

    private fun replaceExchangeSelector(activity: MainActivityV4, root: LinearLayout, index: Int) {
        val old = root.getChildAt(index)
        val params = old.layoutParams
        val method = MainActivityV4::class.java.getDeclaredMethod("buildExchangeSelector").apply { isAccessible = true }
        val selector = method.invoke(activity) as View
        if (selector is ViewGroup) {
            val buttons = mutableListOf<Button>()
            collectButtons(selector, buttons)
            buttons.forEach { button ->
                when (button.text.toString().uppercase()) {
                    "BINANCE" -> button.setOnClickListener {
                        writeStringField(activity, "exchange", "BINANCE")
                        writeStringField(activity, "section", "HOME")
                        invokeNoArg(activity, "rebuildUi")
                    }
                    "BYBIT" -> button.setOnClickListener {
                        writeStringField(activity, "exchange", "BYBIT")
                        writeStringField(activity, "section", "ORDERS")
                        showIntegratedOrders(activity)
                    }
                }
            }
        }
        root.removeViewAt(index)
        root.addView(selector, index, params)
    }

    private fun collectButtons(group: ViewGroup, out: MutableList<Button>) {
        for (i in 0 until group.childCount) {
            when (val child = group.getChildAt(i)) {
                is Button -> out += child
                is ViewGroup -> collectButtons(child, out)
            }
        }
    }

    private fun readStringField(activity: MainActivityV4, name: String): String? = runCatching {
        val field = MainActivityV4::class.java.getDeclaredField(name).apply { isAccessible = true }
        field.get(activity) as? String
    }.getOrNull()

    private fun writeStringField(activity: MainActivityV4, name: String, value: String) {
        val field = MainActivityV4::class.java.getDeclaredField(name).apply { isAccessible = true }
        field.set(activity, value)
    }

    private fun invokeNoArg(activity: MainActivityV4, name: String) {
        val method = MainActivityV4::class.java.getDeclaredMethod(name).apply { isAccessible = true }
        method.invoke(activity)
    }

    private const val ORDERS_TAG = "chk_render_orders_panel"
    private const val SETTINGS_TAG = "chk_render_settings_panel"
    private const val REMOTE_BUTTON_TAG = "chk_render_remote_button"
}

class ChkCryptoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OrdersRoute.install(this)
    }
}
