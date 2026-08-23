package com.chk.binancebybit

import android.app.Activity
import android.graphics.Color
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
    private val prefs by lazy { getSharedPreferences("chk_workspace", MODE_PRIVATE) }

    private var exchange = "BINANCE"
    private var section = "PORTFOLIO"

    private val bg = Color.rgb(10, 10, 12)
    private val card = Color.rgb(25, 26, 30)
    private val card2 = Color.rgb(34, 35, 40)
    private val text = Color.rgb(245, 245, 247)
    private val muted = Color.rgb(165, 165, 175)
    private val yellow = Color.rgb(240, 185, 11)
    private val orange = Color.rgb(245, 142, 30)
    private val green = Color.rgb(35, 190, 120)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureStore = SecureStore(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        setContentView(buildRoot())
        render()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }

        root.addView(TextView(this).apply {
            text = "CHK Crypto Workspace"
            setTextColor(text)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(6), 0, dp(12))
        })

        val exchangeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        exchangeBar.addView(exchangeButton("BINANCE", "Binance"), weightParams())
        exchangeBar.addView(exchangeButton("BYBIT", "Bybit"), weightParams())
        root.addView(exchangeBar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        nav.addView(navButton("PORTFOLIO", "Portefeuille"), weightParams())
        nav.addView(navButton("HISTORY", "Historique"), weightParams())
        nav.addView(navButton("NOTES", "Notes"), weightParams())
        nav.addView(navButton("SETTINGS", "Réglages"), weightParams())
        root.addView(nav, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))
        return root
    }

    private fun exchangeButton(code: String, label: String): Button = Button(this).apply {
        text = label
        setTextColor(text)
        textSize = 16f
        setBackgroundColor(if (exchange == code) if (code == "BINANCE") yellow else orange else card2)
        setOnClickListener {
            exchange = code
            setContentView(buildRoot())
            render()
        }
    }

    private fun navButton(code: String, label: String): Button = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setTextColor(if (section == code) text else muted)
        setBackgroundColor(if (section == code) card2 else bg)
        setPadding(1, 1, 1, 1)
        setOnClickListener {
            section = code
            setContentView(buildRoot())
            render()
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
        if (exchange == "BYBIT") {
            content.addView(scrollPage().apply {
                addView(title("Bybit"))
                addView(infoCard("Connecteur Bybit", "Onglet prêt. La connexion API Bybit sera ajoutée après validation de la partie Binance."))
                addView(infoCard("Objectif", "Portefeuille • historique • prix moyen • alertes • notes CHK, dans la même application."))
            })
            return
        }

        val page = scrollPage()
        page.addView(title("Binance"))
        val cached = loadCachedSnapshot()
        if (cached == null) {
            page.addView(infoCard("Aucune synchronisation", "Va dans Réglages, enregistre une clé API Binance en lecture seule, puis reviens ici."))
        } else {
            page.addView(portfolioHeader(cached))
            cached.holdings.take(30).forEach { page.addView(holdingCard(it)) }
        }
        page.addView(primaryButton("Synchroniser Binance") { syncPortfolio() })
        page.addView(smallText("Lecture seule. L'application ne contient aucune fonction de retrait ou d'envoi d'ordre Binance."))
        content.addView(page)
    }

    private fun portfolioHeader(snapshot: PortfolioSnapshot): View {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(snapshot.capturedAt))
        return cardLayout().apply {
            addView(label("Total estimé"))
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
            setTextColor(text)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this@MainActivity).apply {
            text = "${BinanceClient.fmt(h.valueUsdt)} USDT"
            textSize = 16f
            setTextColor(text)
            gravity = Gravity.END
        })
        addView(row)
        addView(smallText("Qté ${BinanceClient.fmt(h.amount)} • Prix ${BinanceClient.fmt(h.priceUsdt)} USDT"))
    }

    private fun renderHistory() {
        if (exchange == "BYBIT") {
            content.addView(scrollPage().apply {
                addView(title("Historique Bybit"))
                addView(infoCard("À venir", "Le connecteur Bybit sera branché dans cette section."))
            })
            return
        }

        val page = scrollPage()
        page.addView(title("Historique Spot Binance"))
        page.addView(smallText("Entre une paire Binance, par exemple RENDERUSDT, FETUSDT ou LINKUSDT."))
        val symbol = input("RENDERUSDT", false)
        page.addView(symbol)
        val result = TextView(this).apply {
            setTextColor(text)
            textSize = 14f
            setPadding(dp(10), dp(16), dp(10), dp(16))
            setTextIsSelectable(true)
        }
        page.addView(primaryButton("Charger l'historique") {
            val s = symbol.text.toString().trim().uppercase(Locale.US)
            if (s.isBlank()) return@primaryButton
            result.text = "Chargement…"
            runAsync(
                task = {
                    val client = clientOrThrow()
                    client.formatTradeSummary(s, client.loadTrades(s))
                },
                success = { result.text = it },
                failure = { result.text = "Erreur : $it" }
            )
        })
        page.addView(result)
        content.addView(page)
    }

    private fun renderNotes() {
        val page = scrollPage()
        page.addView(title("Bloc-notes CHK"))
        page.addView(smallText("Notes locales sur ton téléphone : ordres envisagés, niveaux, alertes, analyses. Rien n'est publié sur GitHub."))

        val notes = EditText(this).apply {
            setText(prefs.getString("notes", ""))
            hint = "Exemple : RENDER — surveiller 1,30 USDC…"
            setHintTextColor(muted)
            setTextColor(text)
            setBackgroundColor(card)
            gravity = Gravity.TOP
            minLines = 14
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        page.addView(notes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(330)).apply {
            setMargins(0, dp(8), 0, dp(12))
        })

        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quick.addView(secondaryButton("ACHAT") { appendNote(notes, "\nACHAT — ") }, weightParams())
        quick.addView(secondaryButton("VENTE") { appendNote(notes, "\nVENTE — ") }, weightParams())
        quick.addView(secondaryButton("ALERTE") { appendNote(notes, "\nALERTE — ") }, weightParams())
        page.addView(quick)

        page.addView(primaryButton("Enregistrer les notes") {
            prefs.edit().putString("notes", notes.text.toString()).apply()
            Toast.makeText(this, "Notes enregistrées", Toast.LENGTH_SHORT).show()
        })
        page.addView(infoCard("Synchronisation assistant", "La base du bloc-notes est prête. Pour que ChatGPT écrive directement dedans à distance, on branchera ensuite un stockage privé plutôt que ce dépôt GitHub public."))
        content.addView(page)
    }

    private fun appendNote(edit: EditText, prefix: String) {
        val start = edit.selectionStart.coerceAtLeast(0)
        edit.text.insert(start, prefix)
        edit.requestFocus()
    }

    private fun renderSettings() {
        val page = scrollPage()
        page.addView(title(if (exchange == "BINANCE") "Réglages Binance" else "Réglages Bybit"))

        if (exchange == "BYBIT") {
            page.addView(infoCard("Connecteur Bybit", "Les champs API Bybit seront ajoutés à l'étape suivante. Le code est volontairement désactivé pour le moment."))
            content.addView(page)
            return
        }

        page.addView(infoCard("Sécurité", "Utilise une clé API Binance dédiée en lecture seule. N'active jamais les retraits. Le secret est chiffré avec Android Keystore sur ce téléphone."))
        val key = input("Clé API Binance", false).apply { setText(secureStore.get("binance_api_key")) }
        val secret = input("Secret API Binance", true).apply { setText(secureStore.get("binance_api_secret")) }
        page.addView(key)
        page.addView(secret)
        page.addView(primaryButton("Enregistrer") {
            secureStore.put("binance_api_key", key.text.toString().trim())
            secureStore.put("binance_api_secret", secret.text.toString().trim())
            Toast.makeText(this, "Clés enregistrées sur le téléphone", Toast.LENGTH_SHORT).show()
        })
        page.addView(secondaryButton("Tester et synchroniser") {
            secureStore.put("binance_api_key", key.text.toString().trim())
            secureStore.put("binance_api_secret", secret.text.toString().trim())
            syncPortfolio()
        })
        page.addView(smallText("Aucune clé API n'est écrite dans le dépôt GitHub ni dans l'APK."))
        content.addView(page)
    }

    private fun syncPortfolio() {
        Toast.makeText(this, "Synchronisation Binance…", Toast.LENGTH_SHORT).show()
        runAsync(
            task = { clientOrThrow().loadPortfolio() },
            success = {
                saveCachedSnapshot(it)
                section = "PORTFOLIO"
                setContentView(buildRoot())
                render()
                Toast.makeText(this, "Binance synchronisé", Toast.LENGTH_SHORT).show()
            },
            failure = { Toast.makeText(this, "Erreur Binance : $it", Toast.LENGTH_LONG).show() }
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
                holdings += Holding(
                    h.getString("asset"),
                    h.getDouble("amount"),
                    h.getDouble("priceUsdt"),
                    h.getDouble("valueUsdt")
                )
            }
            PortfolioSnapshot(
                obj.getLong("capturedAt"),
                obj.getDouble("totalUsdt"),
                obj.getDouble("totalEur"),
                obj.getDouble("eurUsdt"),
                holdings
            )
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

    private fun scrollPage(): LinearLayout {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(12), dp(2), dp(20))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(inner)
        }
        content.post {
            if (scroll.parent == null) {
                // Le ScrollView est ajouté par wrapPage quand nécessaire.
            }
        }
        return inner.also { it.tag = scroll }
    }

    private fun FrameLayout.addView(page: LinearLayout) {
        val scroll = page.tag as? ScrollView
        if (scroll != null) super.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        else super.addView(page)
    }

    private fun title(value: String) = TextView(this).apply {
        text = value
        setTextColor(text)
        textSize = 22f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(6), dp(4), 0, dp(12))
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        setTextColor(muted)
        textSize = 13f
    }

    private fun bigValue(value: String) = TextView(this).apply {
        text = value
        setTextColor(text)
        textSize = 30f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun smallText(value: String) = TextView(this).apply {
        text = value
        setTextColor(muted)
        textSize = 13f
        setPadding(dp(4), dp(5), dp(4), dp(5))
    }

    private fun cardLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(card)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(10))
        }
    }

    private fun infoCard(head: String, body: String): View = cardLayout().apply {
        addView(TextView(this@MainActivity).apply {
            text = head
            setTextColor(text)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(smallText(body))
    }

    private fun input(hintValue: String, secret: Boolean): EditText = EditText(this).apply {
        hint = hintValue
        setHintTextColor(muted)
        setTextColor(text)
        setBackgroundColor(card)
        textSize = 15f
        singleLine = true
        setPadding(dp(14), dp(12), dp(14), dp(12))
        if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            setMargins(0, 0, 0, dp(10))
        }
    }

    private fun primaryButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.BLACK)
        setBackgroundColor(if (exchange == "BINANCE") yellow else orange)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            setMargins(0, dp(8), 0, dp(8))
        }
    }

    private fun secondaryButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(text)
        setBackgroundColor(card2)
        setOnClickListener { click() }
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
        setMargins(dp(2), dp(2), dp(2), dp(2))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
