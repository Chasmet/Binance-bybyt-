package com.chk.binancebybit

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class RemoteAlertSyncCard(private val activity: Activity) {
    fun build(): View {
        val status = TextView(activity).apply {
            text = "Alarmes MCP → téléphone"
            textSize = 11f
            setTextColor(Color.rgb(153, 162, 174))
        }
        val button = Button(activity).apply {
            text = "SYNCHRONISER MCP"
            textSize = 10.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = rounded(Color.rgb(176, 126, 255), Color.TRANSPARENT, 12)
        }

        button.setOnClickListener {
            button.isEnabled = false
            status.text = "Synchronisation en cours…"
            Thread {
                try {
                    RemoteAlertClient(activity).syncIntoLocal()
                    val refreshed = LocalAlertStore(activity)
                    val total = refreshed.list().size
                    val active = refreshed.activeCount()
                    if (active > 0 && !refreshed.monitoringEnabled()) {
                        runCatching { MarketWatchService.start(activity) }
                    }
                    activity.runOnUiThread {
                        status.text = "$total alarme(s) importée(s) • $active active(s)"
                        Toast.makeText(activity, "$total alarme(s) synchronisée(s)", Toast.LENGTH_SHORT).show()
                        if (activity is MainActivityV4) ExperienceRoute.refreshCurrent(activity)
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        status.text = "Échec : ${e.message ?: "erreur inconnue"}"
                        button.isEnabled = true
                        Toast.makeText(activity, e.message ?: "Échec synchronisation alarmes", Toast.LENGTH_LONG).show()
                    }
                }
            }.apply {
                name = "CHK-ForceAlertSync"
                isDaemon = true
                start()
            }
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(20, 23, 28), Color.rgb(48, 54, 64), 16)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
            addView(TextView(activity).apply {
                text = "Synchronisation CHK Crypto"
                textSize = 14f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.rgb(246, 247, 249))
            })
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
                bottomMargin = dp(8)
            })
            addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
