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

object HomeOrderBookHunterInjector {
    private const val TAG = "chk_orderbook_hunter_home_v099"
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
        val button = Button(activity).apply {
            tag = TAG
            text = "ORDERBOOK HUNTER - carnet temps reel"
            isAllCaps = false
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.rgb(245, 142, 30))
                cornerRadius = dp(16).toFloat()
            }
            setOnClickListener { activity.startActivity(Intent(activity, OrderBookHunterActivity::class.java)) }
        }
        val titleIndex = page.indexOfChild(title)
        val insertAt = (titleIndex + 2).coerceIn(0, page.childCount)
        page.addView(button, insertAt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            setMargins(0, dp(6), 0, dp(6))
        })
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
