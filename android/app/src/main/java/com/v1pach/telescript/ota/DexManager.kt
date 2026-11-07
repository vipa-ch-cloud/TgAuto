package com.v1pach.telescript.ota

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import com.v1pach.telescript.AppSettings
import com.v1pach.telescript.BuildConfig
import com.v1pach.telescript.R
import com.v1pach.telescript.plugin.PluginHost
import com.v1pach.telescript.plugin.PluginScreen
import com.v1pach.telescript.plugin.TgadexPlugin
import dalvik.system.DexClassLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * .tgadex plugins: filesDir/dex/<version>/ holds the verified package (package.tgadex) and meta.json.
 * Pointers "current"/"previous" are swapped atomically. Every start re-verifies the stored package
 * (signature + hashes) before its dex is written read-only (Android 14+) and loaded; there is no
 * optimized directory. A boot guard rolls back to the previous plugin (or none) after two starts
 * that never reached the "confirmed" point. Independent from the .tgadyn overlay machinery.
 */
object DexManager {
    private lateinit var app: Context
    private val root get() = File(app.filesDir, "dex")
    private val bootFile get() = File(root, "boot.json")
    private const val MAX_FAILS = 2
    private const val CONFIRM_DELAY_MS = 15_000L

    @Volatile var rolledBack: String = ""; private set
    @Volatile var loadError: String = ""; private set
    @Volatile var plugin: TgadexPlugin? = null; private set
    @Volatile var loadedManifest: JSONObject? = null; private set
    @Volatile var loadedOfficial: Boolean = true; private set

    fun init(context: Context) {
        app = context.applicationContext
        root.mkdirs()
        runCatching { bootGuard() }.onFailure { rolledBack = "guard: ${it.message}"; runCatching { dropAll() } }
    }

    // ---- pointers / boot guard (same scheme as OtaManager) ----
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
            rolledBack = cur
            writePtr("current", previous())
            writePtr("previous", null)
            File(root, cur).deleteRecursively()
            fails = 0
            bootFile.delete()
            OtaManager.log("Plugin $cur failed to start twice; rolled back to ${current() ?: "built-in (no plugin)"}")
        }
        val now = current() ?: return
        bootFile.writeText(JSONObject().put("pending", true).put("fails", fails).put("version", now).toString())
    }

    fun confirmBoot() {
        runCatching { bootFile.writeText(JSONObject().put("pending", false).put("fails", 0).put("version", current() ?: "").toString()) }
    }

    // ---- verification ----
    fun verifyFile(file: File): DexPackage = DexCore.verify(file, OtaKeys.publicKey, BuildConfig.VERSION_NAME, BuildConfig.FLAVOR,
        OtaManager.trustedKeys().map { it.raw })

    /** Automatic path: official key only. */
    fun verifyOfficial(file: File): DexPackage = DexCore.verify(file, OtaKeys.publicKey, BuildConfig.VERSION_NAME, BuildConfig.FLAVOR)

    private fun dirName(v: String) = v.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Copies the verified package into dex/<version>/ and switches the pointer. Takes effect at next start. */
    fun install(pkg: DexPackage, source: File): String {
        val name = dirName(pkg.id + "-" + pkg.version)
        val tmp = File(root, "$name.tmp")
        tmp.deleteRecursively(); tmp.mkdirs()
        source.copyTo(File(tmp, "package.tgadex"), overwrite = true)
        File(tmp, "meta.json").writeText(JSONObject().put("official", pkg.official)
            .put("signer", pkg.communityKey?.joinToString("") { "%02x".format(it) } ?: "").toString())
        val dst = File(root, name)
        val cur = current()
        dst.deleteRecursively()
        check(tmp.renameTo(dst)) { "rename" }
        if (cur != null && cur != name) writePtr("previous", cur)
        writePtr("current", name)
        bootFile.delete()
        val keep = setOfNotNull(current(), previous())
        root.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
        OtaManager.log("Plugin ${pkg.id} ${pkg.version} installed (${if (pkg.official) "official" else "COMMUNITY"} key); applies at next start")
        return name
    }

    fun rollback() {
        val cur = current()
        writePtr("current", previous())
        writePtr("previous", null)
        cur?.let { File(root, it).deleteRecursively() }
        bootFile.delete()
    }

    fun reset() = dropAll()

    private fun dropAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
        root.mkdirs()
    }

    /** Manifest of an installed plugin for display only (trust is decided by [load]). */
    fun manifestOf(dir: String?): JSONObject? = dir?.let {
        runCatching {
            java.util.zip.ZipFile(File(root, "$it/package.tgadex")).use { z ->
                JSONObject(z.getInputStream(z.getEntry(DexCore.MANIFEST)).readNBytes(DexCore.MAX_MANIFEST).toString(Charsets.UTF_8))
            }
        }.getOrNull()
    }

    fun isOfficial(dir: String?): Boolean? = dir?.let {
        runCatching { JSONObject(File(root, "$it/meta.json").readText()).optBoolean("official", false) }.getOrNull()
    }

    fun installedVersion(): String? = manifestOf(current())?.optString("version")

    // ---- loading ----
    /**
     * Re-verifies the stored package and loads the plugin. Never throws; errors land in [loadError]
     * (the boot guard then counts the start as failed unless the app stays alive until confirmBoot).
     */
    fun load() {
        val name = current() ?: return
        try {
            val official = isOfficial(name) ?: false
            // A community package only loads while its key is still in the user's trusted list.
            val pkg = DexCore.verify(File(root, "$name/package.tgadex"), OtaKeys.publicKey, BuildConfig.VERSION_NAME, BuildConfig.FLAVOR,
                if (official) emptyList() else OtaManager.trustedKeys().map { it.raw })
            val dir = File(app.codeCacheDir, "tgadex")
            dir.deleteRecursively(); dir.mkdirs()
            val paths = pkg.dex.entries.sortedBy { it.key }.map { (p, bytes) ->
                File(dir, p).also { f -> f.writeBytes(bytes); f.setReadOnly() }.absolutePath   // Android 14+: dex must be read-only
            }
            val loader = DexClassLoader(paths.joinToString(File.pathSeparator), null, null, DexManager::class.java.classLoader)
            val cls = loader.loadClass(pkg.entry)
            require(TgadexPlugin::class.java.isAssignableFrom(cls)) { "entry does not implement TgadexPlugin" }
            val p = cls.getDeclaredConstructor().newInstance() as TgadexPlugin
            p.onLoad(Host(pkg.id))
            plugin = p; loadedManifest = pkg.manifest; loadedOfficial = pkg.official
            OtaManager.log("Plugin ${pkg.id} ${pkg.version} loaded")
            Thread({ Thread.sleep(CONFIRM_DELAY_MS); confirmBoot() }, "dex-confirm").apply { isDaemon = true }.start()
        } catch (e: Throwable) {
            loadError = (e.message ?: e.javaClass.simpleName)
            OtaManager.log("Plugin load failed: ${errorText(e)}")
        }
    }

    private fun errorText(e: Throwable) = if (e is OtaException) OtaManager.errorText(e) else (e.message ?: e.javaClass.simpleName)

    private class Host(private val id: String) : PluginHost {
        override fun apiVersion() = DexCore.API_VERSION
        override fun appVersion(): String = BuildConfig.VERSION_NAME
        override fun context(): Context = app
        override fun log(message: String) = OtaManager.log("[$id] ${message.take(200)}")
        override fun prefs(): SharedPreferences = app.getSharedPreferences("tgadex_$id", Context.MODE_PRIVATE)
    }

    fun screens(): List<PluginScreen> = runCatching { plugin?.screens().orEmpty() }.getOrDefault(emptyList())

    fun createView(context: Context, screenId: String): View? = try { plugin?.createView(context, screenId) }
        catch (e: Throwable) { OtaManager.log("Plugin screen failed: ${e.message}"); null }

    // ---- GitHub ----
    /** Downloads each .tgadex asset of the latest release (max 3) and returns those newer than the installed plugin. Blocking. */
    fun checkGithub(): Pair<File, DexPackage>? {
        val rel = JSONObject(String(OtaManager.http("https://api.github.com/repos/${OtaManager.REPO}/releases/latest", 2L * 1024 * 1024)))
        val assets = rel.optJSONArray("assets") ?: return null
        val installed = installedVersion()
        var tried = 0
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (!a.optString("name").endsWith(".tgadex")) continue
            if (++tried > 3) break
            val url = a.optString("browser_download_url")
            if (!url.startsWith("https://")) continue
            val f = File(app.cacheDir, "dex-download.tgadex")
            f.writeBytes(OtaManager.http(url, DexCore.MAX_PACKAGE))
            val pkg = try { verifyFile(f) } catch (e: OtaException) { if (e.code in setOf("wrong_channel", "wrong_base", "wrong_api")) continue else throw e }
            if (installed == null || OtaCore.vcmp(pkg.version, installed) > 0) return f to pkg
        }
        return null
    }

    /** Launch-time check (at most every 6 h). Auto-applies only official-signed packages, only when the setting is on. */
    fun autoCheck() {
        if (!AppSettings.otaAutoCheck.value) return
        val now = System.currentTimeMillis()
        if (now - (AppSettings.dexLastCheck.value.toLongOrNull() ?: 0) < 6 * 3600_000L) return
        AppSettings.dexLastCheck.set(now.toString())
        val r = try { checkGithub() } catch (e: Throwable) { OtaManager.log("Plugin check failed: ${errorText(e)}"); return }
        if (r == null) return
        val (f, pkg) = r
        if (AppSettings.dexAutoApply.value && pkg.official) {
            try {
                install(pkg, f)
                OtaManager.notify(app.getString(R.string.dex_notif_applied, pkg.version), 7002)
            } catch (e: Throwable) { OtaManager.log("Plugin ${pkg.version} rejected: ${errorText(e)}") }
        } else if (AppSettings.dexNotified.value != pkg.version) {
            AppSettings.dexNotified.set(pkg.version)
            OtaManager.log("Plugin ${pkg.version} available (${if (pkg.official) "official" else "community, manual only"})")
            OtaManager.notify(app.getString(R.string.dex_notif_available, pkg.version), 7002)
        }
    }
}
