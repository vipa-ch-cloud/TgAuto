package com.v1pach.telescript.ota

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.v1pach.telescript.AppSettings
import com.v1pach.telescript.BuildConfig
import com.v1pach.telescript.LogBus
import com.v1pach.telescript.MainActivity
import com.v1pach.telescript.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * OTA overlays in filesDir/ota/<version>/. Pointer files "current" and "previous" are replaced
 * atomically (tmp + rename). A boot guard rolls back to the previous overlay (or the built-in
 * code) after two starts that never reached the "confirmed" point.
 */
object OtaManager {
    private lateinit var app: Context
    private val root get() = File(app.filesDir, "ota")
    private val bootFile get() = File(root, "boot.json")
    const val REPO = "vipa-ch-cloud/TgAuto"
    private const val MAX_FAILS = 2

    @Volatile private var strings: Map<String, Map<String, String>> = emptyMap()
    /** Set when the boot guard rolled back during this start (shown once in Settings). */
    @Volatile var rolledBack: String = ""; private set

    fun init(context: Context) {
        app = context.applicationContext
        root.mkdirs()
        runCatching { bootGuard() }.onFailure { rolledBack = "guard: ${it.message}"; runCatching { dropAll() } }
        loadStrings()
    }

    private fun readPtr(name: String): String? =
        runCatching { File(root, name).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() && File(root, it).isDirectory }

    private fun writePtr(name: String, value: String?) {
        val f = File(root, name)
        if (value == null) { f.delete(); return }
        val tmp = File(root, "$name.tmp")
        tmp.writeText(value)
        if (!tmp.renameTo(f)) { f.delete(); check(tmp.renameTo(f)) { "pointer" } }
    }

    fun current(): String? = readPtr("current")
    fun previous(): String? = readPtr("previous")

    private fun bootGuard() {
        val cur = current() ?: run { bootFile.delete(); return }
        val st = runCatching { JSONObject(bootFile.readText()) }.getOrDefault(JSONObject())
        var fails = if (st.optBoolean("pending") && st.optString("version") == cur) st.optInt("fails") + 1 else 0
        if (fails >= MAX_FAILS) {
            val bad = cur
            rolledBack = bad
            writePtr("current", previous())
            writePtr("previous", null)
            File(root, bad).deleteRecursively()
            fails = 0
            bootFile.delete()
            if (current() == null) return
        }
        val now = current() ?: return
        bootFile.writeText(JSONObject().put("pending", true).put("fails", fails).put("version", now).toString())
    }

    /** Called once the Python layer imported and initialised successfully. */
    fun confirmBoot() {
        runCatching { bootFile.writeText(JSONObject().put("pending", false).put("fails", 0).put("version", current() ?: "").toString()) }
    }

    /** Adds the overlay's python/ dir to the front of sys.path. Call after Python.start, before ts_host import. */
    fun applyPythonPath() {
        val v = current() ?: return
        val dir = File(root, "$v/python")
        if (!dir.isDirectory) return
        Python.getInstance().getModule("sys").get("path")?.callAttr("insert", 0, dir.absolutePath)
    }

    fun schemaFor(id: String): String? {
        val v = current() ?: return null
        val f = File(root, "$v/schemas/$id.config.schema.json")
        return if (f.isFile) f.readText() else null
    }

    // ---- strings overlay ----
    private fun loadStrings() {
        val v = current()
        strings = if (v == null) emptyMap() else runCatching {
            val o = JSONObject(File(root, "$v/res/strings.json").readText())
            o.keys().asSequence().associateWith { lang ->
                val m = o.getJSONObject(lang); m.keys().asSequence().associateWith { m.getString(it) }
            }
        }.getOrDefault(emptyMap())
    }

    fun overlayString(name: String): String? {
        if (strings.isEmpty()) return null
        val lang = app.resources.configuration.locales[0].language
        return strings[lang]?.get(name) ?: strings["en"]?.get(name)
    }

    /** Resources whose string getters consult the OTA overlay first (used by Compose's stringResource). */
    @Suppress("DEPRECATION")
    fun wrapResources(base: Resources): Resources {
        if (strings.isEmpty()) return base
        return object : Resources(base.assets, base.displayMetrics, base.configuration) {
            private fun ov(id: Int): String? = runCatching { overlayString(base.getResourceEntryName(id)) }.getOrNull()
            override fun getString(id: Int): String = ov(id) ?: base.getString(id)
            override fun getString(id: Int, vararg args: Any?): String = ov(id)?.let { String.format(it, *args) } ?: base.getString(id, *args)
            override fun getText(id: Int): CharSequence = ov(id) ?: base.getText(id)
        }
    }

    // ---- install / reset ----
    fun trustedKeys(): List<TrustedKey> = TrustedKeys.parse(AppSettings.otaTrustedKeys.value)

    /** Manual install path: official key first, then the user's community keys (the UI then shows a warning). */
    fun verifyFile(file: File): OtaPackage = OtaCore.verify(file, OtaKeys.publicKey, BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE, BuildConfig.FLAVOR, OtaCore.OTA_FORMAT, trustedKeys().map { it.raw })

    /** Automatic path: official key only. */
    fun verifyOfficial(file: File): OtaPackage = OtaCore.verify(file, OtaKeys.publicKey, BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE, BuildConfig.FLAVOR, OtaCore.OTA_FORMAT)

    fun addTrustedKey(name: String, keyText: String) {
        AppSettings.otaTrustedKeys.set(TrustedKeys.serialize(TrustedKeys.add(trustedKeys(), name, keyText, OtaKeys.publicKey)))
    }

    fun removeTrustedKey(keyHex: String) {
        AppSettings.otaTrustedKeys.set(TrustedKeys.serialize(TrustedKeys.remove(trustedKeys(), keyHex)))
    }

    /** Author name for a community-signed package (the label the user gave the key). */
    fun communityAuthor(pkg: OtaPackage): String? = pkg.communityKey?.let { k -> trustedKeys().firstOrNull { it.raw.contentEquals(k) }?.name }

    fun log(msg: String) {
        val t = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
        val lines = (AppSettings.otaLog.value.lines().filter { it.isNotBlank() } + "$t $msg").takeLast(30)
        AppSettings.otaLog.set(lines.joinToString("\n"))
    }

    private fun dirName(version: String) = version.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Writes the verified package into ota/<version>/ and switches the pointer. Takes effect on next start. */
    fun install(pkg: OtaPackage): String {
        val name = dirName(pkg.version)
        val tmp = File(root, "$name.tmp")
        tmp.deleteRecursively()
        for ((path, data) in pkg.files) {
            val f = File(tmp, path)
            check(f.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) { "path" }
            f.parentFile?.mkdirs()
            f.writeBytes(data)
        }
        File(tmp, OtaCore.MANIFEST).writeText(pkg.manifest.toString(2))
        val dst = File(root, name)
        val cur = current()
        dst.deleteRecursively()
        check(tmp.renameTo(dst)) { "rename" }
        if (cur != null && cur != name) writePtr("previous", cur)
        writePtr("current", name)
        bootFile.delete()
        // keep only current + previous
        val keep = setOfNotNull(current(), previous())
        root.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
        return name
    }

    /** Returns to the built-in code (all overlays removed). Effective on next start. */
    fun reset() = dropAll()

    private fun dropAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
        root.mkdirs()
    }

    /** Switches back to the kept previous overlay (or built-in code). */
    fun rollback() {
        val cur = current()
        writePtr("current", previous())
        writePtr("previous", null)
        cur?.let { File(root, it).deleteRecursively() }
        bootFile.delete()
    }

    fun manifestOf(version: String?): JSONObject? = version?.let {
        runCatching { JSONObject(File(root, "$it/${OtaCore.MANIFEST}").readText()) }.getOrNull()
    }

    // ---- error texts ----
    fun errorText(e: Throwable): String {
        if (e !is OtaException) return app.getString(R.string.ota_err_other, e.message.orEmpty())
        val id = app.resources.getIdentifier("ota_err_${e.code}", "string", app.packageName)
        val base = if (id != 0) app.getString(id) else e.code
        return if (e.detail.isEmpty()) base else "$base (${e.detail})"
    }

    // ---- GitHub releases ----
    data class Remote(val tag: String, val version: String, val url: String, val notes: String)

    fun http(url: String, maxBytes: Long = 20L * 1024 * 1024): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "TgAuto/${BuildConfig.VERSION_NAME}")
        c.setRequestProperty("Accept", "application/vnd.github+json, */*")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16384)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw java.io.IOException("too large")
                }
            }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    /** Latest release carrying a .tgadyn asset newer than what is installed, or null. Blocking. */
    fun checkGithub(): Remote? {
        val arr = JSONArray("[" + String(http("https://api.github.com/repos/$REPO/releases/latest", 2L * 1024 * 1024)) + "]")
        val rel = arr.getJSONObject(0)
        val assets = rel.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val n = a.optString("name")
            if (!n.endsWith(".tgadyn")) continue
            val ver = Regex("(\\d+(?:\\.\\d+)*(?:-[A-Za-z0-9.]+)?)\\.tgadyn$").find(n)?.groupValues?.get(1)
                ?: rel.optString("tag_name").trimStart('v')
            if (OtaCore.vcmp(ver, installedVersion()) <= 0) return null
            return Remote(rel.optString("tag_name"), ver, a.optString("browser_download_url"), rel.optString("body"))
        }
        return null
    }

    /** OTA version if one is active, else the base version name. */
    fun installedVersion(): String = current()?.let { manifestOf(it)?.optString("version") } ?: BuildConfig.VERSION_NAME

    fun download(url: String): File {
        require(url.startsWith("https://")) { "https only" }
        val f = File(app.cacheDir, "ota-download.tgadyn")
        f.writeBytes(http(url, OtaCore.MAX_PACKAGE))
        return f
    }

    /**
     * Launch-time check (at most every 6 h). With auto-apply on (default) the package is downloaded, verified
     * against the OFFICIAL key only (community-signed packages are never applied automatically), installed
     * as an overlay and rolled back by the boot guard if it fails to start twice. Otherwise only notifies.
     */
    fun autoCheck() {
        if (!AppSettings.otaAutoCheck.value) return
        val now = System.currentTimeMillis()
        if (now - (AppSettings.otaLastCheck.value.toLongOrNull() ?: 0) < 6 * 3600_000L) return
        AppSettings.otaLastCheck.set(now.toString())
        val r = try { checkGithub() } catch (e: Throwable) { log("OTA check failed: ${e.message}"); return }
        if (r == null) { log("OTA: up to date (${installedVersion()})"); return }
        if (AppSettings.otaAutoApply.value) {
            try {
                val name = install(verifyOfficial(download(r.url)))
                log("OTA ${r.version} downloaded, verified (official key) and installed -> $name; applies at next start")
                notify(app.getString(R.string.ota_notif_applied, r.version), 7001)
            } catch (e: Throwable) {
                log("OTA ${r.version} rejected: ${errorText(e)}")
                notify(app.getString(R.string.ota_notif_failed, r.version), 7001)
            }
        } else if (AppSettings.otaNotified.value != r.version) {
            AppSettings.otaNotified.set(r.version)
            log("OTA ${r.version} available (not applied automatically)")
            notify(app.getString(R.string.ota_notif_available, r.version), 7001)
        }
    }

    fun notify(text: String, id: Int, intent: Intent? = null) {
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("updates", app.getString(R.string.ota_channel), NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(app, id, intent ?: Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(id, NotificationCompat.Builder(app, "updates").setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(app.getString(R.string.app_name)).setContentText(text).setContentIntent(pi).setAutoCancel(true).build())
    }
}
