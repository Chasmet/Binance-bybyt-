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
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "‹  AUTO-TRADE CHK"
            textSize = 24f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(0,0,0,dp(14))
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
                setPadding(0,dp(5),0,dp(10))
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
                        journal.addLog("STATE","Auto-Trade coupé","Coupe-circuit utilisateur",category="AUTO_TRADE")
                        render()
                    } else {
                        AlertDialog.Builder(this@AutoTradeActivity)
                            .setTitle("Activer Auto-Trade ?")
                            .setMessage("Les ordres LIMIT autorisés pourront être envoyés à Bybit sans confirmation supplémentaire. Les plafonds ci-dessous restent obligatoires.")
                            .setNegativeButton("Annuler", null)
                            .setPositiveButton("ACTIVER") { _, _ ->
                                policy.setEnabled(true)
                                journal.addLog("STATE","Auto-Trade activé","Exécution automatique autorisée dans les limites configurées",category="AUTO_TRADE")
                                runCatching { MarketWatchService.start(this@AutoTradeActivity) }
                                render()
                            }.show()
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        })

        val botRules = CheckBox(this).apply {
            text = "Exécuter automatiquement les règles Bot CHK"
            isChecked = policy.allowBotRules()
            setTextColor(textColor)
            buttonTintList = android.content.res.ColorStateList.valueOf(yellow)
        }
        val chatGpt = CheckBox(this).apply {
            text = "Auto-confirmer les propositions ChatGPT"
            isChecked = policy.allowChatGptProposals()
            setTextColor(textColor)
            buttonTintList = android.content.res.ColorStateList.valueOf(yellow)
        }
        val maxOrder = number("Plafond par ordre USDC (max 10)", policy.maxOrderUsdc())
        val daily = number("Plafond total automatique par jour USDC", policy.dailyCapUsdc())
        val count = number("Nombre max d'ordres automatiques / jour", policy.maxOrdersPerDay().toDouble())

        root.addView(card().apply {
            addView(title("Autorisations"))
            addView(botRules)
            addView(chatGpt)
            addView(TextView(this@AutoTradeActivity).apply {
                text = "Seuls les ordres LIMIT sont exécutés automatiquement. MARKET reste bloqué."
                textSize = 12f
                setTextColor(muted)
                setPadding(0,dp(5),0,dp(8))
            })
            addView(maxOrder)
            addView(daily)
            addView(count)
            addView(Button(this@AutoTradeActivity).apply {
                isAllCaps = false
                text = "ENREGISTRER LES LIMITES"
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.BLACK)
                background = rounded(yellow)
                setOnClickListener {
                    policy.setAllowBotRules(botRules.isChecked)
                    policy.setAllowChatGptProposals(chatGpt.isChecked)
                    policy.setMaxOrderUsdc(maxOrder.text.toString().replace(',','.').toDoubleOrNull() ?: 10.0)
                    policy.setDailyCapUsdc(daily.text.toString().replace(',','.').toDoubleOrNull() ?: 30.0)
                    policy.setMaxOrdersPerDay((count.text.toString().replace(',','.').toDoubleOrNull() ?: 3.0).toInt())
                    journal.addLog("STATE","Limites Auto-Trade enregistrées","Max ${fmt(policy.maxOrderUsdc())} USDC/ordre • ${fmt(policy.dailyCapUsdc())} USDC/jour • ${policy.maxOrdersPerDay()} ordre(s)/jour",category="AUTO_TRADE")
                    Toast.makeText(this@AutoTradeActivity,"Auto-Trade enregistré",Toast.LENGTH_SHORT).show()
                    render()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        })

        root.addView(card().apply {
            addView(title("Protection active"))
            addView(body("• Spot CRYPTO/USDC uniquement\n• LIMIT uniquement en automatique\n• plafond 10 USDC maximum par ordre\n• plafond journalier + nombre d'ordres/jour\n• claim serveur atomique avant envoi\n• récupération par orderLinkId si l'état Bybit est incertain\n• journal Bot indépendant\n• bouton coupe-circuit immédiat"))
        })
    }

    private fun number(hint: String, value: Double) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(muted)
        setTextColor(textColor)
        setText(if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US,"%.2f",value))
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        background = rounded(Color.rgb(28,32,38))
        setPadding(dp(14),dp(10),dp(14),dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { setMargins(0,dp(6),0,dp(6)) }
    }

    private fun title(v:String)=TextView(this).apply {
        text=v
        textSize=16f
        setTypeface(Typeface.DEFAULT,Typeface.BOLD)
        setTextColor(textColor)
        setPadding(0,0,0,dp(8))
    }
    private fun body(v:String)=TextView(this).apply { text=v; textSize=12f; setTextColor(muted); setLineSpacing(0f,1.18f) }
    private fun card()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(14),dp(14),dp(14)); background=rounded(surface); layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{setMargins(0,0,0,dp(12))} }
    private fun rounded(color:Int)=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;setColor(color);setStroke(dp(1),border);cornerRadius=dp(16).toFloat()}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun fmt(v:Double)=String.format(Locale.FRANCE,"%.2f",v)
}
