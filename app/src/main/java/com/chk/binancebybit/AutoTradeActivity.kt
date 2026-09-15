package com.chk.binancebybit

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class AutoTradeActivity : Activity() {
    private lateinit var policy: AutoTradePolicyStore
    private lateinit var journal: BotRuleStore
    private val bg = Color.rgb(10,12,15)
    private val surface = Color.rgb(20,23,28)
    private val surface2 = Color.rgb(28,32,38)
    private val border = Color.rgb(48,54,64)
    private val textColor = Color.rgb(246,247,249)
    private val muted = Color.rgb(153,162,174)
    private val green = Color.rgb(57,197,128)
    private val red = Color.rgb(238,91,91)
    private val yellow = Color.rgb(240,185,11)
    private val orange = Color.rgb(245,142,30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        policy = AutoTradePolicyStore(this)
        journal = BotRuleStore(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(30)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })

        root.addView(TextView(this).apply {
            text = "‹  AUTO-TRADE CHK"; textSize = 24f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(textColor); setPadding(0, 0, 0, dp(14)); setOnClickListener { finish() }
        })

        root.addView(card().apply {
            addView(TextView(this@AutoTradeActivity).apply {
                text = if (policy.enabled()) "AUTO-TRADE ACTIF" else "AUTO-TRADE DÉSACTIVÉ"
                textSize = 19f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(if (policy.enabled()) green else red)
            })
            addView(TextView(this@AutoTradeActivity).apply {
                text = "${policy.todayOrders()} ordre(s) auto aujourd'hui • ${fmt(policy.todayNotional())} / ${fmt(policy.dailyCapUsdc())} USDC"
                textSize = 12f; setTextColor(muted); setPadding(0, dp(5), 0, dp(10))
            })
            addView(Button(this@AutoTradeActivity).apply {
                isAllCaps = false; text = if (policy.enabled()) "COUPER AUTO-TRADE" else "ACTIVER AUTO-TRADE"; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (policy.enabled()) textColor else Color.BLACK); background = rounded(if (policy.enabled()) red else green)
                setOnClickListener {
                    if (policy.enabled()) {
                        policy.setEnabled(false)
                        journal.addLog("STATE", "Auto-Trade coupé", "Coupe-circuit utilisateur", category = "AUTO_TRADE")
                        render()
                    } else {
                        AlertDialog.Builder(this@AutoTradeActivity)
                            .setTitle("Activer Auto-Trade ?")
                            .setMessage("Les BUY/SELL autorisés pourront être envoyés à Bybit dans les limites configurées. L'Auto-Cancel reste séparé et ANALYSIS_ONLY par défaut.")
                            .setNegativeButton("Annuler", null)
                            .setPositiveButton("ACTIVER") { _, _ ->
                                policy.setEnabled(true)
                                journal.addLog("STATE", "Auto-Trade activé", "Exécution BUY/SELL autorisée dans les limites configurées", category = "AUTO_TRADE")
                                runCatching { MarketWatchService.start(this@AutoTradeActivity) }
                                render()
                            }.show()
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        })

        val botRules = checkbox("Exécuter automatiquement les règles Bot CHK", policy.allowBotRules())
        val chatGpt = checkbox("Auto-confirmer les propositions ChatGPT", policy.allowChatGptProposals())
        val cancelReplace = checkbox("Autoriser les demandes CANCEL / REPLACE explicites", policy.allowCancelReplace())
        val cancelExecution = checkbox("Autoriser l'exécution Auto-Cancel (quitter ANALYSIS_ONLY)", policy.cancelAutomationMode() == CancelAutomationMode.EXECUTION_ENABLED)
        val maxOrder = number(policy.maxOrderUsdc())
        val daily = number(policy.dailyCapUsdc())
        val count = number(policy.maxOrdersPerDay().toDouble())

        root.addView(card().apply {
            addView(title("Autorisations"))
            addView(botRules); addView(chatGpt); addView(cancelReplace); addView(cancelExecution)
            addView(TextView(this@AutoTradeActivity).apply {
                val analysis = policy.cancelAutomationMode() == CancelAutomationMode.ANALYSIS_ONLY
                text = if (analysis) "AUTO-CANCEL : ANALYSIS_ONLY • aucune annulation réelle" else "AUTO-CANCEL : EXÉCUTION AUTORISÉE • CANCEL/REPLACE explicite obligatoire"
                textSize = 12f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(if (analysis) orange else red); setPadding(0, dp(6), 0, dp(3))
            })
            addView(TextView(this@AutoTradeActivity).apply {
                text = "Une demande sans intention explicite CANCEL ou REPLACE est refusée avant Bybit. REPLACE ne crée le nouvel ordre qu'après confirmation de l'annulation."
                textSize = 11f; setTextColor(muted); setPadding(0, dp(3), 0, dp(12))
            })

            addView(fieldLabel("Maximum par ordre", "1,01 à 30 USDC")); addView(maxOrder)
            addView(fieldLabel("Maximum total par jour", "Plafond cumulé des BUY/SELL automatiques")); addView(daily)
            addView(fieldLabel("Maximum d'ordres par jour", "Les annulations seules ne consomment pas ce compteur")); addView(count)

            addView(Button(this@AutoTradeActivity).apply {
                isAllCaps = false; text = "ENREGISTRER LES AUTORISATIONS"; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(Color.BLACK); background = rounded(yellow)
                setOnClickListener {
                    val enablingCancelPermission = cancelReplace.isChecked && !policy.allowCancelReplace()
                    val enablingCancelExecution = cancelExecution.isChecked && policy.cancelAutomationMode() != CancelAutomationMode.EXECUTION_ENABLED
                    if (enablingCancelPermission || enablingCancelExecution) {
                        AlertDialog.Builder(this@AutoTradeActivity)
                            .setTitle("Autoriser Auto-Cancel réel ?")
                            .setMessage(
                                "Mode sécurisé : ANALYSIS_ONLY est la valeur par défaut.\n\n" +
                                    "Si tu actives l'exécution, seules les NOUVELLES demandes avec intention CANCEL ou REPLACE explicite, créées après cette autorisation, peuvent toucher Bybit. " +
                                    "Chaque demande cible un Order ID précis. REPLACE reste LIMIT et sous tes plafonds."
                            )
                            .setNegativeButton("Rester en ANALYSIS_ONLY", null)
                            .setPositiveButton("AUTORISER") { _, _ -> saveSettings(botRules, chatGpt, cancelReplace, cancelExecution, maxOrder, daily, count) }
                            .show()
                    } else saveSettings(botRules, chatGpt, cancelReplace, cancelExecution, maxOrder, daily, count)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        })

        root.addView(card().apply {
            addView(title("Protection active"))
            addView(body(
                "• Spot CRYPTO/USDC uniquement\n" +
                    "• BUY/SELL automatiques : LIMIT uniquement\n" +
                    "• états séparés : PLACED / OPEN / PARTIAL / FILLED\n" +
                    "• P&L / transactions : FILLED Bybit uniquement\n" +
                    "• plafond 30 USDC maximum par ordre\n" +
                    "• Auto-Cancel = ANALYSIS_ONLY par défaut\n" +
                    "• intention CANCEL ou REPLACE explicite obligatoire\n" +
                    "• annulation : un Order ID précis seulement\n" +
                    "• aucune ancienne demande exécutée lors de l'activation\n" +
                    "• claim serveur atomique puis seconde validation locale\n" +
                    "• remplacement seulement après annulation Bybit confirmée\n" +
                    "• bouton COUPER AUTO-TRADE = coupe-circuit immédiat"
            ))
        })
    }

    private fun saveSettings(
        botRules: CheckBox,
        chatGpt: CheckBox,
        cancelReplace: CheckBox,
        cancelExecution: CheckBox,
        maxOrder: EditText,
        daily: EditText,
        count: EditText
    ) {
        val previousCancel = policy.allowCancelReplace()
        val previousMode = policy.cancelAutomationMode()
        policy.setAllowBotRules(botRules.isChecked)
        policy.setAllowChatGptProposals(chatGpt.isChecked)
        policy.setAllowCancelReplace(cancelReplace.isChecked)
        val desiredMode = if (cancelReplace.isChecked && cancelExecution.isChecked) CancelAutomationMode.EXECUTION_ENABLED else CancelAutomationMode.ANALYSIS_ONLY
        policy.setCancelAutomationMode(desiredMode)
        policy.setMaxOrderUsdc(maxOrder.text.toString().replace(',', '.').toDoubleOrNull() ?: 30.0)
        policy.setDailyCapUsdc(daily.text.toString().replace(',', '.').toDoubleOrNull() ?: 30.0)
        policy.setMaxOrdersPerDay((count.text.toString().replace(',', '.').toDoubleOrNull() ?: 5.0).toInt())

        val cancelChange = when {
            desiredMode == CancelAutomationMode.EXECUTION_ENABLED && previousMode != desiredMode -> " • Auto-Cancel EXÉCUTION AUTORISÉE"
            desiredMode == CancelAutomationMode.ANALYSIS_ONLY && previousMode != desiredMode -> " • Auto-Cancel ANALYSIS_ONLY"
            !previousCancel && policy.allowCancelReplace() -> " • CANCEL/REPLACE autorisés en analyse"
            previousCancel && !policy.allowCancelReplace() -> " • CANCEL/REPLACE COUPÉS"
            else -> ""
        }
        journal.addLog(
            "STATE",
            "Autorisations Auto-Trade enregistrées",
            "Max ${fmt(policy.maxOrderUsdc())} USDC/ordre • ${fmt(policy.dailyCapUsdc())} USDC/jour • ${policy.maxOrdersPerDay()} ordre(s)/jour$cancelChange",
            category = "AUTO_TRADE"
        )
        if (policy.enabled()) runCatching { MarketWatchService.start(this) }
        Toast.makeText(this, "Auto-Trade enregistré", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun checkbox(label: String, checked: Boolean) = CheckBox(this).apply {
        text = label; isChecked = checked; textSize = 15f; setTextColor(textColor); buttonTintList = android.content.res.ColorStateList.valueOf(yellow); setPadding(0, dp(3), 0, dp(3))
    }

    private fun fieldLabel(label: String, helper: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(7), 0, 0)
        addView(TextView(this@AutoTradeActivity).apply { text = label; textSize = 14f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(textColor) })
        addView(TextView(this@AutoTradeActivity).apply { text = helper; textSize = 11f; setTextColor(muted); setPadding(0, dp(2), 0, 0) })
    }

    private fun number(value: Double) = EditText(this).apply {
        setHintTextColor(muted); setTextColor(textColor); setText(if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.2f", value))
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; background = rounded(surface2); setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { setMargins(0, dp(6), 0, dp(6)) }
    }

    private fun title(v: String) = TextView(this).apply { text = v; textSize = 16f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(textColor); setPadding(0, 0, 0, dp(8)) }
    private fun body(v: String) = TextView(this).apply { text = v; textSize = 12f; setTextColor(muted); setLineSpacing(0f, 1.18f) }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); background = rounded(surface); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(12)) } }
    private fun rounded(color: Int) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(color); setStroke(dp(1), border); cornerRadius = dp(16).toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fmt(v: Double) = String.format(Locale.FRANCE, "%.2f", v)
}
