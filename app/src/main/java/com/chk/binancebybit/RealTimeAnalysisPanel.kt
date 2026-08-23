package com.chk.binancebybit

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RealTimeAnalysisPanel(
    private val activity: Activity,
    private val exchangeProvider: () -> String,
    private val workspaceSync: WorkspaceSync
) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(true)
    private val client = MarketAnalysisClient()
    private var interval = "1h"
    private var symbol = "RENDERUSDC"
    private var lastSnapshot: IndicatorSnapshot? = null

    private lateinit var status: TextView
    private lateinit var price: TextView
    private lateinit var change: TextView
    private lateinit var chart: CandlestickChartView
    private lateinit var tools: LinearLayout
    private lateinit var summary: TextView
    private lateinit var symbolInput: EditText
    private lateinit var autoButton: Button

    private val bg = Color.rgb(10, 12, 15)
    private val surface = Color.rgb(20, 23, 28)
    private val surface2 = Color.rgb(28, 32, 38)
    private val border = Color.rgb(48, 54, 64)
    private val text = Color.rgb(246, 247, 249)
    private val muted = Color.rgb(153, 162, 174)
    private val green = Color.rgb(57, 197, 128)
    private val red = Color.rgb(238, 91, 91)
    private val orange = Color.rgb(245, 142, 30)
    private val blue = Color.rgb(93, 148, 255)
    private val purple = Color.rgb(176, 126, 255)

    private var autoRefresh = true
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            if (autoRefresh) refresh(false)
            main.postDelayed(this, 12_000L)
        }
    }

    fun build(): View {
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(26))
            setBackgroundColor(bg)
        }

        page.addView(title())
        page.addView(controlCard())
        page.addView(timeframes())
        page.addView(priceCard())

        chart = CandlestickChartView(activity).apply { background = rounded(Color.rgb(9, 11, 14), border, 18) }
        page.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(430)).apply {
            setMargins(0, dp(8), 0, dp(10))
        })

        page.addView(legend())
        tools = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        page.addView(tools)

        summary = TextView(activity).apply {
            text = "La synthèse Smart Trader apparaîtra ici."
            textSize = 13f
            setTextColor(text)
            setLineSpacing(0f, 1.24f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(surface, border, 16)
        }
        page.addView(summary, margin(bottom = 10))

        page.addView(actionButton("ENREGISTRER CETTE ANALYSE DANS LES NOTES", purple) {
            val snap = lastSnapshot
            if (snap == null) {
                Toast.makeText(activity, "Aucune analyse disponible.", Toast.LENGTH_SHORT).show()
            } else {
                runAsync(
                    task = { workspaceSync.createNote(exchangeProvider(), "ANALYSE", snap.toSmartTraderNote()) },
                    success = { Toast.makeText(activity, "Analyse enregistrée pour Smart Trader.", Toast.LENGTH_LONG).show() },
                    failure = { Toast.makeText(activity, "Note non enregistrée : $it", Toast.LENGTH_LONG).show() }
                )
            }
        })

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                running.set(true)
                main.removeCallbacks(refreshRunnable)
                main.post(refreshRunnable)
            }
            override fun onViewDetachedFromWindow(v: View) {
                running.set(false)
                main.removeCallbacks(refreshRunnable)
            }
        })
        main.post { refresh(true) }
        return scroll
    }

    private fun title(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(activity).apply {
            text = "Analyse en temps réel"
            textSize = 25f
            setTextColor(text)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        addView(TextView(activity).apply {
            text = "Graphique marché • données publiques ${exchangeProvider()} • aucun ordre automatique"
            textSize = 11f
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(8))
        })
    }

    private fun controlCard(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = rounded(surface, border, 16)

        addView(TextView(activity).apply { text = "Crypto à analyser"; textSize = 11f; setTextColor(muted) })

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        symbolInput = EditText(activity).apply {
            setText(symbol)
            hint = "RENDERUSDC"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(text)
            setHintTextColor(muted)
            textSize = 15f
            background = rounded(surface2, border, 12)
            setPadding(dp(12), 0, dp(12), 0)
        }
        row.addView(symbolInput, LinearLayout.LayoutParams(0, dp(50), 1f))
        row.addView(Button(activity).apply {
            text = "CHARGER"
            textSize = 11f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = rounded(orange, orange, 12)
            setOnClickListener {
                symbol = symbolInput.text.toString().trim().ifBlank { "RENDERUSDC" }
                refresh(true)
            }
        }, LinearLayout.LayoutParams(dp(105), dp(50)).apply { setMargins(dp(8), 0, 0, 0) })
        addView(row)

        val quick = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            val inner = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("BTCUSDC","ETHUSDC","RENDERUSDC","LINKUSDC","FETUSDC","SOLUSDC","XRPUSDC").forEach { s ->
                inner.addView(chip(s.removeSuffix("USDC")) {
                    symbol = s
                    symbolInput.setText(s)
                    refresh(true)
                })
            }
            addView(inner)
        }
        addView(quick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) })

        autoButton = Button(activity).apply {
            text = "● AUTO 12s"
            textSize = 10f
            setTextColor(green)
            background = rounded(surface2, border, 12)
            setOnClickListener {
                autoRefresh = !autoRefresh
                text = if (autoRefresh) "● AUTO 12s" else "○ AUTO PAUSE"
                setTextColor(if (autoRefresh) green else muted)
            }
        }
        addView(autoButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
    }

    private fun timeframes(): View {
        val scroller = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        rebuildTimeframeBar(scroller)
        return scroller
    }

    private fun rebuildTimeframeBar(scroller: HorizontalScrollView) {
        val inner = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(6)) }
        listOf("1m","5m","15m","1h","4h","1d","1w").forEach { tf ->
            inner.addView(chip(tf, active = tf == interval) {
                interval = tf
                refresh(true)
                rebuildTimeframeBar(scroller)
            })
        }
        scroller.removeAllViews()
        scroller.addView(inner)
    }

    private fun priceCard(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(surface, border, 16)

        val left = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(activity).apply { text = "Connexion marché…"; textSize = 10f; setTextColor(muted) }
        price = TextView(activity).apply { text = "—"; textSize = 26f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(text) }
        left.addView(status)
        left.addView(price)
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        change = TextView(activity).apply {
            text = "—"
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(surface2, border, 999)
        }
        addView(change)
    }

    private fun legend(): View = TextView(activity).apply {
        text = "EMA20 • EMA50 • Bollinger • Volume   |   pince pour zoomer • glisse pour remonter l'historique"
        textSize = 9.5f
        setTextColor(muted)
        setPadding(dp(4), 0, dp(4), dp(8))
    }

    private fun refresh(manual: Boolean) {
        if (!running.get()) return
        val currentExchange = exchangeProvider()
        val requested = symbolInput.text.toString().trim().ifBlank { symbol }
        status.text = "● ${currentExchange.uppercase(Locale.US)} • ${if (manual) "actualisation…" else "live"}"
        runAsync(
            task = { client.load(currentExchange, requested, interval) },
            success = { snap ->
                if (!running.get()) return@runAsync
                lastSnapshot = snap
                symbol = snap.requestedSymbol
                chart.setSnapshot(snap)
                status.text = "● ${snap.exchange} • ${snap.sourceSymbol} • ${snap.interval} • ${snap.candles.size} bougies"
                status.setTextColor(green)
                price.text = fmt(snap.lastPrice)
                change.text = "${if (snap.changePct >= 0) "+" else ""}${String.format(Locale.FRANCE, "%.2f", snap.changePct)} %"
                change.setTextColor(if (snap.changePct >= 0) green else red)
                renderTools(snap)
                summary.text = buildString {
                    append("SYNTHÈSE SMART TRADER\n")
                    append("${snap.trend} • score ${snap.score}/100\n\n")
                    append(snap.summary)
                    append("\n\nZone technique : support ${fmt(snap.support)} • résistance ${fmt(snap.resistance)}.")
                    append("\nCette page analyse le marché mais ne passe jamais d'ordre.")
                }
            },
            failure = {
                status.text = "Connexion impossible • $it"
                status.setTextColor(red)
            }
        )
    }

    private fun renderTools(s: IndicatorSnapshot) {
        tools.removeAllViews()
        tools.addView(sectionLabel("Outils professionnels"))
        tools.addView(two(
            toolCard("RSI 14", fmt2(s.rsi14), when { s.rsi14 > 70 -> "Surachat"; s.rsi14 < 30 -> "Survente"; else -> "Zone neutre" }, if (s.rsi14 in 30.0..70.0) blue else orange),
            toolCard("Bollinger", fmt(s.bbMiddle), "Bas ${fmt(s.bbLower)} • Haut ${fmt(s.bbUpper)}", purple)
        ))
        tools.addView(two(
            toolCard("EMA 20", fmt(s.ema20), if (s.lastPrice > s.ema20) "Prix au-dessus" else "Prix en dessous", orange),
            toolCard("EMA 50", fmt(s.ema50), if (s.lastPrice > s.ema50) "Prix au-dessus" else "Prix en dessous", blue)
        ))
        tools.addView(two(
            toolCard("MACD", fmt(s.macdHistogram), if (s.macdHistogram >= 0) "Momentum positif" else "Momentum négatif", if (s.macdHistogram >= 0) green else red),
            toolCard("ATR 14", fmt(s.atr14), "Volatilité moyenne", orange)
        ))
        tools.addView(two(
            toolCard("Volume relatif", "${fmt2(s.volumeRatio)}x", if (s.volumeRatio > 1.25) "Volume renforcé" else "Volume normal/faible", blue),
            toolCard("Structure", s.trend, "Score ${s.score}/100", if (s.trend == "HAUSSIÈRE") green else if (s.trend == "BAISSIÈRE") red else muted)
        ))
        tools.addView(infoCard("Divergence", s.divergence, if (s.divergence.contains("HAUSSIÈRE")) green else if (s.divergence.contains("BAISSIÈRE")) red else muted))
        tools.addView(infoCard("Pattern chandeliers", s.pattern, if (s.pattern == "AUCUN") muted else purple))
        tools.addView(infoCard("Support / Résistance", "${fmt(s.support)} ↔ ${fmt(s.resistance)}", orange))
    }

    private fun toolCard(title: String, value: String, detail: String, accent: Int): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(surface, border, 14)
        addView(TextView(activity).apply { text = title; textSize = 10f; setTextColor(muted) })
        addView(TextView(activity).apply { text = value; textSize = 16f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(accent) })
        addView(TextView(activity).apply { text = detail; textSize = 9.5f; setTextColor(text) })
    }

    private fun infoCard(title: String, value: String, accent: Int): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(surface, border, 14)
        layoutParams = margin(bottom = 8)
        addView(TextView(activity).apply { text = title; textSize = 11f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(text) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(activity).apply { text = value; textSize = 11f; setTextColor(accent); gravity = Gravity.END })
    }

    private fun two(a: View, b: View): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(4), dp(8)) })
        addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, 0, dp(8)) })
    }

    private fun sectionLabel(value: String): View = TextView(activity).apply {
        text = value
        textSize = 14f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(text)
        setPadding(dp(2), dp(6), 0, dp(8))
    }

    private fun chip(label: String, active: Boolean = false, onClick: () -> Unit): View = TextView(activity).apply {
        text = label
        textSize = 11f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(if (active) Color.BLACK else text)
        background = rounded(if (active) orange else surface2, if (active) orange else border, 999)
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { setMargins(0, dp(3), dp(6), dp(3)) }
    }

    private fun actionButton(label: String, color: Int, onClick: () -> Unit): View = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 11f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(Color.BLACK)
        background = rounded(color, color, 14)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun margin(bottom: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(bottom)) }
    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
    private fun fmt(v: Double): String = when {
        v >= 1000 -> String.format(Locale.US, "%.2f", v)
        v >= 1 -> String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
        else -> String.format(Locale.US, "%.7f", v).trimEnd('0').trimEnd('.')
    }
    private fun fmt2(v: Double): String = String.format(Locale.FRANCE, "%.2f", v)

    private fun <T> runAsync(task: () -> T, success: (T) -> Unit, failure: (String) -> Unit) {
        executor.execute {
            try { val result = task(); main.post { success(result) } }
            catch (e: Exception) { main.post { failure(e.message ?: e.toString()) } }
        }
    }
}
