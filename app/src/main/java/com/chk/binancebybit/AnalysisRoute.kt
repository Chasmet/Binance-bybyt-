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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object AnalysisRoute {
    private const val TAG = "chk_realtime_analysis_nav"

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is MainActivityV4) {
                    activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener { augmentAnalysisTab(activity) }
                }
            }
            override fun onActivityResumed(activity: Activity) {
                if (activity !is MainActivityV4) return
                activity.window.decorView.post {
                    if (readStringField(activity, "section") == "ANALYSIS") showAnalysis(activity) else augmentAnalysisTab(activity)
                }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun augmentAnalysisTab(activity: MainActivityV4) {
        runCatching {
            val contentField = MainActivityV4::class.java.getDeclaredField("content").apply { isAccessible = true }
            val content = contentField.get(activity) as? FrameLayout ?: return
            val root = content.parent as? LinearLayout ?: return
            val nav = root.getChildAt(root.childCount - 1) as? LinearLayout ?: return
            if (nav.findViewWithTag<View>(TAG) != null) return
            nav.addView(analysisNavButton(activity), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun analysisNavButton(activity: MainActivityV4): View {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val active = readStringField(activity, "section") == "ANALYSIS"
        return LinearLayout(activity).apply {
            tag = TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(1), dp(4), dp(1), dp(4))
            background = rounded(activity, if (active) Color.rgb(28, 32, 38) else Color.TRANSPARENT, 14)
            addView(TextView(activity).apply {
                text = "⌁"
                textSize = 17f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (active) Color.rgb(176, 126, 255) else Color.rgb(153, 162, 174))
                gravity = Gravity.CENTER
            })
            addView(TextView(activity).apply {
                text = "Analyse"
                textSize = 8f
                setTypeface(Typeface.DEFAULT, if (active) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (active) Color.rgb(246, 247, 249) else Color.rgb(153, 162, 174))
                gravity = Gravity.CENTER
            })
            setOnClickListener { showAnalysis(activity) }
        }
    }

    fun showAnalysis(activity: MainActivityV4) {
        runCatching {
            writeStringField(activity, "section", "ANALYSIS")
            val contentField = MainActivityV4::class.java.getDeclaredField("content").apply { isAccessible = true }
            val content = contentField.get(activity) as FrameLayout
            val root = content.parent as? LinearLayout
            val store = SecureStore(activity)
            content.removeAllViews()
            content.addView(
                RealTimeAnalysisPanel(
                    activity = activity,
                    exchangeProvider = { readStringField(activity, "exchange") ?: "BYBIT" },
                    workspaceSync = WorkspaceSync(activity, store)
                ).build(),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            if (root != null && root.childCount >= 4) {
                replaceHeader(activity, root, 0)
                replaceSelector(activity, root, 1)
                replaceBottomNav(activity, root)
                augmentAnalysisTab(activity)
            }
        }.onFailure {
            android.widget.Toast.makeText(activity, "Analyse indisponible : ${it.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun replaceHeader(activity: MainActivityV4, root: LinearLayout, index: Int) {
        val old = root.getChildAt(index)
        val params = old.layoutParams
        val method = MainActivityV4::class.java.getDeclaredMethod("buildHeader").apply { isAccessible = true }
        val replacement = method.invoke(activity) as View
        root.removeViewAt(index)
        root.addView(replacement, index, params)
    }

    private fun replaceSelector(activity: MainActivityV4, root: LinearLayout, index: Int) {
        val old = root.getChildAt(index)
        val params = old.layoutParams
        val method = MainActivityV4::class.java.getDeclaredMethod("buildExchangeSelector").apply { isAccessible = true }
        val selector = method.invoke(activity) as View
        if (selector is ViewGroup) {
            val buttons = mutableListOf<Button>()
            collectButtons(selector, buttons)
            buttons.forEach { b ->
                when (b.text.toString().uppercase()) {
                    "BINANCE" -> b.setOnClickListener { writeStringField(activity, "exchange", "BINANCE"); showAnalysis(activity) }
                    "BYBIT" -> b.setOnClickListener { writeStringField(activity, "exchange", "BYBIT"); showAnalysis(activity) }
                }
            }
        }
        root.removeViewAt(index)
        root.addView(selector, index, params)
    }

    private fun replaceBottomNav(activity: MainActivityV4, root: LinearLayout) {
        val index = root.childCount - 1
        val old = root.getChildAt(index)
        val params = old.layoutParams
        val method = MainActivityV4::class.java.getDeclaredMethod("buildBottomNav").apply { isAccessible = true }
        val replacement = method.invoke(activity) as View
        root.removeViewAt(index)
        root.addView(replacement, index, params)
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

    private fun rounded(activity: Activity, fill: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius * activity.resources.displayMetrics.density
    }
}
