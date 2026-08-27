package com.chk.binancebybit

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object ExperienceRoute {
    private const val TOP_TAG = "chk_top_three_modes"
    private const val SUB_TAG = "chk_context_subnav"

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is MainActivityV4) {
                    activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
                        activity.window.decorView.post { applyShell(activity) }
                    }
                }
            }
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivityV4) activity.window.decorView.post { applyShell(activity) }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun refreshCurrent(activity: MainActivityV4) {
        when (readField(activity, "section")) {
            "ANALYSIS" -> showAnalysis(activity)
            "LOCAL_ALERTS" -> showLocalAlerts(activity)
            "ORDERS" -> showOrders(activity)
            "ORDER_HISTORY" -> showOrderHistory(activity)
            else -> invokeRebuild(activity)
        }
    }

    fun showAnalysis(activity: MainActivityV4) {
        writeField(activity, "section", "ANALYSIS")
        val content = content(activity) ?: return
        content.removeAllViews()
        val store = SecureStore(activity)
        val analysisView = ProAnalysisPanel(
            activity = activity,
            exchangeProvider = { readField(activity, "exchange") ?: "BYBIT" },
            workspaceSync = WorkspaceSync(activity, store)
        ).build()
        val hostedView: View = if (analysisView is ScrollView) {
            AnalysisInteractionHost(activity, analysisView)
        } else {
            analysisView
        }
        content.addView(
            hostedView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        applyShell(activity)
    }

    fun showLocalAlerts(activity: MainActivityV4) {
        writeField(activity, "section", "LOCAL_ALERTS")
        val content = content(activity) ?: return
        content.removeAllViews()
        content.addView(LocalAlertsPanel(activity).build(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applyShell(activity)
    }

    fun showOrders(activity: MainActivityV4) {
        writeField(activity, "section", "ORDERS")
        writeField(activity, "exchange", "BYBIT")
        val content = content(activity) ?: return
        val store = SecureStore(activity)
        content.removeAllViews()
        content.addView(
            CancelAwareTradeOrdersPanel(activity, store, WorkspaceSync(activity, store)).build(),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        applyShell(activity)
    }

    fun showOrderHistory(activity: MainActivityV4) {
        writeField(activity, "section", "ORDER_HISTORY")
        writeField(activity, "exchange", "BYBIT")
        val content = content(activity) ?: return
        content.removeAllViews()
        content.addView(OrdersHistoryPanel(activity).build(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applyShell(activity)
    }

    private fun applyShell(activity: MainActivityV4) {
        val content = content(activity) ?: return
        val root = content.parent as? LinearLayout ?: return
        val section = readField(activity, "section") ?: "HOME"
        val mode = when (section) {
            "ANALYSIS", "LOCAL_ALERTS" -> "ANALYSIS"
            "ORDERS", "ORDER_HISTORY" -> "ORDERS"
            else -> "CLASSIC"
        }
        val signature = "$mode:$section"

        // Bottom mode navigation: replace only when the active mode/section actually changed.
        val last = root.getChildAt(root.childCount - 1)
        if (last?.tag != "$TOP_TAG:$signature") {
            val params = last?.layoutParams ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 66))
            if (last != null) root.removeView(last)
            val nav = buildModeNav(activity, mode).apply { tag = "$TOP_TAG:$signature" }
            root.addView(nav, params)
        }

        // Context navigation: IMPORTANT — do not remove/recreate it on every global-layout pass.
        // Recreating it continuously made taps on Accueil/Actifs/Notes/Réglages get lost.
        val expectedSubTag = "$SUB_TAG:$signature"
        val existingSub = findSubNav(root)
        if (existingSub?.tag != expectedSubTag) {
            if (existingSub != null) root.removeView(existingSub)
            val contentIndex = root.indexOfChild(content)
            if (contentIndex >= 0) {
                val sub = buildSubNav(activity, mode, section).apply { tag = expectedSubTag }
                root.addView(sub, contentIndex, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48)).apply {
                    setMargins(dp(activity, 12), 0, dp(activity, 12), dp(activity, 4))
                })
            }
        }
    }

    private fun findSubNav(root: LinearLayout): View? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            val tag = child.tag as? String ?: continue
            if (tag.startsWith(SUB_TAG)) return child
        }
        return null
    }

    private fun buildModeNav(activity: MainActivityV4, mode: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(activity, 7), dp(activity, 5), dp(activity, 7), dp(activity, 7))
        background = rounded(activity, Color.rgb(20, 23, 28), Color.rgb(48, 54, 64), 0)
        addView(modeButton(activity, "CLASSIC", "▦", "Classique", mode == "CLASSIC") {
            openClassic(activity, "HOME")
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(modeButton(activity, "ANALYSIS", "⌁", "Analyse", mode == "ANALYSIS") {
            showAnalysis(activity)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(modeButton(activity, "ORDERS", "✓", "Ordres", mode == "ORDERS") {
            showOrders(activity)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun buildSubNav(activity: MainActivityV4, mode: String, section: String): View {
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 3), dp(activity, 3), dp(activity, 3), dp(activity, 3))
            background = rounded(activity, Color.rgb(20, 23, 28), Color.rgb(48, 54, 64), 15)
        }
        when (mode) {
            "ANALYSIS" -> {
                row.addView(subButton(activity, "Graphique", section == "ANALYSIS") { showAnalysis(activity) })
                row.addView(subButton(activity, "Alarmes", section == "LOCAL_ALERTS") { showLocalAlerts(activity) })
            }
            "ORDERS" -> {
                row.addView(subButton(activity, "Ordres", section == "ORDERS") { showOrders(activity) })
                row.addView(subButton(activity, "Historique", section == "ORDER_HISTORY") { showOrderHistory(activity) })
            }
            else -> {
                row.addView(subButton(activity, "Accueil", section == "HOME") { openClassic(activity, "HOME") })
                row.addView(subButton(activity, "Actifs", section == "PORTFOLIO") { openClassic(activity, "PORTFOLIO") })
                row.addView(subButton(activity, "Notes", section == "NOTES") { openClassic(activity, "NOTES") })
                row.addView(subButton(activity, "Réglages", section == "SETTINGS") { openClassic(activity, "SETTINGS") })
            }
        }
        scroller.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return scroller
    }

    private fun openClassic(activity: MainActivityV4, section: String) {
        if (readField(activity, "section") == section) return
        writeField(activity, "section", section)
        invokeRebuild(activity)
    }

    private fun modeButton(activity: Activity, code: String, icon: String, label: String, active: Boolean, click: () -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = if (active) rounded(activity, Color.rgb(31, 36, 43), Color.TRANSPARENT, 13) else rounded(activity, Color.TRANSPARENT, Color.TRANSPARENT, 13)
        addView(TextView(activity).apply {
            text = icon
            textSize = 17f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(if (active) when (code) { "ANALYSIS" -> Color.rgb(176, 126, 255); "ORDERS" -> Color.rgb(245, 142, 30); else -> Color.rgb(93, 148, 255) } else Color.rgb(153, 162, 174))
            gravity = Gravity.CENTER
        })
        addView(TextView(activity).apply {
            text = label
            textSize = 9f
            setTypeface(Typeface.DEFAULT, if (active) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (active) Color.rgb(246, 247, 249) else Color.rgb(153, 162, 174))
            gravity = Gravity.CENTER
        })
        setOnClickListener { click() }
    }

    private fun subButton(activity: Activity, label: String, active: Boolean, click: () -> Unit): TextView = TextView(activity).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 10.5f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(if (active) Color.BLACK else Color.rgb(220, 225, 232))
        background = if (active) rounded(activity, Color.rgb(245, 142, 30), Color.TRANSPARENT, 12) else rounded(activity, Color.TRANSPARENT, Color.TRANSPARENT, 12)
        setPadding(dp(activity, 18), 0, dp(activity, 18), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(0, 0, dp(activity, 4), 0) }
    }

    private fun content(activity: MainActivityV4): FrameLayout? = runCatching {
        val f = MainActivityV4::class.java.getDeclaredField("content").apply { isAccessible = true }
        f.get(activity) as? FrameLayout
    }.getOrNull()

    private fun readField(activity: MainActivityV4, name: String): String? = runCatching {
        val f = MainActivityV4::class.java.getDeclaredField(name).apply { isAccessible = true }
        f.get(activity) as? String
    }.getOrNull()

    private fun writeField(activity: MainActivityV4, name: String, value: String) {
        runCatching {
            val f = MainActivityV4::class.java.getDeclaredField(name).apply { isAccessible = true }
            f.set(activity, value)
        }
    }

    private fun invokeRebuild(activity: MainActivityV4) {
        runCatching {
            val m = MainActivityV4::class.java.getDeclaredMethod("rebuildUi").apply { isAccessible = true }
            m.invoke(activity)
            activity.window.decorView.post { applyShell(activity) }
        }
    }

    private fun rounded(activity: Activity, fill: Int, stroke: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(activity, radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(activity, 1), stroke)
    }

    private fun dp(activity: Activity, v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
