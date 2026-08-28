package com.chk.binancebybit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class UpdateInstallActivity : Activity() {
    private val requestUnknownSources = 9401
    private var installStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        continueInstall()
    }

    @Deprecated("Deprecated in Android API, kept for unknown-app-source permission result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestUnknownSources) return
        if (InAppUpdateManager.canInstallPackages(this)) {
            continueInstall()
        } else {
            Toast.makeText(this, "Autorise CHK Crypto à installer sa mise à jour puis réessaie.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun continueInstall() {
        if (installStarted) return
        val verification = InAppUpdateManager.verifyDownloadedPackage(this)
        if (!verification.first) {
            Toast.makeText(this, verification.second, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (!InAppUpdateManager.canInstallPackages(this)) {
            InAppUpdateManager.requestInstallPermission(this, requestUnknownSources)
            return
        }
        val intent = InAppUpdateManager.buildInstallIntent(this)
        if (intent == null) {
            Toast.makeText(this, "Impossible d'ouvrir la mise à jour téléchargée.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        installStarted = true
        startActivity(intent)
        finish()
    }
}
