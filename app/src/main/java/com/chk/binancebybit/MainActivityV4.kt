package com.chk.binancebybit

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivityV4 : Activity() {
    private lateinit var content: FrameLayout
    private lateinit var secureStore: SecureStore
    private lateinit var workspaceSync: WorkspaceSync
    private val prefs by lazy { getSharedPreferences("chk_workspace", MODE_PRIVATE) }

    private var exchange = "BINANCE"
    private var section = "HOME"
    private var noteFilter = "TOUS"
    private var noteKind = "NOTE"
    private var notesCache = ""

    private val bg = Color.rgb(10, 12, 15)
    private val surface = Color.rgb(20, 23, 28)
    private val surface2 = Color.rgb(28, 32, 38)
    private val border = Color.rgb(48, 54, 64)
    private val text = Color.rgb(246, 247, 249)
    private val muted = Color.rgb(153, 162, 174)
    private val yellow = Color.rgb(240, 185, 11)
    private val orange = Color.rgb(245, 142, 30)
    private val green = Color.rgb(57, 197, 128)
    private val red = Color.rgb(238, 91, 91)
    private val blue = Color.rgb(93, 148, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureStore = SecureStore(this)
        workspaceSync = WorkspaceSync(this, secureStore)
        workspaceSync.ensureIdentity()
        AlertCheckReceiver.createChannel(this)
        AlertCheckReceiver.schedule(this)
        requestNotificationPermission()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        rebuildUi()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) runCatching { rebuildUi() }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7001)
        }
    }

    private fun rebuildUi() {
        setContentView(buildRoot())
        render()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        root.addView(buildHeader())
        root.addView(buildExchangeSelector(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            setMargins(dp(16), dp(6), dp(16), dp(8))
        })

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildBottomNav(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)))
        return root
    }

    private fun buildHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))

            addView(ImageView(this@MainActivityV4).apply {
                setImageResource(R.drawable.app_icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(46), dp(46)))

            addView(LinearLayout(this@MainActivityV4).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@MainActivityV4).apply {
                    text = "CHK Crypto"
                    textSize = 22f
                    setTextColor(text)
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                })
                addView(TextView(this@MainActivityV4).apply {
                    text = "Workspace Binance + Bybit"
                    textSize = 12f
                    setTextColor(muted)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(statusPill())
        }
    }

    private fun statusPill(): View {
        val key = if (exchange == "BINANCE") "binance_sync_state" else "bybit_sync_state"
        val ok = (prefs.getString(key, "") ?: "").startsWith("OK")
        return TextView(this).apply {
            text = if (ok) "● CONNECTÉ" else "○ HORS LIGNE"
            textSize = 10f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(if (ok) green else muted)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(if (ok) Color.rgb(18, 45, 35) else surface2, if (ok) Color.rgb(35, 92, 66) else border, 999)
        }
    }

    private fun buildExchangeSelector(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(surface, border, 18)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(exchangeButton("BINANCE", "BINANCE"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(exchangeButton("BYBIT", "BYBIT"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun exchangeButton(code: String, label: String): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(if (exchange == code) Color.BLACK else muted)
        background = if (exchange == code) rounded(if (code == "BINANCE") yellow else orange, Color.TRANSPARENT, 14) else transparentRounded()
        setOnClickListener {
            exchange = code
            rebuildUi()
        }
    }

    private fun buildBottomNav(): View {
        val items = listOf(
            "HOME" to "Accueil",
            "PORTFOLIO" to "Actifs",
            "HISTORY" to "PRU",
            "NOTES" to "Notes",
            "SETTINGS" to "Réglages"
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(8))
            background = rounded(surface, border, 0)
            items.forEach { (code, label) ->
                addView(navButton(code, label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
        }
    }

    private fun navButton(code: String, label: String): View {
        val active = section == code
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(4), dp(2), dp(4))
            background = if (active) rounded(surface2, Color.TRANSPARENT, 14) else transparentRounded()

            addView(TextView(this@MainActivityV4).apply {
                text = when (code) {
                    "HOME" -> "●"
                    "PORTFOLIO" -> "▦"
                    "HISTORY" -> "↗"
                    "NOTES" -> "✎"
                    else -> "⚙"
                }
                textSize = if (code == "HOME") 12f else 18f
                setTextColor(if (active) accent() else muted)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivityV4).apply {
                text = label
                textSize = 10f
                setTypeface(Typeface.DEFAULT, if (active) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (active) text else muted)
                gravity = Gravity.CENTER
            })
            setOnClickListener {
                section = code
                rebuildUi()
            }
        }
    }

    private fun render() {
        content.removeAllViews()
        when (section) {
            "HOME" -> renderHome()
            "PORTFOLIO" -> renderPortfolio()
            "HISTORY" -> renderHistory()
            "NOTES" -> renderNotes()
            "SETTINGS" -> renderSettings()
        }
    }

    private fun renderHome() {
        val page = page()
        page.addView(sectionTitle("Vue d'ensemble", "Une lecture rapide de tes deux plateformes"))

        val binance = loadSnapshot("BINANCE")
        val bybit = loadSnapshot("BYBIT")
        val combined = (binance?.totalEur ?: 0.0) + (bybit?.totalEur ?: 0.0)

        page.addView(heroCard(
            eyebrow = "PATRIMOINE CRYPTO TOTAL",
            value = if (combined > 0.0) "${fmtMoney(combined)} €" else "— €",
            detail = "Binance ${fmtMoney(binance?.totalEur ?: 0.0)} €   •   Bybit ${fmtMoney(bybit?.totalEur ?: 0.0)} €"
        ))

        page.addView(twoColumnCards(
            metricCard("Binance", if (binance != null) "${fmtMoney(binance.totalEur)} €" else "Non synchronisé", yellow),
            metricCard("Bybit", if (bybit != null) "${fmtMoney(bybit.totalEur)} €" else "Non synchronisé", orange)
        ))

        page.addView(subTitle("Actions rapides"))
        page.addView(twoColumnButtons(
            actionButton("↻  Tout synchroniser") { syncAll() },
            actionButton("✎  Nouvelle note") { section = "NOTES"; rebuildUi() }
        ))

        val selected = loadSnapshot(exchange)
        page.addView(subTitle("${prettyExchange()} • principaux actifs"))
        if (selected == null || selected.holdings.isEmpty()) {
            page.addView(emptyCard("Aucune donnée", "Synchronise ${prettyExchange()} pour afficher le portefeuille."))
        } else {
            selected.holdings.take(4).forEach { page.addView(holdingCard(it, compact = true)) }
        }

        val alerts = prefs.getInt("alert_count", 0)
        page.addView(infoBanner(
            "Sécurité active",
            "${if (exchange == "BINANCE") "Binance en lecture seule" else "Bybit : trading Spot autorisé, mais l'app n'envoie encore aucun ordre"} • $alerts alerte(s) active(s).",
            green
        ))
        attach(page)
    }

    private fun renderPortfolio() {
        val page = page()
        val snap = loadSnapshot(exchange)
        page.addView(sectionTitle("Portefeuille ${prettyExchange()}", "Valeurs, quantités et prix de référence"))

        if (snap == null) {
            page.addView(emptyCard("Portefeuille non synchronisé", "Ajoute les clés API dans Réglages puis lance une synchronisation."))
            page.addView(primaryButton("Synchroniser ${prettyExchange()}") { syncSelected() })
            attach(page)
            return
        }

        page.addView(heroCard(
            eyebrow = "VALEUR ESTIMÉE",
            value = "${fmtMoney(snap.totalEur)} €",
            detail = "≈ ${fmt(snap.totalUsdt)} USD/USDT • ${snap.holdings.size} actif(s)"
        ))
        page.addView(primaryButton("Actualiser portefeuille + historique") { syncSelected() })
        page.addView(subTitle("Actifs"))

        snap.holdings.forEach { page.addView(holdingCard(it, compact = false)) }
        attach(page)
    }

    private fun holdingCard(h: Holding, compact: Boolean): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(if (compact) 12 else 15), dp(16), dp(if (compact) 12 else 15))
            background = rounded(surface, border, 18)
            layoutParams = marginParams(bottom = 10)

            addView(LinearLayout(this@MainActivityV4).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(coinBadge(h.asset))
                addView(LinearLayout(this@MainActivityV4).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    addView(TextView(this@MainActivityV4).apply {
                        text = h.asset
                        textSize = 17f
                        setTextColor(text)
                        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    })
                    addView(TextView(this@MainActivityV4).apply {
                        text = "Qté ${fmt(h.amount)}"
                        textSize = 12f
                        setTextColor(muted)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                addView(LinearLayout(this@MainActivityV4).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                    addView(TextView(this@MainActivityV4).apply {
                        text = "${fmtMoney(h.valueUsdt)} $"
                        textSize = 16f
                        setTextColor(text)
                        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                        gravity = Gravity.END
                    })
                    addView(TextView(this@MainActivityV4).apply {
                        text = "${fmt(h.priceUsdt)} $ / unité"
                        textSize = 11f
                        setTextColor(muted)
                        gravity = Gravity.END
                    })
                })
            })
        }
    }

    private fun coinBadge(symbol: String): View = TextView(this).apply {
        text = symbol.take(1)
        gravity = Gravity.CENTER
        textSize = 16f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(Color.BLACK)
        background = rounded(accent(), Color.TRANSPARENT, 999)
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
    }

    private fun renderHistory() {
        val page = page()
        page.addView(sectionTitle("PRU & historique ${prettyExchange()}", "Achats, ventes et performance estimée"))
        val history = prefs.getString(historyKey(exchange), "") ?: ""
        if (history.isBlank()) {
            page.addView(emptyCard("Historique non chargé", "Synchronise ${prettyExchange()} pour récupérer les exécutions Spot disponibles."))
            page.addView(primaryButton("Charger l'historique") { syncSelected() })
            attach(page)
            return
        }

        page.addView(infoBanner("À savoir", "Le PRU est une estimation basée sur l'historique Spot disponible. Convert, carte, Earn, transferts ou certains frais peuvent manquer.", blue))
        history.split("\n\n").map { it.trim() }.filter { it.isNotBlank() }.forEach { block ->
            page.addView(historyBlock(block))
        }
        attach(page)
    }

    private fun historyBlock(block: String): View {
        val lines = block.lines().filter { it.isNotBlank() }
        val first = lines.firstOrNull().orEmpty()
        val isHeader = first.contains("PRU ESTIMÉ") || first.contains("DERNIÈRES")
        if (isHeader) return subTitle(first)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(surface, border, 18)
            layoutParams = marginParams(bottom = 10)
            if (lines.isNotEmpty()) {
                addView(TextView(this@MainActivityV4).apply {
                    text = lines.first()
                    textSize = 16f
                    setTextColor(text)
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                })
            }
            lines.drop(1).forEach { line ->
                val positive = line.contains("+") && (line.contains("%") || line.contains("P/L"))
                val negative = line.contains("-") && (line.contains("%") || line.contains("P/L"))
                addView(TextView(this@MainActivityV4).apply {
                    text = line
                    textSize = 13f
                    setTextColor(when { positive -> green; negative -> red; else -> muted })
                    setPadding(0, dp(3), 0, 0)
                })
            }
        }
    }

    private fun renderNotes() {
        val page = page()
        page.addView(sectionTitle("Bloc-notes CHK", "Plans, alertes et décisions regroupés proprement"))
        page.addView(noteFilterBar())

        val notesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(notesContainer)
        if (notesCache.isBlank()) {
            notesContainer.addView(emptyCard("Chargement…", "Récupération des notes privées."))
            loadNotesInto(notesContainer)
        } else {
            renderNotesInto(notesContainer, notesCache)
        }

        page.addView(subTitle("Nouvelle note"))
        page.addView(noteComposer(notesContainer))
        page.addView(subTitle("Plan d'ordre"))
        page.addView(orderPlanComposer(notesContainer))
        attach(page)
    }

    private fun noteFilterBar(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, dp(8), dp(10))
        }
        listOf("TOUS", "ACHAT", "VENTE", "ALERTE", "PLAN", "NOTE").forEach { kind ->
            row.addView(chip(kind, noteFilter == kind) {
                noteFilter = kind
                rebuildUi()
            })
        }
        scroll.addView(row)
        return scroll
    }

    private fun noteComposer(notesContainer: LinearLayout): View {
        val box = card()
        val kindRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = mutableListOf<Button>()
        listOf("NOTE", "ACHAT", "VENTE", "ALERTE").forEach { kind ->
            val b = Button(this).apply {
                text = kind
                isAllCaps = false
                textSize = 11f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (noteKind == kind) Color.BLACK else muted)
                background = if (noteKind == kind) rounded(kindColor(kind), Color.TRANSPARENT, 12) else rounded(surface2, border, 12)
                setOnClickListener {
                    noteKind = kind
                    buttons.forEach { btn ->
                        val active = btn.text.toString() == noteKind
                        btn.setTextColor(if (active) Color.BLACK else muted)
                        btn.background = if (active) rounded(kindColor(noteKind), Color.TRANSPARENT, 12) else rounded(surface2, border, 12)
                    }
                }
            }
            buttons += b
            kindRow.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        box.addView(kindRow)

        val draft = input("Écris ton analyse, niveau ou rappel…", multi = true)
        box.addView(draft, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)).apply { setMargins(0, dp(10), 0, 0) })
        box.addView(primaryButton("Enregistrer la note") {
            val value = draft.text.toString().trim()
            if (value.isBlank()) {
                Toast.makeText(this, "Écris une note d'abord", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            saveNote(exchange, noteKind, value, notesContainer) { draft.setText("") }
        })
        return box
    }

    private fun orderPlanComposer(notesContainer: LinearLayout): View {
        val box = card()
        box.addView(infoBanner("Brouillon uniquement", "Cette fiche prépare un ordre. Elle n'envoie rien à Bybit ou Binance dans la v0.4.", orange))
        val asset = input("Actif / paire : RENDERUSDC")
        val price = input("Prix limite")
        val amount = input("Montant ou quantité")
        val reason = input("Pourquoi ce niveau ?", multi = true)
        box.addView(asset)
        box.addView(price)
        box.addView(amount)
        box.addView(reason, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100)).apply { setMargins(0, 0, 0, dp(8)) })

        val sideRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var side = "ACHAT"
        val buy = Button(this).apply {
            text = "ACHAT"
            isAllCaps = false
            setTextColor(Color.BLACK)
            background = rounded(green, Color.TRANSPARENT, 12)
        }
        val sell = Button(this).apply {
            text = "VENTE"
            isAllCaps = false
            setTextColor(muted)
            background = rounded(surface2, border, 12)
        }
        buy.setOnClickListener {
            side = "ACHAT"
            buy.setTextColor(Color.BLACK); buy.background = rounded(green, Color.TRANSPARENT, 12)
            sell.setTextColor(muted); sell.background = rounded(surface2, border, 12)
        }
        sell.setOnClickListener {
            side = "VENTE"
            sell.setTextColor(Color.BLACK); sell.background = rounded(red, Color.TRANSPARENT, 12)
            buy.setTextColor(muted); buy.background = rounded(surface2, border, 12)
        }
        sideRow.addView(buy, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(0, 0, dp(4), 0) })
        sideRow.addView(sell, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, 0, 0) })
        box.addView(sideRow)

        box.addView(primaryButton("Ajouter ce plan au bloc-notes") {
            val a = asset.text.toString().trim().uppercase(Locale.US)
            val p = price.text.toString().trim()
            val q = amount.text.toString().trim()
            val r = reason.text.toString().trim()
            if (a.isBlank() || p.isBlank() || q.isBlank()) {
                Toast.makeText(this, "Actif, prix et montant sont obligatoires", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            val contentText = buildString {
                append("$side — $a\n")
                append("Prix limite : $p\n")
                append("Montant/quantité : $q")
                if (r.isNotBlank()) append("\nRaison : $r")
                append("\nStatut : BROUILLON")
            }
            saveNote(exchange, "PLAN", contentText, notesContainer) {
                asset.setText(""); price.setText(""); amount.setText(""); reason.setText("")
            }
        })
        return box
    }

    private fun saveNote(exchangeCode: String, kind: String, value: String, container: LinearLayout, after: () -> Unit) {
        runAsync(
            task = { workspaceSync.createNote(exchangeCode, kind, value) },
            success = {
                after()
                Toast.makeText(this, "Note enregistrée", Toast.LENGTH_SHORT).show()
                notesCache = ""
                loadNotesInto(container)
            },
            failure = { Toast.makeText(this, "Erreur note : $it", Toast.LENGTH_LONG).show() }
        )
    }

    private fun loadNotesInto(container: LinearLayout) {
        runAsync(
            task = { workspaceSync.listNotes() },
            success = { raw ->
                notesCache = raw
                renderNotesInto(container, raw)
            },
            failure = {
                container.removeAllViews()
                container.addView(emptyCard("Notes indisponibles", it))
            }
        )
    }

    private fun renderNotesInto(container: LinearLayout, raw: String) {
        container.removeAllViews()
        val arr = runCatching { JSONObject(raw).optJSONArray("notes") ?: JSONArray() }.getOrDefault(JSONArray())
        var shown = 0
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            val kind = n.optString("kind", "NOTE").uppercase(Locale.FRANCE)
            if (noteFilter != "TOUS" && noteFilter != kind) continue
            shown++
            container.addView(noteCard(n))
        }
        if (shown == 0) container.addView(emptyCard("Aucune note", "Aucune note dans ce filtre pour le moment."))
    }

    private fun noteCard(n: JSONObject): View {
        val kind = n.optString("kind", "NOTE").uppercase(Locale.FRANCE)
        val exch = n.optString("exchange", "GLOBAL")
        val source = n.optString("source", "")
        val whenText = runCatching {
            val ms = java.time.Instant.parse(n.optString("created_at")).toEpochMilli()
            SimpleDateFormat("dd/MM • HH:mm", Locale.FRANCE).format(Date(ms))
        }.getOrDefault("")

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(surface, border, 18)
            layoutParams = marginParams(bottom = 10)

            addView(LinearLayout(this@MainActivityV4).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivityV4).apply {
                    text = kind
                    textSize = 10f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(Color.BLACK)
                    setPadding(dp(9), dp(5), dp(9), dp(5))
                    background = rounded(kindColor(kind), Color.TRANSPARENT, 999)
                })
                addView(TextView(this@MainActivityV4).apply {
                    text = "  $exch${if (source == "chatgpt") " • ChatGPT" else ""}"
                    textSize = 11f
                    setTextColor(muted)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@MainActivityV4).apply {
                    text = whenText
                    textSize = 10f
                    setTextColor(muted)
                })
            })

            addView(TextView(this@MainActivityV4).apply {
                text = n.optString("content")
                textSize = 14f
                setTextColor(text)
                setLineSpacing(0f, 1.18f)
                setPadding(0, dp(10), 0, 0)
                setTextIsSelectable(true)
            })
        }
    }

    private fun renderSettings() {
        val page = page()
        page.addView(sectionTitle("Réglages ${prettyExchange()}", "Connexion API et sécurité locale"))

        val isBinance = exchange == "BINANCE"
        page.addView(infoBanner(
            "Sécurité des clés",
            if (isBinance)
                "Binance doit rester en lecture seule. Les clés sont chiffrées avec Android Keystore et ne sont jamais envoyées à GitHub."
            else
                "Bybit peut avoir la permission Trader Spot. La v0.4 ne contient encore aucune fonction d'envoi d'ordre : les plans restent des brouillons.",
            if (isBinance) yellow else orange
        ))

        val apiName = if (isBinance) "binance_api_key" else "bybit_api_key"
        val secretName = if (isBinance) "binance_api_secret" else "bybit_api_secret"
        val keyField = input("API Key ${prettyExchange()}").apply { setText(secureStore.get(apiName)) }
        val secretField = input("Secret Key ${prettyExchange()}", secret = true).apply { setText(secureStore.get(secretName)) }
        page.addView(keyField)
        page.addView(secretField)

        page.addView(primaryButton("Enregistrer les clés") {
            secureStore.put(apiName, keyField.text.toString().trim())
            secureStore.put(secretName, secretField.text.toString().trim())
            Toast.makeText(this, "Clés enregistrées sur ce téléphone", Toast.LENGTH_SHORT).show()
        })
        page.addView(secondaryButton("Tester + synchroniser") {
            secureStore.put(apiName, keyField.text.toString().trim())
            secureStore.put(secretName, secretField.text.toString().trim())
            syncSelected()
        })

        val stateKey = if (isBinance) "binance_sync_state" else "bybit_sync_state"
        val state = prefs.getString(stateKey, "Jamais synchronisé") ?: "Jamais synchronisé"
        page.addView(infoBanner("État de connexion", state, if (state.startsWith("OK")) green else muted))
        page.addView(subTitle("Protection de l'application"))
        page.addView(settingRow("Clés API", "Chiffrées localement avec Android Keystore", "ACTIF"))
        page.addView(settingRow("Retraits", "Aucune fonction de retrait dans l'application", "BLOQUÉ"))
        page.addView(settingRow("Transferts", "Aucune fonction de transfert dans l'application", "BLOQUÉ"))
        page.addView(settingRow("Ordres Bybit", "Spot Limit + confirmation e-mail prévu pour la prochaine étape", "À VENIR"))
        attach(page)
    }

    private fun settingRow(title: String, desc: String, status: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(surface, border, 16)
            layoutParams = marginParams(bottom = 8)
            addView(LinearLayout(this@MainActivityV4).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivityV4).apply { text = title; textSize = 14f; setTextColor(text); setTypeface(Typeface.DEFAULT, Typeface.BOLD) })
                addView(TextView(this@MainActivityV4).apply { text = desc; textSize = 11f; setTextColor(muted) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivityV4).apply {
                text = status
                textSize = 10f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (status == "ACTIF" || status == "BLOQUÉ") green else orange)
            })
        }
    }

    private fun syncSelected() {
        if (exchange == "BINANCE") syncBinance() else syncBybit()
    }

    private fun syncAll() {
        Toast.makeText(this, "Synchronisation Binance + Bybit…", Toast.LENGTH_SHORT).show()
        runAsync(
            task = {
                val result = mutableListOf<String>()
                val bk = secureStore.get("binance_api_key")
                val bs = secureStore.get("binance_api_secret")
                if (bk.isNotBlank() && bs.isNotBlank()) {
                    runCatching {
                        val data = BinanceClient(bk, bs).loadWorkspaceData()
                        val at = workspaceSync.syncBinance(bk, data.snapshotJson)
                        saveSnapshot("BINANCE", data.portfolio)
                        prefs.edit().putString(historyKey("BINANCE"), data.historyText).putString("binance_sync_state", "OK • $at").apply()
                        result += "Binance OK"
                    }.onFailure { result += "Binance: ${it.message}" }
                }
                val yk = secureStore.get("bybit_api_key")
                val ys = secureStore.get("bybit_api_secret")
                if (yk.isNotBlank() && ys.isNotBlank()) {
                    runCatching {
                        val data = BybitClient(yk, ys).loadWorkspaceData()
                        val at = workspaceSync.syncBybit(yk, data.snapshotJson)
                        saveSnapshot("BYBIT", data.portfolio)
                        prefs.edit().putString(historyKey("BYBIT"), data.historyText).putString("bybit_sync_state", "OK • $at").putString("bybit_api_info", data.apiInfoText).apply()
                        result += "Bybit OK"
                    }.onFailure { result += "Bybit: ${it.message}" }
                }
                result.joinToString("\n")
            },
            success = { Toast.makeText(this, it, Toast.LENGTH_LONG).show(); rebuildUi() },
            failure = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        )
    }

    private fun syncBinance() {
        val key = secureStore.get("binance_api_key")
        val secret = secureStore.get("binance_api_secret")
        if (key.isBlank() || secret.isBlank()) {
            Toast.makeText(this, "Ajoute d'abord les clés Binance dans Réglages", Toast.LENGTH_LONG).show()
            section = "SETTINGS"; rebuildUi(); return
        }
        Toast.makeText(this, "Binance : synchronisation…", Toast.LENGTH_SHORT).show()
        runAsync(
            task = {
                val data = BinanceClient(key, secret).loadWorkspaceData()
                val at = workspaceSync.syncBinance(key, data.snapshotJson)
                Triple(data.portfolio, data.historyText, at)
            },
            success = { (portfolio, history, at) ->
                saveSnapshot("BINANCE", portfolio)
                prefs.edit().putString(historyKey("BINANCE"), history).putString("binance_sync_state", "OK • $at").apply()
                AlertCheckReceiver.checkNow(this)
                Toast.makeText(this, "Binance synchronisé", Toast.LENGTH_SHORT).show()
                rebuildUi()
            },
            failure = {
                prefs.edit().putString("binance_sync_state", "Échec • ${it.take(100)}").apply()
                Toast.makeText(this, "Erreur Binance : $it", Toast.LENGTH_LONG).show()
                rebuildUi()
            }
        )
    }

    private fun syncBybit() {
        val key = secureStore.get("bybit_api_key")
        val secret = secureStore.get("bybit_api_secret")
        if (key.isBlank() || secret.isBlank()) {
            Toast.makeText(this, "Ajoute d'abord les clés Bybit dans Réglages", Toast.LENGTH_LONG).show()
            section = "SETTINGS"; rebuildUi(); return
        }
        Toast.makeText(this, "Bybit : synchronisation…", Toast.LENGTH_SHORT).show()
        runAsync(
            task = {
                val data = BybitClient(key, secret).loadWorkspaceData()
                val at = workspaceSync.syncBybit(key, data.snapshotJson)
                arrayOf(data.portfolio, data.historyText, at, data.apiInfoText)
            },
            success = { result ->
                val portfolio = result[0] as PortfolioSnapshot
                val history = result[1] as String
                val at = result[2] as String
                val apiInfo = result[3] as String
                saveSnapshot("BYBIT", portfolio)
                prefs.edit().putString(historyKey("BYBIT"), history).putString("bybit_sync_state", "OK • $at").putString("bybit_api_info", apiInfo).apply()
                Toast.makeText(this, "Bybit synchronisé", Toast.LENGTH_SHORT).show()
                rebuildUi()
            },
            failure = {
                prefs.edit().putString("bybit_sync_state", "Échec • ${it.take(100)}").apply()
                Toast.makeText(this, "Erreur Bybit : $it", Toast.LENGTH_LONG).show()
                rebuildUi()
            }
        )
    }

    private fun saveSnapshot(code: String, s: PortfolioSnapshot) {
        val obj = JSONObject().apply {
            put("capturedAt", s.capturedAt)
            put("totalUsdt", s.totalUsdt)
            put("totalEur", s.totalEur)
            put("eurUsdt", s.eurUsdt)
            put("holdings", JSONArray().apply {
                s.holdings.forEach { h ->
                    put(JSONObject().apply {
                        put("asset", h.asset)
                        put("amount", h.amount)
                        put("priceUsdt", h.priceUsdt)
                        put("valueUsdt", h.valueUsdt)
                    })
                }
            })
        }
        prefs.edit().putString(snapshotKey(code), obj.toString()).apply()
    }

    private fun loadSnapshot(code: String): PortfolioSnapshot? {
        val raw = prefs.getString(snapshotKey(code), null)
            ?: if (code == "BINANCE") prefs.getString("last_snapshot", null) else prefs.getString("bybit_last_snapshot", null)
            ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val a = o.optJSONArray("holdings") ?: JSONArray()
            val holdings = mutableListOf<Holding>()
            for (i in 0 until a.length()) {
                val h = a.optJSONObject(i) ?: continue
                holdings += Holding(h.optString("asset"), h.optDouble("amount"), h.optDouble("priceUsdt"), h.optDouble("valueUsdt"))
            }
            PortfolioSnapshot(o.optLong("capturedAt"), o.optDouble("totalUsdt"), o.optDouble("totalEur"), o.optDouble("eurUsdt"), holdings)
        }.getOrNull()
    }

    private fun snapshotKey(code: String) = "v4_snapshot_${code.lowercase(Locale.US)}"
    private fun historyKey(code: String) = if (code == "BINANCE") "binance_history" else "bybit_history"

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(26))
    }

    private fun attach(page: LinearLayout) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(14))
        addView(TextView(this@MainActivityV4).apply {
            text = title
            textSize = 24f
            setTextColor(text)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        addView(TextView(this@MainActivityV4).apply {
            text = subtitle
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(3), 0, 0)
        })
    }

    private fun subTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(text)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(dp(2), dp(14), 0, dp(9))
    }

    private fun heroCard(eyebrow: String, value: String, detail: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(18))
        background = rounded(surface, accent(), 22)
        layoutParams = marginParams(bottom = 12)
        addView(TextView(this@MainActivityV4).apply { text = eyebrow; textSize = 11f; setTextColor(accent()); setTypeface(Typeface.DEFAULT, Typeface.BOLD) })
        addView(TextView(this@MainActivityV4).apply { text = value; textSize = 34f; setTextColor(text); setTypeface(Typeface.DEFAULT, Typeface.BOLD); setPadding(0, dp(3), 0, dp(3)) })
        addView(TextView(this@MainActivityV4).apply { text = detail; textSize = 12f; setTextColor(muted) })
    }

    private fun metricCard(title: String, value: String, color: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(surface, border, 18)
        addView(TextView(this@MainActivityV4).apply { text = title; textSize = 11f; setTextColor(color); setTypeface(Typeface.DEFAULT, Typeface.BOLD) })
        addView(TextView(this@MainActivityV4).apply { text = value; textSize = 17f; setTextColor(text); setTypeface(Typeface.DEFAULT, Typeface.BOLD); setPadding(0, dp(5), 0, 0) })
    }

    private fun twoColumnCards(left: View, right: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(5), 0) })
        addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(5), 0, 0, 0) })
    }

    private fun twoColumnButtons(left: View, right: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(0, 0, dp(5), 0) })
        addView(right, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(5), 0, 0, 0) })
    }

    private fun actionButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(text)
        background = rounded(surface2, border, 16)
        setOnClickListener { click() }
    }

    private fun primaryButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(Color.BLACK)
        background = rounded(accent(), Color.TRANSPARENT, 16)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { setMargins(0, dp(10), 0, dp(4)) }
    }

    private fun secondaryButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(text)
        background = rounded(surface2, border, 16)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { setMargins(0, dp(6), 0, dp(4)) }
    }

    private fun chip(label: String, active: Boolean, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 10f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(if (active) Color.BLACK else muted)
        background = if (active) rounded(accent(), Color.TRANSPARENT, 999) else rounded(surface2, border, 999)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { setMargins(0, 0, dp(6), 0) }
        setPadding(dp(12), 0, dp(12), 0)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(15), dp(15), dp(15))
        background = rounded(surface, border, 18)
        layoutParams = marginParams(bottom = 10)
    }

    private fun emptyCard(title: String, body: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(24), dp(20), dp(24))
        background = rounded(surface, border, 18)
        layoutParams = marginParams(bottom = 10)
        addView(TextView(this@MainActivityV4).apply { text = title; textSize = 16f; setTextColor(text); setTypeface(Typeface.DEFAULT, Typeface.BOLD); gravity = Gravity.CENTER })
        addView(TextView(this@MainActivityV4).apply { text = body; textSize = 12f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
    }

    private fun infoBanner(title: String, body: String, color: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(surface2, color, 16)
        layoutParams = marginParams(bottom = 10)
        addView(TextView(this@MainActivityV4).apply { text = title; textSize = 12f; setTextColor(color); setTypeface(Typeface.DEFAULT, Typeface.BOLD) })
        addView(TextView(this@MainActivityV4).apply { text = body; textSize = 11f; setTextColor(muted); setPadding(0, dp(4), 0, 0) })
    }

    private fun input(hintValue: String, secret: Boolean = false, multi: Boolean = false): EditText = EditText(this).apply {
        hint = hintValue
        setHintTextColor(Color.rgb(112, 121, 134))
        setTextColor(text)
        textSize = 14f
        background = rounded(surface2, border, 14)
        setPadding(dp(13), dp(11), dp(13), dp(11))
        gravity = if (multi) Gravity.TOP else Gravity.CENTER_VERTICAL
        if (multi) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
        } else {
            setSingleLine(true)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (multi) dp(100) else dp(52)).apply { setMargins(0, 0, 0, dp(8)) }
    }

    private fun marginParams(bottom: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        setMargins(0, 0, 0, dp(bottom))
    }

    private fun accent() = if (exchange == "BINANCE") yellow else orange
    private fun prettyExchange() = if (exchange == "BINANCE") "Binance" else "Bybit"
    private fun kindColor(kind: String) = when (kind.uppercase(Locale.FRANCE)) {
        "ACHAT" -> green
        "VENTE" -> red
        "ALERTE" -> yellow
        "PLAN" -> orange
        else -> blue
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun transparentRounded() = rounded(Color.TRANSPARENT, Color.TRANSPARENT, 14)

    private fun <T> runAsync(task: () -> T, success: (T) -> Unit, failure: (String) -> Unit) {
        Thread {
            try {
                val result = task()
                runOnUiThread { success(result) }
            } catch (e: Exception) {
                runOnUiThread { failure(e.message ?: e.javaClass.simpleName) }
            }
        }.start()
    }

    private fun fmt(v: Double): String = when {
        kotlin.math.abs(v) >= 1000 -> String.format(Locale.FRANCE, "%,.0f", v)
        kotlin.math.abs(v) >= 100 -> String.format(Locale.FRANCE, "%.1f", v)
        kotlin.math.abs(v) >= 10 -> String.format(Locale.FRANCE, "%.2f", v)
        kotlin.math.abs(v) >= 1 -> String.format(Locale.FRANCE, "%.3f", v)
        kotlin.math.abs(v) >= .01 -> String.format(Locale.FRANCE, "%.5f", v)
        else -> String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
    }

    private fun fmtMoney(v: Double): String = String.format(Locale.FRANCE, "%.2f", v)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}