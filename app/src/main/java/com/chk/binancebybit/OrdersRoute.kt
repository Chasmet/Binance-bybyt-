package com.chk.binancebybit

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object OrdersRoute {
    @Volatile
    private var requested = false

    fun requestOpen() {
        requested = true
    }

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
                        augmentSettings(activity)
                    }
                }
            }

            override fun onActivityResumed(activity: Activity) {
                if (activity !is MainActivityV4) return
                val section = readStringField(activity, "section")
                if (consumeRequest() || section == "ORDERS") {
                    activity.window.decorView.post { showIntegratedOrders(activity) }
                } else {
                    activity.window.decorView.post { augmentSettings(activity) }
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun showIntegratedOrders(activity: MainActivityV4) {
        runCatching {
            writeStringField(activity, "section", "ORDERS")
            writeStringField(activity, "exchange", "BYBIT")

            val contentField = MainActivityV4::class.java.getDeclaredField("content").apply { isAccessible = true }
            val content = contentField.get(activity) as FrameLayout
            val root = content.parent as? LinearLayout
            val secureStore = SecureStore(activity)

            content.removeAllViews()
            content.addView(
                CancelAwareTradeOrdersPanel(activity, secureStore, WorkspaceSync(activity, secureStore)).build(),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )

            if (root != null && root.childCount >= 4) {
                replaceMainChild(activity, root, 0, "buildHeader")
                replaceExchangeSelector(activity, root, 1)
                replaceMainChild(activity, root, root.childCount - 1, "buildBottomNav")
            }
        }
    }

    private fun augmentSettings(activity: MainActivityV4) {
        if (readStringField(activity, "section") != "SETTINGS") return
        runCatching {
            val contentField = MainActivityV4::class.java.getDeclaredField("content").apply { isAccessible = true }
            val content = contentField.get(activity) as? FrameLayout ?: return
            val scroll = content.getChildAt(0) as? ScrollView ?: return
            val page = scroll.getChildAt(0) as? LinearLayout ?: return
            if (page.findViewWithTag<View>(BACKUP_TAG) != null) return

            val density = activity.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            fun rounded(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(fill)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), stroke)
            }

            val container = LinearLayout(activity).apply {
                tag = BACKUP_TAG
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = rounded(Color.rgb(20, 23, 28), Color.rgb(48, 54, 64))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dp(10), 0, dp(8))
                }
            }
            container.addView(TextView(activity).apply {
                text = "Sauvegarde de secours"
                textSize = 14f
                setTextColor(Color.rgb(246, 247, 249))
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })
            container.addView(TextView(activity).apply {
                text = "Exporte ou restaure les clés API et l'identité CHK Crypto dans un fichier chiffré par mot de passe."
                textSize = 11f
                setTextColor(Color.rgb(153, 162, 174))
                setPadding(0, dp(4), 0, dp(8))
            })
            container.addView(Button(activity).apply {
                text = "SAUVEGARDE / RESTAURATION CHIFFRÉE"
                isAllCaps = false
                textSize = 12f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.BLACK)
                background = rounded(Color.rgb(245, 142, 30), Color.rgb(245, 142, 30))
                setOnClickListener {
                    activity.startActivity(Intent(activity, BackupActivity::class.java))
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
            page.addView(container)
        }
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

    private const val BACKUP_TAG = "chk_crypto_backup_controls"
}

class ChkCryptoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OrdersRoute.install(this)
    }
}
