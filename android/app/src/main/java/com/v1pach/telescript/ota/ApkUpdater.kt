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

    @Volatile private var lastInteractive = 0L
    class Pending(val version: String, val file: File)

    /** Appends to the "last attempt" log shown on the install screen (and to the general update log). */
    private fun alog(msg: String) {
        OtaManager.log(msg)
        val t = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val lines = (AppSettings.apkAttemptLog.value.lines().filter { it.isNotBlank() } + "$t $msg").takeLast(25)
        AppSettings.apkAttemptLog.set(lines.joinToString("\n"))
    }

    fun attemptLog(): String = AppSettings.apkAttemptLog.value

    /** In-app install screen: tap target of every APK notification (starting an Activity from a tap is always allowed). */
    fun screenIntent(): Intent = Intent(app, ApkInstallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** The verified APK waiting for install, or null. Re-checked on every call: a stale or already installed file is dropped. */
    fun pending(): Pending? {
        val raw = AppSettings.apkPending.value
        if (raw.isBlank()) return null
        val f = File(dir, raw.substringAfter('\t'))
        val ok = f.isFile && runCatching {
            val info = archiveInfo(f.absolutePath)!!
            val mine = installedInfo()
            ApkCore.checkIdentity(app.packageName, info.packageName, code(mine), code(info), certs(mine), certs(info)); true
        }.getOrDefault(false)
        if (!ok) { AppSettings.apkPending.set(""); return null }
        return Pending(raw.substringBefore('\t'), f)
    }

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
        // the install permission is checked BEFORE spending traffic on a file that could not be installed
        if (!canInstall()) { alog("APK ${r.version}: download skipped, install permission missing"); throw OtaException("apk_perm") }
        alog("APK ${r.version}: download started")
        dir.deleteRecursively(); dir.mkdirs()
        AppSettings.apkPending.set("")
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
            alog("APK ${r.version}: size ok, ${if (expected != null) "sha256 ok" else "no checksum published"}, signature matches")
            val fin = File(dir, r.name)
            if (!part.renameTo(fin)) throw IOException("rename")
            ready = fin; progress = 1f
            AppSettings.apkPending.set("${r.version}\t${r.name}")
            alog("APK ${r.version}: ready to install")
            return fin
        } catch (e: Throwable) {
            part.delete(); File(dir, r.name).delete(); ready = null; progress = -1f
            alog("APK ${r.version}: rejected (${OtaManager.errorText(e)})")
            throw e
        }
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
    fun install(apk: File, silent: Boolean, interactive: Boolean = false) {
        if (interactive) lastInteractive = System.currentTimeMillis()
        if (!canInstall()) { alog("APK install blocked: install permission missing"); notifyAction(app.getString(R.string.apk_notif_perm)); return }
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
            alog("APK install session committed (${if (silent) "no-prompt requested" else "user confirmation"})")
        } catch (e: Throwable) {
            alog("PackageInstaller failed (${e.message}), falling back to system installer")
            // A foreground tap may open the system installer; from the background only a notification is allowed.
            if (interactive) { try { app.startActivity(viewIntent(apk)) } catch (e2: Throwable) { notifyAction(app.getString(R.string.apk_notif_ready, "")) } }
            else notifyAction(app.getString(R.string.apk_notif_ready, ""))
        }
    }

    /** Normal-importance notification whose tap opens the in-app install screen. */
    private fun notifyAction(text: String) = OtaManager.notify(text, 7002, screenIntent(), important = true)

    /** Receives the PackageInstaller result: shows the system confirmation when the OS asks for it. */
    class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    alog("APK install needs your confirmation")
                    // Never startActivity from the background (blocked on Android 10+): a tap on the notification
                    // opens the confirmation. Only when the user just pressed install in the app do we open it directly.
                    if (System.currentTimeMillis() - lastInteractive < 60_000L) runCatching { context.startActivity(confirm) }
                    else OtaManager.notify(context.getString(R.string.apk_notif_confirm), 7002,
                        Intent(confirm), important = true)
                }
                PackageInstaller.STATUS_SUCCESS -> { alog("APK installed"); AppSettings.apkPending.set("") }
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                    alog("APK install failed: status $st $msg")
                    OtaManager.notify(context.getString(R.string.apk_notif_failed, msg), 7002, screenIntent(), important = true)
                }
            }
        }
    }

    /** Launch-time check, at most every 6 h. */
    fun autoCheck() {
        val mode = AppSettings.apkMode.value
        if (mode == "off") return
        if (RemoteFlags.apkPaused()) { alog("APK: paused by a remote flag"); return }
        val now = System.currentTimeMillis()
        if (now - (AppSettings.apkLastCheck.value.toLongOrNull() ?: 0) < 6 * 3600_000L) return
        AppSettings.apkLastCheck.set(now.toString())
        val r = try { check() } catch (e: Throwable) { alog("APK check failed: ${e.message}"); return }
        if (r == null) { alog("APK: up to date (${BuildConfig.VERSION_NAME})"); return }
        if (r.version in RemoteFlags.cached().revoked("revoked_apk")) { alog("APK ${r.version}: revoked remotely, skipped"); return }
        if (!ApkCore.downloads(mode)) {
            if (AppSettings.apkNotified.value != r.version) {
                AppSettings.apkNotified.set(r.version)
                alog("APK ${r.version} available")
                OtaManager.notify(app.getString(R.string.apk_notif_available, r.version), 7002)
            }
            return
        }
        try {
            val f = download(r)
            if (mode == "auto") install(f, silent = true)
            else {
                alog("APK ${r.version} downloaded; waiting for the install tap")
                notifyAction(app.getString(R.string.apk_notif_ready, r.version))
            }
        } catch (e: Throwable) {
            alog("APK ${r.version} not installed: ${OtaManager.errorText(e)}")
            OtaManager.notify(
                if ((e as? OtaException)?.code == "apk_perm") app.getString(R.string.apk_notif_perm) else app.getString(R.string.apk_notif_rejected, r.version),
                7002, screenIntent(), important = true)
        }
    }
}
