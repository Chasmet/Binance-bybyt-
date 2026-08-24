package com.chk.binancebybit

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class OrdersHistoryPanel(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("chk_workspace", Activity.MODE_PRIVATE)
    private val bg = Color.rgb(10, 12, 15)
    private val surface = Color.rgb(20, 23, 28)
    private val border = Color.rgb(48, 54, 64)
    private val text = Color.rgb(246, 247, 249)
    private val muted = Color.rgb(153, 162, 174)
    private val orange = Color.rgb(245, 142, 30)

    fun build(): View {
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(28))
            setBackgroundColor(bg)
        }
        page.addView(TextView(activity).apply {
            text = "Historique Bybit"
            textSize = 25f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(text)
        })
        page.addView(TextView(activity).apply {
            text = "Exécutions Spot, PRU estimé et historique déjà conservé par CHK Crypto"
            textSize = 10.5f
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(10))
        })

        val raw = prefs.getString("bybit_history", "") ?: ""
        if (raw.isBlank()) {
            page.addView(card("Aucun historique local", "Passe par Classique → Actifs et synchronise Bybit. La mise à jour ne supprime aucune donnée existante."))
        } else {
            raw.split("\n\n").map { it.trim() }.filter { it.isNotBlank() }.forEach { block ->
                val lines = block.lines()
                if (lines.size == 1 && (block.contains("PRU ESTIMÉ") || block.contains("DERNIÈRES"))) {
                    page.addView(TextView(activity).apply {
                        text = block
                        textSize = 14f
                        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                        setTextColor(orange)
                        setPadding(dp(2), dp(12), 0, dp(7))
                    })
                } else {
                    page.addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(13), dp(12), dp(13), dp(12))
                        background = rounded(surface, border, 14)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(7)) }
                        lines.forEachIndexed { index, line ->
                            addView(TextView(activity).apply {
                                text = line
                                textSize = if (index == 0) 12f else 10.5f
                                setTypeface(Typeface.DEFAULT, if (index == 0) Typeface.BOLD else Typeface.NORMAL)
                                setTextColor(if (index == 0) text else muted)
                            })
                        }
                    })
                }
            }
        }

        return ScrollView(activity).apply {
            isFillViewport = true
            addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun card(title: String, body: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(18), dp(16), dp(18))
        background = rounded(surface, border, 16)
        addView(TextView(activity).apply { text = title; textSize = 14f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(text) })
        addView(TextView(activity).apply { text = body; textSize = 10.5f; setTextColor(muted); setPadding(0, dp(4), 0, 0) })
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
