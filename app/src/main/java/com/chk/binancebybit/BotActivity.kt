package com.chk.binancebybit

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BotActivity : Activity() {
    private lateinit var store: BotRuleStore
    private lateinit var root: LinearLayout

    private val bg = Color.rgb(10, 12, 15)
    private val surface = Color.rgb(20, 23, 28)
    private val surface2 = Color.rgb(28, 32, 38)
    private val border = Color.rgb(48, 54, 64)
    private val text = Color.rgb(246, 247, 249)
    private val muted = Color.rgb(153, 162, 174)
    private val yellow = Color.rgb(240, 185, 11)
    private val orange = Color.rgb(245, 142, 30)
    private val green = Color.rgb(57, 197, 128)
    private val red = Color.rgb(238, 91, 91)
    private val blue = Color.rgb(93, 148, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BotRuleStore(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) rebuild()
    }

    private fun rebuild() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(30))
        }
        scroll.removeAllViews()
        scroll.addView(root)
        setContentView(scroll)

        root.addView(header())
        root.addView(statusCard())
        root.addView(infoCard(
            "Sécurité",
            "Bot CHK fonctionne localement, sans OpenAI. Il peut surveiller et préparer une proposition BUY/SELL, mais aucun ordre réel n'est envoyé tant que tu n'appuies pas sur CONFIRMER dans CHK Crypto.",
            green
        ))
        root.addView(sectionTitle("Nouvelle règle"))
        root.addView(ruleComposer())
        root.addView(sectionTitle("Mes règles • ${store.activeCount()} active(s)"))
        val rules = store.list()
        if (rules.isEmpty()) {
            root.addView(infoCard("Aucune règle", "Crée ta première règle prix ou prix + RSI ci-dessus.", muted))
        } else {
            rules.forEach { root.addView(ruleCard(it)) }
        }
        root.addView(sectionTitle("Actions"))
        root.addView(actionPanel())
        root.addView(sectionTitle("Journal du Bot"))
        val logs = store.logs()
        if (logs.isEmpty()) {
            root.addView(infoCard("Aucune activité", "Les déclenchements, propositions et erreurs apparaîtront ici.", muted))
        } else {
            logs.take(30).forEach { root.addView(logCard(it)) }
        }
    }

    private fun header(): ViewGroup = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(2), dp(2), dp(14))
        addView(TextView(this@BotActivity).apply {
            text = "‹"
            textSize = 34f
            setTextColor(text)
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        addView(LinearLayout(this@BotActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@BotActivity).apply {
                text = "BOT CHK"
                textSize = 24f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(text)
            })
            addView(TextView(this@BotActivity).apply {
                text = "Assistant local • zéro coût API IA"
                textSize = 12f
                setTextColor(muted)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@BotActivity).apply {
            text = "v1"
            textSize = 11f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(yellow, Color.TRANSPARENT, 999)
        })
    }

    private fun statusCard(): ViewGroup = card().apply {
        addView(LinearLayout(this@BotActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@BotActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@BotActivity).apply {
                    text = if (store.enabled()) "Bot actif" else "Bot en pause"
                    textSize = 18f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(if (store.enabled()) green else text)
                })
                addView(TextView(this@BotActivity).apply {
                    text = "${store.activeCount()} règle(s) active(s) • surveillance via CHK Crypto"
                    textSize = 12f
                    setTextColor(muted)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@BotActivity).apply {
                isAllCaps = false
                text = if (store.enabled()) "PAUSE" else "ACTIVER"
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (store.enabled()) text else Color.BLACK)
                background = rounded(if (store.enabled()) surface2 else green, if (store.enabled()) border else Color.TRANSPARENT, 14)
                setOnClickListener {
                    val next = !store.enabled()
                    store.setEnabled(next)
                    store.addLog("STATE", if (next) "Bot CHK activé" else "Bot CHK mis en pause", "${store.activeCount()} règle(s) active(s)")
                    if (next) runCatching { MarketWatchService.start(this@BotActivity) }
                    rebuild()
                }
            }, LinearLayout.LayoutParams(dp(110), dp(48)))
        })
    }

    private fun ruleComposer(): ViewGroup {
        val box = card()
        val name = input("Nom : RENDER accumulation")
        val symbol = input("Paire : RENDERUSDC")
        val target = numberInput("Prix cible : 1.460")
        var priceCondition = "below"
        val priceConditionButton = toggleButton("Prix ≤ cible")
        priceConditionButton.setOnClickListener {
            priceCondition = if (priceCondition == "below") "above" else "below"
            priceConditionButton.text = if (priceCondition == "below") "Prix ≤ cible" else "Prix ≥ cible"
        }

        val rsiEnabled = CheckBox(this).apply {
            text = "Ajouter une condition RSI14"
            setTextColor(text)
            buttonTintList = android.content.res.ColorStateList.valueOf(yellow)
            setPadding(0, dp(6), 0, dp(6))
        }
        val timeframes = listOf("1m", "5m", "15m", "1h", "4h", "1d")
        var tfIndex = 2
        val timeframeButton = toggleButton("RSI timeframe : ${timeframes[tfIndex]}")
        timeframeButton.setOnClickListener {
            tfIndex = (tfIndex + 1) % timeframes.size
            timeframeButton.text = "RSI timeframe : ${timeframes[tfIndex]}"
        }
        var rsiCondition = "below"
        val rsiConditionButton = toggleButton("RSI ≤ seuil")
        rsiConditionButton.setOnClickListener {
            rsiCondition = if (rsiCondition == "below") "above" else "below"
            rsiConditionButton.text = if (rsiCondition == "below") "RSI ≤ seuil" else "RSI ≥ seuil"
        }
        val rsiThreshold = numberInput("Seuil RSI : 35").apply { setText("35") }

        val actions = listOf(
            BotRuleStore.ACTION_NOTIFY to "Action : ALERTE",
            BotRuleStore.ACTION_PREPARE_BUY to "Action : PRÉPARER BUY",
            BotRuleStore.ACTION_PREPARE_SELL to "Action : PRÉPARER SELL"
        )
        var actionIndex = 0
        val actionButton = toggleButton(actions[actionIndex].second)
        actionButton.setOnClickListener {
            actionIndex = (actionIndex + 1) % actions.size
            actionButton.text = actions[actionIndex].second
        }
        val amount = numberInput("Montant proposition USDC (max 10)").apply { setText("10") }
        val oneShot = CheckBox(this).apply {
            text = "Une seule fois puis désactiver la règle"
            isChecked = true
            setTextColor(text)
            buttonTintList = android.content.res.ColorStateList.valueOf(yellow)
        }

        box.addView(name)
        box.addView(symbol)
        box.addView(target)
        box.addView(priceConditionButton)
        box.addView(rsiEnabled)
        box.addView(timeframeButton)
        box.addView(rsiConditionButton)
        box.addView(rsiThreshold)
        box.addView(actionButton)
        box.addView(amount)
        box.addView(oneShot)
        box.addView(primaryButton("Ajouter la règle") {
            val p = target.text.toString().replace(',', '.').toDoubleOrNull()
            if (p == null || p <= 0.0) {
                Toast.makeText(this, "Prix cible invalide", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            val q = amount.text.toString().replace(',', '.').toDoubleOrNull() ?: 10.0
            val r = rsiThreshold.text.toString().replace(',', '.').toDoubleOrNull() ?: 35.0
            val created = store.create(
                name = name.text.toString(),
                symbol = symbol.text.toString(),
                priceCondition = priceCondition,
                targetPrice = p,
                rsiEnabled = rsiEnabled.isChecked,
                rsiTimeframe = timeframes[tfIndex],
                rsiCondition = rsiCondition,
                rsiThreshold = r,
                action = actions[actionIndex].first,
                amountUsdc = q,
                oneShot = oneShot.isChecked
            )
            store.addLog("RULE", "Règle créée", "${created.name} • ${created.symbol}")
            if (store.enabled()) runCatching { MarketWatchService.start(this) }
            Toast.makeText(this, "Règle Bot CHK ajoutée", Toast.LENGTH_SHORT).show()
            rebuild()
        })
        return box
    }

    private fun ruleCard(rule: BotRuleStore.Rule): ViewGroup = card().apply {
        val relation = if (rule.priceCondition == "above") "≥" else "≤"
        val action = when (rule.action) {
            BotRuleStore.ACTION_PREPARE_BUY -> "PRÉPARER BUY"
            BotRuleStore.ACTION_PREPARE_SELL -> "PRÉPARER SELL"
            else -> "ALERTE"
        }
        addView(LinearLayout(this@BotActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@BotActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@BotActivity).apply {
                    text = rule.name
                    textSize = 16f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(text)
                })
                addView(TextView(this@BotActivity).apply {
                    text = "${rule.symbol} • prix $relation ${fmt(rule.targetPrice)}"
                    textSize = 13f
                    setTextColor(if (rule.enabled) yellow else muted)
                })
                if (rule.rsiEnabled) addView(TextView(this@BotActivity).apply {
                    val rr = if (rule.rsiCondition == "above") "≥" else "≤"
                    text = "RSI14 ${rule.rsiTimeframe} $rr ${String.format(Locale.FRANCE, "%.1f", rule.rsiThreshold)}"
                    textSize = 12f
                    setTextColor(muted)
                })
                addView(TextView(this@BotActivity).apply {
                    text = "$action${if (rule.action != BotRuleStore.ACTION_NOTIFY) " • ${fmt(rule.amountUsdc)} USDC" else ""} • ${if (rule.oneShot) "1 fois" else "répétable / 1h"}"
                    textSize = 11f
                    setTextColor(if (rule.action == BotRuleStore.ACTION_NOTIFY) blue else orange)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@BotActivity).apply {
                isAllCaps = false
                text = if (rule.enabled) "ON" else "OFF"
                setTextColor(if (rule.enabled) Color.BLACK else muted)
                background = rounded(if (rule.enabled) green else surface2, if (rule.enabled) Color.TRANSPARENT else border, 12)
                setOnClickListener {
                    store.setRuleEnabled(rule.id, !rule.enabled)
                    rebuild()
                }
            }, LinearLayout.LayoutParams(dp(68), dp(44)))
        })
        addView(secondaryButton("Supprimer") {
            store.delete(rule.id)
            store.addLog("RULE", "Règle supprimée", rule.name)
            rebuild()
        })
    }

    private fun actionPanel(): ViewGroup = card().apply {
        addView(primaryButton("Tester les règles maintenant") {
            Toast.makeText(this@BotActivity, "Bot CHK vérifie le marché…", Toast.LENGTH_SHORT).show()
            Thread {
                val result = runCatching { BotEngine(this@BotActivity).evaluateOnce() }
                runOnUiThread {
                    result.onSuccess {
                        Toast.makeText(this@BotActivity, "${it.checked} vérifiée(s) • ${it.triggered} déclenchée(s) • ${it.proposals} proposition(s)", Toast.LENGTH_LONG).show()
                        rebuild()
                    }.onFailure {
                        Toast.makeText(this@BotActivity, "Erreur Bot : ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        })
        addView(secondaryButton("Ouvrir les ordres à confirmer") {
            startActivity(android.content.Intent(this@BotActivity, TradeActivity::class.java))
        })
        addView(secondaryButton("Effacer le journal") {
            store.clearLogs()
            rebuild()
        })
    }

    private fun logCard(log: BotRuleStore.LogEntry): ViewGroup = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(surface, border, 16)
        layoutParams = margins(bottom = 8)
        addView(LinearLayout(this@BotActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@BotActivity).apply {
                text = log.title
                textSize = 13f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(when (log.level) {
                    "ERROR" -> red
                    "PROPOSAL" -> orange
                    "ALERT" -> yellow
                    else -> text
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@BotActivity).apply {
                text = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(log.at))
                textSize = 10f
                setTextColor(muted)
            })
        })
        addView(TextView(this@BotActivity).apply {
            text = log.detail
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(5), 0, 0)
        })
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 17f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(text)
        setPadding(dp(2), dp(18), 0, dp(9))
    }

    private fun infoCard(title: String, detail: String, accent: Int): ViewGroup = card().apply {
        addView(TextView(this@BotActivity).apply {
            text = title
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(accent)
        })
        addView(TextView(this@BotActivity).apply {
            text = detail
            textSize = 12f
            setTextColor(muted)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(5), 0, 0)
        })
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(14))
        background = rounded(surface, border, 18)
        layoutParams = margins(bottom = 10)
    }

    private fun input(hintText: String) = EditText(this).apply {
        hint = hintText
        setHintTextColor(muted)
        setTextColor(text)
        textSize = 14f
        singleLine = true
        setPadding(dp(13), dp(11), dp(13), dp(11))
        background = rounded(surface2, border, 12)
        layoutParams = margins(bottom = 8)
    }

    private fun numberInput(hintText: String) = input(hintText).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun toggleButton(label: String) = secondaryButton(label) {}

    private fun primaryButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(Color.BLACK)
        background = rounded(yellow, Color.TRANSPARENT, 14)
        setOnClickListener { action() }
        layoutParams = margins(height = 50, bottom = 8)
    }

    private fun secondaryButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(text)
        background = rounded(surface2, border, 14)
        setOnClickListener { action() }
        layoutParams = margins(height = 48, bottom = 8)
    }

    private fun margins(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, bottom: Int = 10) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (height == ViewGroup.LayoutParams.WRAP_CONTENT) height else dp(height)).apply {
            setMargins(0, 0, 0, dp(bottom))
        }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun fmt(v: Double): String = when {
        v >= 1000 -> String.format(Locale.US, "%.2f", v)
        v >= 100 -> String.format(Locale.US, "%.3f", v)
        v >= 1 -> String.format(Locale.US, "%.5f", v).trimEnd('0').trimEnd('.')
        else -> String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
