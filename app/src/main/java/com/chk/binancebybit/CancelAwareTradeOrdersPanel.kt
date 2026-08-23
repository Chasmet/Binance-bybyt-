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
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Ajoute les propositions d'annulation Bybit au-dessus du carnet BUY/SELL existant.
 * Une annulation réelle n'est envoyée qu'après confirmation explicite dans CHK Crypto.
 */
class CancelAwareTradeOrdersPanel(
    private val activity: Activity,
    private val secureStore: SecureStore,
    private val workspaceSync: WorkspaceSync
) {
    private val cancelClient = CancelProposalClient(activity, secureStore)
    private lateinit var cancelSection: LinearLayout

    private val surface = Color.rgb(20, 23, 28)
    private val surface2 = Color.rgb(28, 32, 38)
    private val border = Color.rgb(48, 54, 64)
    private val text = Color.rgb(246, 247, 249)
    private val muted = Color.rgb(153, 162, 174)
    private val orange = Color.rgb(245, 142, 30)
    private val red = Color.rgb(238, 91, 91)

    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 10, 13))
        }
        cancelSection = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        root.addView(cancelSection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(
            TradeOrdersPanel(activity, secureStore, workspaceSync).build(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        loadCancels()
        return root
    }

    private fun loadCancels() {
        cancelSection.removeAllViews()
        cancelSection.addView(TextView(activity).apply {
            text = "ANNULATIONS À CONFIRMER"
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(6))
        })
        Thread {
            val result = runCatching { cancelClient.list() }
            activity.runOnUiThread {
                cancelSection.removeAllViews()
                val bundle = result.getOrNull()
                if (bundle == null) {
                    cancelSection.addView(info("Annulations indisponibles", result.exceptionOrNull()?.message ?: "Erreur inconnue", red))
                    return@runOnUiThread
                }
                cancelSection.addView(TextView(activity).apply {
                    text = "ANNULATIONS À CONFIRMER (${bundle.pending.size})"
                    textSize = 14f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(if (bundle.pending.isEmpty()) muted else orange)
                    setPadding(0, dp(4), 0, dp(6))
                })
                bundle.pending.forEach { cancelSection.addView(cancelCard(it)) }
            }
        }.start()
    }

    private fun cancelCard(p: CancelProposal): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(surface, orange, 18)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(10))
        }

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = "ANNULER"
                textSize = 13f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                background = rounded(orange, orange, 20)
                setPadding(dp(12), dp(7), dp(12), dp(7))
            })
            addView(TextView(activity).apply {
                text = p.symbol
                textSize = 20f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(text)
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        addView(TextView(activity).apply {
            text = buildString {
                append("Order ID : ${p.targetOrderId}")
                if (p.targetOrderLinkId != null) append("\nOrder Link ID : ${p.targetOrderLinkId}")
                if (p.confidence != null) append("\nConfiance : ${p.confidence}%")
            }
            textSize = 13f
            setTextColor(text)
            setPadding(0, dp(10), 0, dp(8))
        })
        if (p.rationale.isNotBlank()) addView(info("Raison", p.rationale, orange))

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(activity).apply {
            text = "CONFIRMER L’ANNULATION"
            isAllCaps = false
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = rounded(orange, orange, 14)
            setOnClickListener { confirmCancel(p, this) }
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(0, 0, dp(5), 0) })
        row.addView(Button(activity).apply {
            text = "GARDER L’ORDRE"
            isAllCaps = false
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(text)
            background = rounded(surface2, border, 14)
            setOnClickListener { rejectCancel(p) }
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(dp(5), 0, 0, 0) })
        addView(row)
    }

    private fun confirmCancel(p: CancelProposal, button: Button) {
        AlertDialog.Builder(activity)
            .setTitle("Confirmer l’annulation Bybit")
            .setMessage("Annuler réellement l’ordre ${p.symbol} ?\n\nOrder ID : ${p.targetOrderId}\n\nAucun autre ordre ne sera modifié.")
            .setNegativeButton("Retour", null)
            .setPositiveButton("CONFIRMER") { _, _ -> executeCancel(p, button) }
            .show()
    }

    private fun executeCancel(original: CancelProposal, button: Button) {
        val key = secureStore.get("bybit_api_key")
        val secret = secureStore.get("bybit_api_secret")
        if (key.isBlank() || secret.isBlank()) {
            Toast.makeText(activity, "Clés Bybit absentes. Ouvre Réglages Bybit.", Toast.LENGTH_LONG).show()
            return
        }
        button.isEnabled = false
        button.text = "ANNULATION…"
        Thread {
            val outcome = runCatching {
                val claimed = cancelClient.claim(original.id)
                try {
                    val result = BybitCancelClient(key, secret).cancel(claimed)
                    cancelClient.markResult(claimed.id, "executed", result.toJson())
                    runCatching {
                        workspaceSync.createNote(
                            "BYBIT",
                            "PLAN",
                            "ANNULATION CONFIRMÉE — ${claimed.symbol}\nOrder ID : ${result.orderId}\nStatut Bybit : ${result.orderStatus}\nAucun autre ordre modifié."
                        )
                    }
                    result
                } catch (e: Exception) {
                    runCatching { cancelClient.markResult(claimed.id, "error", JSONObject().put("error", e.message ?: e.toString())) }
                    throw e
                }
            }
            activity.runOnUiThread {
                val result = outcome.getOrNull()
                if (result != null) {
                    AlertDialog.Builder(activity)
                        .setTitle("Ordre annulé")
                        .setMessage("Bybit confirme : ${result.orderStatus}\nOrder ID : ${result.orderId}\n\nTu peux maintenant confirmer une nouvelle proposition de remplacement quand elle apparaît.")
                        .setPositiveButton("OK") { _, _ -> loadCancels() }
                        .show()
                } else {
                    button.isEnabled = true
                    button.text = "CONFIRMER L’ANNULATION"
                    AlertDialog.Builder(activity)
                        .setTitle("Annulation non exécutée")
                        .setMessage(outcome.exceptionOrNull()?.message ?: "Erreur inconnue")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun rejectCancel(p: CancelProposal) {
        AlertDialog.Builder(activity)
            .setTitle("Garder cet ordre ?")
            .setMessage("La proposition d’annulation sera rejetée. L’ordre Bybit reste intact.")
            .setNegativeButton("Retour", null)
            .setPositiveButton("GARDER") { _, _ ->
                Thread {
                    val r = runCatching { cancelClient.markResult(p.id, "rejected", JSONObject().put("reason", "user_kept_order")) }
                    activity.runOnUiThread {
                        if (r.isFailure) Toast.makeText(activity, r.exceptionOrNull()?.message, Toast.LENGTH_LONG).show()
                        loadCancels()
                    }
                }.start()
            }
            .show()
    }

    private fun info(title: String, message: String, accent: Int): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(surface2, accent, 14)
        addView(TextView(activity).apply {
            text = title
            textSize = 13f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(accent)
        })
        addView(TextView(activity).apply {
            text = message
            textSize = 12f
            setTextColor(text)
            setPadding(0, dp(3), 0, 0)
        })
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
