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

        val exchanges = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        exchanges.addView(exchangeButton("BINANCE", "Binance"), weightFullParams())
        exchanges.addView(exchangeButton("BYBIT", "Bybit"), weightFullParams())
        root.addView(exchanges, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        nav.addView(navButton("PORTFOLIO", "Portefeuille"), weightFullParams())
        nav.addView(navButton("HISTORY", "PRU"), weightFullParams())
        nav.addView(navButton("NOTES", "Notes"), weightFullParams())
        nav.addView(navButton("SETTINGS", "Réglages"), weightFullParams())
        root.addView(nav, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))
        return root
    }

    private fun exchangeButton(code: String, label: String): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(if (exchange == code) Color.BLACK else fgColor)
        setBackgroundColor(if (exchange == code) if (code == "BINANCE") yellowColor else orangeColor else card2Color)
        setOnClickListener { exchange = code; section = "PORTFOLIO"; rebuildUi() }
    }

    private fun navButton(code: String, label: String): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 11f
        setTextColor(if (section == code) fgColor else mutedColor)
        setBackgroundColor(if (section == code) card2Color else bgColor)
        setPadding(1, 1, 1, 1)
        setOnClickListener { section = code; rebuildUi() }
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
        val prefix = if (exchange == "BINANCE") "binance" else "bybit"
        val cached = loadSnapshot(prefix) ?: if (exchange == "BINANCE") loadLegacyBinanceSnapshot() else null

        if (exchange == "BINANCE") {
            page.addView(title("Binance"))
            page.addView(infoCard("Mode", "Lecture du portefeuille, historique Spot, PRU estimé et alertes. Les clés restent chiffrées sur ce téléphone."))
        } else {
            page.addView(title("Bybit EU"))
            page.addView(infoCard("API V5 Bybit EU", "Connexion directe à api.bybit.eu. La clé et le Secret restent chiffrés avec Android Keystore sur ce téléphone."))
            val apiInfo = prefs.getString("bybit_api_info", null)
            if (!apiInfo.isNullOrBlank()) page.addView(infoCard("Clé détectée", apiInfo))
            page.addView(infoCard("Ordres limite", "Sécurité volontaire : cette version ne transmet encore aucun ordre. L'envoi Spot Limit sera activé seulement après ajout de la confirmation par e-mail."))
        }

        if (cached == null) {
            page.addView(infoCard("Aucune synchronisation", "Va dans Réglages, enregistre les clés API de ${if (exchange == "BINANCE") "Binance" else "Bybit"}, puis lance la synchronisation."))
        } else {
            page.addView(portfolioHeader(cached))
            cached.holdings.take(30).forEach { page.addView(holdingCard(it)) }
        }

        val stateKey = if (exchange == "BINANCE") "workspace_sync" else "bybit_workspace_sync"
        page.addView(infoCard("Workspace partagé", "Synchronisation privée : ${prefs.getString(stateKey, "En attente") ?: "En attente"}"))
        page.addView(primaryButton("Actualiser portefeuille + historique") {
            if (exchange == "BINANCE") syncBinancePortfolio() else syncBybitPortfolio()
        })
        if (exchange == "BINANCE") {
            page.addView(secondaryButton("Vérifier les alertes maintenant") {
                AlertCheckReceiver.checkNow(this)
                Toast.makeText(this, "Vérification des seuils lancée", Toast.LENGTH_SHORT).show()
            })
        }
        attachPage(page)
    }

    private fun portfolioHeader(snapshot: PortfolioSnapshot): View {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(snapshot.capturedAt))
        return cardLayout().apply {
            addView(label("Valeur estimée du portefeuille"))
            addView(bigValue("${fmt(snapshot.totalEur)} €"))
            addView(smallText("≈ ${fmt(snapshot.totalUsdt)} USD/USDT"))
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
            text = "${fmt(h.valueUsdt)} USD"
            textSize = 16f
            setTextColor(fgColor)
            gravity = Gravity.END
        })
        addView(row)
        addView(smallText("Qté ${fmt(h.amount)} • Prix ${fmt(h.priceUsdt)} USD"))
    }

    private fun renderHistory() {
        val page = pageLayout()
        if (exchange == "BINANCE") {
            page.addView(title("PRU & historique Binance"))
            showCachedHistory(page, "binance_history")
            page.addView(label("Recherche détaillée d'une paire Binance"))
            val symbol = input("RENDERUSDT", false)
            val result = resultText()
            page.addView(symbol)
            page.addView(primaryButton("Charger cette paire") {
                val s = symbol.text.toString().trim().uppercase(Locale.US)
                if (s.isBlank()) return@primaryButton
                result.text = "Chargement…"
                runAsync(
                    task = { val c = binanceClientOrThrow(); c.formatTradeSummary(s, c.loadTrades(s, 1000)) },
                    success = { result.text = it },
                    failure = { result.text = "Erreur : $it" }
                )
            })
            page.addView(result)
        } else {
            page.addView(title("PRU & historique Bybit"))
            showCachedHistory(page, "bybit_history")
            page.addView(smallText("Le PRU est calculé à partir des exécutions Spot disponibles via l'API V5 Bybit. Les transferts, Convert, Earn et certains frais peuvent ne pas être inclus."))
        }
        attachPage(page)
    }

    private fun showCachedHistory(page: LinearLayout, key: String) {
        val history = prefs.getString(key, null)
        if (history.isNullOrBlank()) page.addView(infoCard("Historique non chargé", "Actualise le portefeuille pour récupérer les opérations Spot et calculer un PRU estimé."))
        else page.addView(resultText().apply { text = history })
    }

    private fun renderNotes() {
        val page = pageLayout()
        page.addView(title("Bloc-notes CHK partagé"))
        page.addView(smallText("Notes privées communes à Binance et Bybit : niveaux d'achat/vente, alertes, analyses et futurs ordres proposés."))

        val remote = resultText().apply { text = "Chargement des notes partagées…"; setBackgroundColor(cardColor) }
        page.addView(remote)
        page.addView(secondaryButton("Actualiser les notes") { loadRemoteNotes(remote) })

        val draft = EditText(this).apply {
            hint = "Exemple : RENDER — achat limite à 1,30 USDC"
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
                success = { draft.setText(""); Toast.makeText(this, "Note synchronisée", Toast.LENGTH_SHORT).show(); loadRemoteNotes(remote) },
                failure = { Toast.makeText(this, "Note non synchronisée : $it", Toast.LENGTH_LONG).show() }
            )
        })
        attachPage(page)
        loadRemoteNotes(remote)
    }

    private fun loadRemoteNotes(target: TextView) {
        runAsync(
            task = { workspaceSync.listNotes() },
            success = { raw ->
                val arr = JSONObject(raw).optJSONArray("notes") ?: JSONArray()
                if (arr.length() == 0) { target.text = "Aucune note partagée pour le moment."; return@runAsync }
                val df = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
                val out = StringBuilder()
                for (i in 0 until arr.length()) {
                    val n = arr.optJSONObject(i) ?: continue
                    val whenText = runCatching { df.format(Date(java.time.Instant.parse(n.optString("created_at")).toEpochMilli())) }.getOrDefault("")
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

    private fun renderSettings() {
        val page = pageLayout()
        if (exchange == "BINANCE") {
            page.addView(title("Réglages Binance"))
            page.addView(infoCard("Sécurité", "Clé Binance dédiée en lecture seule recommandée. Le Secret reste chiffré par Android Keystore."))
            val key = input("Clé API Binance", false).apply { setText(secureStore.get("binance_api_key")) }
            val secret = input("Secret API Binance", true).apply { setText(secureStore.get("binance_api_secret")) }
            page.addView(key); page.addView(secret)
            page.addView(primaryButton("Enregistrer") {
                secureStore.put("binance_api_key", key.text.toString().trim())
                secureStore.put("binance_api_secret", secret.text.toString().trim())
                Toast.makeText(this, "Clés Binance enregistrées sur le téléphone", Toast.LENGTH_SHORT).show()
            })
            page.addView(secondaryButton("Tester + synchroniser") {
                secureStore.put("binance_api_key", key.text.toString().trim())
                secureStore.put("binance_api_secret", secret.text.toString().trim())
                syncBinancePortfolio()
            })
        } else {
            page.addView(title("Réglages Bybit EU"))
            page.addView(infoCard("Sécurité", "Colle ici l'API Key et le Secret Bybit que tu viens de créer. Ils restent chiffrés uniquement sur ce téléphone et ne sont jamais envoyés à GitHub, Supabase ou ChatGPT."))
            page.addView(infoCard("Permission Trader", "Ta clé peut avoir la permission Spot Trader pour la future fonction d'ordre limite. Cette v0.3 n'appelle pourtant aucun endpoint de création d'ordre : le trading reste verrouillé jusqu'à la confirmation e-mail."))
            val key = input("API Key Bybit", false).apply { setText(secureStore.get("bybit_api_key")) }
            val secret = input("Secret Key Bybit", true).apply { setText(secureStore.get("bybit_api_secret")) }
            page.addView(key); page.addView(secret)
            page.addView(primaryButton("Enregistrer") {
                secureStore.put("bybit_api_key", key.text.toString().trim())
                secureStore.put("bybit_api_secret", secret.text.toString().trim())
                Toast.makeText(this, "Clés Bybit chiffrées sur le téléphone", Toast.LENGTH_SHORT).show()
            })
            page.addView(secondaryButton("Tester + synchroniser Bybit") {
                secureStore.put("bybit_api_key", key.text.toString().trim())
                secureStore.put("bybit_api_secret", secret.text.toString().trim())
                syncBybitPortfolio()
            })
        }
        page.addView(smallText("Ne publie jamais une Secret Key dans une capture, un dépôt GitHub ou un message."))
        attachPage(page)
    }

    private fun syncBinancePortfolio() {
        Toast.makeText(this, "Binance : synchronisation…", Toast.LENGTH_SHORT).show()
        runAsync(
            task = {
                val key = secureStore.get("binance_api_key")
                val data = binanceClientOrThrow().loadWorkspaceData()
                val syncedAt = workspaceSync.syncBinance(key, data.snapshotJson)
                data to syncedAt
            },
            success = { (data, syncedAt) ->
                saveSnapshot("binance", data.portfolio)
                prefs.edit().putString("binance_history", data.historyText).putString("workspace_sync", "OK • $syncedAt").apply()
                AlertCheckReceiver.checkNow(this)
                section = "PORTFOLIO"; rebuildUi()
                Toast.makeText(this, "Binance synchronisé", Toast.LENGTH_SHORT).show()
            },
            failure = { prefs.edit().putString("workspace_sync", "Échec : ${it.take(80)}").apply(); Toast.makeText(this, "Erreur Binance : $it", Toast.LENGTH_LONG).show() }
        )
    }

    private fun syncBybitPortfolio() {
        Toast.makeText(this, "Bybit EU : synchronisation…", Toast.LENGTH_SHORT).show()
        runAsync(
            task = {
                val key = secureStore.get("bybit_api_key")
                val data = bybitClientOrThrow().loadWorkspaceData()
                val syncedAt = workspaceSync.syncBybit(key, data.snapshotJson)
                data to syncedAt
            },
            success = { (data, syncedAt) ->
                saveSnapshot("bybit", data.portfolio)
                prefs.edit()
                    .putString("bybit_history", data.historyText)
                    .putString("bybit_api_info", data.apiInfoText)
                    .putString("bybit_workspace_sync", "OK • $syncedAt")
                    .apply()
                section = "PORTFOLIO"; rebuildUi()
                Toast.makeText(this, "Bybit EU synchronisé", Toast.LENGTH_SHORT).show()
            },
            failure = {
                prefs.edit().putString("bybit_workspace_sync", "Échec : ${it.take(90)}").apply()
                Toast.makeText(this, "Erreur Bybit : $it", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun binanceClientOrThrow(): BinanceClient {
        val key = secureStore.get("binance_api_key")
        val secret = secureStore.get("binance_api_secret")
        if (key.isBlank() || secret.isBlank()) throw IllegalStateException("Configure d'abord les clés Binance dans Réglages.")
        return BinanceClient(key, secret)
    }

    private fun bybitClientOrThrow(): BybitClient {
        val key = secureStore.get("bybit_api_key")
        val secret = secureStore.get("bybit_api_secret")
        if (key.isBlank() || secret.isBlank()) throw IllegalStateException("Configure d'abord les deux clés Bybit dans Réglages.")
        return BybitClient(key, secret)
    }

    private fun saveSnapshot(prefix: String, s: PortfolioSnapshot) {
        val obj = JSONObject().apply {
            put("capturedAt", s.capturedAt); put("totalUsdt", s.totalUsdt); put("totalEur", s.totalEur); put("eurUsdt", s.eurUsdt)
            put("holdings", JSONArray().apply {
                s.holdings.forEach { h -> put(JSONObject().apply { put("asset", h.asset); put("amount", h.amount); put("priceUsdt", h.priceUsdt); put("valueUsdt", h.valueUsdt) }) }
            })
        }
        prefs.edit().putString("${prefix}_snapshot", obj.toString()).apply()
    }

    private fun loadSnapshot(prefix: String): PortfolioSnapshot? = parseSnapshot(prefs.getString("${prefix}_snapshot", null))
    private fun loadLegacyBinanceSnapshot(): PortfolioSnapshot? = parseSnapshot(prefs.getString("last_snapshot", null))

    private fun parseSnapshot(raw: String?): PortfolioSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(raw); val arr = obj.getJSONArray("holdings"); val holdings = mutableListOf<Holding>()
            for (i in 0 until arr.length()) {
                val h = arr.getJSONObject(i)
                holdings += Holding(h.getString("asset"), h.getDouble("amount"), h.getDouble("priceUsdt"), h.getDouble("valueUsdt"))
            }
            PortfolioSnapshot(obj.getLong("capturedAt"), obj.getDouble("totalUsdt"), obj.getDouble("totalEur"), obj.getDouble("eurUsdt"), holdings)
        }.getOrNull()
    }

    private fun detectNoteKind(value: String): String = when {
        value.uppercase(Locale.FRANCE).startsWith("ACHAT") -> "ACHAT"
        value.uppercase(Locale.FRANCE).startsWith("VENTE") -> "VENTE"
        value.uppercase(Locale.FRANCE).startsWith("ALERTE") -> "ALERTE"
        else -> "NOTE"
    }

    private fun appendNote(edit: EditText, prefix: String) {
        edit.text.insert(edit.selectionStart.coerceAtLeast(0), prefix)
        edit.requestFocus()
    }

    private fun <T> runAsync(task: () -> T, success: (T) -> Unit, failure: (String) -> Unit) {
        Thread {
            try { val result = task(); runOnUiThread { success(result) } }
            catch (e: Exception) { runOnUiThread { failure(e.message ?: e.javaClass.simpleName) } }
        }.start()
    }

    private fun pageLayout() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(12), dp(2), dp(20)) }
    private fun attachPage(page: LinearLayout) {
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        content.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun title(value: String) = TextView(this).apply { text = value; setTextColor(fgColor); textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(dp(6), dp(4), 0, dp(12)) }
    private fun label(value: String) = TextView(this).apply { text = value; setTextColor(mutedColor); textSize = 13f; setPadding(dp(4), dp(5), dp(4), dp(5)) }
    private fun bigValue(value: String) = TextView(this).apply { text = value; setTextColor(fgColor); textSize = 30f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(4), 0, dp(4)) }
    private fun smallText(value: String) = TextView(this).apply { text = value; setTextColor(mutedColor); textSize = 13f; setPadding(dp(4), dp(5), dp(4), dp(5)) }
    private fun resultText() = TextView(this).apply { setTextColor(fgColor); textSize = 14f; setTextIsSelectable(true); setPadding(dp(10), dp(12), dp(10), dp(16)) }

    private fun cardLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(cardColor); setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(10)) }
    }
    private fun infoCard(head: String, body: String): View = cardLayout().apply {
        addView(TextView(this@MainActivity).apply { text = head; setTextColor(fgColor); textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        addView(smallText(body))
    }
    private fun input(hintValue: String, secret: Boolean): EditText = EditText(this).apply {
        hint = hintValue; setHintTextColor(mutedColor); setTextColor(fgColor); setBackgroundColor(cardColor); textSize = 15f; setSingleLine(true); setPadding(dp(14), dp(12), dp(14), dp(12))
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { setMargins(0, 0, 0, dp(10)) }
    }
    private fun primaryButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; setTextColor(Color.BLACK); setBackgroundColor(if (exchange == "BINANCE") yellowColor else orangeColor); setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { setMargins(0, dp(8), 0, dp(8)) }
    }
    private fun secondaryButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; setTextColor(fgColor); setBackgroundColor(card2Color); setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { setMargins(0, dp(6), 0, dp(6)) }
    }

    private fun fmt(v: Double): String = if (exchange == "BINANCE") BinanceClient.fmt(v) else BybitClient.fmt(v)
    private fun weightFullParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
    private fun weightWrapParams() = LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
