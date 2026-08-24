package com.chk.binancebybit

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalAlertsPanel(private val activity: Activity) {
    private val store = LocalAlertStore(activity)
    private val bg = Color.rgb(10, 12, 15)
    private val surface = Color.rgb(20, 23, 28)
    private val surface2 = Color.rgb(28, 32, 38)
    private val border = Color.rgb(48, 54, 64)
    private val text = Color.rgb(246, 247, 249)
    private val muted = Color.rgb(153, 162, 174)
    private val green = Color.rgb(57, 197, 128)
    private val orange = Color.rgb(245, 142, 30)
    private val red = Color.rgb(238, 91, 91)
    private val purple = Color.rgb(176, 126, 255)

    fun build(): View {
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(28))
            setBackgroundColor(bg)
        }

        page.addView(TextView(activity).apply {
            text = "Alarmes locales"
            textSize = 25f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(text)
        })
        page.addView(TextView(activity).apply {
            text = "Surveillance directement sur ce téléphone • Bybit public • zéro Render • zéro API OpenAI"
            textSize = 10.5f
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(10))
        })

        page.addView(monitoringCard())
        page.addView(createCard())

        page.addView(TextView(activity).apply {
            text = "Alarmes enregistrées"
            textSize = 15f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(text)
            setPadding(dp(2), dp(14), 0, dp(8))
        })

        val alerts = store.list().sortedByDescending { it.createdAt }
        if (alerts.isEmpty()) {
            page.addView(infoCard("Aucune alarme", "Crée une alarme ici ou fais un appui long sur le graphique Analyse."))
        } else {
            alerts.forEach { page.addView(alertCard(it)) }
        }

        return ScrollView(activity).apply {
            isFillViewport = true
            addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun monitoringCard(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(surface, if (store.monitoringEnabled()) green else border, 17)
        layoutParams = margin(bottom = 10)

        val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = if (store.monitoringEnabled()) "● Surveillance active" else "○ Surveillance arrêtée"
                textSize = 14f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (store.monitoringEnabled()) green else text)
            })
            addView(TextView(activity).apply {
                text = "${store.activeCount()} alarme(s) active(s)"
                textSize = 10f
                setTextColor(muted)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Button(activity).apply {
            text = if (store.monitoringEnabled()) "ARRÊTER" else "DÉMARRER"
            textSize = 10f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = rounded(if (store.monitoringEnabled()) red else green, Color.TRANSPARENT, 12)
            setOnClickListener {
                if (store.monitoringEnabled()) MarketWatchService.stop(activity) else MarketWatchService.start(activity)
                activity.window.decorView.postDelayed({ ExperienceRoute.refreshCurrent(activity as MainActivityV4) }, 250L)
            }
        }, LinearLayout.LayoutParams(dp(100), dp(43)))
        addView(top)

        val smart = Switch(activity).apply {
            text = "Détecter aussi les mouvements rapides (~5 min)"
            textSize = 11f
            setTextColor(text)
            isChecked = store.smartWatchEnabled()
            setOnCheckedChangeListener { _, enabled -> store.setSmartWatchEnabled(enabled) }
        }
        addView(smart)

        val thresholdRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        thresholdRow.addView(TextView(activity).apply {
            text = "Seuil mouvement :"
            textSize = 10.5f
            setTextColor(muted)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        thresholdRow.addView(EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.1f", store.smartMoveThresholdPct()))
            setTextColor(text)
            textSize = 11f
            gravity = Gravity.CENTER
            background = rounded(surface2, border, 10)
            setOnFocusChangeListener { v, hasFocus ->
                if (!hasFocus) {
                    val value = (v as EditText).text.toString().replace(',', '.').toDoubleOrNull()
                    if (value != null) store.setSmartMoveThresholdPct(value)
                }
            }
        }, LinearLayout.LayoutParams(dp(80), dp(38)))
        thresholdRow.addView(TextView(activity).apply { text = "%"; textSize = 11f; setTextColor(muted); setPadding(dp(5), 0, 0, 0) })
        addView(thresholdRow)
    }

    private fun createCard(): View {
        val symbol = EditText(activity).apply {
            hint = "RENDERUSDC"
            setText("RENDERUSDC")
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(text)
            setHintTextColor(muted)
            background = rounded(surface2, border, 11)
            setPadding(dp(10), 0, dp(10), 0)
        }
        val price = EditText(activity).apply {
            hint = "Prix cible"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(text)
            setHintTextColor(muted)
            background = rounded(surface2, border, 11)
            setPadding(dp(10), 0, dp(10), 0)
        }
        var condition = "below"
        val below = Button(activity)
        val above = Button(activity)
        fun updateButtons() {
            below.text = if (condition == "below") "✓ PRIX ≤ CIBLE" else "PRIX ≤ CIBLE"
            above.text = if (condition == "above") "✓ PRIX ≥ CIBLE" else "PRIX ≥ CIBLE"
            below.setTextColor(if (condition == "below") Color.BLACK else text)
            above.setTextColor(if (condition == "above") Color.BLACK else text)
            below.background = rounded(if (condition == "below") orange else surface2, border, 11)
            above.background = rounded(if (condition == "above") orange else surface2, border, 11)
        }
        below.setOnClickListener { condition = "below"; updateButtons() }
        above.setOnClickListener { condition = "above"; updateButtons() }
        updateButtons()

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(surface, border, 17)
            layoutParams = margin(bottom = 8)
            addView(TextView(activity).apply {
                text = "Créer une alarme prix"
                textSize = 14f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(text)
            })
            addView(TextView(activity).apply {
                text = "Le prix est contrôlé directement depuis api.bybit.eu par le téléphone."
                textSize = 10f
                setTextColor(muted)
                setPadding(0, dp(2), 0, dp(8))
            })
            addView(symbol, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(7) })
            addView(price, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(7) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(below, LinearLayout.LayoutParams(0, dp(43), 1f).apply { rightMargin = dp(4) })
                addView(above, LinearLayout.LayoutParams(0, dp(43), 1f).apply { leftMargin = dp(4) })
            })
            addView(Button(activity).apply {
                text = "CRÉER L'ALARME LOCALE"
                textSize = 10.5f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.BLACK)
                background = rounded(purple, Color.TRANSPARENT, 12)
                setOnClickListener {
                    val target = price.text.toString().replace(',', '.').toDoubleOrNull()
                    if (target == null || target <= 0.0) {
                        Toast.makeText(activity, "Prix cible invalide", Toast.LENGTH_LONG).show()
                    } else {
                        runCatching { store.add(symbol.text.toString(), condition, target) }
                            .onSuccess {
                                if (!store.monitoringEnabled()) MarketWatchService.start(activity)
                                AlertCheckReceiver.checkNow(activity)
                                Toast.makeText(activity, "Alarme créée", Toast.LENGTH_SHORT).show()
                                ExperienceRoute.refreshCurrent(activity as MainActivityV4)
                            }
                            .onFailure { Toast.makeText(activity, it.message ?: "Erreur", Toast.LENGTH_LONG).show() }
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })
        }
    }

    private fun alertCard(a: LocalMarketAlert): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(12), dp(13), dp(12))
        background = rounded(surface, if (a.enabled) green else border, 15)
        layoutParams = margin(bottom = 8)

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = a.symbol
                    textSize = 15f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(text)
                })
                addView(TextView(activity).apply {
                    val relation = if (a.condition == "above") "≥" else "≤"
                    text = "Prix $relation ${fmt(a.targetPrice)} USDC • ${if (a.enabled) "ACTIVE" else "EN PAUSE"}"
                    textSize = 10.5f
                    setTextColor(if (a.enabled) green else muted)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(activity).apply {
                text = if (a.enabled) "PAUSE" else "ACTIVER"
                textSize = 9f
                setTextColor(text)
                background = rounded(surface2, border, 10)
                setOnClickListener { store.setEnabled(a.id, !a.enabled); ExperienceRoute.refreshCurrent(activity as MainActivityV4) }
            }, LinearLayout.LayoutParams(dp(82), dp(38)))
        })

        addView(TextView(activity).apply {
            text = "Créée ${date(a.createdAt)}${if (a.lastTriggeredAt > 0) " • déclenchée ${date(a.lastTriggeredAt)}" else ""}"
            textSize = 9f
            setTextColor(muted)
            setPadding(0, dp(5), 0, dp(6))
        })

        addView(Button(activity).apply {
            text = "SUPPRIMER"
            textSize = 9f
            setTextColor(red)
            background = rounded(surface2, border, 10)
            setOnClickListener { store.delete(a.id); ExperienceRoute.refreshCurrent(activity as MainActivityV4) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(37)))
    }

    private fun infoCard(title: String, body: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(18), dp(16), dp(18))
        background = rounded(surface, border, 16)
        addView(TextView(activity).apply { text = title; textSize = 14f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(text) })
        addView(TextView(activity).apply { text = body; textSize = 10.5f; setTextColor(muted); setPadding(0, dp(4), 0, 0) })
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun margin(bottom: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        setMargins(0, 0, 0, dp(bottom))
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private fun date(ms: Long) = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(ms))
    private fun fmt(v: Double): String = when {
        v >= 1000 -> String.format(Locale.US, "%.2f", v)
        v >= 1 -> String.format(Locale.US, "%.5f", v).trimEnd('0').trimEnd('.')
        else -> String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
    }
}
