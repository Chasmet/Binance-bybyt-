package com.chk.binancebybit

import android.app.AlertDialog
import android.app.Activity
import android.content.Intent
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ProAnalysisPanel(
    private val activity: Activity,
    private val exchangeProvider: () -> String,
    private val workspaceSync: WorkspaceSync
) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val running = AtomicBoolean(true)
    private val client = MarketAnalysisClient()
    private val alertStore = LocalAlertStore(activity)
    private val chartStore = ChartStateStore(activity)
    private val remoteChart = ChartRemoteClient(activity)

    private var session = chartStore.loadCurrent()
    private var interval = session.timeframe
    private var symbol = session.symbol
    private var lastSnapshot: IndicatorSnapshot? = null
    private var autoRefresh = true
    private var lastSnapshotUploadAt = 0L
    private var remotePushScheduled = false

    private lateinit var status: TextView
    private lateinit var price: TextView
    private lateinit var change: TextView
    private lateinit var chart: CandlestickChartView
    private lateinit var tools: LinearLayout
    private lateinit var summary: TextView
    private lateinit var symbolInput: EditText
    private lateinit var autoButton: Button
    private lateinit var autoScaleButton: Button
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

    private val commandRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            executor.execute {
                val cmd = runCatching { remoteChart.pullCommand() }.getOrNull()
                if (cmd != null) main.post { applyRemoteCommand(cmd) }
            }
            main.postDelayed(this, 1_500L)
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
        page.addView(profileRow())
        page.addView(priceCard())

        chart = CandlestickChartView(activity).apply {
            background = rounded(Color.rgb(9, 11, 14), border, 18)
            setIndicators(session.indicators)
            setDrawings(session.drawings)
            applyViewport(session.viewport)
            setOnAlertRequestListener { pair, target -> showCreateAlertDialog(pair, target) }
            setOnStateChangedListener { viewport ->
                session = session.copy(
                    symbol = symbol,
                    timeframe = interval,
                    viewport = viewport,
                    indicators = currentIndicators(),
                    drawings = currentDrawings()
                )
                chartStore.save(session)
                updateAutoScaleButton()
                scheduleRemotePush(false)
            }
        }
        page.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(590)).apply {
            setMargins(0, dp(8), 0, dp(8))
        })

        page.addView(TextView(activity).apply {
            text = "1 doigt = historique • pince = zoom centré • appui long = crosshair • glisse crosshair = inspection • double-tap = reset • glisse axe prix = zoom vertical"
            textSize = 9.5f
            setTextColor(muted)
            setPadding(dp(3), 0, dp(3), dp(8))
        })

        page.addView(twoButtons(
            smallAction("DERNIER PRIX", green) { chart.goToLatest() },
            smallAction("RESET", orange) { chart.resetView() }
        ))
        page.addView(twoButtons(
            smallAction("INDICATEURS", blue) { showIndicatorsDialog() },
            smallAction("PLEIN ÉCRAN", purple) { openFullscreen() }
        ))
        page.addView(twoButtons(
            smallAction("DESSIN CURSEUR", blue) { showDrawingDialog() },
            smallAction("ALARME CURSEUR", purple) {
                val target = chart.selectedTargetPrice()
                if (target == null || target <= 0.0) Toast.makeText(activity, "Place d'abord le crosshair sur un prix.", Toast.LENGTH_SHORT).show()
                else showCreateAlertDialog(symbol, target)
            }
        ))

        autoScaleButton = smallAction("AUTO SCALE", green) {
            chart.setAutoScale(!chart.exportViewport().autoScale)
            updateAutoScaleButton()
        }
        page.addView(twoButtons(
            autoScaleButton,
            smallAction("ACTUALISER", orange) { refresh(true) }
        ))
        updateAutoScaleButton()

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
                success = { Toast.makeText(activity, "Analyse ajoutée au carnet.", Toast.LENGTH_LONG).show() },
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
                main.removeCallbacks(commandRunnable)
                main.post(refreshRunnable)
                main.post(commandRunnable)
            }
            override fun onViewDetachedFromWindow(v: View) {
                running.set(false)
                main.removeCallbacks(refreshRunnable)
                main.removeCallbacks(commandRunnable)
            }
        })
        main.post {
            syncServerStateThenRefresh()
        }
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
            text = "Graphique pro synchronisé APK ↔ MCP ↔ ChatGPT • viewport réel • dessins • crosshair"
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
                changeChart(normalize(symbolInput.text.toString()), interval, true)
            }
        }, LinearLayout.LayoutParams(dp(100), dp(48)).apply { setMargins(dp(7), 0, 0, 0) })
        addView(row)

        val quick = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            val inner = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("BTCUSDC", "ETHUSDC", "RENDERUSDC", "ADAUSDC", "SUIUSDC", "ONDOUSDC", "LINKUSDC", "SOLUSDC", "XRPUSDC").forEach { s ->
                inner.addView(chip(s.removeSuffix("USDC"), s == symbol) { changeChart(s, interval, true) })
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
        listOf("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d", "3d", "1w").forEach { tf ->
            inner.addView(chip(tf.uppercase(Locale.US), tf == interval) { changeChart(symbol, tf, true) })
        }
        timeframesHost.removeAllViews()
        timeframesHost.addView(inner)
    }

    private fun profileRow(): View = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(5)) }
        listOf("SCALP", "INTRADAY", "SWING").forEach { p ->
            row.addView(chip(p, session.profile == p) {
                val prof = chartStore.profile(p, symbol)
                session = prof
                interval = prof.timeframe
                chart.setIndicators(prof.indicators)
                chart.applyViewport(prof.viewport)
                rebuildTimeframes()
                refresh(true)
                scheduleRemotePush(false)
            })
        }
        addView(row)
    }

    private fun priceCard(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(13), dp(10), dp(13), dp(10))
        background = rounded(surface, border, 15)
        val left = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(activity).apply { text = "Connexion marché…"; textSize = 9.5f; setTextColor(muted) }
        price = TextView(activity).apply { text = "—"; textSize = 25f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(text) }
        left.addView(status); left.addView(price)
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        change = TextView(activity).apply {
            text = "—"; textSize = 13f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(11), dp(7), dp(11), dp(7)); background = rounded(surface2, border, 999)
        }
        addView(change)
    }

    private fun changeChart(newSymbol: String, newTimeframe: String, refreshNow: Boolean) {
        symbol = normalize(newSymbol)
        interval = ChartSessionState.normalizeTimeframe(newTimeframe)
        symbolInput.setText(symbol)
        val stored = chartStore.loadFor(symbol, interval)
        session = stored.copy(symbol = symbol, timeframe = interval)
        if (::chart.isInitialized) {
            chart.setIndicators(session.indicators)
            chart.setDrawings(session.drawings)
            chart.applyViewport(session.viewport)
        }
        rebuildTimeframes()
        chartStore.save(session)
        if (refreshNow) refresh(true)
        scheduleRemotePush(false)
    }

    private fun refresh(manual: Boolean) {
        if (!running.get()) return
        val currentExchange = exchangeProvider().uppercase(Locale.US)
        val requested = normalize(symbolInput.text.toString().ifBlank { symbol })
        status.text = "● $currentExchange • ${if (manual) "actualisation…" else "live"}"
        runAsync(
            task = {
                val snap = client.load(currentExchange, requested, interval, 420)
                val overlays = if (currentExchange == "BYBIT") {
                    val store = SecureStore(activity)
                    val key = store.get("bybit_api_key"); val secret = store.get("bybit_api_secret")
                    runCatching { BybitChartOverlayClient(key, secret).load(snap.requestedSymbol) }.getOrDefault(emptyList<ChartTradeMarker>() to emptyList())
                } else emptyList<ChartTradeMarker>() to emptyList()
                Triple(snap, overlays.first, overlays.second)
            },
            success = { result ->
                if (!running.get()) return@runAsync
                val snap = result.first
                val same = lastSnapshot?.requestedSymbol == snap.requestedSymbol && lastSnapshot?.interval == snap.interval
                lastSnapshot = snap
                symbol = snap.requestedSymbol
                chart.setSnapshot(snap, preserveViewport = same)
                if (!same) chart.applyViewport(session.viewport)
                chart.setIndicators(session.indicators)
                chart.setDrawings(session.drawings)
                chart.setTradeMarkers(result.second)
                chart.setOrderLevels(result.third)
                symbolInput.setText(symbol)
                status.text = "● ${snap.exchange} • ${snap.sourceSymbol} • ${snap.interval} • ${snap.candles.size} bougies • MCP synchronisé"
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
                    append("\nLe viewport affiché est synchronisé avec le MCP. Aucun ordre réel n'est exécuté sans confirmation.")
                }
                session = session.copy(symbol = symbol, timeframe = interval, viewport = chart.exportViewport(), indicators = chart.currentIndicators(), drawings = chart.currentDrawings())
                chartStore.save(session)
                pushCurrentState(snap, uploadSnapshot = System.currentTimeMillis() - lastSnapshotUploadAt >= 30_000L)
            },
            failure = {
                status.text = "Connexion impossible • $it"
                status.setTextColor(red)
            }
        )
    }

    private fun syncServerStateThenRefresh() {
        executor.execute {
            val server = runCatching { remoteChart.getServerState() }.getOrNull()
            main.post {
                val state = server?.optJSONObject("state")
                if (state != null && state.length() > 0) {
                    val remoteState = ChartSessionState.fromJson(state)
                    session = remoteState
                    symbol = remoteState.symbol
                    interval = remoteState.timeframe
                    symbolInput.setText(symbol)
                    rebuildTimeframes()
                    chart.setIndicators(remoteState.indicators)
                    chart.setDrawings(remoteState.drawings)
                    chart.applyViewport(remoteState.viewport)
                    chartStore.save(remoteState)
                }
                refresh(true)
            }
        }
    }

    private fun applyRemoteCommand(remote: ChartRemoteCommand) {
        val c = remote.command
        val op = c.optString("op").lowercase(Locale.US)
        val args = c.optJSONObject("args") ?: JSONObject()
        var needsRefresh = false
        try {
            when (op) {
                "set_symbol" -> { symbol = normalize(args.optString("symbol", symbol)); symbolInput.setText(symbol); needsRefresh = true }
                "set_timeframe" -> { interval = ChartSessionState.normalizeTimeframe(args.optString("timeframe", interval)); rebuildTimeframes(); needsRefresh = true }
                "set_indicators" -> {
                    val cfg = ChartIndicatorConfig.fromJson(args.optJSONObject("indicators") ?: args)
                    chart.setIndicators(cfg); session = session.copy(indicators = cfg)
                }
                "set_visible_range" -> {
                    val v = chart.exportViewport().copy(
                        visibleCount = args.optInt("visible_count", chart.exportViewport().visibleCount).coerceIn(12, 600),
                        offsetFromEnd = args.optInt("offset_from_end", chart.exportViewport().offsetFromEnd).coerceAtLeast(0)
                    )
                    chart.applyViewport(v)
                }
                "zoom_in" -> chart.zoomIn()
                "zoom_out" -> chart.zoomOut()
                "pan_left" -> chart.panLeft(args.optInt("candles", maxOf(1, chart.exportViewport().visibleCount / 5)))
                "pan_right" -> chart.panRight(args.optInt("candles", maxOf(1, chart.exportViewport().visibleCount / 5)))
                "go_to_latest" -> chart.goToLatest()
                "reset_view" -> chart.resetView()
                "set_auto_scale" -> chart.setAutoScale(args.optBoolean("enabled", true))
                "set_crosshair" -> chart.setCrosshair(args.optLong("timestamp", 0L).takeIf { it > 0L }, args.optDouble("price", Double.NaN).takeIf { it.isFinite() && it > 0.0 })
                "add_drawing" -> ChartDrawing.fromJson(args.optJSONObject("drawing") ?: args)?.let { chart.addDrawing(it) }
                "update_drawing" -> ChartDrawing.fromJson(args.optJSONObject("drawing") ?: args)?.let { chart.updateDrawing(it) }
                "remove_drawing" -> chart.removeDrawing(args.optString("id"))
                "clear_drawings" -> chart.clearDrawings()
                "set_profile" -> {
                    session = chartStore.profile(args.optString("profile", "INTRADAY"), symbol)
                    symbol = session.symbol; interval = session.timeframe
                    chart.setIndicators(session.indicators); chart.applyViewport(session.viewport)
                    rebuildTimeframes(); needsRefresh = true
                }
            }
            session = session.copy(symbol = symbol, timeframe = interval, viewport = chart.exportViewport(), indicators = chart.currentIndicators(), drawings = chart.currentDrawings())
            chartStore.save(session)
            executor.execute { runCatching { remoteChart.ack(remote.seq) } }
            if (needsRefresh) refresh(true) else scheduleRemotePush(true)
            Toast.makeText(activity, "Graphique piloté par ChatGPT • $op", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(activity, "Commande graphique refusée : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun scheduleRemotePush(withSnapshot: Boolean) {
        if (remotePushScheduled) return
        remotePushScheduled = true
        main.postDelayed({
            remotePushScheduled = false
            lastSnapshot?.let { pushCurrentState(it, withSnapshot) }
        }, 450L)
    }

    private fun pushCurrentState(snap: IndicatorSnapshot, uploadSnapshot: Boolean) {
        session = session.copy(symbol = symbol, timeframe = interval, viewport = chart.exportViewport(), indicators = chart.currentIndicators(), drawings = chart.currentDrawings())
        chartStore.save(session)
        val market = remoteChart.marketJson(snap, session.viewport)
        val png = if (uploadSnapshot) chart.capturePng() else ByteArray(0)
        if (uploadSnapshot && png.isNotEmpty()) lastSnapshotUploadAt = System.currentTimeMillis()
        executor.execute {
            runCatching { remoteChart.pushState(session, market) }
            if (png.isNotEmpty()) runCatching { remoteChart.uploadSnapshot(png) }
        }
    }

    private fun showIndicatorsDialog() {
        val current = chart.currentIndicators()
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(6), dp(24), 0) }
        fun field(label: String, value: String): EditText = EditText(activity).apply { hint = label; setText(value); inputType = InputType.TYPE_CLASS_TEXT }
        val ma = field("MA, ex: 7,14,28", current.maPeriods.joinToString(","))
        val ema = field("EMA, ex: 9,20,50,200", current.emaPeriods.joinToString(","))
        val rsi = field("RSI période", current.rsiPeriod.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val atr = field("ATR période", current.atrPeriod.toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val volume = CheckBox(activity).apply { text = "Volume"; isChecked = current.volume }
        val boll = CheckBox(activity).apply { text = "Bollinger"; isChecked = current.bollinger }
        val macd = CheckBox(activity).apply { text = "MACD"; isChecked = current.macd }
        listOf(ma, ema, rsi, atr, volume, boll, macd).forEach { box.addView(it) }
        AlertDialog.Builder(activity).setTitle("Indicateurs du graphique").setView(box)
            .setPositiveButton("APPLIQUER") { _, _ ->
                fun periods(v: String, fallback: List<Int>) = v.split(',',';',' ').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..500 }.distinct().ifEmpty { fallback }
                val cfg = ChartIndicatorConfig(
                    maPeriods = periods(ma.text.toString(), listOf(7,14,28)),
                    emaPeriods = periods(ema.text.toString(), listOf(9,20,50,200)),
                    volume = volume.isChecked,
                    bollinger = boll.isChecked,
                    rsiPeriod = rsi.text.toString().toIntOrNull()?.coerceIn(2,100) ?: 14,
                    macd = macd.isChecked,
                    atrPeriod = atr.text.toString().toIntOrNull()?.coerceIn(2,100) ?: 14
                )
                chart.setIndicators(cfg); session = session.copy(indicators = cfg); chartStore.save(session); scheduleRemotePush(true)
            }.setNegativeButton("ANNULER", null).show()
    }

    private fun showDrawingDialog() {
        val p = chart.selectedTargetPrice()
        if (p == null || p <= 0.0) { Toast.makeText(activity, "Place le crosshair sur le niveau à tracer.", Toast.LENGTH_SHORT).show(); return }
        val types = arrayOf("support", "resistance", "buy", "sell", "invalidation", "tp", "rebuy", "horizontal")
        var selected = 0
        AlertDialog.Builder(activity).setTitle("Tracer au niveau ${fmt(p)}")
            .setSingleChoiceItems(types, selected) { _, which -> selected = which }
            .setPositiveButton("TRACER") { _, _ ->
                chart.addDrawing(ChartDrawing(id = UUID.randomUUID().toString(), type = types[selected], label = types[selected].uppercase(), price1 = p))
                session = session.copy(drawings = chart.currentDrawings()); chartStore.save(session); scheduleRemotePush(true)
            }.setNegativeButton("ANNULER", null).show()
    }

    private fun openFullscreen() {
        session = session.copy(symbol = symbol, timeframe = interval, viewport = chart.exportViewport(), indicators = chart.currentIndicators(), drawings = chart.currentDrawings())
        chartStore.save(session)
        activity.startActivity(Intent(activity, ChartFullscreenActivity::class.java).apply {
            putExtra("symbol", symbol); putExtra("timeframe", interval)
        })
    }

    private fun updateAutoScaleButton() {
        if (!::autoScaleButton.isInitialized || !::chart.isInitialized) return
        val enabled = chart.exportViewport().autoScale
        autoScaleButton.text = if (enabled) "AUTO SCALE ✓" else "AUTO SCALE OFF"
        autoScaleButton.setTextColor(if (enabled) green else muted)
    }

    private fun renderTools(s: IndicatorSnapshot, executions: Int, orders: Int) {
        tools.removeAllViews(); tools.addView(sectionLabel("Lecture technique"))
        tools.addView(two(toolCard("RSI 14", fmt2(s.rsi14), when { s.rsi14 > 70 -> "Surachat"; s.rsi14 < 30 -> "Survente"; else -> "Neutre" }, blue), toolCard("MACD hist.", fmt(s.macdHistogram), if (s.macdHistogram >= 0) "Momentum +" else "Momentum -", if (s.macdHistogram >= 0) green else red)))
        tools.addView(two(toolCard("EMA 20", fmt(s.ema20), if (s.lastPrice > s.ema20) "Prix au-dessus" else "Prix en dessous", orange), toolCard("EMA 50", fmt(s.ema50), if (s.lastPrice > s.ema50) "Prix au-dessus" else "Prix en dessous", blue)))
        tools.addView(two(toolCard("Bollinger", fmt(s.bbMiddle), "${fmt(s.bbLower)} ↔ ${fmt(s.bbUpper)}", purple), toolCard("ATR 14", fmt(s.atr14), "Volatilité", orange)))
        tools.addView(two(toolCard("Volume relatif", "${fmt2(s.volumeRatio)}x", if (s.volumeRatio > 1.25) "Renforcé" else "Normal/faible", blue), toolCard("Structure", s.trend, "Score ${s.score}/100", if (s.trend == "HAUSSIÈRE") green else if (s.trend == "BAISSIÈRE") red else muted)))
        tools.addView(infoCard("Divergence", s.divergence, if (s.divergence.contains("HAUSSIÈRE")) green else if (s.divergence.contains("BAISSIÈRE")) red else muted))
        tools.addView(infoCard("Pattern", s.pattern, if (s.pattern == "AUCUN") muted else purple))
        tools.addView(infoCard("Marqueurs Bybit", "$executions exécution(s) • $orders ordre(s)", orange))
    }

    private fun showCreateAlertDialog(pair: String, suggested: Double) {
        val field = EditText(activity).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(fmt(suggested)); selectAll() }
        val choices = arrayOf("Prix au-dessus / égal", "Prix en-dessous / égal"); var choice = 1
        AlertDialog.Builder(activity).setTitle("Créer une alarme locale • ${normalize(pair)}").setSingleChoiceItems(choices, choice) { _, which -> choice = which }.setView(field)
            .setMessage("Le téléphone surveille directement Bybit public.")
            .setPositiveButton("CRÉER") { _, _ ->
                val target = field.text.toString().replace(',', '.').toDoubleOrNull()
                if (target == null || target <= 0.0) Toast.makeText(activity, "Prix invalide", Toast.LENGTH_LONG).show()
                else { alertStore.add(normalize(pair), if (choice == 0) "above" else "below", target); if (!alertStore.monitoringEnabled()) MarketWatchService.start(activity); AlertCheckReceiver.checkNow(activity); Toast.makeText(activity, "Alarme créée à ${fmt(target)} USDC", Toast.LENGTH_LONG).show() }
            }.setNegativeButton("ANNULER", null).show()
    }

    private fun toolCard(title: String, value: String, detail: String, accent: Int): View = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(11),dp(9),dp(11),dp(9));background=rounded(surface,border,14);addView(TextView(activity).apply{text=title;textSize=9.5f;setTextColor(muted)});addView(TextView(activity).apply{text=value;textSize=15f;setTypeface(Typeface.DEFAULT,Typeface.BOLD);setTextColor(accent)});addView(TextView(activity).apply{text=detail;textSize=9f;setTextColor(text)}) }
    private fun infoCard(title: String, value: String, accent: Int): View = LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(13),dp(11),dp(13),dp(11));background=rounded(surface,border,14);layoutParams=margin(bottom=7);addView(TextView(activity).apply{text=title;textSize=10.5f;setTypeface(Typeface.DEFAULT,Typeface.BOLD);setTextColor(text)},LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));addView(TextView(activity).apply{text=value;textSize=10.5f;setTextColor(accent);gravity=Gravity.END}) }
    private fun sectionLabel(value:String):View=TextView(activity).apply{text=value;textSize=14f;setTypeface(Typeface.DEFAULT,Typeface.BOLD);setTextColor(text);setPadding(dp(2),dp(10),0,dp(8))}
    private fun two(a:View,b:View):View=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL;addView(a,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply{setMargins(0,0,dp(4),dp(7))});addView(b,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply{setMargins(dp(4),0,0,dp(7))})}
    private fun twoButtons(a:View,b:View):View=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL;addView(a,LinearLayout.LayoutParams(0,dp(45),1f).apply{setMargins(0,0,dp(4),dp(8))});addView(b,LinearLayout.LayoutParams(0,dp(45),1f).apply{setMargins(dp(4),0,0,dp(8))})}
    private fun smallAction(label:String,accent:Int,click:()->Unit):Button=Button(activity).apply{text=label;isAllCaps=false;textSize=9.5f;setTypeface(Typeface.DEFAULT,Typeface.BOLD);setTextColor(text);background=rounded(surface2,accent,13);setOnClickListener{click()}}
    private fun actionButton(label:String,accent:Int,click:()->Unit):Button=Button(activity).apply{text=label;isAllCaps=false;textSize=11f;setTypeface(Typeface.DEFAULT,Typeface.BOLD);setTextColor(text);background=rounded(surface2,accent,14);setOnClickListener{click()};layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48)).apply{setMargins(0,dp(3),0,0)}}
    private fun chip(label:String,active:Boolean=false,click:()->Unit):TextView=TextView(activity).apply{text=label;gravity=Gravity.CENTER;textSize=10f;setTypeface(Typeface.DEFAULT,Typeface.BOLD);setTextColor(if(active)Color.BLACK else text);background=if(active)rounded(orange,orange,999)else rounded(surface2,border,999);setPadding(dp(13),0,dp(13),0);setOnClickListener{click()};layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(36)).apply{setMargins(0,0,dp(5),0)}}
    private fun rounded(fill:Int,stroke:Int,radius:Int):GradientDrawable=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;setColor(fill);cornerRadius=dp(radius).toFloat();if(stroke!=Color.TRANSPARENT)setStroke(dp(1),stroke)}
    private fun margin(bottom:Int=0):LinearLayout.LayoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{setMargins(0,0,0,dp(bottom))}
    private fun dp(v:Int)=(v*activity.resources.displayMetrics.density).toInt()
    private fun normalize(value:String)=ChartSessionState.normalizeSymbol(value)
    private fun fmt2(v:Double)=String.format(Locale.FRANCE,"%.2f",v)
    private fun fmt(v:Double):String=when{v>=1000->String.format(Locale.US,"%.2f",v);v>=100->String.format(Locale.US,"%.3f",v).trimEnd('0').trimEnd('.');v>=1->String.format(Locale.US,"%.5f",v).trimEnd('0').trimEnd('.');else->String.format(Locale.US,"%.8f",v).trimEnd('0').trimEnd('.')}

    private fun <T> runAsync(task:()->T,success:(T)->Unit,failure:(String)->Unit){executor.execute{try{val result=task();main.post{success(result)}}catch(e:Throwable){main.post{failure(e.message?:e.javaClass.simpleName)}}}}
}
