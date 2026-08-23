package com.chk.binancebybit

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

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
            override fun onActivityResumed(activity: Activity) {
                if (activity !is MainActivityV4) return
                val section = readStringField(activity, "section")
                if (consumeRequest() || section == "ORDERS") {
                    activity.window.decorView.post { showIntegratedOrders(activity) }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
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

            content.removeAllViews()
            content.addView(
                TradeOrdersPanel(activity, SecureStore(activity), WorkspaceSync(activity, SecureStore(activity))).build(),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )

            if (root != null && root.childCount >= 4) {
                replaceMainChild(activity, root, 0, "buildHeader")
                replaceExchangeSelector(activity, root, 1)
                replaceMainChild(activity, root, root.childCount - 1, "buildBottomNav")
            }
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
}

class ChkCryptoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OrdersRoute.install(this)
    }
}
