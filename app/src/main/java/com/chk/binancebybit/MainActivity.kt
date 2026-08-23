package com.chk.binancebybit

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private lateinit var secureStore: SecureStore
    private lateinit var workspaceSync: WorkspaceSync
    private val prefs by lazy { getSharedPreferences("chk_workspace", MODE_PRIVATE) }

    private var exchange = "BINANCE"
    private var section = "PORTFOLIO"

    private val bgColor = Color.rgb(10, 10, 12)
    private val cardColor = Color.rgb(25, 26, 30)
    private val card2Color = Color.rgb(34, 35, 40)
    private val fgColor = Color.rgb(245, 245, 247)
    private val mutedColor = Color.rgb(165, 165, 175)
    private val yellowColor = Color.rgb(240, 185, 11)
    private val orangeColor = Color.rgb(245, 142, 30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureStore = SecureStore(this)
        workspaceSync = WorkspaceSync(this, secureStore)
        workspaceSync.ensureIdentity()
        AlertCheckReceiver.createChannel(this)
        AlertCheckReceiver.schedule(this)
        requestNotificationPermission()
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor
        rebuildUi()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized && exchange == "BINANCE" && section == "PORTFOLIO") {
            runCatching { rebuildUi() }
        }
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
            setBackgroundColor(bgColor)
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }

        root.addView(TextView(this).apply {
            text = "CHK Crypto Workspace"
            setTextColor(fgColor)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(6), 0, dp(12))
        })

        val exchangeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        exchangeBar.addView(exchangeButton("BINANCE", "Binance"), weightFullParams())
        exchangeBar.addView(exchangeButton("BYBIT", "Bybit"), weightFullParams())
        root.addView(exchangeBar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        nav.addView(navButton("PORTFOLIO", "Portefeuille"), weightFullParams())
        nav.addView(navButton("HISTORY", "PRU"), weightFullParams())
        nav.addView(navButton("NOTES", "Notes"), weightFullParams())
        nav.addView(navButton("SETTINGS", "Réglages"), weightFullParams())
        root.addView(nav, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))
        return root
    }

    private fun exchangeButton(code: String, label: String): Button = Button(this).apply {
        text = label
        setTextColor(if (exchange == code) Color.BLACK else fgColor)
        textSize = 16f
        isAllCaps = false
        setBackgroundColor(if (exchange == code) if (code == "BINANCE") yellowColor else orangeColor else card2Color)
        setOnClickListener {
            exchange = code
            section = "PORTFOLIO"
            rebuildUi()
        }
    }

    private fun navButton(code: String, label: String): Button = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setTextColor(if (section == code) fgColor else mutedColor)
        setBackgroundColor(if (section == code) card2Color else bgColor)
        setPadding(1, 1, 1, 1)
        setOnClickListener {
            section = code
            rebuildUi()
        }
    }

    private fun render() {
        content.removeAllViews()
        when (section) {
            "PORTFOLIO" -> renderPortfolio()
            "HISTORY" -> renderHistory()
            "NOTES" -> renderNotes()
            "SETTINGS" -> renderSettings()
        }
    }

    private fun renderPortfolio() {
        val page = pageLayout()
        if (exchange == "BYBIT") {
            page.addView(title("Bybit"))
            page.addView(infoCard("Connecteur Bybit", "Onglet déjà intégré. La connexion Bybit API sera branchée à l'étape suivante sans toucher à la partie Binance."))
            page.addView(infoCard("Prévu", "Portefeuille • historique • PRU • alertes • notes partagées CHK."))
            attachPage(page)
            return
        }

        page.addView(title("Binance • lecture seule"))
        val cached = loadCachedSnapshot()
        if (cached == null) {
            page.addView(infoCard("Aucune synchronisation", "Va dans Réglages, ajoute ta clé API Binance en lecture seule puis lance la synchronisation."))
        } else {
            page.addView(portfolioHeader(cached))
            cached.holdings.take(30).forEach { page.addView(holdingCard(it)) }
        }

        val syncState = prefs.getString("workspace_sync", "En attente") ?: "En attente"
        val alertCount = prefs.getInt("alert_count", 0)
        page.addView(infoCard("Workspace ChatGPT", "Synchronisation privée : $syncState\nAlertes actives : $alertCount"))
        page.addView(primaryButton("Actualiser portefeuille + historique") { syncPortfolio() })
        page.addView(secondaryButton("Vérifier les alertes maintenant") {
            AlertCheckReceiver.checkNow(this)
            Toast.makeText(this, "Vérification des seuils lancée", Toast.LENGTH_SHORT).show()
        })
        page.addView(smallText("Aucun outil de retrait, transfert ou ordre automatique n'est présent dans l'application."))
        attachPage(page)
    }

    private fun portfolioHeader(snapshot: PortfolioSnapshot): View {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(snapshot.capturedAt))
        return cardLayout().apply {
            addView(label("Valeur estimée du portefeuille"))
            addView(bigValue("${BinanceClient.fmt(snapshot.totalEur)} €"))
            addView(smallText("≈ ${BinanceClient.fmt(snapshot.totalUsdt)} USDT"))
            addView(smallText("Dernière synchro : $date"))
        }
    }

    private fun holdingCard(h: Holding): View = cardLayout().apply {
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this@MainActivity).apply {
            text = h.asset
            textSize = 18f
            setTextColor(fgColor)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this@MainActivity).apply {
            text = "${BinanceClient.fmt(h.valueUsdt)} USDT"
            textSize = 16f
            setTextColor(fgColor)
            gravity = Gravity.END
        })
        addView(row)
        addView(smallText("Qté ${BinanceClient.fmt(h.amount)} • Prix ${BinanceClient.fmt(h.priceUsdt)} USDT"))
    }

    private fun renderHistory() {
        val page = pageLayout()
        if (exchange == "BYBIT") {
            page.addView(title("PRU / Historique Bybit"))
            page.addView(infoCard("À venir", "Cette zone sera alimentée par le connecteur Bybit."))
            attachPage(page)
            return
        }

        page.addView(title("PRU & historique Binance"))
        val cachedHistory = prefs.getString("binance_history", null)
        if (!cachedHistory.isNullOrBlank()) {
            page.addView(TextView(this).apply {
                text = cachedHistory
                setTextColor(fgColor)
                textSize = 14f
                setTextIsSelectable(true)
                setPadding(dp(10), dp(12), dp(10), dp(16))
            })
        } else {
            page.addView(infoCard("Historique non chargé", "Actualise le portefeuille pour calculer les PRU estimés des actifs détenus."))
        }

        page.addView(label("Recherche détaillée d'une paire"))
        val symbol = input("RENDERUSDT", false)
        page.addView(symbol)
        val result = TextView(this).apply {
            setTextColor(fgColor)
            textSize = 14f
            setPadding(dp(10), dp(16), dp(10), dp(16))
            setTextIsSelectable(true)
        }
        page.addView(primaryButton("Charger cette paire") {
            val s = symbol.text.toString().trim().uppercase(Locale.US)
            if (s.isBlank()) return@primaryButton
            result.text = "Chargement…"
            runAsync(
                task = {
                    val client = clientOrThrow()
                    client.formatTradeSummary(s, client.loadTrades(s, 1000))
                },
                success = { result.text = it },
                failure = { result.text = "Erreur : $it" }
            )
        })
        page.addView(result)
        page.addView(smallText("PRU estimé à partir des transactions Spot disponibles. Les achats carte, Convert, transferts, Earn et certains frais peuvent manquer."))
        attachPage(page)
    }

    private fun renderNotes() {
        val page = pageLayout()
        page.addView(title("Bloc-notes CHK partagé"))
        page.addView(smallText("Les notes partagées sont privées et liées au compte synchronisé. ChatGPT pourra y écrire des niveaux d'achat, vente, alertes ou analyses."))

        val remote = TextView(this).apply {
            text = "Chargement des notes partagées…"
            setTextColor(fgColor)
            textSize = 14f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(cardColor)
            setTextIsSelectable(true)
        }
        page.addView(remote)
        page.addView(secondaryButton("Actualiser les notes partagées") { loadRemoteNotes(remote) })

        val draft = EditText(this).apply {
            hint = "Nouvelle note : ex. RENDER — achat limite à 1,30 USDC"
            setHintTextColor(mutedColor)
            setTextColor(fgColor)
            setBackgroundColor(cardColor)
            gravity = Gravity.TOP
            minLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        page.addView(draft, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)).apply { setMargins(0, dp(12), 0, dp(8)) })

        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quick.addView(secondaryButton("ACHAT") { appendNote(draft, "ACHAT — ") }, weightWrapParams())
        quick.addView(secondaryButton("VENTE") { appendNote(draft, "VENTE — ") }, weightWrapParams())
        quick.addView(secondaryButton("ALERTE") { appendNote(draft, "ALERTE — ") }, weightWrapParams())
        page.addView(quick)

        page.addView(primaryButton("Ajouter au bloc-notes partagé") {
            val value = draft.text.toString().trim()
            if (value.isBlank()) {
                Toast.makeText(this, "Écris une note d'abord", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            runAsync(
                task = { workspaceSync.createNote(exchange, detectNoteKind(value), value) },
                success = {
                    draft.setText("")
                    Toast.makeText(this, "Note synchronisée", Toast.LENGTH_SHORT).show()
                    loadRemoteNotes(remote)
                },
                failure = { Toast.makeText(this, "Note non synchronisée : $it", Toast.LENGTH_LONG).show() }
            )
        })
        page.addView(smallText("La synchronisation des notes devient disponible après une première synchronisation Binance réussie sur cette application."))
        attachPage(page)
        loadRemoteNotes(remote)
    }

    private fun loadRemoteNotes(target: TextView) {
        runAsync(
            task = { workspaceSync.listNotes() },
            success = { raw ->
                val arr = JSONObject(raw).optJSONArray("notes") ?: JSONArray()
                if (arr.length() == 0) {
                    target.text = "Aucune note partagée pour le moment."
                    return@runAsync
                }
                val df = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
                val out = StringBuilder()
                for (i in 0 until arr.length()) {
                    val n = arr.optJSONObject(i) ?: continue
                    val whenText = runCatching {
                        val rawDate = n.optString("created_at")
                        val date = java.time.Instant.parse(rawDate).toEpochMilli()
                        df.format(Date(date))
                    }.getOrDefault("")
                    out.append(n.optString("kind", "NOTE")).append(" • ").append(n.optString("exchange", "GLOBAL"))
                    if (whenText.isNotBlank()) out.append(" • ").append(whenText)
                    if (n.optString("source") == "chatgpt") out.append(" • ChatGPT")
                    out.append('\n').append(n.optString("content")).append("\n\n")
                }
                target.text = out.toString().trim()
            },
            failure = { target.text = "Notes partagées indisponibles : $it" }
        )
    }

    private fun detectNoteKind(value: String): String {
        val v = value.uppercase(Locale.FRANCE)
        return when {
            v.startsWith("ACHAT") -> "ACHAT"
            v.startsWith("VENTE") -> "VENTE"
            v.startsWith("ALERTE") -> "ALERTE"
            else -> "NOTE"
        }
    }

    private fun appendNote(edit: EditText, prefix: String) {
        val start = edit.selectionStart.coerceAtLeast(0)
        edit.text.insert(start, prefix)
        edit.requestFocus()
    }

    private fun renderSettings() {
        val page = pageLayout()
        page.addView(title(if (exchange == "BINANCE") "Réglages Binance" else "Réglages Bybit"))

        if (exchange == "BYBIT") {
            page.addView(infoCard("Connecteur Bybit", "Les champs API Bybit seront ajoutés dans l'étape suivante. La structure de l'onglet est déjà prête."))
            attachPage(page)
            return
        }

        page.addView(infoCard("Sécurité", "Utilise une clé Binance dédiée en lecture seule. Ne donne pas les permissions de trading, transfert ou retrait. La clé et le secret restent chiffrés par Android Keystore sur ce téléphone."))
        val key = input("Clé API Binance", false).apply { setText(secureStore.get("binance_api_key")) }
        val secret = input("Secret API Binance", true).apply { setText(secureStore.get("binance_api_secret")) }
        page.addView(key)
        page.addView(secret)

        page.addView(primaryButton("Enregistrer") {
            secureStore.put("binance_api_key", key.text.toString().trim())
            secureStore.put("binance_api_secret", secret.text.toString().trim())
            Toast.makeText(this, "Clés enregistrées sur le téléphone", Toast.LENGTH_SHORT).show()
        })
        page.addView(secondaryButton("Tester + synchroniser Workspace") {
            secureStore.put("binance_api_key", key.text.toString().trim())
            secureStore.put("binance_api_secret", secret.text.toString().trim())
            syncPortfolio()
        })
        page.addView(smallText("Aucune clé API ni Secret Key n'est écrite dans GitHub, dans les notes partagées ou dans le snapshot envoyé au Workspace."))
        attachPage(page)
    }

    private fun syncPortfolio() {
        Toast.makeText(this, "Binance : portefeuille + historique…", Toast.LENGTH_SHORT).show()
        runAsync(
            task = {
                val key = secureStore.get("binance_api_key")
                val data = clientOrThrow().loadWorkspaceData()
                val syncedAt = workspaceSync.syncBinance(key, data.snapshotJson)
                Triple(data, syncedAt, key)
            },
            success = { (data, syncedAt, _) ->
                saveCachedSnapshot(data.portfolio)
                prefs.edit()
                    .putString("binance_history", data.historyText)
                    .putInt("binance_buy_count", data.buyCount)
                    .putString("workspace_sync", "OK • $syncedAt")
                    .apply()
                AlertCheckReceiver.checkNow(this)
                section = "PORTFOLIO"
                rebuildUi()
                Toast.makeText(this, "Binance et Workspace synchronisés", Toast.LENGTH_SHORT).show()
            },
            failure = {
                prefs.edit().putString("workspace_sync", "Échec : ${it.take(80)}").apply()
                Toast.makeText(this, "Erreur Binance : $it", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun clientOrThrow(): BinanceClient {
        val key = secureStore.get("binance_api_key")
        val secret = secureStore.get("binance_api_secret")
        if (key.isBlank() || secret.isBlank()) throw IllegalStateException("Configure d'abord la clé API dans Réglages.")
        return BinanceClient(key, secret)
    }

    private fun saveCachedSnapshot(s: PortfolioSnapshot) {
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
        prefs.edit().putString("last_snapshot", obj.toString()).apply()
    }

    private fun loadCachedSnapshot(): PortfolioSnapshot? {
        val raw = prefs.getString("last_snapshot", null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val arr = obj.getJSONArray("holdings")
            val holdings = mutableListOf<Holding>()
            for (i in 0 until arr.length()) {
                val h = arr.getJSONObject(i)
                holdings += Holding(h.getString("asset"), h.getDouble("amount"), h.getDouble("priceUsdt"), h.getDouble("valueUsdt"))
            }
            PortfolioSnapshot(obj.getLong("capturedAt"), obj.getDouble("totalUsdt"), obj.getDouble("totalEur"), obj.getDouble("eurUsdt"), holdings)
        }.getOrNull()
    }

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

    private fun pageLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(12), dp(2), dp(20))
    }

    private fun attachPage(page: LinearLayout) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun title(value: String) = TextView(this).apply {
        text = value
        setTextColor(fgColor)
        textSize = 22f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(6), dp(4), 0, dp(12))
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        setTextColor(mutedColor)
        textSize = 13f
        setPadding(dp(4), dp(5), dp(4), dp(5))
    }

    private fun bigValue(value: String) = TextView(this).apply {
        text = value
        setTextColor(fgColor)
        textSize = 30f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun smallText(value: String) = TextView(this).apply {
        text = value
        setTextColor(mutedColor)
        textSize = 13f
        setPadding(dp(4), dp(5), dp(4), dp(5))
    }

    private fun cardLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(cardColor)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(10)) }
    }

    private fun infoCard(head: String, body: String): View = cardLayout().apply {
        addView(TextView(this@MainActivity).apply {
            text = head
            setTextColor(fgColor)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(smallText(body))
    }

    private fun input(hintValue: String, secret: Boolean): EditText = EditText(this).apply {
        hint = hintValue
        setHintTextColor(mutedColor)
        setTextColor(fgColor)
        setBackgroundColor(cardColor)
        textSize = 15f
        setSingleLine(true)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { setMargins(0, 0, 0, dp(10)) }
    }

    private fun primaryButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.BLACK)
        setBackgroundColor(if (exchange == "BINANCE") yellowColor else orangeColor)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { setMargins(0, dp(8), 0, dp(8)) }
    }

    private fun secondaryButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(fgColor)
        setBackgroundColor(card2Color)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { setMargins(0, dp(6), 0, dp(6)) }
    }

    private fun weightFullParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
    private fun weightWrapParams() = LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
