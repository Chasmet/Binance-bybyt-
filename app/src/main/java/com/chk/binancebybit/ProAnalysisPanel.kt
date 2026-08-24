package com.chk.binancebybit

import android.app.AlertDialog
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

class ProAnalysisPanel(
    private val activity: Activity,
    private val exchangeProvider: () -> String,
    private val workspaceSync: WorkspaceSync
) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(true)
    private val client = MarketAnalysisClient()
    private val alertStore = LocalAlertStore(activity)
    private var interval = "1h"
    private var symbol = "RENDERUSDC"
    private var lastSnapshot: IndicatorSnapshot? = null
    private var autoRefresh = true

    private lateinit var status: TextView
    private lateinit var price: TextView
    private lateinit var change: TextView
    private lateinit var chart: CandlestickChartView
    private lateinit var tools: LinearLayout
    private lateinit var summary: TextView
    private lateinit var symbolInput: EditText
    private lateinit var autoButton: Button
    private lateinit var timeframesHost: HorizontalScrollView

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
            setPadding(dp(12), dp(6), dp(12), dp(28))
            setBackgroundColor(bg)
        }

        page.addView(title())
        page.addView(controlCard())
        timeframesHost = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        rebuildTimeframes()
        page.addView(timeframesHost)
        page.addView(priceCard())

        chart = CandlestickChartView(activity).apply {
            background = rounded(Color.rgb(9, 11, 14), border, 18)
            setOnAlertRequestListener { pair, target -> showCreateAlertDialog(pair, target) }
        }
        page.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(520)).apply {
            setMargins(0, dp(8), 0, dp(8))
        })

        page.addView(TextView(activity).apply {
            text = "Pince = zoom • glisse = historique • touche = crosshair • double-tap = reset • appui long = créer une alarme au prix visé"
            textSize = 9.5f
            setTextColor(muted)
            setPadding(dp(3), 0, dp(3), dp(8))
        })

        page.addView(twoButtons(
            smallAction("🔔 ALARME AU CURSEUR", purple) {
                val target = chart.selectedTargetPrice()
                if (target == null || target <= 0.0) Toast.makeText(activity, "Touche d'abord un prix sur le graphique.", Toast.LENGTH_SHORT).show()
                else showCreateAlertDialog(symbol, target)
            },
            smallAction("↻ ACTUALISER", orange) { refresh(true) }
        ))

        tools = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        page.addView(tools)

        summary = TextView(activity).apply {
            text = "La synthèse technique apparaîtra ici."
            textSize = 12.5f
            setTextColor(text)
            setLineSpacing(0f, 1.22f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(surface, border, 16)
        }
        page.addView(summary, margin(bottom = 10))

        page.addView(actionButton("ENREGISTRER CETTE ANALYSE DANS LE CARNET", purple) {
            val snap = lastSnapshot
            if (snap == null) Toast.makeText(activity, "Aucune analyse disponible.", Toast.LENGTH_SHORT).show()
            else runAsync(
                task = { workspaceSync.createNote(exchangeProvider(), "ANALYSE", snap.toSmartTraderNote()) },
                success = { Toast.makeText(activity, "Analyse ajoutée au carnet sans effacer les anciennes notes.", Toast.LENGTH_LONG).show() },
                failure = { Toast.makeText(activity, "Note non enregistrée : $it", Toast.LENGTH_LONG).show() }
            )
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
            text = "Analyse marché"
            textSize = 25f
            setTextColor(text)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        addView(TextView(activity).apply {
            text = "Graphique pro • tactile précis • axes temps/prix • alarmes locales • zéro Render pour la surveillance"
            textSize = 10.5f
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(8))
        })
    }

    private fun controlCard(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = rounded(surface, border, 16)

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        symbolInput = EditText(activity).apply {
            setText(symbol)
            hint = "RENDERUSDC"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextColor(text)
            setHintTextColor(muted)
            textSize = 14f
            background = rounded(surface2, border, 12)
            setPadding(dp(12), 0, dp(12), 0)
        }
        row.addView(symbolInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(Button(activity).apply {
            text = "CHARGER"
            textSize = 10.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = rounded(orange, orange, 12)
            setOnClickListener {
                symbol = normalize(symbolInput.text.toString())
                symbolInput.setText(symbol)
                refresh(true)
            }
        }, LinearLayout.LayoutParams(dp(100), dp(48)).apply { setMargins(dp(7), 0, 0, 0) })
        addView(row)

        val quick = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            val inner = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("BTCUSDC", "ETHUSDC", "RENDERUSDC", "SUIUSDC", "ONDOUSDC", "LINKUSDC", "SOLUSDC", "XRPUSDC").forEach { s ->
                inner.addView(chip(s.removeSuffix("USDC"), s == symbol) {
                    symbol = s
                    symbolInput.setText(s)
                    refresh(true)
                })
            }
            addView(inner)
        }
        addView(quick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(5) })

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
        addView(autoButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
    }

    private fun rebuildTimeframes() {
        val inner = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, dp(5)) }
        listOf("1m", "5m", "15m", "1h", "4h", "1d", "1w").forEach { tf ->
            inner.addView(chip(tf, tf == interval) {
                interval = tf
                rebuildTimeframes()
                refresh(true)
            })
        }
        timeframesHost.removeAllViews()
        timeframesHost.addView(inner)
    }

    private fun priceCard(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(13), dp(10), dp(13), dp(10))
        background = rounded(surface, border, 15)
        val left = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(activity).apply { text = "Connexion marché…"; textSize = 9.5f; setTextColor(muted) }
        price = TextView(activity).apply { text = "—"; textSize = 25f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(text) }
        left.addView(status)
        left.addView(price)
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        change = TextView(activity).apply {
            text = "—"
            textSize = 13f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = rounded(surface2, border, 999)
        }
        addView(change)
    }

    private fun refresh(manual: Boolean) {
        if (!running.get()) return
        val currentExchange = exchangeProvider().uppercase(Locale.US)
        val requested = normalize(symbolInput.text.toString().ifBlank { symbol })
        status.text = "● $currentExchange • ${if (manual) "actualisation…" else "live"}"
        runAsync(
            task = {
                val snap = client.load(currentExchange, requested, interval, 300)
                val overlays = if (currentExchange == "BYBIT") {
                    val store = SecureStore(activity)
                    val key = store.get("bybit_api_key")
                    val secret = store.get("bybit_api_secret")
                    runCatching { BybitChartOverlayClient(key, secret).load(snap.requestedSymbol) }.getOrDefault(emptyList<ChartTradeMarker>() to emptyList())
                } else emptyList<ChartTradeMarker>() to emptyList()
                Triple(snap, overlays.first, overlays.second)
            },
            success = { result ->
                if (!running.get()) return@runAsync
                val snap = result.first
                lastSnapshot = snap
                symbol = snap.requestedSymbol
                chart.setSnapshot(snap)
                chart.setTradeMarkers(result.second)
                chart.setOrderLevels(result.third)
                status.text = "● ${snap.exchange} • ${snap.sourceSymbol} • ${snap.interval} • ${snap.candles.size} bougies"
                status.setTextColor(green)
                price.text = fmt(snap.lastPrice)
                change.text = "${if (snap.changePct >= 0) "+" else ""}${String.format(Locale.FRANCE, "%.2f", snap.changePct)} %"
                change.setTextColor(if (snap.changePct >= 0) green else red)
                renderTools(snap, result.second.size, result.third.size)
                summary.text = buildString {
                    append("SYNTHÈSE SMART TRADER\n")
                    append("${snap.trend} • score ${snap.score}/100\n\n")
                    append(snap.summary)
                    append("\n\nSupport ${fmt(snap.support)} • résistance ${fmt(snap.resistance)}.")
                    append("\n${result.second.size} exécution(s) et ${result.third.size} ordre(s) ouvert(s) visibles sur le graphique Bybit.")
                    append("\nLa page analyse et alerte ; elle ne passe jamais d'ordre automatiquement.")
                }
            },
            failure = {
                status.text = "Connexion impossible • $it"
                status.setTextColor(red)
            }
        )
    }

    private fun renderTools(s: IndicatorSnapshot, executions: Int, orders: Int) {
        tools.removeAllViews()
        tools.addView(sectionLabel("Lecture technique"))
        tools.addView(two(
            toolCard("RSI 14", fmt2(s.rsi14), when { s.rsi14 > 70 -> "Surachat"; s.rsi14 < 30 -> "Survente"; else -> "Neutre" }, blue),
            toolCard("MACD hist.", fmt(s.macdHistogram), if (s.macdHistogram >= 0) "Momentum +" else "Momentum -", if (s.macdHistogram >= 0) green else red)
        ))
        tools.addView(two(
            toolCard("EMA 20", fmt(s.ema20), if (s.lastPrice > s.ema20) "Prix au-dessus" else "Prix en dessous", orange),
            toolCard("EMA 50", fmt(s.ema50), if (s.lastPrice > s.ema50) "Prix au-dessus" else "Prix en dessous", blue)
        ))
        tools.addView(two(
            toolCard("Bollinger", fmt(s.bbMiddle), "${fmt(s.bbLower)} ↔ ${fmt(s.bbUpper)}", purple),
            toolCard("ATR 14", fmt(s.atr14), "Volatilité", orange)
        ))
        tools.addView(two(
            toolCard("Volume relatif", "${fmt2(s.volumeRatio)}x", if (s.volumeRatio > 1.25) "Renforcé" else "Normal/faible", blue),
            toolCard("Structure", s.trend, "Score ${s.score}/100", if (s.trend == "HAUSSIÈRE") green else if (s.trend == "BAISSIÈRE") red else muted)
        ))
        tools.addView(infoCard("Divergence", s.divergence, if (s.divergence.contains("HAUSSIÈRE")) green else if (s.divergence.contains("BAISSIÈRE")) red else muted))
        tools.addView(infoCard("Pattern", s.pattern, if (s.pattern == "AUCUN") muted else purple))
        tools.addView(infoCard("Marqueurs Bybit", "$executions exécution(s) • $orders ordre(s)", orange))
    }

    private fun showCreateAlertDialog(pair: String, suggested: Double) {
        val field = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(fmt(suggested))
            selectAll()
        }
        val choices = arrayOf("Prix au-dessus / égal", "Prix en-dessous / égal")
        var choice = 1
        AlertDialog.Builder(activity)
            .setTitle("Créer une alarme locale • ${normalize(pair)}")
            .setSingleChoiceItems(choices, choice) { _, which -> choice = which }
            .setView(field)
            .setMessage("Le téléphone surveille directement Bybit public. Aucun Render ni API OpenAI n'est utilisé.")
            .setPositiveButton("CRÉER") { _, _ ->
                val target = field.text.toString().replace(',', '.').toDoubleOrNull()
                if (target == null || target <= 0.0) {
                    Toast.makeText(activity, "Prix invalide", Toast.LENGTH_LONG).show()
                } else {
                    alertStore.add(normalize(pair), if (choice == 0) "above" else "below", target)
                    if (!alertStore.monitoringEnabled()) MarketWatchService.start(activity)
                    AlertCheckReceiver.checkNow(activity)
                    Toast.makeText(activity, "Alarme locale créée à ${fmt(target)} USDC", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("ANNULER", null)
            .show()
    }

    private fun toolCard(title: String, value: String, detail: String, accent: Int): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(11), dp(9), dp(11), dp(9))
        background = rounded(surface, border, 14)
        addView(TextView(activity).apply { text = title; textSize = 9.5f; setTextColor(muted) })
        addView(TextView(activity).apply { text = value; textSize = 15f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(accent) })
        addView(TextView(activity).apply { text = detail; textSize = 9f; setTextColor(text) })
    }

    private fun infoCard(title: String, value: String, accent: Int): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(13), dp(11), dp(13), dp(11))
        background = rounded(surface, border, 14)
        layoutParams = margin(bottom = 7)
        addView(TextView(activity).apply { text = title; textSize = 10.5f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(text) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(activity).apply { text = value; textSize = 10.5f; setTextColor(accent); gravity = Gravity.END })
    }

    private fun sectionLabel(value: String): View = TextView(activity).apply {
        text = value
        textSize = 14f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(text)
        setPadding(dp(2), dp(10), 0, dp(8))
    }

    private fun two(a: View, b: View): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(4), dp(7)) })
        addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, 0, dp(7)) })
    }

    private fun twoButtons(a: View, b: View): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, dp(45), 1f).apply { setMargins(0, 0, dp(4), dp(8)) })
        addView(b, LinearLayout.LayoutParams(0, dp(45), 1f).apply { setMargins(dp(4), 0, 0, dp(8)) })
    }

    private fun smallAction(label: String, accent: Int, click: () -> Unit): Button = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 9.5f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(text)
        background = rounded(surface2, accent, 13)
        setOnClickListener { click() }
    }

    private fun actionButton(label: String, accent: Int, click: () -> Unit): Button = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 11f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(text)
        background = rounded(surface2, accent, 14)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { setMargins(0, dp(3), 0, 0) }
    }

    private fun chip(label: String, active: Boolean = false, click: () -> Unit): TextView = TextView(activity).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 10f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(if (active) Color.BLACK else text)
        background = if (active) rounded(orange, orange, 999) else rounded(surface2, border, 999)
        setPadding(dp(13), 0, dp(13), 0)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { setMargins(0, 0, dp(5), 0) }
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun margin(bottom: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        setMargins(0, 0, 0, dp(bottom))
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private fun normalize(value: String): String {
        val raw = value.trim().uppercase(Locale.US).replace("/", "").replace("-", "")
        return when {
            raw.endsWith("USDC") -> raw
            raw.endsWith("USDT") -> raw.removeSuffix("USDT") + "USDC"
            raw.isBlank() -> "RENDERUSDC"
            else -> raw + "USDC"
        }
    }
    private fun fmt2(v: Double) = String.format(Locale.FRANCE, "%.2f", v)
    private fun fmt(v: Double): String = when {
        v >= 1000 -> String.format(Locale.US, "%.2f", v)
        v >= 100 -> String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
        v >= 1 -> String.format(Locale.US, "%.5f", v).trimEnd('0').trimEnd('.')
        else -> String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
    }

    private fun <T> runAsync(task: () -> T, success: (T) -> Unit, failure: (String) -> Unit) {
        executor.execute {
            try {
                val result = task()
                main.post { success(result) }
            } catch (e: Throwable) {
                main.post { failure(e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}
