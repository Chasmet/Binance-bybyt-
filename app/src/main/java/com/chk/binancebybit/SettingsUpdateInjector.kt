package com.chk.binancebybit

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
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

object SettingsUpdateInjector {
    private const val TAG = "chk_settings_update_tab_v095"
    private val installed = AtomicBoolean(false)
    private val listeners = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()

    fun install(app: Application) {
        if (!installed.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is MainActivityV4 || listeners.containsKey(activity)) return
                val decor = activity.window.decorView
                val listener = ViewTreeObserver.OnGlobalLayoutListener { injectIfSettingsVisible(activity) }
                listeners[activity] = listener
                decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
                decor.post { injectIfSettingsVisible(activity) }
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

    private fun injectIfSettingsVisible(activity: MainActivityV4) {
        if (activity.isFinishing || activity.isDestroyed) return
        val title = findText(activity.window.decorView, "Réglages") ?: return
        val page = pageRoot(title) ?: return
        if (page.findViewWithTag<View>(TAG) != null) return

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val surface = Color.rgb(20, 23, 28)
        val surface2 = Color.rgb(28, 32, 38)
        val border = Color.rgb(48, 54, 64)
        val text = Color.rgb(246, 247, 249)
        val muted = Color.rgb(153, 162, 174)
        val yellow = Color.rgb(240, 185, 11)

        fun bg(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

        val row = LinearLayout(activity).apply {
            tag = TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = bg(surface, border, 16)
        }

        val connection = Button(activity).apply {
            text = "Connexion API"
            isAllCaps = false
            textSize = 12f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(text)
            background = bg(surface2, Color.TRANSPARENT, 12)
            isClickable = false
            isFocusable = false
        }
        row.addView(connection, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            setMargins(0, 0, dp(3), 0)
        })

        val available = InAppUpdateManager.cachedRelease(activity)
        val update = Button(activity).apply {
            text = if (available != null) "Mise à jour • v${available.version}" else "Mise à jour"
            isAllCaps = false
            textSize = 12f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(if (available != null) Color.BLACK else yellow)
            background = if (available != null) bg(yellow, Color.TRANSPARENT, 12) else bg(surface2, border, 12)
            setOnClickListener {
                activity.startActivity(Intent(activity, UpdateCenterActivity::class.java))
            }
        }
        row.addView(update, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            setMargins(dp(3), 0, 0, 0)
        })

        val insertAt = if (page.childCount > 0) 1.coerceAtMost(page.childCount) else 0
        page.addView(row, insertAt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            setMargins(0, dp(4), 0, dp(12))
        })
    }

    private fun findText(view: View, prefix: String): TextView? {
        if (view is TextView && view.text?.toString()?.startsWith(prefix) == true) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findText(view.getChildAt(i), prefix)?.let { return it }
            }
        }
        return null
    }

    private fun pageRoot(view: View): LinearLayout? {
        var p: ViewParent? = view.parent
        var lastLinear: LinearLayout? = null
        while (p != null) {
            if (p is LinearLayout) lastLinear = p
            if (p is ScrollView) {
                val child = if (p.childCount > 0) p.getChildAt(0) else null
                return child as? LinearLayout ?: lastLinear
            }
            p = p.parent
        }
        return null
    }
}
