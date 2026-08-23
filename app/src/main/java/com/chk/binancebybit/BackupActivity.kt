package com.chk.binancebybit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

class BackupActivity : Activity() {
    private lateinit var passwordField: EditText
    private var pendingPassword: CharArray? = null

    private val bg = Color.rgb(10, 12, 15)
    private val surface = Color.rgb(20, 23, 28)
    private val surface2 = Color.rgb(28, 32, 38)
    private val border = Color.rgb(48, 54, 64)
    private val text = Color.rgb(246, 247, 249)
    private val muted = Color.rgb(153, 162, 174)
    private val orange = Color.rgb(245, 142, 30)
    private val green = Color.rgb(57, 197, 128)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        setContentView(buildUi())
    }

    override fun onDestroy() {
        pendingPassword?.fill('\u0000')
        pendingPassword = null
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        setBackgroundColor(bg)

        addView(TextView(this@BackupActivity).apply {
            text = "CHK Crypto • Sauvegarde"
            textSize = 24f
            setTextColor(text)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        addView(TextView(this@BackupActivity).apply {
            text = "Export chiffré des clés API et réglages importants"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(16))
        })

        addView(info(
            "Protection",
            "Le fichier est chiffré en AES-256-GCM avec une clé dérivée de ton mot de passe. Le mot de passe n'est jamais enregistré dans l'application.",
            green
        ))

        passwordField = EditText(this@BackupActivity).apply {
            hint = "Mot de passe de sauvegarde"
            setHintTextColor(Color.rgb(112, 121, 134))
            setTextColor(text)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = rounded(surface2, border, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        addView(passwordField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            setMargins(0, 0, 0, dp(10))
        })

        addView(actionButton("EXPORTER LA SAUVEGARDE CHIFFRÉE", green) { beginExport() })
        addView(actionButton("RESTAURER UNE SAUVEGARDE", orange) { beginImport() })
        addView(actionButton("RETOUR À CHK CRYPTO", surface2) { finish() })

        addView(info(
            "Important",
            "Conserve le fichier et son mot de passe séparément. Sans ce mot de passe, la sauvegarde ne peut pas être déchiffrée.",
            orange
        ))
    }

    private fun beginExport() {
        val password = passwordOrError() ?: return
        pendingPassword?.fill('\u0000')
        pendingPassword = password
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "CHK-Crypto-backup.chk")
        }
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    private fun beginImport() {
        val password = passwordOrError() ?: return
        pendingPassword?.fill('\u0000')
        pendingPassword = password
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    private fun passwordOrError(): CharArray? {
        val value = passwordField.text?.toString().orEmpty()
        if (value.length < 10) {
            Toast.makeText(this, "Utilise un mot de passe d'au moins 10 caractères", Toast.LENGTH_LONG).show()
            return null
        }
        return value.toCharArray()
    }

    @Deprecated("Legacy Activity result kept for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            clearPendingPassword()
            return
        }
        val uri = data?.data ?: run {
            clearPendingPassword()
            return
        }
        val password = pendingPassword ?: return
        try {
            when (requestCode) {
                REQUEST_EXPORT -> {
                    val bytes = encrypt(buildPlainBackup().toString().toByteArray(Charsets.UTF_8), password)
                    contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                        ?: error("Impossible d'écrire le fichier")
                    Toast.makeText(this, "Sauvegarde CHK Crypto créée", Toast.LENGTH_LONG).show()
                }
                REQUEST_IMPORT -> {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Impossible de lire le fichier")
                    val plain = decrypt(bytes, password)
                    restorePlainBackup(JSONObject(String(plain, Charsets.UTF_8)))
                    Toast.makeText(this, "Clés et réglages restaurés", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Échec : ${e.message ?: "sauvegarde invalide ou mauvais mot de passe"}", Toast.LENGTH_LONG).show()
        } finally {
            clearPendingPassword()
        }
    }

    private fun buildPlainBackup(): JSONObject {
        val secureStore = SecureStore(this)
        val secure = JSONObject()
        SECURE_KEYS.forEach { key ->
            val value = secureStore.get(key)
            if (value.isNotBlank()) secure.put(key, value)
        }

        val workspace = getSharedPreferences("chk_workspace", Context.MODE_PRIVATE)
        val notificationPrefs = getSharedPreferences("chk_trade_notifications", Context.MODE_PRIVATE)

        return JSONObject().apply {
            put("format", "CHK_CRYPTO_BACKUP")
            put("version", 1)
            put("createdAt", System.currentTimeMillis())
            put("secure", secure)
            put("workspace", encodePreferences(workspace.all))
            put("tradeNotifications", encodePreferences(notificationPrefs.all))
        }
    }

    private fun restorePlainBackup(root: JSONObject) {
        require(root.optString("format") == "CHK_CRYPTO_BACKUP") { "Format de sauvegarde inconnu" }
        require(root.optInt("version") == 1) { "Version de sauvegarde non supportée" }

        val secureStore = SecureStore(this)
        val secure = root.optJSONObject("secure") ?: JSONObject()
        SECURE_KEYS.forEach { key ->
            if (secure.has(key)) secureStore.put(key, secure.optString(key))
        }

        restorePreferences(
            getSharedPreferences("chk_workspace", Context.MODE_PRIVATE),
            root.optJSONObject("workspace") ?: JSONObject()
        )
        restorePreferences(
            getSharedPreferences("chk_trade_notifications", Context.MODE_PRIVATE),
            root.optJSONObject("tradeNotifications") ?: JSONObject()
        )
    }

    private fun encodePreferences(values: Map<String, *>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) ->
            val entry = JSONObject()
            when (value) {
                is String -> { entry.put("type", "string"); entry.put("value", value) }
                is Int -> { entry.put("type", "int"); entry.put("value", value) }
                is Long -> { entry.put("type", "long"); entry.put("value", value) }
                is Boolean -> { entry.put("type", "boolean"); entry.put("value", value) }
                is Float -> { entry.put("type", "float"); entry.put("value", value.toDouble()) }
                is Set<*> -> {
                    entry.put("type", "stringset")
                    entry.put("value", JSONArray(value.filterIsInstance<String>()))
                }
                else -> return@forEach
            }
            put(key, entry)
        }
    }

    private fun restorePreferences(prefs: android.content.SharedPreferences, encoded: JSONObject) {
        val editor = prefs.edit()
        val keys = encoded.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = encoded.optJSONObject(key) ?: continue
            when (entry.optString("type")) {
                "string" -> editor.putString(key, entry.optString("value"))
                "int" -> editor.putInt(key, entry.optInt("value"))
                "long" -> editor.putLong(key, entry.optLong("value"))
                "boolean" -> editor.putBoolean(key, entry.optBoolean("value"))
                "float" -> editor.putFloat(key, entry.optDouble("value").toFloat())
                "stringset" -> {
                    val array = entry.optJSONArray("value") ?: JSONArray()
                    val set = mutableSetOf<String>()
                    for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(set::add)
                    editor.putStringSet(key, set)
                }
            }
        }
        editor.apply()
    }

    private fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plain)
        key.encoded?.fill(0)

        return JSONObject().apply {
            put("format", "CHK_CRYPTO_ENCRYPTED")
            put("version", 1)
            put("kdf", "PBKDF2-HMAC-SHA256")
            put("iterations", KDF_ITERATIONS)
            put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decrypt(containerBytes: ByteArray, password: CharArray): ByteArray {
        val box = JSONObject(String(containerBytes, Charsets.UTF_8))
        require(box.optString("format") == "CHK_CRYPTO_ENCRYPTED") { "Fichier CHK Crypto invalide" }
        require(box.optInt("version") == 1) { "Version chiffrée non supportée" }
        val iterations = box.optInt("iterations", KDF_ITERATIONS).coerceIn(100_000, 1_000_000)
        val salt = Base64.decode(box.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(box.getString("iv"), Base64.NO_WRAP)
        val ciphertext = Base64.decode(box.getString("ciphertext"), Base64.NO_WRAP)
        require(salt.size >= 16 && iv.size == 12 && ciphertext.isNotEmpty()) { "Contenu chiffré invalide" }

        val key = deriveKey(password, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        } finally {
            key.encoded?.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = KDF_ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun clearPendingPassword() {
        pendingPassword?.fill('\u0000')
        pendingPassword = null
    }

    private fun actionButton(label: String, fill: Int, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(if (fill == surface2) text else Color.BLACK)
        background = rounded(fill, if (fill == surface2) border else Color.TRANSPARENT, 15)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            setMargins(0, 0, 0, dp(10))
        }
    }

    private fun info(title: String, body: String, accent: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(surface, accent, 16)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(12))
        }
        addView(TextView(this@BackupActivity).apply {
            text = title
            textSize = 13f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(accent)
        })
        addView(TextView(this@BackupActivity).apply {
            text = body
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_EXPORT = 9901
        private const val REQUEST_IMPORT = 9902
        private const val KDF_ITERATIONS = 180_000
        private val SECURE_KEYS = listOf(
            "binance_api_key",
            "binance_api_secret",
            "bybit_api_key",
            "bybit_api_secret",
            "sync_secret"
        )
    }
}
