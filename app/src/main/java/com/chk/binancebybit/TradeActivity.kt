package com.chk.binancebybit

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TradeActivity : Activity() {
    private lateinit var secureStore: SecureStore
    private lateinit var workspaceSync: WorkspaceSync
    private lateinit var proposalClient: TradeProposalClient
    private lateinit var body: LinearLayout

    private val bg = Color.rgb(10, 12, 15)
    private val surface = Color.rgb(20, 23, 28)
    private val surface2 = Color.rgb(28, 32, 38)
    private val border = Color.rgb(48, 54, 64)
    private val text = Color.rgb(246, 247, 249)
    private val muted = Color.rgb(153, 162, 174)
    private val orange = Color.rgb(245, 142, 30)
    private val green = Color.rgb(57, 197, 128)
    private val red = Color.rgb(238, 91, 91)
    private val blue = Color.rgb(93, 148, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureStore = SecureStore(this)
        workspaceSync = WorkspaceSync(this, secureStore)
        proposalClient = TradeProposalClient(this, secureStore)
        workspaceSync.ensureIdentity()
        TradeProposalReceiver.createChannel(this)
        TradeProposalReceiver.schedule(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()
        reload()
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) reload()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }

        root.addView(TextView(this).apply {
            text = "CHK Crypto • Achat / Vente"
            textSize = 24f
            setTextColor(text)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "ChatGPT prépare • toi tu confirmes • Bybit exécute"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(3), 0, dp(12))
        })

        root.addView(infoBanner(
            "Aucun ordre automatique",
            "ACHAT ou VENTE ne part jamais sans ton appui sur CONFIRMER. Plafond local : ${BybitTradeClient.MAX_ORDER_USDC.toInt()} USDC par ordre. Le minimum Bybit dépend de la paire.",
            green
        ))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("← Portefeuille CHK") {
            if (isTaskRoot) {
                startActivity(Intent(this, MainActivityV4::class.java))
            } else {
                finish()
            }
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(0, 0, dp(5), 0) })
        actions.addView(actionButton("Actualiser") { reload() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(dp(5), 0, 0, 0) })
        root.addView(actions)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(28))
        }
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun reload() {
        body.removeAllViews()
        body.addView(infoBanner("Synchronisation", "Recherche des propositions en attente…", blue))
        runAsync(
            task = { proposalClient.list() },
            success = { render(it) },
            failure = {
                body.removeAllViews()
                body.addView(infoBanner("Propositions indisponibles", it, red))
                body.addView(actionButton("Réessayer") { reload() })
            }
        )
    }

    private fun render(bundle: TradeProposalClient.Bundle) {
        body.removeAllViews()
        body.addView(sectionTitle("EN ATTENTE (${bundle.pending.size})"))
        if (bundle.pending.isEmpty()) {
            body.addView(infoBanner("Aucun ordre à confirmer", "Quand une opportunité est préparée, elle apparaît ici avec ACHAT/VENTE, quantité, prix et raison.", muted))
        } else {
            bundle.pending.forEach { body.addView(proposalCard(it)) }
        }

        body.addView(sectionTitle("HISTORIQUE RÉCENT"))
        if (bundle.recent.length() == 0) {
            body.addView(infoBanner("Aucun historique", "Les ordres confirmés, annulés ou en erreur apparaîtront ici.", muted))
        } else {
            for (i in 0 until bundle.recent.length()) {
                val item = bundle.recent.optJSONObject(i) ?: continue
                body.addView(recentCard(item))
            }
        }
    }

    private fun proposalCard(p: TradeProposal): View {
        val card = card()
        card.addView(headerRow(p.side, p.symbol, p.orderType))

        val base = p.baseAsset
        val detail = buildString {
            append(if (p.side == "BUY") "ACHAT préparé" else "VENTE préparée")
            append("\nMontant cible : ${fmt(p.quoteAmountUsdc)} USDC")
            if (p.baseQuantity != null) append("\nQuantité : ${fmt(p.baseQuantity)} $base")
            if (p.limitPrice != null) append("\nPrix LIMIT : ${fmt(p.limitPrice)} USDC")
            if (p.confidence != null) append("\nConfiance analyse : ${p.confidence}%")
            if (p.expiresAt != null) append("\nExpiration : ${shortDate(p.expiresAt)}")
        }
        card.addView(TextView(this).apply {
            text = detail
            textSize = 14f
            setTextColor(text)
            setLineSpacing(0f, 1.22f)
            setPadding(0, dp(12), 0, dp(8))
        })

        if (p.rationale.isNotBlank()) {
            card.addView(infoBanner("Pourquoi cet ordre ?", p.rationale, blue))
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val confirm = Button(this).apply {
            text = "CONFIRMER"
            isAllCaps = true
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = rounded(if (p.side == "BUY") green else orange, Color.TRANSPARENT, 14)
            setOnClickListener { confirmProposal(p, this) }
        }
        val reject = Button(this).apply {
            text = "ANNULER"
            isAllCaps = true
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(text)
            background = rounded(surface2, border, 14)
            setOnClickListener { rejectProposal(p) }
        }
        buttons.addView(confirm, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(0, 0, dp(5), 0) })
        buttons.addView(reject, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(5), 0, 0, 0) })
        card.addView(buttons)
        return card
    }

    private fun confirmProposal(p: TradeProposal, button: Button) {
        val base = p.baseAsset
        val message = buildString {
            if (p.side == "BUY") {
                append("Confirmer l'ACHAT préparé de ${p.symbol} ?\n\n")
                append("Montant : ${fmt(p.quoteAmountUsdc)} USDC\n")
                if (p.baseQuantity != null) append("Quantité prévue : ${fmt(p.baseQuantity)} $base\n")
            } else {
                append("Confirmer la VENTE préparée de ${p.symbol} ?\n\n")
                append("Quantité : ${fmt(p.baseQuantity ?: 0.0)} $base\n")
                append("Valeur cible ≈ ${fmt(p.quoteAmountUsdc)} USDC\n")
            }
            append("Type : ${p.orderType}")
            if (p.limitPrice != null) append("\nPrix : ${fmt(p.limitPrice)} USDC")
            append("\n\nAprès CONFIRMER, l'ordre réel est envoyé à Bybit EU Spot.")
        }

        AlertDialog.Builder(this)
            .setTitle(if (p.side == "BUY") "Confirmer l'achat" else "Confirmer la vente")
            .setMessage(message)
            .setNegativeButton("Retour", null)
            .setPositiveButton("CONFIRMER") { _, _ -> executeProposal(p, button) }
            .show()
    }

    private fun executeProposal(p: TradeProposal, button: Button) {
        val key = secureStore.get("bybit_api_key")
        val secret = secureStore.get("bybit_api_secret")
        if (key.isBlank() || secret.isBlank()) {
            Toast.makeText(this, "Clés Bybit absentes. Ouvre Portefeuille CHK > Réglages Bybit.", Toast.LENGTH_LONG).show()
            return
        }

        button.isEnabled = false
        button.text = "ENVOI…"
        runAsync(
            task = {
                val result = BybitTradeClient(key, secret).execute(p)
                proposalClient.markResult(p.id, "executed", result.orderId, result.toJson())
                val note = executionNote(p, result)
                runCatching { workspaceSync.createNote("BYBIT", if (p.side == "BUY") "ACHAT" else "VENTE", note) }
                result
            },
            success = { result ->
                val title = when {
                    result.orderStatus.equals("Filled", true) -> "Ordre exécuté"
                    result.orderStatus.equals("New", true) -> "Ordre ouvert"
                    else -> "Ordre envoyé"
                }
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(executionNote(p, result))
                    .setPositiveButton("OK") { _, _ -> reload() }
                    .show()
            },
            failure = { error ->
                runAsync(
                    task = {
                        proposalClient.markResult(p.id, "error", null, JSONObject().put("error", error))
                        runCatching {
                            workspaceSync.createNote("BYBIT", "ALERTE", "ORDRE NON EXÉCUTÉ — ${p.side} ${p.symbol}\nErreur : $error")
                        }
                        "OK"
                    },
                    success = {
                        Toast.makeText(this, "Ordre refusé : $error", Toast.LENGTH_LONG).show()
                        reload()
                    },
                    failure = {
                        Toast.makeText(this, "Ordre refusé : $error", Toast.LENGTH_LONG).show()
                        reload()
                    }
                )
            }
        )
    }

    private fun rejectProposal(p: TradeProposal) {
        AlertDialog.Builder(this)
            .setTitle("Annuler la proposition ?")
            .setMessage("Aucun ordre Bybit ne sera envoyé.")
            .setNegativeButton("Retour", null)
            .setPositiveButton("ANNULER LA PROPOSITION") { _, _ ->
                runAsync(
                    task = {
                        proposalClient.markResult(p.id, "rejected", null, JSONObject().put("reason", "user_rejected"))
                        runCatching { workspaceSync.createNote("BYBIT", "PLAN", "PROPOSITION ANNULÉE — ${p.side} ${p.symbol}") }
                        "OK"
                    },
                    success = { reload() },
                    failure = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                )
            }
            .show()
    }

    private fun executionNote(p: TradeProposal, result: TradeExecutionResult): String {
        val base = p.baseAsset
        val action = if (p.side == "BUY") "ACHAT" else "VENTE"
        return buildString {
            append("$action CONFIRMÉ — ${p.symbol}\n")
            append("Type : ${p.orderType}\n")
            if (p.side == "SELL") append("Quantité demandée : ${fmt(p.baseQuantity ?: 0.0)} $base\n")
            if (p.side == "BUY" && p.baseQuantity != null) append("Quantité demandée : ${fmt(p.baseQuantity)} $base\n")
            append("Montant cible : ${fmt(p.quoteAmountUsdc)} USDC\n")
            if (p.limitPrice != null) append("Prix LIMIT : ${fmt(p.limitPrice)} USDC\n")
            if (result.executedQty > 0.0) append("Quantité exécutée : ${fmt(result.executedQty)} $base\n")
            if (result.averagePrice > 0.0) append("Prix moyen : ${fmt(result.averagePrice)} USDC\n")
            if (result.executedValueUsdc > 0.0) append("Valeur exécutée : ${fmt(result.executedValueUsdc)} USDC\n")
            append("Statut Bybit : ${result.orderStatus}\n")
            append("Order ID : ${result.orderId.ifBlank { result.orderLinkId }}\n")
            append("Source : proposition ChatGPT confirmée dans CHK Crypto")
        }
    }

    private fun recentCard(o: JSONObject): View {
        val id = o.optString("id")
        val side = o.optString("side", "BUY").uppercase()
        val symbol = o.optString("symbol")
        val status = o.optString("status")
        val result = o.optJSONObject("result")
        val bybitStatus = result?.optString("orderStatus").orEmpty()
        val errorMessage = result?.optString("error").orEmpty()
        val openBybit = bybitStatus.equals("New", true) ||
            bybitStatus.equals("PartiallyFilled", true) ||
            bybitStatus.equals("Untriggered", true)
        val displayStatus = when {
            openBybit -> "OUVERT"
            status.equals("executed", true) && bybitStatus.equals("Filled", true) -> "EXÉCUTÉ"
            status.equals("executed", true) -> "ENVOYÉ"
            status.equals("rejected", true) -> "ANNULÉ"
            status.equals("error", true) -> "ERREUR"
            status.equals("expired", true) -> "EXPIRÉ"
            else -> status.uppercase()
        }

        return card().apply {
            addView(headerRow(side, symbol, o.optString("order_type")))
            addView(TextView(this@TradeActivity).apply {
                text = buildString {
                    append("Statut : $displayStatus")
                    if (bybitStatus.isNotBlank()) append(" • Bybit $bybitStatus")
                    append("\nMontant cible : ${fmt(o.optDouble("quote_amount_usdc", 0.0))} USDC")
                    if (!o.isNull("base_quantity")) append("\nQuantité : ${fmt(o.optDouble("base_quantity"))} ${symbol.removeSuffix("USDC")}")
                    if (!o.isNull("limit_price")) append("\nPrix : ${fmt(o.optDouble("limit_price"))} USDC")
                }
                textSize = 13f
                setTextColor(when {
                    status.equals("error", true) -> red
                    openBybit -> orange
                    else -> muted
                })
                setPadding(0, dp(10), 0, 0)
            })
            if (errorMessage.isNotBlank()) {
                addView(infoBanner("Pourquoi l'ordre a été refusé", errorMessage, red))
            }
            if (id.isNotBlank() && !openBybit) {
                addView(Button(this@TradeActivity).apply {
                    text = "SUPPRIMER DE L'HISTORIQUE"
                    isAllCaps = true
                    textSize = 11f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(red)
                    background = rounded(surface2, red, 14)
                    setOnClickListener { deleteHistory(id, symbol) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                    setMargins(0, dp(8), 0, 0)
                })
            }
        }
    }

    private fun deleteHistory(id: String, symbol: String) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer de l'historique ?")
            .setMessage("Supprimer cette ligne $symbol de l'historique CHK Crypto ? Cela ne modifie aucun ordre Bybit.")
            .setNegativeButton("Retour", null)
            .setPositiveButton("SUPPRIMER") { _, _ ->
                runAsync(
                    task = { proposalClient.deleteHistory(id) },
                    success = {
                        Toast.makeText(this, "Ligne supprimée", Toast.LENGTH_SHORT).show()
                        reload()
                    },
                    failure = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                )
            }
            .show()
    }

    private fun headerRow(side: String, symbol: String, type: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@TradeActivity).apply {
            text = side
            textSize = 11f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(if (side == "BUY") green else red, Color.TRANSPARENT, 999)
        })
        addView(TextView(this@TradeActivity).apply {
            text = "  $symbol"
            textSize = 18f
            setTextColor(text)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@TradeActivity).apply {
            text = type
            textSize = 11f
            setTextColor(orange)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(surface, border, 18)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(10))
        }
    }

    private fun sectionTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(text)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(dp(2), dp(8), 0, dp(9))
    }

    private fun infoBanner(title: String, detail: String, color: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(surface2, color, 16)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(10))
        }
        addView(TextView(this@TradeActivity).apply {
            text = title
            textSize = 13f
            setTextColor(color)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        addView(TextView(this@TradeActivity).apply {
            text = detail
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun actionButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(text)
        background = rounded(surface2, border, 14)
        setOnClickListener { click() }
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun shortDate(value: String): String = runCatching {
        val instant = java.time.Instant.parse(value)
        SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date.from(instant))
    }.getOrDefault(value.take(16).replace("T", " "))

    private fun fmt(value: Double): String = when {
        value >= 1000 -> String.format(Locale.US, "%.2f", value)
        value >= 1 -> String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
        else -> String.format(Locale.US, "%.10f", value).trimEnd('0').trimEnd('.')
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

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
}
