package com.v1pach.telescript.ota

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.v1pach.telescript.AppSettings
import com.v1pach.telescript.BuildConfig
import com.v1pach.telescript.R
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Self-update from GitHub releases (vipa-ch-cloud/TgAuto, releases/latest). Modes: off | notify | download | auto.
 * A downloaded APK is installed only after: size match, sha256 (when the release publishes one), same package name,
 * higher versionCode and an IDENTICAL signing certificate to the installed app. Otherwise it is deleted.
 */
object ApkUpdater {
    private lateinit var app: Context
    fun init(c: Context) { app = c.applicationContext }

    private const val ACTION = "com.v1pach.telescript.APK_INSTALL_RESULT"
    private val dir get() = File(app.cacheDir, "apk-update")

    @Volatile var progress: Float = -1f; private set
    @Volatile var ready: File? = null; private set

    /** Blocking. Returns a newer APK for this channel or null. */
    fun check(): ApkRelease? {
        val json = JSONObject(String(OtaManager.http("https://api.github.com/repos/${OtaManager.REPO}/releases/latest", 2L * 1024 * 1024)))
        return ApkCore.pick(json, BuildConfig.FLAVOR, BuildConfig.VERSION_NAME)
    }

    private fun certs(info: android.content.pm.PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory.takeLast(1).toTypedArray()
        } else @Suppress("DEPRECATION") (info.signatures ?: emptyArray())
        return sigs.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun archiveInfo(path: String) =
        if (Build.VERSION.SDK_INT >= 28) app.packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        else @Suppress("DEPRECATION") app.packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)

    private fun installedInfo() =
        if (Build.VERSION.SDK_INT >= 28) app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        else @Suppress("DEPRECATION") app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES)

    @Suppress("DEPRECATION")
    private fun code(i: android.content.pm.PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else i.versionCode.toLong()

    /** Streams to a temp file with progress, then verifies. Blocking. Returns the verified APK. */
    fun download(r: ApkRelease, onProgress: (Float) -> Unit = {}): File {
        require(r.url.startsWith("https://")) { "https only" }
        dir.deleteRecursively(); dir.mkdirs()
        val part = File(dir, "${r.name}.part")
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val c = URL(r.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "TgAuto/${BuildConfig.VERSION_NAME}")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            if (!c.url.protocol.equals("https", true)) throw IOException("redirect to non-https")
            val len = c.contentLengthLong
            c.inputStream.use { input -> part.outputStream().use { out ->
                val buf = ByteArray(32768)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); total += n
                    if (total > ApkCore.MAX_APK) throw IOException("too large")
                    val denom = if (r.size > 0) r.size else len
                    if (denom > 0) { progress = total.toFloat() / denom; onProgress(progress) }
                }
            } }
        } catch (e: Throwable) { part.delete(); progress = -1f; throw e } finally { c.disconnect() }
        try {
            if (r.size > 0 && total != r.size) throw OtaException("apk_size", "$total != ${r.size}")
            val got = md.digest().joinToString("") { "%02x".format(it) }
            val expected = r.sha256 ?: r.checksumUrl?.let { u ->
                runCatching { ApkCore.parseChecksum(String(OtaManager.http(u, 1024 * 1024)), r.name) }.getOrNull()
            }
            if (expected != null && expected != got) throw OtaException("apk_hash")
            val info = archiveInfo(part.absolutePath) ?: throw OtaException("apk_unreadable")
            val mine = installedInfo()
            ApkCore.checkIdentity(app.packageName, info.packageName, code(mine), code(info), certs(mine), certs(info))
            OtaManager.log("APK ${r.version}: size ok, ${if (expected != null) "sha256 ok" else "no checksum published"}, signature matches")
            val fin = File(dir, r.name)
            if (!part.renameTo(fin)) throw IOException("rename")
            ready = fin; progress = 1f
            return fin
        } catch (e: Throwable) { part.delete(); File(dir, r.name).delete(); ready = null; progress = -1f; throw e }
    }

    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    /** Settings screen for "install unknown apps" for this app. */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** ACTION_VIEW intent for the system installer (tap target of the "downloaded" notification). */
    fun viewIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.apkfiles", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Installs through PackageInstaller. [silent] requests USER_ACTION_NOT_REQUIRED (Android 12+; the OS honours it only
     * when this app is the installer of record and the update meets its conditions, otherwise it asks as usual).
     * Falls back to ACTION_VIEW + FileProvider if the session cannot be created.
     */
    fun install(apk: File, silent: Boolean) {
        try {
            val pi = app.packageManager.packageInstaller
            val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            p.setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= 31 && silent) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = pi.createSession(p)
            pi.openSession(id).use { s ->
                apk.inputStream().use { i -> s.openWrite("base.apk", 0, apk.length()).use { o -> i.copyTo(o); s.fsync(o) } }
                val cb = PendingIntent.getBroadcast(app, id, Intent(ACTION).setPackage(app.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
                s.commit(cb.intentSender)
            }
            OtaManager.log("APK install session committed (${if (silent) "no-prompt requested" else "user confirmation"})")
        } catch (e: Throwable) {
            OtaManager.log("PackageInstaller failed (${e.message}), falling back to system installer")
            val view = viewIntent(apk)
            try { app.startActivity(view) } catch (e2: Throwable) {
                // background activity start is blocked on Android 10+: leave a tappable notification instead
                OtaManager.notify(app.getString(R.string.apk_notif_ready, "") , 7002, view)
            }
        }
    }

    /** Receives the PackageInstaller result: shows the system confirmation when the OS asks for it. */
    class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    OtaManager.log("APK install needs your confirmation")
                    // Starting from the background may be blocked by Android 10+, so also leave a notification.
                    OtaManager.notify(context.getString(R.string.apk_notif_confirm), 7002, confirm)
                    runCatching { context.startActivity(confirm) }
                }
                PackageInstaller.STATUS_SUCCESS -> OtaManager.log("APK installed")
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                    OtaManager.log("APK install failed: status $st $msg")
                    OtaManager.notify(context.getString(R.string.apk_notif_failed, msg), 7002)
                }
            }
        }
    }

    /** Launch-time check, at most every 6 h. */
    fun autoCheck() {
        val mode = AppSettings.apkMode.value
        if (mode == "off") return
        val now = System.currentTimeMillis()
        if (now - (AppSettings.apkLastCheck.value.toLongOrNull() ?: 0) < 6 * 3600_000L) return
        AppSettings.apkLastCheck.set(now.toString())
        val r = try { check() } catch (e: Throwable) { OtaManager.log("APK check failed: ${e.message}"); return }
        if (r == null) { OtaManager.log("APK: up to date (${BuildConfig.VERSION_NAME})"); return }
        if (!ApkCore.downloads(mode)) {
            if (AppSettings.apkNotified.value != r.version) {
                AppSettings.apkNotified.set(r.version)
                OtaManager.log("APK ${r.version} available")
                OtaManager.notify(app.getString(R.string.apk_notif_available, r.version), 7002)
            }
            return
        }
        try {
            val f = download(r)
            if (mode == "auto" && canInstall()) { install(f, silent = true) }
            else {
                OtaManager.log("APK ${r.version} downloaded; open Settings > Updates to install")
                // tap installs: system installer for the file, or the "unknown sources" screen when not allowed yet
                OtaManager.notify(app.getString(R.string.apk_notif_ready, r.version), 7002,
                    if (canInstall()) viewIntent(f) else unknownSourcesIntent())
            }
        } catch (e: Throwable) {
            OtaManager.log("APK ${r.version} rejected: ${OtaManager.errorText(e)}")
            OtaManager.notify(app.getString(R.string.apk_notif_rejected, r.version), 7002)
        }
    }
}
