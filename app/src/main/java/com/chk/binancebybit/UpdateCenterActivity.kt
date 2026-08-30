package com.chk.binancebybit

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class UpdateCenterActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private lateinit var installedValue: TextView
    private lateinit var latestValue: TextView
    private lateinit var statusValue: TextView
    private lateinit var percentValue: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var primaryAction: Button
    private lateinit var checkButton: Button

    private val bg = Color.rgb(10, 12, 15)
    private val surface = Color.rgb(20, 23, 28)
    private val surface2 = Color.rgb(28, 32, 38)
    private val border = Color.rgb(48, 54, 64)
    private val text = Color.rgb(246, 247, 249)
    private val muted = Color.rgb(153, 162, 174)
    private val yellow = Color.rgb(240, 185, 11)
    private val green = Color.rgb(57, 197, 128)
    private val red = Color.rgb(238, 91, 91)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        setContentView(buildUi())
        refreshState()
        checkNow()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
    }

    override fun onPause() {
        polling = false
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(32))
        }
        scroll.addView(root)

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@UpdateCenterActivity).apply {
                text = "‹"
                textSize = 34f
                setTextColor(text)
                gravity = Gravity.CENTER
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(this@UpdateCenterActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@UpdateCenterActivity).apply {
                    text = "Mise à jour"
                    textSize = 24f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(text)
                })
                addView(TextView(this@UpdateCenterActivity).apply {
                    text = "CHK Crypto • centre de mise à jour"
                    textSize = 12f
                    setTextColor(muted)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        root.addView(card().apply {
            addView(label("Version installée"))
            installedValue = value("—")
            addView(installedValue)
            addView(space(12))
            addView(label("Dernière version"))
            latestValue = value("Vérification…")
            addView(latestValue)
        })

        root.addView(card().apply {
            addView(TextView(this@UpdateCenterActivity).apply {
                text = "État"
                textSize = 14f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(yellow)
            })
            statusValue = TextView(this@UpdateCenterActivity).apply {
                text = "Vérification…"
                textSize = 14f
                setTextColor(text)
                setPadding(0, dp(8), 0, dp(12))
            }
            addView(statusValue)

            progressBar = ProgressBar(this@UpdateCenterActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                progressTintList = ColorStateList.valueOf(yellow)
                progressBackgroundTintList = ColorStateList.valueOf(surface2)
            }
            addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)))

            percentValue = TextView(this@UpdateCenterActivity).apply {
                text = "0 %"
                textSize = 12f
                setTextColor(muted)
                gravity = Gravity.END
                setPadding(0, dp(7), 0, 0)
            }
            addView(percentValue)
        })

        root.addView(infoCard(
            "Tes données restent en place",
            "La mise à jour s'installe par-dessus CHK Crypto avec le même package et la même signature. Les clés API chiffrées, alarmes, règles Bot CHK, réglages et données locales ne sont pas effacés."
        ))

        primaryAction = primaryButton("Vérifier les mises à jour") { onPrimaryAction() }
        root.addView(primaryAction)
        checkButton = secondaryButton("Vérifier maintenant") { checkNow() }
        root.addView(checkButton)

        root.addView(TextView(this).apply {
            text = "Pendant le téléchargement, la barre ci-dessus monte de 0 à 100 %. Tu peux quitter cet écran : Android continue le téléchargement en arrière-plan et CHK Crypto te prévient quand l'APK est prête à installer."
            textSize = 12f
            setTextColor(muted)
            setLineSpacing(0f, 1.18f)
            setPadding(dp(4), dp(14), dp(4), 0)
        })
        return scroll
    }

    private fun checkNow() {
        checkButton.isEnabled = false
        checkButton.text = "Vérification…"
        statusValue.text = "Recherche de la dernière version…"
        InAppUpdateManager.checkForUpdate(this, force = true, showDialog = false) {
            checkButton.isEnabled = true
            checkButton.text = "Vérifier maintenant"
            if (it.error != null && it.release == null) {
                Toast.makeText(this, it.error, Toast.LENGTH_LONG).show()
            }
            refreshState()
        }
    }

    private fun onPrimaryAction() {
        val state = InAppUpdateManager.currentDownloadState(this)
        when {
            state.readyToInstall -> InAppUpdateManager.launchInstaller(this)
            state.status == "DOWNLOADING" || state.status == "PENDING" || state.status == "PAUSED" -> {
                Toast.makeText(this, "Téléchargement déjà en cours", Toast.LENGTH_SHORT).show()
            }
            state.status == "FAILED" -> {
                if (!InAppUpdateManager.retryDownload(this)) checkNow()
                refreshState()
            }
            else -> {
                val release = InAppUpdateManager.cachedRelease(this)
                if (release != null) {
                    InAppUpdateManager.startBackgroundDownload(this, release)
                    refreshState()
                } else {
                    checkNow()
                }
            }
        }
    }

    private fun refreshState() {
        val state = InAppUpdateManager.currentDownloadState(this)
        installedValue.text = "v${state.installedVersion}"
        latestValue.text = state.latestVersion?.let { "v$it" } ?: "—"
        statusValue.text = state.message
        statusValue.setTextColor(when (state.status) {
            "READY" -> green
            "FAILED", "ERROR" -> red
            "AVAILABLE" -> yellow
            else -> text
        })

        val visualProgress = when (state.status) {
            "READY" -> 100
            else -> state.progressPercent
        }
        progressBar.setProgress(visualProgress, true)
        percentValue.text = if (state.totalBytes > 0L && state.status in listOf("DOWNLOADING", "PAUSED", "READY")) {
            "${visualProgress} % • ${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}"
        } else {
            "$visualProgress %"
        }

        primaryAction.text = when {
            state.readyToInstall -> "Installer v${state.latestVersion ?: ""} maintenant"
            state.status == "DOWNLOADING" -> "Téléchargement en cours…"
            state.status == "PENDING" -> "Téléchargement en attente…"
            state.status == "PAUSED" -> "Téléchargement en pause"
            state.status == "FAILED" -> "Relancer le téléchargement"
            state.updateAvailable -> "Télécharger v${state.latestVersion ?: ""}"
            else -> "Vérifier les mises à jour"
        }
        primaryAction.isEnabled = state.status !in listOf("DOWNLOADING", "PENDING")
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(object : Runnable {
            override fun run() {
                if (!polling || isFinishing || isDestroyed) return
                refreshState()
                handler.postDelayed(this, 600L)
            }
        })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(surface, border, 18)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(12), 0, 0)
        }
    }

    private fun infoCard(titleText: String, detail: String): LinearLayout = card().apply {
        addView(TextView(this@UpdateCenterActivity).apply {
            text = titleText
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(green)
        })
        addView(TextView(this@UpdateCenterActivity).apply {
            text = detail
            textSize = 12f
            setTextColor(muted)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun label(value: String) = TextView(this).apply {
        text = value.uppercase(Locale.FRANCE)
        textSize = 10f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(muted)
    }

    private fun value(value: String) = TextView(this).apply {
        text = value
        textSize = 22f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(text)
        setPadding(0, dp(3), 0, 0)
    }

    private fun primaryButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(Color.BLACK)
        background = rounded(yellow, Color.TRANSPARENT, 14)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            setMargins(0, dp(16), 0, 0)
        }
    }

    private fun secondaryButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(text)
        background = rounded(surface2, border, 14)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            setMargins(0, dp(9), 0, 0)
        }
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun space(height: Int) = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun formatBytes(value: Long): String = when {
        value >= 1024L * 1024L -> String.format(Locale.FRANCE, "%.1f Mo", value / (1024.0 * 1024.0))
        value >= 1024L -> String.format(Locale.FRANCE, "%.0f Ko", value / 1024.0)
        else -> "$value o"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
