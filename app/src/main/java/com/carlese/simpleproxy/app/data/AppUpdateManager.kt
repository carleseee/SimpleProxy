package com.carlese.simpleproxy.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import com.carlese.simpleproxy.app.MainActivity
import com.carlese.simpleproxy.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.UUID

data class ReleaseInfo(
    val versionName: String,
    val apkUrl: String?,
    val htmlUrl: String,
    val releaseNotes: String,
    val assetSizeBytes: Long
)

data class DownloadedApk(
    val file: File?,
    val mediaStoreUri: Uri?,
    val displayName: String,
    val sizeBytes: Long
)

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val release: ReleaseInfo) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

object AppUpdateManager {
    const val EXTRA_OPEN_UPDATES = "extra_open_updates"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/carleseee/SimpleProxy/releases/latest"
    private const val RELEASES_FALLBACK_URL = "https://github.com/carleseee/SimpleProxy/releases"
    private const val PREFS_NAME = "simple_proxy_update_prefs"
    private const val KEY_AUTO_DELETE_APK = "auto_delete_apk_after_process"
    private const val KEY_PENDING_CLEANUP_PATH = "pending_cleanup_apk_path"
    private const val KEY_LAST_AUTO_CHECK_MS = "last_auto_check_ms"
    private const val AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val CHANNEL_UPDATES_ID = "simple_proxy_updates"
    private const val NOTIF_UPDATE_ID = 4102
    private const val MAX_REDIRECTS = 5

    private val _availableRelease = MutableStateFlow<ReleaseInfo?>(null)
    val availableRelease: StateFlow<ReleaseInfo?> = _availableRelease.asStateFlow()

    fun getInstalledVersionName(context: Context): String = runCatching {
        val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        pkgInfo.versionName ?: "1.0.0"
    }.getOrDefault("1.0.0")

    fun isAutoDeleteAfterProcessEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_DELETE_APK, false)

    fun setAutoDeleteAfterProcessEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_AUTO_DELETE_APK, enabled)
        }
    }

    fun isTrustedGithubHttpsUrl(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return runCatching {
            val uri = URI(raw.trim())
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            val host = uri.host?.lowercase(Locale.US) ?: return false
            host == "github.com" ||
                host.endsWith(".github.com") ||
                host == "githubusercontent.com" ||
                host.endsWith(".githubusercontent.com")
        }.getOrDefault(false)
    }

    fun formatSizeMb(bytes: Long): String {
        if (bytes <= 0L) return "APK"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }

    private fun compareSemver(remote: String, current: String): Int {
        fun parseParts(v: String): List<Int> = v.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }

        val r = parseParts(remote)
        val c = parseParts(current)
        val maxLen = maxOf(r.size, c.size)
        for (i in 0 until maxLen) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv - cv
        }
        return 0
    }

    suspend fun fetchLatestRelease(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "SimpleProxy-Android")
            }
            try {
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    _availableRelease.value = null
                    return@withContext UpdateCheckResult.UpToDate
                }
                if (code !in 200..299) {
                    return@withContext UpdateCheckResult.Error("HTTP $code")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "").trim()
                if (tag.isEmpty() || compareSemver(tag, currentVersion) <= 0) {
                    _availableRelease.value = null
                    return@withContext UpdateCheckResult.UpToDate
                }

                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                var apkSize = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "")
                        val downloadUrl = asset.optString("browser_download_url", "")
                        if (name.endsWith(".apk", ignoreCase = true) && isTrustedGithubHttpsUrl(downloadUrl)) {
                            apkUrl = downloadUrl
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                }

                val rawHtmlUrl = json.optString("html_url", "")
                val htmlUrl = if (isTrustedGithubHttpsUrl(rawHtmlUrl)) rawHtmlUrl else RELEASES_FALLBACK_URL
                val notes = json.optString("body", "").take(500).trim()

                val releaseInfo = ReleaseInfo(
                    versionName = tag.removePrefix("v").removePrefix("V"),
                    apkUrl = apkUrl,
                    htmlUrl = htmlUrl,
                    releaseNotes = notes,
                    assetSizeBytes = apkSize
                )
                _availableRelease.value = releaseInfo
                UpdateCheckResult.Available(releaseInfo)
            } finally {
                conn.disconnect()
            }
        }.getOrElse { err ->
            UpdateCheckResult.Error(err.localizedMessage ?: "Network error")
        }
    }

    suspend fun checkAndNotifyIfAvailable(
        context: Context,
        title: String,
        channelName: String,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(KEY_LAST_AUTO_CHECK_MS, 0L)
        if (!force && now - lastCheck < AUTO_CHECK_INTERVAL_MS) return@withContext
        prefs.edit { putLong(KEY_LAST_AUTO_CHECK_MS, now) }

        val result = fetchLatestRelease(getInstalledVersionName(context))
        if (result is UpdateCheckResult.Available) {
            postUpdateNotification(context, result.release, title, channelName)
        }
    }

    private fun postUpdateNotification(
        context: Context,
        release: ReleaseInfo,
        title: String,
        channelName: String
    ) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_UPDATES_ID,
            channelName,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
        val openIntent = PendingIntent.getActivity(
            context,
            NOTIF_UPDATE_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_UPDATES, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES_ID)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle(title)
            .setContentText("Simple Proxy ${release.versionName}")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(NOTIF_UPDATE_ID, notification)
    }

    private fun openTrustedConnection(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS) {
            require(isTrustedGithubHttpsUrl(currentUrl)) { "Untrusted update URL" }
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "SimpleProxy-Android")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                require(!next.isNullOrBlank() && isTrustedGithubHttpsUrl(next)) { "Untrusted redirect URL" }
                currentUrl = next
            } else {
                return conn
            }
        }
        error("Too many redirects")
    }

    suspend fun downloadApk(
        context: Context,
        release: ReleaseInfo,
        saveToDownloads: Boolean,
        onProgress: (Int) -> Unit
    ): DownloadedApk = withContext(Dispatchers.IO) {
        val url = release.apkUrl ?: error("Missing APK URL")
        val safeVersion = release.versionName.replace(Regex("[^A-Za-z0-9._-]"), "").ifBlank { "update" }
        val fileName = "SimpleProxy-v$safeVersion.apk"

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val tempFile = File(updatesDir, "${UUID.randomUUID()}-$fileName")
        require(tempFile.canonicalPath.startsWith(updatesDir.canonicalPath + File.separator)) {
            "Invalid destination path"
        }
        if (tempFile.exists()) tempFile.delete()

        val conn = openTrustedConnection(url)
        try {
            val code = conn.responseCode
            require(code in 200..299) { "Download failed (HTTP $code)" }
            val total = conn.contentLengthLong.takeIf { it > 0L } ?: release.assetSizeBytes

            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var downloaded = 0L
                    var lastPct = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0L) {
                            val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                withContext(Dispatchers.Main) { onProgress(pct) }
                            }
                        }
                    }
                    output.flush()
                }
            }
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        } finally {
            conn.disconnect()
        }

        // Verify valid ZIP/APK magic bytes ("PK")
        require(tempFile.length() > 1024L) { "Downloaded APK is invalid" }
        val header = ByteArray(2)
        FileInputStream(tempFile).use { it.read(header) }
        require(header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) {
            tempFile.delete()
            "Invalid APK signature"
        }

        withContext(Dispatchers.Main) { onProgress(100) }

        if (saveToDownloads) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create APK in Downloads")
                try {
                    val copiedBytes = resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(tempFile).use { input -> input.copyTo(out) }
                    } ?: error("Could not write APK to Downloads")
                    require(copiedBytes == tempFile.length()) { "APK was not fully saved to Downloads" }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    check(resolver.update(uri, values, null, null) > 0) {
                        "Could not finish saving APK to Downloads"
                    }
                } catch (t: Throwable) {
                    resolver.delete(uri, null, null)
                    throw t
                }
                val size = tempFile.length()
                tempFile.delete()
                return@withContext DownloadedApk(
                    file = null,
                    mediaStoreUri = uri,
                    displayName = fileName,
                    sizeBytes = size
                )
            }
            val extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.apply { mkdirs() }
            if (extDir != null) {
                val dest = File(extDir, fileName)
                if (dest.exists()) dest.delete()
                FileInputStream(tempFile).use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                }
                val size = dest.length()
                tempFile.delete()
                return@withContext DownloadedApk(
                    file = dest,
                    mediaStoreUri = null,
                    displayName = fileName,
                    sizeBytes = size
                )
            }
        }

        if (isAutoDeleteAfterProcessEnabled(context)) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_PENDING_CLEANUP_PATH, tempFile.absolutePath)
            }
        }

        DownloadedApk(
            file = tempFile,
            mediaStoreUri = null,
            displayName = fileName,
            sizeBytes = tempFile.length()
        )
    }

    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri()
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun launchApkInstaller(context: Context, downloaded: DownloadedApk): Boolean = runCatching {
        val uri = downloaded.mediaStoreUri ?: downloaded.file
            ?.takeIf { it.exists() }
            ?.let {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    it
                )
            }
            ?: return@runCatching false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    fun deleteDownloadedApk(context: Context, downloaded: DownloadedApk): Boolean {
        var deleted = false
        downloaded.file?.let { file ->
            if (file.exists()) {
                deleted = file.delete()
            }
        }
        downloaded.mediaStoreUri?.let { uri ->
            runCatching {
                if (context.contentResolver.delete(uri, null, null) > 0) {
                    deleted = true
                }
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(KEY_PENDING_CLEANUP_PATH)
        }
        return deleted
    }

    fun cleanupPendingUpdateIfRequested(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO_DELETE_APK, false)) return
        val path = prefs.getString(KEY_PENDING_CLEANUP_PATH, null) ?: return
        val updatesDir = File(context.cacheDir, "updates")
        val target = File(path)
        runCatching {
            if (target.canonicalPath.startsWith(updatesDir.canonicalPath + File.separator) && target.exists()) {
                target.delete()
            }
        }
        prefs.edit { remove(KEY_PENDING_CLEANUP_PATH) }
    }
}
