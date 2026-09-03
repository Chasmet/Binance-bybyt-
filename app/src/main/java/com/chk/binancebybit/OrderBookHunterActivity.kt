package com.chk.binancebybit

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.activity.ComponentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class OrderBookHunterActivity : ComponentActivity() {
    private lateinit var db: OrderBookHunterDb
    private val handler = Handler(Looper.getMainLooper())
    private var selectedSymbol: String = ""
    private lateinit var watchStrip: LinearLayout
    private lateinit var scanStatus: TextView
    private lateinit var detailContainer: LinearLayout
    private lateinit var symbolInput: EditText
    private var marketCache: List<HunterMarket> = emptyList()

    private val bg = Color.rgb(10, 12, 16)
    private val surface = Color.rgb(20, 23, 28)
    private val surface2 = Color.rgb(29, 33, 40)
    private val border = Color.rgb(49, 55, 66)
    private val text = Color.rgb(244, 246, 249)
    private val muted = Color.rgb(160, 169, 181)
    private val orange = Color.rgb(245, 142, 30)
    private val yellow = Color.rgb(240, 185, 11)
    private val green = Color.rgb(57, 197, 128)
    private val red = Color.rgb(242, 96, 96)

    private val refresh = object : Runnable {
        override fun run() {
            renderWatches()
            renderDetail()
            handler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = OrderBookHunterDb(this)
        selectedSymbol = intent.getStringExtra(OrderBookHunterService.EXTRA_SYMBOL).orEmpty()
        setContentView(buildUi())
        renderWatches()
        renderDetail()
        scanAllBybitMarkets(silent = true)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
            addView(Button(this@OrderBookHunterActivity).apply {
                text = "‹"
                textSize = 24f
                setTextColor(text)
                background = transparentRounded()
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(this@OrderBookHunterActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, 0, 0)
                addView(TextView(this@OrderBookHunterActivity).apply {
                    text = "CHK OrderBook Hunter"
                    textSize = 21f
                    setTextColor(text)
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                })
                addView(TextView(this@OrderBookHunterActivity).apply {
                    text = "Bot 2 indépendant • mémoire temporelle des carnets Bybit EU Spot"
                    textSize = 10.5f
                    setTextColor(muted)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        val scroll = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(28))
        }
        scroll.addView(page)

        page.addView(card().apply {
            addView(sectionTitle("Traquer une crypto"))
            addView(TextView(this@OrderBookHunterActivity).apply {
                text = "Le Hunter observe et mémorise. Il ne passe jamais d'ordre BUY/SELL. 20 marchés maximum en simultané pour protéger batterie et performances."
                textSize = 11f
                setTextColor(muted)
                setPadding(0, dp(4), 0, dp(10))
            })
            symbolInput = EditText(this@OrderBookHunterActivity).apply {
                hint = "Ex. SKRUSDC"
                setHintTextColor(Color.rgb(110, 120, 132))
                setTextColor(text)
                setSingleLine(true)
                textSize = 15f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(surface2, border, 13)
            }
            addView(symbolInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { setMargins(0, 0, 0, dp(8)) })
            addView(actionButton("TRАQUER CETTE CRYPTO", orange) {
                startManualWatch()
            })
            addView(actionButton("SCANNER TOUT BYBIT EU • SPOT USDC", yellow) {
                scanAllBybitMarkets(silent = false)
            }, top = 8)
            scanStatus = TextView(this@OrderBookHunterActivity).apply {
                text = "Scan global Bybit EU en attente…"
                textSize = 10.5f
                setTextColor(muted)
                setPadding(2, dp(8), 2, 0)
            }
            addView(scanStatus)
        })

        page.addView(TextView(this).apply {
            text = "CRYPTOS TRAQUÉES"
            textSize = 11f
            setTextColor(muted)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(4), dp(16), 0, dp(7))
        })
        val horizontal = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        watchStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        horizontal.addView(watchStrip)
        page.addView(horizontal, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))

        detailContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(detailContainer)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun startManualWatch() {
        val symbol = runCatching { OrderBookHunterStore.normalizeSymbol(symbolInput.text?.toString().orEmpty()) }
            .getOrElse { Toast.makeText(this, "Symbole invalide", Toast.LENGTH_SHORT).show(); return }
        if (!db.isWatching(symbol) && db.watches().size >= OrderBookHunterWebSocket.MAX_SYMBOLS) {
            Toast.makeText(this, "Maximum ${OrderBookHunterWebSocket.MAX_SYMBOLS} marchés simultanés", Toast.LENGTH_LONG).show()
            return
        }
        selectedSymbol = symbol
        symbolInput.setText(symbol)
        OrderBookHunterService.startWatch(this, symbol)
        Toast.makeText(this, "$symbol • surveillance activée", Toast.LENGTH_SHORT).show()
        renderWatches()
        renderDetail()
    }

    private fun scanAllBybitMarkets(silent: Boolean) {
        scanStatus.text = "Scan de toutes les paires CRYPTO/USDC Bybit EU…"
        Thread {
            val result = runCatching { OrderBookHunterMarketScanner().scanAllUsdcMarkets() }
            runOnUiThread {
                result.onSuccess { markets ->
                    marketCache = markets
                    val active = db.watches().map { it.symbol }.toSet()
                    val untracked = markets.count { it.symbol !in active }
                    scanStatus.text = "${markets.size} marchés Spot USDC scannés • $untracked non traqués • ${active.size} traqués en temps réel"
                    if (!silent) showMarketScannerDialog(markets)
                }.onFailure {
                    scanStatus.text = "Scan Bybit indisponible : ${it.message ?: "réseau"}"
                    if (!silent) Toast.makeText(this, scanStatus.text, Toast.LENGTH_LONG).show()
                }
            }
        }.apply { name = "CHK-Hunter-Market-Scanner"; isDaemon = true; start() }
    }

    private fun showMarketScannerDialog(markets: List<HunterMarket>) {
        val items = markets.take(120).map { m ->
            val sign = if (m.change24hPct >= 0) "+" else ""
            "${m.symbol}   ${fmtPrice(m.lastPrice)}   $sign${String.format(Locale.US, "%.1f", m.change24hPct)}%   vol ${fmtMoney(m.turnover24h)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bybit EU • ${markets.size} marchés USDC")
            .setItems(items) { _, which ->
                val market = markets[which]
                symbolInput.setText(market.symbol)
                selectedSymbol = market.symbol
                renderDetail()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun renderWatches() {
        if (!::watchStrip.isInitialized) return
        watchStrip.removeAllViews()
        val watches = db.watches()
        if (selectedSymbol.isBlank() && watches.isNotEmpty()) selectedSymbol = watches.first().symbol
        watches.forEach { w ->
            val status = OrderBookHunterStore.get(w.symbol)
            val score = status?.anomalyScore
            val selected = w.symbol == selectedSymbol
            watchStrip.addView(Button(this).apply {
                text = if (score == null) w.symbol else "${w.symbol}  $score"
                isAllCaps = false
                textSize = 11.5f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (selected) Color.BLACK else text)
                background = rounded(if (selected) orange else surface2, if (selected) orange else border, 14)
                setOnClickListener { selectedSymbol = w.symbol; symbolInput.setText(w.symbol); renderWatches(); renderDetail() }
            }, LinearLayout.LayoutParams(dp(126), dp(48)).apply { setMargins(0, 0, dp(7), 0) })
        }
        if (watches.isEmpty()) {
            watchStrip.addView(TextView(this).apply {
                text = "Aucune crypto suivie. Ajoute SKRUSDC ou choisis un marché depuis le scan."
                textSize = 11f
                setTextColor(muted)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        }
    }

    private fun renderDetail() {
        if (!::detailContainer.isInitialized) return
        detailContainer.removeAllViews()
        if (selectedSymbol.isBlank()) return
        val symbol = runCatching { OrderBookHunterStore.normalizeSymbol(selectedSymbol) }.getOrNull() ?: return
        val watching = db.isWatching(symbol)
        val status = OrderBookHunterStore.get(symbol)
        val events = db.events(symbol, 100)
        val recent30m = db.events(symbol, 500, System.currentTimeMillis() - 30L * 60L * 1000L)

        detailContainer.addView(card(topMargin = 14).apply {
            addView(LinearLayout(this@OrderBookHunterActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@OrderBookHunterActivity).apply {
                    text = symbol
                    textSize = 20f
                    setTextColor(text)
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@OrderBookHunterActivity).apply {
                    text = if (watching) "● ACTIF" else "ARRÊTÉ"
                    textSize = 12f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(if (watching) green else muted)
                })
            })
            if (status == null) {
                addView(TextView(this@OrderBookHunterActivity).apply {
                    text = if (watching) "Connexion WebSocket / snapshot Bybit en cours…" else "Cette paire n'est pas actuellement traquée."
                    textSize = 12f
                    setTextColor(muted)
                    setPadding(0, dp(10), 0, 0)
                })
            } else {
                addView(TextView(this@OrderBookHunterActivity).apply {
                    text = "${status.anomalyScore}/100 • ${status.classification}"
                    textSize = 18f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(scoreColor(status.anomalyScore))
                    setPadding(0, dp(8), 0, dp(3))
                })
                addView(TextView(this@OrderBookHunterActivity).apply {
                    text = HunterClassification.safeExplanation(status.anomalyScore)
                    textSize = 11f
                    setTextColor(muted)
                })
                addView(metrics(status))
            }
        })

        if (status != null) {
            detailContainer.addView(card(topMargin = 10).apply {
                addView(sectionTitle("Pression du carnet visible"))
                status.imbalances.forEach { i ->
                    addView(TextView(this@OrderBookHunterActivity).apply {
                        text = "±${i.distancePercent}%   BUY ${i.buyPressure.toInt()}%   •   SELL ${i.sellPressure.toInt()}%"
                        textSize = 12f
                        setTextColor(if (i.buyPressure >= 60) green else if (i.buyPressure <= 40) red else text)
                        setPadding(0, dp(5), 0, 0)
                    })
                }
                addView(TextView(this@OrderBookHunterActivity).apply {
                    text = "Pression calculée uniquement sur les ordres visibles : ce n'est pas une garantie de direction."
                    textSize = 10f
                    setTextColor(muted)
                    setPadding(0, dp(7), 0, 0)
                })
            })
            detailContainer.addView(wallsCard("TOP MURS BUY", status.bidWalls, green))
            detailContainer.addView(wallsCard("TOP MURS SELL", status.askWalls, red))
        }

        detailContainer.addView(card(topMargin = 10).apply {
            addView(sectionTitle("30 dernières minutes"))
            val types = listOf(
                HunterEventType.LARGE_WALL to "Murs créés",
                HunterEventType.WALL_DISAPPEARED to "Murs disparus",
                HunterEventType.WALL_RETREAT to "Murs reculés",
                HunterEventType.WALL_CHASING_PRICE to "Murs qui suivent le prix",
                HunterEventType.WALL_CANCELLED_NEAR_TOUCH to "Annulations près du contact",
                HunterEventType.WALL_ABSORPTION to "Absorptions réelles probables",
                HunterEventType.WALL_REFILL to "Refills",
                HunterEventType.ORDERBOOK_SWEEP to "Sweeps"
            )
            types.forEach { (type, label) ->
                val n = recent30m.count { it.type == type }
                addView(TextView(this@OrderBookHunterActivity).apply {
                    text = "$label : $n"
                    textSize = 11.5f
                    setTextColor(text)
                    setPadding(0, dp(3), 0, 0)
                })
            }
        })

        detailContainer.addView(card(topMargin = 10).apply {
            addView(sectionTitle("Timeline prix / murs"))
            addView(OrderBookHunterTimelineView(this@OrderBookHunterActivity).apply {
                setBackgroundColor(Color.rgb(14, 17, 21))
                setEvents(events.reversed())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { setMargins(0, dp(8), 0, 0) })
        })

        detailContainer.addView(card(topMargin = 10).apply {
            addView(sectionTitle("Carnet de bord OrderBook Hunter"))
            val noteInput = EditText(this@OrderBookHunterActivity).apply {
                hint = "Note sur $symbol…"
                setHintTextColor(Color.rgb(110, 120, 132))
                setTextColor(text)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(surface2, border, 13)
            }
            addView(noteInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { setMargins(0, dp(8), 0, dp(7)) })
            addView(actionButton("AJOUTER AU CARNET DE BORD", yellow) {
                val note = noteInput.text?.toString().orEmpty().trim()
                if (note.isNotBlank()) {
                    OrderBookHunterService.addNote(this@OrderBookHunterActivity, symbol, note, "USER")
                    noteInput.setText("")
                    Toast.makeText(this@OrderBookHunterActivity, "Note enregistrée", Toast.LENGTH_SHORT).show()
                }
            })
            val notes = db.notes(symbol, 8)
            if (notes.isNotEmpty()) addView(TextView(this@OrderBookHunterActivity).apply {
                text = notes.joinToString("\n\n") { n -> "${formatTime(n.createdAt)} • ${n.author}\n${n.text}" }
                textSize = 10.5f
                setTextColor(muted)
                setPadding(0, dp(9), 0, 0)
            })
        })

        detailContainer.addView(card(topMargin = 10).apply {
            addView(sectionTitle("Historique événements"))
            if (events.isEmpty()) addView(TextView(this@OrderBookHunterActivity).apply {
                text = "Aucun événement significatif mémorisé."
                textSize = 11f
                setTextColor(muted)
                setPadding(0, dp(7), 0, 0)
            }) else addView(TextView(this@OrderBookHunterActivity).apply {
                text = events.take(35).joinToString("\n\n") { e ->
                    "${formatTime(e.createdAt)} • ${e.type.name}\n${e.detail}"
                }
                textSize = 10.5f
                setTextColor(text)
                setLineSpacing(0f, 1.12f)
                setPadding(0, dp(7), 0, 0)
            })
        })

        detailContainer.addView(card(topMargin = 10).apply {
            addView(sectionTitle("Contrôles"))
            if (!watching) addView(actionButton("COMMENCER LA TRAQUE", green) {
                selectedSymbol = symbol
                OrderBookHunterService.startWatch(this@OrderBookHunterActivity, symbol)
            }) else addView(actionButton("ARRÊTER LA TRAQUE", red) {
                OrderBookHunterService.stopWatch(this@OrderBookHunterActivity, symbol)
            })
            val alerts = db.alertsEnabled(symbol)
            addView(actionButton(if (alerts) "ALERTES : ON" else "ALERTES : OFF", if (alerts) green else surface2, top = 7) {
                OrderBookHunterService.setAlerts(this@OrderBookHunterActivity, symbol, !alerts)
                handler.postDelayed({ renderDetail() }, 250)
            })
            addView(actionButton("EFFACER HISTORIQUE (notes conservées)", surface2, top = 7) {
                AlertDialog.Builder(this@OrderBookHunterActivity)
                    .setTitle("Effacer l'historique $symbol ?")
                    .setMessage("Les événements, murs et scores seront supprimés. Les notes du carnet de bord sont conservées.")
                    .setPositiveButton("Effacer") { _, _ -> OrderBookHunterService.clearHistory(this@OrderBookHunterActivity, symbol) }
                    .setNegativeButton("Annuler", null)
                    .show()
            })
        })
    }

    private fun metrics(status: HunterStatus): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, 0)
        val sign = if (status.change24hPct >= 0) "+" else ""
        addView(metricLine("Prix", fmtPrice(status.lastPrice), "24 h", "$sign${String.format(Locale.US, "%.2f", status.change24hPct)} %"))
        addView(metricLine("Spread", "${String.format(Locale.US, "%.3f", status.spreadPercent)} %", "Turnover 24 h", "${fmtMoney(status.turnover24h)} USDC"))
        addView(metricLine("Volume 24 h", fmtQty(status.volume24h), "Carnet", if (status.synchronized) "SYNCHRONISÉ" else "DÉSYNCHRONISÉ"))
    }

    private fun metricLine(a: String, av: String, b: String, bv: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(3), 0, dp(3))
        addView(metricCell(a, av), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(metricCell(b, bv), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun metricCell(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@OrderBookHunterActivity).apply { text = label; textSize = 9.5f; setTextColor(muted) })
        addView(TextView(this@OrderBookHunterActivity).apply { text = value; textSize = 12f; setTextColor(text); setTypeface(Typeface.DEFAULT, Typeface.BOLD) })
    }

    private fun wallsCard(title: String, walls: List<HunterWallView>, color: Int): View = card(topMargin = 10).apply {
        addView(sectionTitle(title))
        if (walls.isEmpty()) addView(TextView(this@OrderBookHunterActivity).apply {
            text = "Aucun mur significatif selon les seuils dynamiques actuels."
            textSize = 11f
            setTextColor(muted)
            setPadding(0, dp(7), 0, 0)
        }) else walls.take(8).forEach { w ->
            addView(TextView(this@OrderBookHunterActivity).apply {
                text = "${fmtPrice(w.price)}  •  ${fmtQty(w.qty)}  •  ${fmtMoney(w.notionalUsdc)} USDC  •  dist ${String.format(Locale.US, "%.2f", w.distanceFromMidPercent)}%  •  ${w.significanceScore.toInt()}/100"
                textSize = 10.7f
                setTextColor(color)
                setPadding(0, dp(5), 0, 0)
            })
        }
    }

    private fun card(topMargin: Int = 0): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = rounded(surface, border, 17)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(topMargin), 0, 0)
        }
    }

    private fun sectionTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(text)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun actionButton(label: String, color: Int, onClick: () -> Unit, top: Int = 0): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(if (color == yellow || color == orange || color == green) Color.BLACK else text)
        background = rounded(color, color, 14)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(49)).apply { setMargins(0, dp(top), 0, 0) }
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun transparentRounded(): GradientDrawable = rounded(Color.TRANSPARENT, Color.TRANSPARENT, 10)
    private fun scoreColor(score: Int): Int = when (score) { in 0..39 -> green; in 40..59 -> yellow; else -> red }
    private fun formatTime(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date(ms))
    private fun fmtPrice(v: Double): String = when {
        abs(v) >= 1000 -> String.format(Locale.US, "%.2f", v)
        abs(v) >= 1 -> String.format(Locale.US, "%.5f", v).trimEnd('0').trimEnd('.')
        else -> String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
    }
    private fun fmtMoney(v: Double): String = when { v >= 1_000_000 -> String.format(Locale.US, "%.2fM", v / 1_000_000.0); v >= 1_000 -> String.format(Locale.US, "%.1fk", v / 1_000.0); else -> String.format(Locale.US, "%.2f", v) }
    private fun fmtQty(v: Double): String = fmtMoney(v)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
