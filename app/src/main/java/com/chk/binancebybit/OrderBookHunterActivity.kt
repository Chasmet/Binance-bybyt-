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
    private var selectedSymbol = ""
    private lateinit var symbolInput: EditText
    private lateinit var scanStatus: TextView
    private lateinit var watchStrip: LinearLayout
    private lateinit var detail: LinearLayout

    private val pageBg = Color.rgb(10, 12, 16)
    private val cardBg = Color.rgb(20, 23, 28)
    private val fieldBg = Color.rgb(29, 33, 40)
    private val border = Color.rgb(49, 55, 66)
    private val primaryText = Color.rgb(244, 246, 249)
    private val muted = Color.rgb(160, 169, 181)
    private val orange = Color.rgb(245, 142, 30)
    private val yellow = Color.rgb(240, 185, 11)
    private val green = Color.rgb(57, 197, 128)
    private val red = Color.rgb(242, 96, 96)

    private val refresher = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                renderWatchStrip()
                renderDetail()
                handler.postDelayed(this, 2_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = OrderBookHunterDb(this)
        selectedSymbol = intent.getStringExtra(OrderBookHunterService.EXTRA_SYMBOL).orEmpty()
        setContentView(buildUi())
        renderWatchStrip()
        renderDetail()
        scanAllMarkets(showDialog = false)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresher)
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBg)
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
            addView(Button(this@OrderBookHunterActivity).apply {
                text = "‹"
                textSize = 24f
                setTextColor(primaryText)
                background = rounded(Color.TRANSPARENT, Color.TRANSPARENT, 12)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(this@OrderBookHunterActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, 0, 0)
                addView(TextView(this@OrderBookHunterActivity).apply {
                    text = "CHK OrderBook Hunter"
                    textSize = 21f
                    setTextColor(primaryText)
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                })
                addView(TextView(this@OrderBookHunterActivity).apply {
                    text = "Bot 2 indépendant • mémoire temporelle Bybit EU Spot"
                    textSize = 10.5f
                    setTextColor(muted)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(28))
        }
        scroll.addView(body)

        body.addView(card().apply {
            addView(title("Traquer une crypto"))
            addView(TextView(this@OrderBookHunterActivity).apply {
                text = "Le Hunter observe, mémorise et alerte. Il ne passe aucun BUY/SELL. Maximum 20 marchés suivis simultanément."
                textSize = 11f
                setTextColor(muted)
                setPadding(0, dp(5), 0, dp(10))
            })
            symbolInput = EditText(this@OrderBookHunterActivity).apply {
                hint = "Ex. SKRUSDC"
                setHintTextColor(Color.rgb(110, 120, 132))
                setTextColor(primaryText)
                setSingleLine(true)
                textSize = 15f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(fieldBg, border, 13)
            }
            addView(symbolInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                setMargins(0, 0, 0, dp(8))
            })
            addView(actionButton("TRAQUER CETTE CRYPTO", orange) { startManualWatch() })
            addView(actionButton("SCANNER TOUT BYBIT EU • SPOT USDC", yellow, top = 8) { scanAllMarkets(showDialog = true) })
            scanStatus = TextView(this@OrderBookHunterActivity).apply {
                text = "Scan global Bybit EU en attente…"
                textSize = 10.5f
                setTextColor(muted)
                setPadding(2, dp(8), 2, 0)
            }
            addView(scanStatus)
        })

        body.addView(TextView(this).apply {
            text = "CRYPTOS TRAQUÉES"
            textSize = 11f
            setTextColor(muted)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(4), dp(16), 0, dp(7))
        })
        val watchScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        watchStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        watchScroll.addView(watchStrip)
        body.addView(watchScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))

        detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(detail)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun startManualWatch() {
        val symbol = runCatching { OrderBookHunterStore.normalizeSymbol(symbolInput.text?.toString().orEmpty()) }
            .getOrElse {
                Toast.makeText(this, "Symbole invalide", Toast.LENGTH_SHORT).show()
                return
            }
        if (!db.isWatching(symbol) && db.watches().size >= OrderBookHunterWebSocket.MAX_SYMBOLS) {
            Toast.makeText(this, "Maximum ${OrderBookHunterWebSocket.MAX_SYMBOLS} marchés simultanés", Toast.LENGTH_LONG).show()
            return
        }
        selectedSymbol = symbol
        symbolInput.setText(symbol)
        OrderBookHunterService.startWatch(this, symbol)
        Toast.makeText(this, "$symbol • surveillance activée", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ renderWatchStrip(); renderDetail() }, 300L)
    }

    private fun scanAllMarkets(showDialog: Boolean) {
        if (::scanStatus.isInitialized) scanStatus.text = "Scan de toutes les paires CRYPTO/USDC Bybit EU…"
        Thread {
            val result = runCatching { OrderBookHunterMarketScanner().scanAllUsdcMarkets() }
            runOnUiThread {
                result.onSuccess { markets ->
                    val active = db.watches().map { it.symbol }.toSet()
                    scanStatus.text = "${markets.size} marchés Spot USDC scannés • ${markets.count { it.symbol !in active }} disponibles • ${active.size} traqués"
                    if (showDialog) showMarkets(markets)
                }.onFailure {
                    scanStatus.text = "Scan Bybit indisponible : ${it.message ?: "réseau"}"
                    if (showDialog) Toast.makeText(this, scanStatus.text, Toast.LENGTH_LONG).show()
                }
            }
        }.apply {
            name = "CHK-Hunter-All-Bybit-EU"
            isDaemon = true
            start()
        }
    }

    private fun showMarkets(markets: List<HunterMarket>) {
        val visible = markets.take(150)
        val labels = visible.map { m ->
            val sign = if (m.change24hPct >= 0) "+" else ""
            "${m.symbol}   ${fmtPrice(m.lastPrice)}   $sign${String.format(Locale.US, "%.1f", m.change24hPct)}%   ${fmtMoney(m.turnover24h)} USDC"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bybit EU • ${markets.size} marchés USDC")
            .setItems(labels) { _, index ->
                val m = visible[index]
                selectedSymbol = m.symbol
                symbolInput.setText(m.symbol)
                renderDetail()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun renderWatchStrip() {
        if (!::watchStrip.isInitialized) return
        val watches = runCatching { db.watches() }.getOrDefault(emptyList())
        if (selectedSymbol.isBlank() && watches.isNotEmpty()) selectedSymbol = watches.first().symbol
        watchStrip.removeAllViews()
        if (watches.isEmpty()) {
            watchStrip.addView(TextView(this).apply {
                text = "Aucune crypto suivie. Ajoute SKRUSDC ou choisis un marché dans le scan."
                textSize = 11f
                setTextColor(muted)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
            return
        }
        watches.forEach { watch ->
            val score = OrderBookHunterStore.get(watch.symbol)?.anomalyScore
            val selected = watch.symbol == selectedSymbol
            watchStrip.addView(Button(this).apply {
                text = if (score == null) watch.symbol else "${watch.symbol}  $score"
                isAllCaps = false
                textSize = 11f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (selected) Color.BLACK else primaryText)
                background = rounded(if (selected) orange else fieldBg, if (selected) orange else border, 14)
                setOnClickListener {
                    selectedSymbol = watch.symbol
                    symbolInput.setText(watch.symbol)
                    renderWatchStrip()
                    renderDetail()
                }
            }, LinearLayout.LayoutParams(dp(128), dp(48)).apply { setMargins(0, 0, dp(7), 0) })
        }
    }

    private fun renderDetail() {
        if (!::detail.isInitialized) return
        detail.removeAllViews()
        if (selectedSymbol.isBlank()) return
        val symbol = runCatching { OrderBookHunterStore.normalizeSymbol(selectedSymbol) }.getOrNull() ?: return
        val watching = runCatching { db.isWatching(symbol) }.getOrDefault(false)
        val alerts = runCatching { db.alertsEnabled(symbol) }.getOrDefault(true)
        val status = OrderBookHunterStore.get(symbol)
        val events = runCatching { db.events(symbol, 100) }.getOrDefault(emptyList())
        val recent30m = runCatching { db.events(symbol, 500, System.currentTimeMillis() - 30L * 60L * 1000L) }.getOrDefault(emptyList())

        detail.addView(card(14).apply {
            addView(LinearLayout(this@OrderBookHunterActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@OrderBookHunterActivity).apply {
                    text = symbol
                    textSize = 20f
                    setTextColor(primaryText)
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
            detail.addView(card(10).apply {
                addView(title("Pression du carnet visible"))
                status.imbalances.forEach { imbalance ->
                    addView(TextView(this@OrderBookHunterActivity).apply {
                        text = "±${imbalance.distancePercent}%   BUY ${imbalance.buyPressure.toInt()}%   •   SELL ${imbalance.sellPressure.toInt()}%"
                        textSize = 12f
                        setTextColor(if (imbalance.buyPressure >= 60) green else if (imbalance.buyPressure <= 40) red else primaryText)
                        setPadding(0, dp(5), 0, 0)
                    })
                }
                addView(TextView(this@OrderBookHunterActivity).apply {
                    text = "Ordres visibles uniquement : aucune garantie directionnelle."
                    textSize = 10f
                    setTextColor(muted)
                    setPadding(0, dp(7), 0, 0)
                })
            })
            detail.addView(wallsCard("TOP MURS BUY", status.bidWalls, green))
            detail.addView(wallsCard("TOP MURS SELL", status.askWalls, red))
        }

        detail.addView(card(10).apply {
            addView(title("30 dernières minutes"))
            val lines = listOf(
                HunterEventType.LARGE_WALL to "Murs créés",
                HunterEventType.WALL_DISAPPEARED to "Murs disparus",
                HunterEventType.WALL_RETREAT to "Murs reculés",
                HunterEventType.WALL_CHASING_PRICE to "Murs qui suivent le prix",
                HunterEventType.WALL_CANCELLED_NEAR_TOUCH to "Annulations près du contact",
                HunterEventType.WALL_ABSORPTION to "Absorptions réelles probables",
                HunterEventType.WALL_REFILL to "Refills",
                HunterEventType.ORDERBOOK_SWEEP to "Sweeps"
            )
            lines.forEach { (type, label) ->
                addView(TextView(this@OrderBookHunterActivity).apply {
                    text = "$label : ${recent30m.count { it.type == type }}"
                    textSize = 11.5f
                    setTextColor(primaryText)
                    setPadding(0, dp(3), 0, 0)
                })
            }
        })

        detail.addView(card(10).apply {
            addView(title("Timeline prix / murs"))
            addView(OrderBookHunterTimelineView(this@OrderBookHunterActivity).apply {
                setBackgroundColor(Color.rgb(14, 17, 21))
                setEvents(events.reversed())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { setMargins(0, dp(8), 0, 0) })
        })

        detail.addView(card(10).apply {
            addView(title("Carnet de bord spécifique"))
            val noteInput = EditText(this@OrderBookHunterActivity).apply {
                hint = "Note sur $symbol…"
                setHintTextColor(Color.rgb(110, 120, 132))
                setTextColor(primaryText)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(fieldBg, border, 13)
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
            val notes = runCatching { db.notes(symbol, 8) }.getOrDefault(emptyList())
            if (notes.isNotEmpty()) addView(TextView(this@OrderBookHunterActivity).apply {
                text = notes.joinToString("\n\n") { "${formatTime(it.createdAt)} • ${it.author}\n${it.text}" }
                textSize = 10.5f
                setTextColor(muted)
                setPadding(0, dp(9), 0, 0)
            })
        })

        detail.addView(card(10).apply {
            addView(title("Historique événements"))
            addView(TextView(this@OrderBookHunterActivity).apply {
                text = if (events.isEmpty()) "Aucun événement significatif mémorisé." else events.take(35).joinToString("\n\n") {
                    "${formatTime(it.createdAt)} • ${it.type.name}\n${it.detail}"
                }
                textSize = 10.5f
                setTextColor(if (events.isEmpty()) muted else primaryText)
                setLineSpacing(0f, 1.12f)
                setPadding(0, dp(7), 0, 0)
            })
        })

        detail.addView(card(10).apply {
            addView(title("Contrôles"))
            if (watching) {
                addView(actionButton("ARRÊTER LA TRAQUE", red) { OrderBookHunterService.stopWatch(this@OrderBookHunterActivity, symbol) })
            } else {
                addView(actionButton("COMMENCER LA TRAQUE", green) {
                    selectedSymbol = symbol
                    OrderBookHunterService.startWatch(this@OrderBookHunterActivity, symbol)
                })
            }
            addView(actionButton(if (alerts) "ALERTES : ON" else "ALERTES : OFF", if (alerts) green else fieldBg, top = 7) {
                OrderBookHunterService.setAlerts(this@OrderBookHunterActivity, symbol, !alerts)
                handler.postDelayed({ renderDetail() }, 300L)
            })
            addView(actionButton("EFFACER HISTORIQUE (notes conservées)", fieldBg, top = 7) {
                AlertDialog.Builder(this@OrderBookHunterActivity)
                    .setTitle("Effacer l'historique $symbol ?")
                    .setMessage("Événements, murs et scores seront supprimés. Les notes restent conservées.")
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
        addView(TextView(this@OrderBookHunterActivity).apply { text = value; textSize = 12f; setTextColor(primaryText); setTypeface(Typeface.DEFAULT, Typeface.BOLD) })
    }

    private fun wallsCard(header: String, walls: List<HunterWallView>, color: Int): View = card(10).apply {
        addView(title(header))
        if (walls.isEmpty()) {
            addView(TextView(this@OrderBookHunterActivity).apply {
                text = "Aucun mur significatif selon les seuils dynamiques actuels."
                textSize = 11f
                setTextColor(muted)
                setPadding(0, dp(7), 0, 0)
            })
        } else {
            walls.take(8).forEach { wall ->
                addView(TextView(this@OrderBookHunterActivity).apply {
                    text = "${fmtPrice(wall.price)} • ${fmtQty(wall.qty)} • ${fmtMoney(wall.notionalUsdc)} USDC • dist ${String.format(Locale.US, "%.2f", wall.distanceFromMidPercent)}% • ${wall.significanceScore.toInt()}/100"
                    textSize = 10.7f
                    setTextColor(color)
                    setPadding(0, dp(5), 0, 0)
                })
            }
        }
    }

    private fun card(top: Int = 0): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = rounded(cardBg, border, 17)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(top), 0, 0)
        }
    }

    private fun title(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(primaryText)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun actionButton(label: String, color: Int, top: Int = 0, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(if (color == yellow || color == orange || color == green) Color.BLACK else primaryText)
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

    private fun scoreColor(score: Int): Int = when (score) {
        in 0..39 -> green
        in 40..59 -> yellow
        else -> red
    }

    private fun formatTime(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date(ms))
    private fun fmtPrice(v: Double): String = when {
        abs(v) >= 1000.0 -> String.format(Locale.US, "%.2f", v)
        abs(v) >= 1.0 -> String.format(Locale.US, "%.5f", v).trimEnd('0').trimEnd('.')
        else -> String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
    }
    private fun fmtMoney(v: Double): String = when {
        v >= 1_000_000.0 -> String.format(Locale.US, "%.2fM", v / 1_000_000.0)
        v >= 1_000.0 -> String.format(Locale.US, "%.1fk", v / 1_000.0)
        else -> String.format(Locale.US, "%.2f", v)
    }
    private fun fmtQty(v: Double): String = fmtMoney(v)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
