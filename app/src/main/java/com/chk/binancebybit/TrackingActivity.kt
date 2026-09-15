package com.chk.binancebybit

import android.app.Activity
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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class TrackingActivity : Activity() {
    private lateinit var store: TrackingStore
    private lateinit var content: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private var section = "OVERVIEW"
    private var resumed = false

    private val bg = Color.rgb(10, 12, 15)
    private val surface = Color.rgb(20, 23, 28)
    private val surface2 = Color.rgb(28, 32, 38)
    private val border = Color.rgb(48, 54, 64)
    private val text = Color.rgb(246, 247, 249)
    private val muted = Color.rgb(153, 162, 174)
    private val green = Color.rgb(57, 197, 128)
    private val yellow = Color.rgb(240, 185, 11)
    private val orange = Color.rgb(245, 142, 30)
    private val red = Color.rgb(238, 91, 91)
    private val blue = Color.rgb(93, 148, 255)

    private val refresher = object : Runnable {
        override fun run() {
            if (!resumed) return
            render()
            handler.postDelayed(this, 4_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TrackingStore(this)
        store.setEnabled(true)
        runCatching { TrackingService.start(this) }
        window.statusBarColor = bg
        window.navigationBarColor = bg
        setContentView(buildRoot())
        render()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        handler.removeCallbacks(refresher)
        handler.postDelayed(refresher, 1_500L)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        root.addView(buildHeader())
        root.addView(buildTabs(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(10))
        addView(TextView(this@TrackingActivity).apply {
            text = "‹"; textSize = 34f; setTextColor(text); gravity = Gravity.CENTER; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(42), dp(48)))
        addView(LinearLayout(this@TrackingActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("CHK Crypto • Tracking", 22f, text, true))
            addView(label("Wall Tracker local • Binance + Bybit", 11f, muted, false))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val rt = getSharedPreferences("chk_tracking_runtime", MODE_PRIVATE)
        val bReady = rt.getBoolean("binance_feed_ready", false)
        val yReady = rt.getBoolean("bybit_feed_ready", false)
        val ok = bReady || yReady
        addView(label(if (ok) "● DATA" else "○ ATTENTE", 10f, if (ok) green else muted, true).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7)); background = rounded(if (ok) Color.rgb(18, 45, 35) else surface2, if (ok) Color.rgb(35, 92, 66) else border, 999)
        })
    }

    private fun buildTabs(): View {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(6), dp(12), dp(6)) }
        listOf("OVERVIEW" to "Vue globale", "BOOK" to "Carnet", "WALLS" to "Murs", "HISTORY" to "Historique", "NOTES" to "Carnet Tracking")
            .forEach { (code, title) -> row.addView(tabButton(code, title)) }
        scroll.addView(row)
        return scroll
    }

    private fun tabButton(code: String, title: String): Button = Button(this).apply {
        text = title; isAllCaps = false; textSize = 11f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(if (section == code) Color.BLACK else muted)
        background = if (section == code) rounded(green, Color.TRANSPARENT, 999) else rounded(surface2, border, 999)
        setOnClickListener { section = code; setContentView(buildRoot()); render() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { setMargins(0, 0, dp(7), 0) }
        setPadding(dp(13), 0, dp(13), 0)
    }

    private fun render() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        when (section) {
            "OVERVIEW" -> renderOverview()
            "BOOK" -> renderBook()
            "WALLS" -> renderWalls()
            "HISTORY" -> renderHistory()
            "NOTES" -> renderNotes()
        }
    }

    private fun renderOverview() {
        val page = page()
        val rt = getSharedPreferences("chk_tracking_runtime", MODE_PRIVATE)
        val held = store.heldAssets().sorted()
        val priority = rt.getString("priority_assets", "").orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }
        val eligible = rt.getString("eligible_assets", "").orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val walls = store.activeWalls(200)
        val now = System.currentTimeMillis()
        page.addView(sectionTitle("Vue globale", "BTC/ETH obligatoires + plus grosses positions de plus de 10 USDC"))
        page.addView(hero("TRACKING LOCAL", if (store.enabled()) "ACTIF" else "ARRÊTÉ", "${priority.size} suivi(s) • ${held.size} détenu(s) • ${walls.size} mur(s)", if (store.enabled()) green else red))
        page.addView(twoCards(
            feedMetric("Binance", "binance", yellow, rt, now),
            feedMetric("Bybit", "bybit", orange, rt, now)
        ))
        page.addView(info("Filtre automatique", "BTC et ETH restent toujours suivis. Les autres cryptos doivent représenter strictement plus de 10 USDC au total sur Binance + Bybit. CHK garde ensuite les plus grosses positions : 5 actifs max en ULTRA ÉCO, 8 en ÉQUILIBRÉ et 12 en PERFORMANCE.", green))
        page.addView(info("Architecture batterie", "WebSocket événementiel : les deltas carnet sont agrégés en mémoire, les trades sont regroupés et les écritures baissent écran éteint. Aucun polling intensif du marché et aucun carnet brut envoyé à Render.", blue))
        page.addView(info("Attribution", "Le Fingerprint et le spoofing sont probabilistes. CHK Crypto ne qualifie jamais un mur d’institutionnel sans preuve externe vérifiable.", orange))

        page.addView(subTitle("Actifs prioritaires"))
        if (priority.isEmpty()) page.addView(empty("Flux en préparation", "Synchronise le portefeuille Classique. BTC/ETH sont prioritaires dès qu’une paire exploitable est résolue."))
        else priority.forEach { asset ->
            val count = walls.count { it.asset == asset }
            val tags = buildList {
                if (asset == "BTC" || asset == "ETH") add("obligatoire")
                if (asset in eligible) add(">10 USDC")
                if (asset in held) add("détenu")
            }.joinToString(" • ")
            page.addView(card().apply {
                addView(label(asset, 18f, text, true))
                addView(label("$count mur(s) actif(s)${if (tags.isNotBlank()) " • $tags" else ""}", 12f, muted, false).apply { setPadding(0, dp(4), 0, 0) })
            })
        }

        page.addView(subTitle("Consommation"))
        val power = card()
        power.addView(label("Mode actuel : ${powerLabel(store.powerMode())}", 14f, green, true))
        power.addView(label("ÉQUILIBRÉ est le mode par défaut : 8 actifs maximum. ULTRA ÉCO limite à 5 ; PERFORMANCE à 12.", 11f, muted, false).apply { setPadding(0, dp(4), 0, dp(8)) })
        power.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(
                TrackingPowerMode.ULTRA_ECO to "ULTRA ÉCO",
                TrackingPowerMode.BALANCED to "ÉQUILIBRÉ",
                TrackingPowerMode.PERFORMANCE to "PERFORMANCE"
            ).forEach { (mode, name) ->
                addView(Button(this@TrackingActivity).apply {
                    text = name; isAllCaps = false; textSize = 10f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(if (store.powerMode() == mode) Color.BLACK else text)
                    background = if (store.powerMode() == mode) rounded(green, Color.TRANSPARENT, 12) else rounded(surface2, border, 12)
                    setOnClickListener {
                        store.setPowerMode(mode)
                        runCatching { TrackingService.start(this@TrackingActivity) }
                        Toast.makeText(this@TrackingActivity, "Tracking : ${powerLabel(mode)}", Toast.LENGTH_SHORT).show()
                        render()
                    }
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
        })
        page.addView(power)

        page.addView(subTitle("Réglages Wall Tracker"))
        val settings = card()
        val notional = input("Seuil valeur mur (USDT/USDC)").apply { setText(store.minWallNotional().toInt().toString()) }
        val strength = input("Wall Strength minimum (× médiane)").apply { setText(String.format(Locale.US, "%.1f", store.minWallStrength())) }
        settings.addView(notional); settings.addView(strength)
        settings.addView(primary("Enregistrer les seuils") {
            val n = notional.text.toString().replace(',', '.').toDoubleOrNull()
            val s = strength.text.toString().replace(',', '.').toDoubleOrNull()
            if (n == null || s == null) Toast.makeText(this, "Valeurs invalides", Toast.LENGTH_SHORT).show()
            else { store.setMinWallNotional(n); store.setMinWallStrength(s); Toast.makeText(this, "Seuils enregistrés", Toast.LENGTH_SHORT).show(); render() }
        })
        settings.addView(secondary(if (store.enabled()) "Arrêter le Tracking" else "Démarrer le Tracking") {
            store.setEnabled(!store.enabled())
            if (store.enabled()) TrackingService.start(this) else TrackingService.stop(this)
            Toast.makeText(this, if (store.enabled()) "Tracking activé" else "Tracking désactivé", Toast.LENGTH_SHORT).show(); render()
        })
        page.addView(settings)
        attach(page)
    }

    private fun feedMetric(title: String, key: String, accent: Int, rt: android.content.SharedPreferences, now: Long): View {
        val ready = rt.getBoolean("${key}_feed_ready", false)
        val at = rt.getLong("${key}_last_data_at", 0L)
        val age = if (at > 0L) max(0L, now - at) else -1L
        val freshness = when { !ready -> "En chauffe…"; age < 8_000L -> "Frais • ${age / 1000}s"; else -> "Ancien • ${age / 1000}s" }
        return metric(title, freshness, accent)
    }

    private fun renderBook() {
        val page = page()
        page.addView(sectionTitle("Carnet Tracking", "Un gros mur garde son identité et son historique dans le temps"))
        val walls = store.activeWalls(200)
        if (walls.isEmpty()) page.addView(empty("Aucun mur significatif", "Le moteur continue à surveiller les carnets publics en arrière-plan."))
        else walls.groupBy { it.asset }.forEach { (asset, rows) ->
            page.addView(subTitle(asset))
            listOf("BINANCE", "BYBIT").forEach { exchange ->
                val ex = rows.filter { it.exchange == exchange }
                if (ex.isNotEmpty()) {
                    page.addView(label(exchange, 12f, if (exchange == "BINANCE") yellow else orange, true).apply { setPadding(dp(2), dp(7), 0, dp(5)) })
                    ex.sortedWith(compareBy<TrackingWall>({ it.side }, { it.currentPrice })).forEach { page.addView(bookWall(it)) }
                }
            }
        }
        attach(page)
    }

    private fun bookWall(w: TrackingWall): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(surface, border, 14); layoutParams = marginParams(bottom = 6)
        addView(label(w.side, 11f, if (w.side == "BUY") green else red, true), LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(label(fmt(w.currentPrice), 14f, text, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(LinearLayout(this@TrackingActivity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            addView(label("${fmt(w.currentQty)} ${w.asset}", 13f, text, true).apply { gravity = Gravity.END })
            addView(label("${String.format(Locale.FRANCE, "%.1f", w.strength)}× • ${age(w.firstSeen)} • ${w.lastEvent}", 10f, muted, false).apply { gravity = Gravity.END })
        })
    }

    private fun renderWalls() {
        val page = page()
        page.addView(sectionTitle("Wall Fingerprint", "Réapparition probabiliste + score spoofing comportemental"))
        page.addView(info("Règle", "Fingerprint ≠ identité certaine. Spoofing probable ≠ preuve d’un acteur institutionnel.", orange))
        val walls = store.activeWalls(150)
        if (walls.isEmpty()) page.addView(empty("Aucun fingerprint actif", "Un mur apparaîtra ici dès qu'il dépasse les seuils de valeur et de force."))
        else walls.forEach { w -> page.addView(wallCard(w)) }
        attach(page)
    }

    private fun wallCard(w: TrackingWall): View = card().apply {
        addView(LinearLayout(this@TrackingActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label("${w.asset} • ${w.exchange}", 17f, text, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(w.side, 11f, if (w.side == "BUY") green else red, true))
        })
        addView(label("${w.id}\n${fmt(w.currentQty)} ${w.asset} @ ${fmt(w.currentPrice)} • force ${String.format(Locale.FRANCE, "%.1f", w.strength)}×", 12f, muted, false).apply { setPadding(0, dp(5), 0, dp(7)); setLineSpacing(0f, 1.15f) })
        addView(label("Fingerprint : ${w.fingerprintId.ifBlank { w.id }} • confiance ${w.fingerprintConfidence}% • réapparitions ${w.reappearances}", 12f, blue, true))
        addView(label("Spoofing probable : ${w.spoofingProbability}% • ${if (w.spoofingProbability >= 75) "signal fort" else if (w.spoofingProbability >= 55) "signal possible" else "pas de signal fort"}", 12f, if (w.spoofingProbability >= 75) red else orange, true).apply { setPadding(0, dp(5), 0, 0) })
        addView(label("Acteur unique / petit groupe : ${w.singleActorProbability}% • Multi-traders : ${w.multiTraderProbability}% • Indéterminé : ${w.indeterminateProbability}%", 11f, muted, false).apply { setPadding(0, dp(5), 0, 0) })
        addView(label("Mouvements ${w.moves} • replenishment ${w.replenishments} • exécuté ~${fmt(w.executedQty)} • annulé ~${fmt(w.cancelledQty)}", 11f, muted, false).apply { setPadding(0, dp(7), 0, 0) })
    }

    private fun renderHistory() {
        val page = page()
        page.addView(sectionTitle("Historique Tracking", "Prix, quantité, durée, déplacements, disparition, absorption et replenishment"))
        val matches = store.recentMatches(30)
        if (matches.length() > 0) {
            page.addView(subTitle("Binance ↔ Bybit"))
            for (i in 0 until matches.length()) {
                val m = matches.optJSONObject(i) ?: continue
                page.addView(card().apply {
                    addView(label("${m.optString("asset")} • match ${m.optInt("score")}%", 15f, green, true))
                    addView(label("${m.optString("exchangeA")} ${m.optString("wallA").takeLast(9)} ↔ ${m.optString("exchangeB")} ${m.optString("wallB").takeLast(9)}\n${whenTime(m.optLong("at"))}", 11f, muted, false).apply { setPadding(0, dp(5), 0, 0) })
                })
            }
        }
        val events = store.recentEvents(120)
        page.addView(subTitle("Événements"))
        if (events.length() == 0) page.addView(empty("Historique vide", "Le suivi vient de démarrer ou aucun gros mur n'a encore été détecté."))
        else for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            page.addView(card().apply {
                addView(label("${e.optString("event")} • ${e.optString("asset")} • ${e.optString("exchange")}", 14f, eventColor(e.optString("event")), true))
                addView(label("${e.optString("side")} ${fmt(e.optDouble("quantity"))} @ ${fmt(e.optDouble("price"))}\n${e.optString("detail")}\n${whenTime(e.optLong("at"))}", 11f, muted, false).apply { setPadding(0, dp(4), 0, 0) })
            })
        }
        val gaps = store.recentGaps(20)
        if (gaps.length() > 0) {
            page.addView(subTitle("Coupures de données"))
            for (i in 0 until gaps.length()) {
                val g = gaps.optJSONObject(i) ?: continue
                val end = g.optLong("endedAt")
                page.addView(info("DATA GAP", "${whenTime(g.optLong("startedAt"))} → ${if (end > 0) whenTime(end) else "en cours"} • ${g.optString("reason")}", orange))
            }
        }
        attach(page)
    }

    private fun renderNotes() {
        val page = page()
        page.addView(sectionTitle("Carnet Tracking", "Mémoire analytique séparée des Notes Classiques"))
        val notes = store.notes(100)
        if (notes.isEmpty()) page.addView(empty("Aucune note Tracking", "Ajoute une observation sur une crypto, un mur ou un fingerprint."))
        else notes.forEach { n ->
            page.addView(card().apply {
                addView(label(listOf(n.asset, n.wallId).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { "NOTE TRACKING" }, 13f, green, true))
                addView(label(n.content, 13f, text, false).apply { setPadding(0, dp(6), 0, dp(6)) })
                addView(label(whenTime(n.updatedAt), 10f, muted, false))
                addView(secondary("Supprimer") { store.deleteNote(n.id); render() })
            })
        }
        page.addView(subTitle("Nouvelle note"))
        val composer = card()
        val asset = input("Crypto (optionnel, ex. RENDER)")
        val wall = input("Wall ID (optionnel)")
        val body = input("Observation / comportement à retenir", true)
        composer.addView(asset); composer.addView(wall); composer.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)))
        composer.addView(primary("Enregistrer dans le Carnet Tracking") {
            val value = body.text.toString().trim()
            if (value.isBlank()) Toast.makeText(this, "Écris une note", Toast.LENGTH_SHORT).show()
            else {
                val a = asset.text.toString().trim().uppercase(Locale.US)
                if (a.isNotBlank() && a !in priorityAssets()) Toast.makeText(this, "Cette crypto n'est pas suivie", Toast.LENGTH_LONG).show()
                else { store.addNote(a, wall.text.toString().trim(), value); asset.setText(""); wall.setText(""); body.setText(""); Toast.makeText(this, "Note Tracking enregistrée", Toast.LENGTH_SHORT).show(); render() }
            }
        })
        page.addView(composer)
        attach(page)
    }

    private fun priorityAssets(): Set<String> = getSharedPreferences("chk_tracking_runtime", MODE_PRIVATE)
        .getString("priority_assets", "").orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()

    private fun powerLabel(mode: TrackingPowerMode) = when (mode) { TrackingPowerMode.ULTRA_ECO -> "ULTRA ÉCO"; TrackingPowerMode.BALANCED -> "ÉQUILIBRÉ"; TrackingPowerMode.PERFORMANCE -> "PERFORMANCE" }
    private fun page() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(28)) }
    private fun attach(page: LinearLayout) { content.addView(ScrollView(this).apply { addView(page) }, FrameLayout.LayoutParams(-1, -1)) }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14)); background = rounded(surface, border, 18); layoutParams = marginParams(bottom = 9) }
    private fun sectionTitle(title: String, subtitle: String) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(14)); addView(label(title, 24f, text, true)); addView(label(subtitle, 12f, muted, false).apply { setPadding(0, dp(3), 0, 0) }) }
    private fun subTitle(v: String) = label(v, 15f, text, true).apply { setPadding(dp(2), dp(14), 0, dp(8)) }
    private fun hero(eyebrow: String, value: String, detail: String, accent: Int) = card().apply { background = rounded(surface, accent, 22); addView(label(eyebrow, 11f, accent, true)); addView(label(value, 34f, text, true).apply { setPadding(0, dp(2), 0, dp(2)) }); addView(label(detail, 12f, muted, false)) }
    private fun metric(title: String, value: String, accent: Int) = card().apply { addView(label(title, 11f, accent, true)); addView(label(value, 14f, text, true).apply { setPadding(0, dp(4), 0, 0) }) }
    private fun twoCards(a: View, b: View) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(5) }); addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(5) }) }
    private fun info(title: String, body: String, accent: Int) = card().apply { background = rounded(surface, accent, 18); addView(label(title, 13f, accent, true)); addView(label(body, 12f, muted, false).apply { setPadding(0, dp(5), 0, 0); setLineSpacing(0f, 1.17f) }) }
    private fun empty(title: String, body: String) = card().apply { gravity = Gravity.CENTER; addView(label(title, 16f, text, true).apply { gravity = Gravity.CENTER }); addView(label(body, 12f, muted, false).apply { gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0) }) }
    private fun input(hint: String, multi: Boolean = false) = EditText(this).apply { this.hint = hint; setTextColor(text); setHintTextColor(muted); textSize = 13f; background = rounded(surface2, border, 12); setPadding(dp(12), dp(10), dp(12), dp(10)); isSingleLine = !multi; layoutParams = LinearLayout.LayoutParams(-1, if (multi) dp(110) else dp(50)).apply { bottomMargin = dp(8) } }
    private fun primary(title: String, click: () -> Unit) = Button(this).apply { text = title; isAllCaps = false; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(Color.BLACK); background = rounded(green, Color.TRANSPARENT, 14); setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(6) } }
    private fun secondary(title: String, click: () -> Unit) = Button(this).apply { text = title; isAllCaps = false; setTextColor(text); background = rounded(surface2, border, 14); setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) } }
    private fun label(v: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply { text = v; textSize = size; setTextColor(color); if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD) }
    private fun rounded(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(radius).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke) }
    private fun marginParams(bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }
    private fun eventColor(e: String) = when (e) { "APPEARED", "REAPPEARED" -> green; "MOVED" -> blue; "REPLENISHED" -> yellow; "ABSORBED" -> orange; "CANCELLED", "SPOOFING_PROBABLE" -> red; else -> muted }
    private fun fmt(v: Double): String = when { kotlin.math.abs(v) >= 1000 -> String.format(Locale.FRANCE, "%.0f", v); kotlin.math.abs(v) >= 1 -> String.format(Locale.FRANCE, "%.4f", v); else -> String.format(Locale.FRANCE, "%.8f", v) }
    private fun age(first: Long): String { val s = max(0L, System.currentTimeMillis() - first) / 1000; return if (s < 60) "${s}s" else if (s < 3600) "${s / 60}m" else "${s / 3600}h${(s % 3600) / 60}m" }
    private fun whenTime(at: Long): String = if (at <= 0L) "—" else SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date(at))
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
}
