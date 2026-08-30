package com.chk.binancebybit

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object InAppUpdateManager {
    private const val PREFS = "chk_in_app_update"
    private const val REPO = "Chasmet/Binance-bybyt-"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val CHECK_INTERVAL_MS = 30L * 60L * 1000L
    private const val DISMISS_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val CHANNEL_ID = "chk_crypto_updates"
    const val EXTRA_AUTO_INSTALL = "chk_auto_install"

    private val installed = AtomicBoolean(false)
    private val checking = AtomicBoolean(false)
    private val dialogVisible = AtomicBoolean(false)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    data class ReleaseInfo(
        val version: String,
        val apkUrl: String,
        val notes: String
    )

    data class CheckResult(
        val installedVersion: String,
        val release: ReleaseInfo?,
        val updateAvailable: Boolean,
        val error: String? = null
    )

    data class DownloadState(
        val installedVersion: String,
        val latestVersion: String?,
        val status: String,
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val updateAvailable: Boolean,
        val readyToInstall: Boolean,
        val message: String
    )

    fun install(app: Application) {
        if (!installed.compareAndSet(false, true)) return
        createNotificationChannel(app)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is MainActivityV4) return
                cleanupAfterSuccessfulUpdate(activity)
                if (downloadReady(activity)) {
                    showReadyDialog(activity)
                } else {
                    checkForUpdate(activity, force = false)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun checkForUpdate(activity: Activity, force: Boolean) {
        checkForUpdate(activity, force, showDialog = true, callback = null)
    }

    fun checkForUpdate(
        activity: Activity,
        force: Boolean,
        showDialog: Boolean,
        callback: ((CheckResult) -> Unit)?
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("last_check_ms", 0L) < CHECK_INTERVAL_MS) {
            callback?.let { cb ->
                val cached = cachedRelease(activity)
                activity.runOnUiThread {
                    cb(CheckResult(installedVersionName(activity), cached, cached != null, null))
                }
            }
            return
        }
        if (!checking.compareAndSet(false, true)) {
            callback?.let { cb ->
                val cached = cachedRelease(activity)
                activity.runOnUiThread {
                    cb(CheckResult(installedVersionName(activity), cached, cached != null, "Vérification déjà en cours"))
                }
            }
            return
        }
        prefs.edit().putLong("last_check_ms", now).apply()

        Thread {
            var result: CheckResult? = null
            try {
                val request = Request.Builder()
                    .url(LATEST_RELEASE_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "CHK-Crypto-Android-Updater")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val message = "Vérification impossible (${response.code})"
                        prefs.edit().putString("last_error", message).apply()
                        result = CheckResult(installedVersionName(activity), cachedRelease(activity), false, message)
                        if (force && showDialog) showToast(activity, message)
                        return@use
                    }
                    val root = JSONObject(response.body?.string().orEmpty())
                    if (root.optBoolean("draft") || root.optBoolean("prerelease")) {
                        result = CheckResult(installedVersionName(activity), null, false, null)
                        return@use
                    }
                    val latest = root.optString("tag_name").removePrefix("v").trim()
                    val assets = root.optJSONArray("assets")
                    var stableUrl = ""
                    var anyApkUrl = ""
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.optJSONObject(i) ?: continue
                            val name = a.optString("name")
                            if (!name.endsWith(".apk", ignoreCase = true)) continue
                            val url = a.optString("browser_download_url")
                            if (url.isBlank()) continue
                            if (anyApkUrl.isBlank()) anyApkUrl = url
                            if (name.contains("stable", ignoreCase = true)) stableUrl = url
                        }
                    }
                    val apkUrl = stableUrl.ifBlank { anyApkUrl }
                    val notes = root.optString("body").trim().take(1800)
                    if (latest.isNotBlank()) {
                        prefs.edit()
                            .putString("latest_version", latest)
                            .putString("latest_apk_url", apkUrl)
                            .putString("latest_notes", notes)
                            .putLong("latest_checked_ms", System.currentTimeMillis())
                            .remove("last_error")
                            .apply()
                    }
                    val installedVersion = installedVersionName(activity)
                    val available = latest.isNotBlank() && apkUrl.isNotBlank() && compareVersions(latest, installedVersion) > 0
                    val release = if (available) ReleaseInfo(latest, apkUrl, notes) else null
                    result = CheckResult(installedVersion, release, available, null)
                    if (!available) {
                        if (force && showDialog) showToast(activity, "CHK Crypto est déjà à jour")
                    } else if (showDialog) {
                        activity.runOnUiThread { showUpdateDialog(activity, release!!) }
                    }
                }
            } catch (e: Exception) {
                val message = "Vérification impossible : ${e.message ?: "réseau"}"
                prefs.edit().putString("last_error", message).apply()
                result = CheckResult(installedVersionName(activity), cachedRelease(activity), false, message)
                if (force && showDialog) showToast(activity, message)
            } finally {
                checking.set(false)
                val finalResult = result ?: CheckResult(installedVersionName(activity), cachedRelease(activity), false, "Réponse de mise à jour indisponible")
                callback?.let { cb ->
                    activity.runOnUiThread {
                        if (!activity.isFinishing && !activity.isDestroyed) cb(finalResult)
                    }
                }
            }
        }.apply {
            name = "CHK-Update-Check"
            isDaemon = true
            start()
        }
    }

    fun installedVersionName(context: Context): String = currentPackageInfo(context).versionName ?: "0"

    fun cachedRelease(context: Context): ReleaseInfo? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString("latest_version", "").orEmpty()
        val url = prefs.getString("latest_apk_url", "").orEmpty()
        val notes = prefs.getString("latest_notes", "").orEmpty()
        if (version.isBlank() || url.isBlank()) return null
        if (compareVersions(version, installedVersionName(context)) <= 0) return null
        return ReleaseInfo(version, url, notes)
    }

    fun currentDownloadState(context: Context): DownloadState {
        cleanupAfterSuccessfulUpdate(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installedVersion = installedVersionName(context)
        val latest = prefs.getString("latest_version", "").orEmpty().ifBlank { null }
        val releaseAvailable = latest != null && compareVersions(latest, installedVersion) > 0
        val id = prefs.getLong("download_id", -1L)
        val downloadVersion = prefs.getString("download_version", "").orEmpty().ifBlank { latest }
        if (id <= 0L) {
            val error = prefs.getString("last_error", "").orEmpty()
            return DownloadState(
                installedVersion = installedVersion,
                latestVersion = latest,
                status = if (releaseAvailable) "AVAILABLE" else if (error.isNotBlank()) "ERROR" else "IDLE",
                progressPercent = 0,
                downloadedBytes = 0L,
                totalBytes = 0L,
                updateAvailable = releaseAvailable,
                readyToInstall = false,
                message = when {
                    releaseAvailable -> "Mise à jour v$latest disponible"
                    error.isNotBlank() -> error
                    else -> "CHK Crypto est à jour"
                }
            )
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return runCatching {
            dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use DownloadState(installedVersion, latest, "FAILED", 0, 0, 0, releaseAvailable, false, "Téléchargement introuvable")
                }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0L)
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val pct = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
                val ready = status == DownloadManager.STATUS_SUCCESSFUL && downloadReady(context)
                val state = when (status) {
                    DownloadManager.STATUS_PENDING -> "PENDING"
                    DownloadManager.STATUS_RUNNING -> "DOWNLOADING"
                    DownloadManager.STATUS_PAUSED -> "PAUSED"
                    DownloadManager.STATUS_SUCCESSFUL -> if (ready) "READY" else "VERIFYING"
                    DownloadManager.STATUS_FAILED -> "FAILED"
                    else -> "UNKNOWN"
                }
                val message = when (state) {
                    "PENDING" -> "Téléchargement en attente…"
                    "DOWNLOADING" -> "Téléchargement v${downloadVersion ?: ""} • $pct %"
                    "PAUSED" -> "Téléchargement en pause • $pct %"
                    "READY" -> "Mise à jour v${downloadVersion ?: ""} prête à installer"
                    "VERIFYING" -> "Vérification de l'APK…"
                    "FAILED" -> "Échec du téléchargement. Tu peux relancer."
                    else -> "État du téléchargement indisponible"
                }
                DownloadState(installedVersion, latest, state, pct, downloaded, total, releaseAvailable, ready, message)
            }
        }.getOrElse {
            DownloadState(installedVersion, latest, "ERROR", 0, 0, 0, releaseAvailable, false, it.message ?: "Erreur DownloadManager")
        }
    }

    private fun showUpdateDialog(activity: Activity, release: ReleaseInfo) {
        if (activity.isFinishing || activity.isDestroyed || !dialogVisible.compareAndSet(false, true)) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val dismissedVersion = prefs.getString("dismissed_version", "").orEmpty()
        val dismissedAt = prefs.getLong("dismissed_at", 0L)
        if (dismissedVersion == release.version && now - dismissedAt < DISMISS_INTERVAL_MS) {
            dialogVisible.set(false)
            return
        }
        val notes = release.notes.ifBlank { "Améliorations et corrections de CHK Crypto." }
        val message = buildString {
            append("Version ${installedVersionName(activity)} → ${release.version}\n\n")
            append(notes)
            append("\n\nTu peux aussi suivre le téléchargement dans Réglages → Mise à jour.")
        }
        AlertDialog.Builder(activity)
            .setTitle("Nouvelle mise à jour CHK Crypto")
            .setMessage(message)
            .setPositiveButton("Télécharger") { _, _ ->
                dialogVisible.set(false)
                startBackgroundDownload(activity, release)
            }
            .setNegativeButton("Plus tard") { _, _ ->
                prefs.edit()
                    .putString("dismissed_version", release.version)
                    .putLong("dismissed_at", System.currentTimeMillis())
                    .apply()
                dialogVisible.set(false)
            }
            .setOnCancelListener {
                prefs.edit()
                    .putString("dismissed_version", release.version)
                    .putLong("dismissed_at", System.currentTimeMillis())
                    .apply()
                dialogVisible.set(false)
            }
            .show()
    }

    fun startBackgroundDownload(context: Context, release: ReleaseInfo): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldId = prefs.getLong("download_id", -1L)
        if (oldId > 0L) {
            runCatching { (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(oldId) }
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "CHK-Crypto-v${safeVersion(release.version)}-stable.apk"
        val target = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        runCatching { if (target.exists()) target.delete() }

        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("CHK Crypto v${release.version}")
            .setDescription("Téléchargement de la mise à jour")
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)

        val id = dm.enqueue(request)
        prefs.edit()
            .putLong("download_id", id)
            .putString("download_version", release.version)
            .putString("download_path", target.absolutePath)
            .putLong("download_started_ms", System.currentTimeMillis())
            .putString("latest_version", release.version)
            .putString("latest_apk_url", release.apkUrl)
            .putString("latest_notes", release.notes)
            .remove("dismissed_version")
            .remove("dismissed_at")
            .remove("last_error")
            .apply()
        Toast.makeText(context, "Téléchargement v${release.version} lancé", Toast.LENGTH_SHORT).show()
        return id
    }

    fun retryDownload(context: Context): Boolean {
        val release = cachedRelease(context) ?: return false
        startBackgroundDownload(context, release)
        return true
    }

    fun handleDownloadCompleted(context: Context, completedId: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (completedId <= 0L || completedId != prefs.getLong("download_id", -1L)) return
        if (!downloadReady(context)) return
        notifyUpdateReady(context)
    }

    fun downloadReady(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong("download_id", -1L)
        val path = prefs.getString("download_path", "").orEmpty()
        if (id <= 0L || path.isBlank() || !File(path).isFile) return false
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return runCatching {
            dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val idx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                idx >= 0 && cursor.getInt(idx) == DownloadManager.STATUS_SUCCESSFUL
            }
        }.getOrDefault(false)
    }

    private fun showReadyDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed || !dialogVisible.compareAndSet(false, true)) return
        val version = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("download_version", "nouvelle").orEmpty()
        AlertDialog.Builder(activity)
            .setTitle("Mise à jour prête")
            .setMessage("CHK Crypto v$version est téléchargée. L'installation conserve tes données, règles Bot, alarmes, réglages et clés API chiffrées.")
            .setPositiveButton("Installer maintenant") { _, _ ->
                dialogVisible.set(false)
                launchInstaller(activity)
            }
            .setNegativeButton("Plus tard") { _, _ -> dialogVisible.set(false) }
            .setOnCancelListener { dialogVisible.set(false) }
            .show()
    }

    fun launchInstaller(activity: Activity) {
        activity.startActivity(Intent(activity, UpdateInstallActivity::class.java).putExtra(EXTRA_AUTO_INSTALL, true))
    }

    fun verifyDownloadedPackage(context: Context): Pair<Boolean, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = prefs.getString("download_path", "").orEmpty()
        if (path.isBlank()) return false to "Fichier de mise à jour introuvable"
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) return false to "Téléchargement incomplet"

        return runCatching {
            val pm = context.packageManager
            val archive = packageArchiveInfo(pm, file.absolutePath)
                ?: return@runCatching false to "APK invalide"
            if (archive.packageName != context.packageName) {
                return@runCatching false to "APK refusée : package différent"
            }
            val current = currentPackageInfo(context)
            val currentSigners = signerDigests(current)
            val archiveSigners = signerDigests(archive)
            if (currentSigners.isEmpty() || archiveSigners.isEmpty() || currentSigners != archiveSigners) {
                return@runCatching false to "APK refusée : signature différente"
            }
            val currentCode = longVersionCode(current)
            val archiveCode = longVersionCode(archive)
            if (archiveCode <= currentCode) {
                return@runCatching false to "Cette version est déjà installée"
            }
            true to "OK"
        }.getOrElse { false to (it.message ?: "Vérification APK impossible") }
    }

    fun canInstallPackages(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    }

    fun requestInstallPermission(activity: Activity, requestCode: Int) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivityForResult(intent, requestCode)
    }

    fun buildInstallIntent(context: Context): Intent? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong("download_id", -1L)
        if (id <= 0L) return null
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(id) ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = android.content.ClipData.newRawUri("CHK Crypto update", uri)
        }
    }

    private fun notifyUpdateReady(context: Context) {
        createNotificationChannel(context)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val version = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("download_version", "").orEmpty()
        val intent = Intent(context, UpdateInstallActivity::class.java).putExtra(EXTRA_AUTO_INSTALL, true)
        val pending = PendingIntent.getActivity(
            context,
            4902,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("CHK Crypto • mise à jour prête")
            .setContentText("Touchez pour installer v$version sans perdre vos données")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(4902, notification)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mises à jour CHK Crypto", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Téléchargement et installation des nouvelles versions de CHK Crypto"
            }
        )
    }

    private fun cleanupAfterSuccessfulUpdate(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val downloaded = prefs.getString("download_version", "").orEmpty()
        if (downloaded.isBlank() || compareVersions(installedVersionName(context), downloaded) < 0) return
        val path = prefs.getString("download_path", "").orEmpty()
        if (path.isNotBlank()) runCatching { File(path).delete() }
        prefs.edit()
            .remove("download_id")
            .remove("download_version")
            .remove("download_path")
            .remove("download_started_ms")
            .remove("dismissed_version")
            .remove("dismissed_at")
            .apply()
    }

    private fun currentPackageInfo(context: Context): PackageInfo {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }

    private fun packageArchiveInfo(pm: PackageManager, path: String): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList().orEmpty()
        }
        return signatures.map { sig ->
            val bytes = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
            bytes.joinToString("") { "%02x".format(Locale.US, it) }
        }.toSet()
    }

    private fun longVersionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val aa = a.removePrefix("v").split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val bb = b.removePrefix("v").split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(aa.size, bb.size)
        for (i in 0 until n) {
            val x = aa.getOrElse(i) { 0 }
            val y = bb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun safeVersion(v: String): String = v.replace(Regex("[^0-9A-Za-z._-]"), "_")

    private fun showToast(activity: Activity, text: String) {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
        }
    }
}
