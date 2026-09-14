package com.chk.binancebybit

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ModeSelectActivity : Activity() {
    private val bg = Color.rgb(10, 12, 15)
    private val surface = Color.rgb(20, 23, 28)
    private val border = Color.rgb(48, 54, 64)
    private val text = Color.rgb(246, 247, 249)
    private val muted = Color.rgb(153, 162, 174)
    private val yellow = Color.rgb(240, 185, 11)
    private val green = Color.rgb(57, 197, 128)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        requestNotifications()
        if (TrackingStore(this).enabled()) runCatching { MarketWatchService.start(this) }
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(34))
        }
        scroll.addView(root)

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@ModeSelectActivity).apply {
                setImageResource(R.drawable.app_icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(58), dp(58)))
            addView(LinearLayout(this@ModeSelectActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(label("CHK Crypto", 28f, text, true))
                addView(label("Choisis ton espace", 13f, muted, false))
            })
        })

        root.addView(label("Deux espaces séparés. Classique conserve ton application actuelle ; Tracking surveille uniquement les cryptos réellement détenues, même écran éteint quand Internet est disponible.", 14f, muted, false).apply {
            setPadding(0, dp(18), 0, dp(18))
            setLineSpacing(0f, 1.18f)
        })

        root.addView(modeCard(
            title = "📊  CLASSIQUE",
            subtitle = "Portefeuille • Analyse • Ordres • Auto-Trade • Notes",
            body = "Ouvre CHK Crypto exactement comme aujourd'hui. Aucune fonction existante n'est supprimée.",
            accent = yellow,
            buttonText = "Ouvrir Classique"
        ) { startActivity(Intent(this, MainActivityV4::class.java)) })

        root.addView(modeCard(
            title = "🎯  TRACKING",
            subtitle = "Wall Tracker • Fingerprints • Binance ↔ Bybit • Carnet Tracking",
            body = "Les carnets publics sont analysés directement sur le téléphone. Les flux bruts ne passent pas par Render. Le MCP reçoit seulement un résumé compact.",
            accent = green,
            buttonText = "Ouvrir Tracking"
        ) {
            TrackingStore(this).setEnabled(true)
            MarketWatchService.start(this)
            startActivity(Intent(this, TrackingActivity::class.java))
        })

        val rt = getSharedPreferences("chk_tracking_runtime", MODE_PRIVATE)
        val count = rt.getInt("tracked_asset_count", TrackingStore(this).heldAssets().size)
        val b = rt.getBoolean("binance_connected", false)
        val y = rt.getBoolean("bybit_connected", false)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(surface, border, 18)
            addView(label("Tracking de fond", 14f, text, true))
            addView(label("${if (TrackingStore(this@ModeSelectActivity).enabled()) "ACTIF" else "ARRÊTÉ"} • $count actif(s) • Binance ${if (b) "✓" else "…"} • Bybit ${if (y) "✓" else "…"}", 12f, if (b || y) green else muted, false).apply { setPadding(0, dp(5), 0, 0) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        return scroll
    }

    private fun modeCard(title: String, subtitle: String, body: String, accent: Int, buttonText: String, click: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(surface, accent, 22)
            addView(label(title, 21f, accent, true))
            addView(label(subtitle, 13f, text, true).apply { setPadding(0, dp(7), 0, 0) })
            addView(label(body, 12f, muted, false).apply { setPadding(0, dp(8), 0, dp(14)); setLineSpacing(0f, 1.18f) })
            addView(Button(this@ModeSelectActivity).apply {
                text = buttonText
                isAllCaps = false
                textSize = 14f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.BLACK)
                background = rounded(accent, Color.TRANSPARENT, 15)
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) } }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9010)
        }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
}
