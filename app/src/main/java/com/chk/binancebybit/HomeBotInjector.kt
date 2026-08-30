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
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

object HomeBotInjector {
    private const val TAG = "chk_home_bot_group_v097"
    private val installed = AtomicBoolean(false)
    private val listeners = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()

    fun install(app: Application) {
        if (!installed.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is MainActivityV4 || listeners.containsKey(activity)) return
                val decor = activity.window.decorView
                val listener = ViewTreeObserver.OnGlobalLayoutListener { inject(activity) }
                listeners[activity] = listener
                decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
                decor.post { inject(activity) }
            }
            override fun onActivityDestroyed(activity: Activity) {
                val listener = listeners.remove(activity) ?: return
                val observer = activity.window.decorView.viewTreeObserver
                if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    private fun inject(activity: MainActivityV4) {
        if (activity.isFinishing || activity.isDestroyed) return
        val title = findText(activity.window.decorView, "Actions rapides") ?: return
        val page = pageRoot(title) ?: return
        if (page.findViewWithTag<View>(TAG) != null) return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val yellow = Color.rgb(240,185,11)
        val green = Color.rgb(57,197,128)
        val autoPolicy = AutoTradePolicyStore(activity)

        fun button(label: String, color: Int, onClick: () -> Unit) = Button(activity).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color)
                cornerRadius = dp(16).toFloat()
            }
            setOnClickListener { onClick() }
        }

        val group = LinearLayout(activity).apply {
            tag = TAG
            orientation = LinearLayout.VERTICAL
            addView(button("🤖  BOT CHK • surveillance autonome", yellow) {
                activity.startActivity(Intent(activity, BotActivity::class.java))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                setMargins(0, dp(8), 0, dp(5))
            })
            addView(button(
                if (autoPolicy.enabled()) "⚡  AUTO-TRADE • ACTIF" else "⚡  AUTO-TRADE • configurer",
                if (autoPolicy.enabled()) green else Color.rgb(225, 230, 238)
            ) {
                activity.startActivity(Intent(activity, AutoTradeActivity::class.java))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                setMargins(0, dp(5), 0, dp(10))
            })
        }

        val titleIndex = page.indexOfChild(title)
        val insertAt = (titleIndex + 2).coerceIn(0, page.childCount)
        page.addView(group, insertAt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun findText(view: View, prefix: String): TextView? {
        if (view is TextView && view.text?.toString()?.startsWith(prefix) == true) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findText(view.getChildAt(i), prefix)?.let { return it }
        return null
    }

    private fun pageRoot(view: View): LinearLayout? {
        var p: ViewParent? = view.parent
        var last: LinearLayout? = null
        while (p != null) {
            if (p is LinearLayout) last = p
            if (p is ScrollView) return (if (p.childCount > 0) p.getChildAt(0) else null) as? LinearLayout ?: last
            p = p.parent
        }
        return last
    }
}
