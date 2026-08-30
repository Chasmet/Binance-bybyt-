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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        policy = AutoTradePolicyStore(this)
        journal = BotRuleStore(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(30))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "‹  AUTO-TRADE CHK"
            textSize = 24f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(0, 0, 0, dp(14))
            setOnClickListener { finish() }
        })

        root.addView(card().apply {
            addView(TextView(this@AutoTradeActivity).apply {
                text = if (policy.enabled()) "AUTO-TRADE ACTIF" else "AUTO-TRADE DÉSACTIVÉ"
                textSize = 19f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (policy.enabled()) green else red)
            })
            addView(TextView(this@AutoTradeActivity).apply {
                text = "${policy.todayOrders()} ordre(s) auto aujourd'hui • ${fmt(policy.todayNotional())} / ${fmt(policy.dailyCapUsdc())} USDC"
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(5), 0, dp(10))
            })
            addView(Button(this@AutoTradeActivity).apply {
                isAllCaps = false
                text = if (policy.enabled()) "COUPER AUTO-TRADE" else "ACTIVER AUTO-TRADE"
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (policy.enabled()) textColor else Color.BLACK)
                background = rounded(if (policy.enabled()) red else green)
                setOnClickListener {
                    if (policy.enabled()) {
                        policy.setEnabled(false)
                        journal.addLog("STATE", "Auto-Trade coupé", "Coupe-circuit utilisateur", category = "AUTO_TRADE")
                        render()
                    } else {
                        AlertDialog.Builder(this@AutoTradeActivity)
                            .setTitle("Activer Auto-Trade ?")
                            .setMessage("Les actions autorisées ci-dessous pourront être envoyées à Bybit sans deuxième confirmation. Les anciennes propositions restent bloquées.")
                            .setNegativeButton("Annuler", null)
                            .setPositiveButton("ACTIVER") { _, _ ->
                                policy.setEnabled(true)
                                journal.addLog("STATE", "Auto-Trade activé", "Exécution automatique autorisée dans les limites configurées", category = "AUTO_TRADE")
                                runCatching { MarketWatchService.start(this@AutoTradeActivity) }
                                render()
                            }
                            .show()
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        })

        val botRules = checkbox(
            "Exécuter automatiquement les règles Bot CHK",
            policy.allowBotRules()
        )
        val chatGpt = checkbox(
            "Auto-confirmer les propositions ChatGPT",
            policy.allowChatGptProposals()
        )
        val cancelReplace = checkbox(
            "Autoriser Bot CHK à annuler/remplacer mes ordres sur demande",
            policy.allowCancelReplace()
        )

        val maxOrder = number(policy.maxOrderUsdc())
        val daily = number(policy.dailyCapUsdc())
        val count = number(policy.maxOrdersPerDay().toDouble())

        root.addView(card().apply {
            addView(title("Autorisations"))
            addView(botRules)
            addView(chatGpt)
            addView(cancelReplace)
            addView(TextView(this@AutoTradeActivity).apply {
                text = "Annulation/remplacement est une autorisation séparée. Elle ne s'active jamais toute seule."
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(5), 0, dp(12))
            })

            addView(fieldLabel("Maximum par ordre", "1,01 à 10 USDC"))
            addView(maxOrder)
            addView(fieldLabel("Maximum total par jour", "Plafond cumulé des BUY/SELL automatiques"))
            addView(daily)
            addView(fieldLabel("Maximum d'ordres par jour", "Les annulations seules ne consomment pas ce compteur"))
            addView(count)

            addView(Button(this@AutoTradeActivity).apply {
                isAllCaps = false
                text = "ENREGISTRER LES AUTORISATIONS"
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.BLACK)
                background = rounded(yellow)
                setOnClickListener {
                    val enablingCancel = cancelReplace.isChecked && !policy.allowCancelReplace()
                    if (enablingCancel) {
                        AlertDialog.Builder(this@AutoTradeActivity)
                            .setTitle("Autoriser annulation/remplacement ?")
                            .setMessage(
                                "À partir de maintenant, une NOUVELLE demande d'annulation créée pour Bot CHK pourra annuler réellement l'ordre Bybit ciblé sans deuxième clic.\n\n" +
                                    "Si la demande contient un ordre de remplacement, celui-ci reste soumis aux limites Auto-Trade et doit être LIMIT. Les anciennes demandes d'annulation sont ignorées."
                            )
                            .setNegativeButton("Retour", null)
                            .setPositiveButton("AUTORISER") { _, _ ->
                                saveSettings(botRules, chatGpt, cancelReplace, maxOrder, daily, count)
                            }
                            .show()
                    } else {
                        saveSettings(botRules, chatGpt, cancelReplace, maxOrder, daily, count)
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        })

        root.addView(card().apply {
            addView(title("Protection active"))
            addView(body(
                "• Spot CRYPTO/USDC uniquement\n" +
                    "• BUY/SELL automatiques : LIMIT uniquement\n" +
                    "• plafond 10 USDC maximum par ordre\n" +
                    "• plafond journalier + nombre d'ordres/jour\n" +
                    "• annulation : un Order ID précis seulement\n" +
                    "• aucune ancienne demande exécutée lors de l'activation\n" +
                    "• claim serveur atomique avant action\n" +
                    "• remplacement créé seulement après annulation Bybit confirmée\n" +
                    "• journal Bot indépendant + notification\n" +
                    "• bouton COUPER AUTO-TRADE = coupe-circuit immédiat"
            ))
        })
    }

    private fun saveSettings(
        botRules: CheckBox,
        chatGpt: CheckBox,
        cancelReplace: CheckBox,
        maxOrder: EditText,
        daily: EditText,
        count: EditText
    ) {
        val previousCancel = policy.allowCancelReplace()
        policy.setAllowBotRules(botRules.isChecked)
        policy.setAllowChatGptProposals(chatGpt.isChecked)
        policy.setAllowCancelReplace(cancelReplace.isChecked)
        policy.setMaxOrderUsdc(maxOrder.text.toString().replace(',', '.').toDoubleOrNull() ?: 10.0)
        policy.setDailyCapUsdc(daily.text.toString().replace(',', '.').toDoubleOrNull() ?: 30.0)
        policy.setMaxOrdersPerDay((count.text.toString().replace(',', '.').toDoubleOrNull() ?: 3.0).toInt())

        val cancelChange = when {
            !previousCancel && policy.allowCancelReplace() -> " • annulation/remplacement AUTORISÉ"
            previousCancel && !policy.allowCancelReplace() -> " • annulation/remplacement COUPÉ"
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
        text = label
        isChecked = checked
        textSize = 15f
        setTextColor(textColor)
        buttonTintList = android.content.res.ColorStateList.valueOf(yellow)
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun fieldLabel(label: String, helper: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(7), 0, 0)
        addView(TextView(this@AutoTradeActivity).apply {
            text = label
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(textColor)
        })
        addView(TextView(this@AutoTradeActivity).apply {
            text = helper
            textSize = 11f
            setTextColor(muted)
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun number(value: Double) = EditText(this).apply {
        setHintTextColor(muted)
        setTextColor(textColor)
        setText(if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.2f", value))
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        background = rounded(surface2)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            setMargins(0, dp(6), 0, dp(6))
        }
    }

    private fun title(v: String) = TextView(this).apply {
        text = v
        textSize = 16f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(textColor)
        setPadding(0, 0, 0, dp(8))
    }

    private fun body(v: String) = TextView(this).apply {
        text = v
        textSize = 12f
        setTextColor(muted)
        setLineSpacing(0f, 1.18f)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(surface)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(12))
        }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        setStroke(dp(1), border)
        cornerRadius = dp(16).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fmt(v: Double) = String.format(Locale.FRANCE, "%.2f", v)
}
