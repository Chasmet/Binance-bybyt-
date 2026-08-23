package com.chk.binancebybit

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Écran Ordres v8.
 * Les clés Bybit restent exclusivement sur Render.
 * Le téléphone ne transmet que l'identité CHK Crypto et l'ID de la proposition déjà claimée.
 */
class RenderTradeOrdersPanel(
    private val activity: Activity,
    private val secureStore: SecureStore,
    private val workspaceSync: WorkspaceSync
) {
    private val proposalClient = TradeProposalClient(activity, secureStore)
    private val renderClient = RenderCryptoClient(activity, secureStore)
    private lateinit var body: LinearLayout

    private val surface = Color.rgb(20, 23, 28)
    private val surface2 = Color.rgb(28, 32, 38)
    private val border = Color.rgb(48, 54, 64)
    private val text = Color.rgb(246, 247, 249)
    private val muted = Color.rgb(153, 162, 174)
    private val orange = Color.rgb(245, 142, 30)
    private val green = Color.rgb(57, 197, 128)
    private val red = Color.rgb(238, 91, 91)
    private val blue = Color.rgb(93, 148, 255)

    fun build(): View {
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(28))
        }
        page.addView(TextView(activity).apply {
            text = "Ordres Bybit"
            textSize = 24f
            setTextColor(text)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        page.addView(TextView(activity).apply {
            text = "Clés sécurisées sur Render • Bybit EU Spot • maximum 10 USDC"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(3), 0, dp(12))
        })
        page.addView(infoBanner(
            "Confirmation obligatoire",
            "ChatGPT prépare l'ordre. Ton appui sur CONFIRMER verrouille la proposition. Render vérifie ensuite ce verrou avant tout ordre réel. Les clés Bybit ne sont jamais envoyées au téléphone.",
            green
        ))
        page.addView(actionButton("Actualiser les propositions") { reload() })
        body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        page.addView(body)
        return ScrollView(activity).apply {
            isFillViewport = true
            addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            post { reload() }
        }
    }

    private fun reload() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        body.addView(infoBanner("Synchronisation", "Recherche des propositions…", blue))
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
        body.addView(subTitle("EN ATTENTE (${bundle.pending.size})"))
        if (bundle.pending.isEmpty()) {
            body.addView(infoBanner("Aucun ordre à confirmer", "Les futures propositions ChatGPT apparaîtront ici.", muted))
        } else {
            bundle.pending.forEach { body.addView(proposalCard(it)) }
        }

        body.addView(subTitle("HISTORIQUE RÉCENT"))
        if (bundle.recent.length() == 0) {
            body.addView(infoBanner("Aucun historique", "Les ordres et propositions terminés apparaîtront ici.", muted))
        } else {
            for (i in 0 until bundle.recent.length()) {
                bundle.recent.optJSONObject(i)?.let { body.addView(recentCard(it)) }
            }
        }
    }

    private fun proposalCard(p: TradeProposal): View = card().apply {
        addView(headerRow(p.side, p.symbol, p.orderType))
        addView(TextView(activity).apply {
            text = buildString {
                append(if (p.side == "BUY") "ACHAT préparé" else "VENTE préparée")
                append("\nMontant : ${fmt(p.quoteAmountUsdc)} USDC")
                if (p.baseQuantity != null) append("\nQuantité : ${fmt(p.baseQuantity)} ${p.baseAsset}")
                if (p.limitPrice != null) append("\nPrix LIMIT : ${fmt(p.limitPrice)} USDC")
                if (p.confidence != null) append("\nConfiance : ${p.confidence}%")
                if (p.expiresAt != null) append("\nExpiration : ${shortDate(p.expiresAt)}")
            }
            textSize = 14f
            setTextColor(text)
            setLineSpacing(0f, 1.22f)
            setPadding(0, dp(12), 0, dp(8))
        })
        if (p.rationale.isNotBlank()) addView(infoBanner("Raison", p.rationale, blue))

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(activity).apply {
            text = "CONFIRMER"
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = rounded(if (p.side == "BUY") green else orange, Color.TRANSPARENT, 14)
            setOnClickListener { confirmProposal(p, this) }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(0, 0, dp(5), 0) })
        row.addView(Button(activity).apply {
            text = "ANNULER"
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(text)
            background = rounded(surface2, border, 14)
            setOnClickListener { rejectProposal(p) }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(5), 0, 0, 0) })
        addView(row)
    }

    private fun confirmProposal(p: TradeProposal, button: Button) {
        val message = buildString {
            append(if (p.side == "BUY") "Confirmer l'ACHAT de ${p.symbol} ?" else "Confirmer la VENTE de ${p.symbol} ?")
            append("\n\nMontant : ${fmt(p.quoteAmountUsdc)} USDC")
            if (p.baseQuantity != null) append("\nQuantité : ${fmt(p.baseQuantity)} ${p.baseAsset}")
            append("\nType : ${p.orderType}")
            if (p.limitPrice != null) append("\nPrix : ${fmt(p.limitPrice)} USDC")
            append("\n\nAprès CONFIRMER : claim Supabase → vérification Render → ordre réel Bybit EU Spot.")
        }
        AlertDialog.Builder(activity)
            .setTitle(if (p.side == "BUY") "Confirmer l'achat" else "Confirmer la vente")
            .setMessage(message)
            .setNegativeButton("Retour", null)
            .setPositiveButton("CONFIRMER") { _, _ -> executeProposal(p, button) }
            .show()
    }

    private fun executeProposal(original: TradeProposal, button: Button) {
        button.isEnabled = false
        button.text = "VÉRIFICATION…"
        runAsync(
            task = {
                val claimed = proposalClient.claim(original.id)
                try {
                    val result = renderClient.executeProposal(claimed.id)
                    runCatching {
                        workspaceSync.createNote("BYBIT", if (claimed.side == "BUY") "ACHAT" else "VENTE", executionNote(claimed, result))
                    }
                    claimed to result
                } catch (uncertain: BybitExecutionUncertainException) {
                    runCatching {
                        workspaceSync.createNote("BYBIT", "ALERTE", "ORDRE À VÉRIFIER — ${claimed.side} ${claimed.symbol}\n${uncertain.message}\nAucun renvoi automatique.")
                    }
                    throw uncertain
                } catch (error: Exception) {
                    // Render marque lui-même un refus définitif. On ne force jamais un second changement d'état ici.
                    runCatching {
                        workspaceSync.createNote("BYBIT", "ALERTE", "ORDRE NON EXÉCUTÉ — ${claimed.side} ${claimed.symbol}\nErreur : ${error.message}")
                    }
                    throw error
                }
            },
            success = { (claimed, result) ->
                val title = when {
                    result.orderStatus.equals("Filled", true) -> "Ordre exécuté"
                    result.orderStatus.equals("New", true) || result.orderStatus.equals("PartiallyFilled", true) -> "Ordre ouvert"
                    else -> "Ordre envoyé"
                }
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(executionNote(claimed, result))
                    .setPositiveButton("OK") { _, _ -> reload() }
                    .show()
            },
            failure = { error ->
                val uncertain = error.contains("incertain", ignoreCase = true)
                AlertDialog.Builder(activity)
                    .setTitle(if (uncertain) "Ordre à vérifier" else "Ordre non exécuté")
                    .setMessage(if (uncertain) "$error\n\nAucun second ordre ne sera envoyé automatiquement." else error)
                    .setPositiveButton("OK") { _, _ -> reload() }
                    .show()
            }
        )
    }

    private fun rejectProposal(p: TradeProposal) {
        AlertDialog.Builder(activity)
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
                    failure = { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() }
                )
            }
            .show()
    }

    private fun verifyProcessing(o: JSONObject) {
        val p = runCatching { TradeProposal.fromJson(o) }.getOrNull() ?: return
        Toast.makeText(activity, "Vérification sur Bybit via Render…", Toast.LENGTH_SHORT).show()
        runAsync(
            task = { renderClient.reconcileProposal(p.id) },
            success = { result ->
                if (result == null) {
                    Toast.makeText(activity, "Aucun ordre correspondant trouvé. Aucun renvoi automatique.", Toast.LENGTH_LONG).show()
                } else {
                    runCatching { workspaceSync.createNote("BYBIT", if (p.side == "BUY") "ACHAT" else "VENTE", executionNote(p, result)) }
                    Toast.makeText(activity, "Ordre retrouvé : ${result.orderStatus}", Toast.LENGTH_LONG).show()
                }
                reload()
            },
            failure = { Toast.makeText(activity, "Vérification impossible : $it", Toast.LENGTH_LONG).show() }
        )
    }

    private fun recentCard(o: JSONObject): View {
        val id = o.optString("id")
        val side = o.optString("side", "BUY").uppercase()
        val symbol = o.optString("symbol")
        val status = o.optString("status").lowercase()
        val result = o.optJSONObject("result")
        val bybitStatus = result?.optString("orderStatus").orEmpty()
        val errorMessage = result?.optString("error").orEmpty()
        val open = bybitStatus.equals("New", true) || bybitStatus.equals("PartiallyFilled", true) || bybitStatus.equals("Untriggered", true)
        val processing = status == "processing"
        val display = when {
            processing -> "À VÉRIFIER"
            open -> "OUVERT"
            status == "executed" && bybitStatus.equals("Filled", true) -> "EXÉCUTÉ"
            status == "executed" -> "ENVOYÉ"
            status == "rejected" -> "ANNULÉ"
            status == "error" -> "ERREUR"
            status == "expired" -> "EXPIRÉ"
            else -> status.uppercase()
        }
        return card().apply {
            addView(headerRow(side, symbol, o.optString("order_type")))
            addView(TextView(activity).apply {
                text = buildString {
                    append("Statut : $display")
                    if (bybitStatus.isNotBlank()) append(" • Bybit $bybitStatus")
                    append("\nMontant : ${fmt(o.optDouble("quote_amount_usdc", 0.0))} USDC")
                    if (!o.isNull("base_quantity")) append("\nQuantité : ${fmt(o.optDouble("base_quantity"))} ${symbol.removeSuffix("USDC")}")
                    if (!o.isNull("limit_price")) append("\nPrix : ${fmt(o.optDouble("limit_price"))} USDC")
                }
                textSize = 13f
                setTextColor(if (status == "error") red else if (processing || open) orange else muted)
                setPadding(0, dp(10), 0, 0)
            })
            if (errorMessage.isNotBlank()) addView(infoBanner("Détail", errorMessage, red))
            if (processing && id.isNotBlank()) {
                addView(infoBanner("Anti-double ordre", "Render vérifie l'orderLinkId déterministe avant toute action. Aucun renvoi automatique.", orange))
                addView(actionButton("VÉRIFIER SUR BYBIT") { verifyProcessing(o) })
            } else if (id.isNotBlank() && !open) {
                addView(actionButton("Supprimer de l'historique") {
                    AlertDialog.Builder(activity)
                        .setTitle("Supprimer cette ligne ?")
                        .setMessage("Cela ne modifie aucun ordre Bybit.")
                        .setNegativeButton("Retour", null)
                        .setPositiveButton("SUPPRIMER") { _, _ ->
                            runAsync(
                                task = { proposalClient.deleteHistory(id) },
                                success = { reload() },
                                failure = { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() }
                            )
                        }.show()
                })
            }
        }
    }

    private fun executionNote(p: TradeProposal, result: TradeExecutionResult): String = buildString {
        append(if (p.side == "BUY") "ACHAT CONFIRMÉ — ${p.symbol}\n" else "VENTE CONFIRMÉE — ${p.symbol}\n")
        append("Type : ${p.orderType}\n")
        if (p.baseQuantity != null) append("Quantité demandée : ${fmt(p.baseQuantity)} ${p.baseAsset}\n")
        append("Montant cible : ${fmt(p.quoteAmountUsdc)} USDC\n")
        if (p.limitPrice != null) append("Prix LIMIT : ${fmt(p.limitPrice)} USDC\n")
        if (result.executedQty > 0) append("Quantité exécutée : ${fmt(result.executedQty)} ${p.baseAsset}\n")
        if (result.averagePrice > 0) append("Prix moyen : ${fmt(result.averagePrice)} USDC\n")
        if (result.executedValueUsdc > 0) append("Valeur exécutée : ${fmt(result.executedValueUsdc)} USDC\n")
        append("Statut Bybit : ${result.orderStatus}\n")
        append("Order ID : ${result.orderId.ifBlank { result.orderLinkId }}\n")
        append("Order Link ID : ${result.orderLinkId}\n")
        append("Clé utilisée : Render • confirmation humaine CHK Crypto")
    }

    private fun headerRow(side: String, symbol: String, type: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(activity).apply {
            text = side
            textSize = 11f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(if (side == "BUY") green else red, Color.TRANSPARENT, 999)
        })
        addView(TextView(activity).apply {
            text = "  $symbol"
            textSize = 18f
            setTextColor(text)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(activity).apply {
            text = type
            textSize = 11f
            setTextColor(orange)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
    }

    private fun subTitle(value: String) = TextView(activity).apply {
        text = value
        textSize = 13f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(muted)
        setPadding(dp(2), dp(18), 0, dp(8))
    }

    private fun card() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(surface, border, 18)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(10)) }
    }

    private fun infoBanner(title: String, detail: String, accent: Int): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(surface2, accent, 16)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(10)) }
        addView(TextView(activity).apply { text = title; textSize = 13f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(accent) })
        addView(TextView(activity).apply { text = detail; textSize = 12f; setTextColor(text); setPadding(0, dp(4), 0, 0) })
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(text)
        background = rounded(surface2, border, 14)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { setMargins(0, dp(6), 0, 0) }
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
    private fun shortDate(raw: String): String = runCatching {
        DateTimeFormatter.ofPattern("dd/MM HH:mm").format(Instant.parse(raw).atZone(ZoneId.systemDefault()))
    }.getOrDefault(raw)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun <T> runAsync(task: () -> T, success: (T) -> Unit, failure: (String) -> Unit) {
        Thread {
            try {
                val result = task()
                activity.runOnUiThread { success(result) }
            } catch (error: Throwable) {
                activity.runOnUiThread { failure(error.message ?: error.toString()) }
            }
        }.start()
    }
}
