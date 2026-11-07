package com.v1pach.telescript.store

import com.v1pach.telescript.R
import android.content.Context
import com.v1pach.telescript.*
import com.v1pach.telescript.ota.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Metadata of an installed store script (store/<id>/meta.json). */
data class InstalledScript(
    val id: String, val name: String, val version: String, val previous: String?, val author: String,
    val verified: Boolean, val signed: Boolean, val sha256: String, val source: String, val hosts: List<String>,
    /** Catalog source id ("official", "u…", or "file" for a local package). */
    val sourceId: String = StoreMeta.OFFICIAL_ID, val meta: ModuleMeta = ModuleMeta(),
) {
    fun title(ru: Boolean) = meta.title(ru, name)
}

/** A package that passed structural/hash/signature checks and awaits the user's decision. */
class Candidate(
    val pkg: OtaPackage, val sha256: String, val scan: ScanResult,
    /** Catalog entry it came from; null for a local file. */
    val entry: StoreEntry?, val source: String,
) {
    val m: JSONObject get() = pkg.manifest
    val id: String get() = m.getString("id")
    val name: String get() = m.optString("name", id)
    val version: String get() = pkg.version
    val author: String get() = m.optString("author", "")
    val signed: Boolean get() = pkg.signed
    /** Verified only if the signed official catalog says so (the package signature alone is not an endorsement). */
    val verified: Boolean get() = entry?.verified == true
    val sourceId: String get() = entry?.sourceId ?: "file"
    val meta: ModuleMeta get() = ModuleMeta.from(m)
    val needsWarning: Boolean get() = !verified
}

object StoreManager {
    const val REPO = "vipa-ch-cloud/TgAutoS"
    private const val BASE = "https://raw.githubusercontent.com/$REPO/main/"
    private lateinit var app: Context
    private val root get() = File(app.filesDir, "store")

    val OFFICIAL = StoreSource(StoreMeta.OFFICIAL_ID, "TgAutoS", BASE + "index.json", OtaKeys.PUBLIC_KEY_HEX)

    /** Built-in official source first, then the user's community sources. */
    fun sources(): List<StoreSource> = listOf(OFFICIAL) + StoreMeta.parseSources(AppSettings.storeSources.value)
    private fun source(id: String) = sources().firstOrNull { it.id == id }

    /** Adds a community source (the UI has already shown the warning). Throws IllegalArgumentException("url"|"key"). */
    fun addSource(name: String, url: String, key: String) {
        val s = StoreMeta.newSource(name, url, key)
        val cur = sources().drop(1)
        if (cur.any { it.id == s.id }) return
        require(cur.size < StoreMeta.MAX_SOURCES) { "limit" }
        AppSettings.storeSources.set(StoreMeta.serializeSources(cur + s))
    }

    fun removeSource(id: String) {
        AppSettings.storeSources.set(StoreMeta.serializeSources(sources().drop(1).filter { it.id != id }))
        _entries.value = _entries.value.filter { it.sourceId != id }
    }

    private val _entries = MutableStateFlow<List<StoreEntry>>(emptyList())
    val entries: StateFlow<List<StoreEntry>> = _entries
    private val _installed = MutableStateFlow<Map<String, InstalledScript>>(emptyMap())
    val installed: StateFlow<Map<String, InstalledScript>> = _installed
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error

    fun init(context: Context) {
        app = context.applicationContext
        root.mkdirs()
        reloadInstalled()
    }

    fun reloadInstalled() {
        _installed.value = root.listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
            runCatching {
                val j = JSONObject(File(d, "meta.json").readText())
                InstalledScript(d.name, j.getString("name"), j.getString("version"), j.optString("previous").ifEmpty { null },
                    j.optString("author"), j.optBoolean("verified"), j.optBoolean("signed"), j.optString("sha256"),
                    j.optString("source"), j.optJSONArray("hosts")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                    // meta.json written by 1.9.0 has no sourceId: "store" meant the official catalog.
                    j.optString("sourceId").ifEmpty { if (j.optString("source") == "file") "file" else StoreMeta.OFFICIAL_ID },
                    ModuleMeta.from(j))
            }.getOrNull()?.takeIf { File(d, it.version).isDirectory }
        }?.associateBy { it.id } ?: emptyMap()
    }

    // ---- catalog ----
    /** Downloads and verifies every source's index. One failing source does not hide the others. Blocking. */
    fun refresh() {
        _loading.value = true
        val all = ArrayList<StoreEntry>(); val errors = ArrayList<String>()
        try {
            for (s in sources()) {
                try { all += fetchSource(s) } catch (e: Throwable) {
                    errors += (if (s.official) "" else s.name + ": ") + OtaManager.errorText(e)
                }
            }
            _entries.value = all
            _error.value = errors.joinToString("\n")
        } finally {
            _loading.value = false
        }
    }

    /** Index of one source. Signature checked with the source's key; no key means unsigned (catalog trusted for nothing). */
    private fun fetchSource(s: StoreSource): List<StoreEntry> {
        require(s.url.startsWith("https://")) { "https only" }
        val data = OtaManager.http(s.url, 4L * 1024 * 1024)
        if (s.hasKey) {
            val sig = OtaManager.http(s.url + ".sig", 4096)
            if (!OtaCore.verifyIndex(data, sig, OtaCore.hex(s.keyHex!!))) throw OtaException("bad_signature", "index.json")
        }
        val j = JSONObject(String(data, Charsets.UTF_8))
        if (j.optString("format") != OtaCore.INDEX_FORMAT) throw OtaException("bad_format", "index")
        return StoreMeta.parseEntries(j.getJSONArray("scripts"), s)
    }

    fun updates(): List<StoreEntry> = _entries.value.filter { e ->
        _installed.value[e.id]?.let { it.sourceId == e.sourceId && OtaCore.vcmp(e.version, it.version) > 0 } == true
    }

    // ---- preparing a package ----
    /** Local files are checked with the official key (a present signature must match it); a community source with its own key uses that key, a keyless one has no signature check. */
    private fun verifyScript(file: File, key: ByteArray? = OtaKeys.publicKey): OtaPackage = OtaCore.verify(file, key, BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE, BuildConfig.FLAVOR, OtaCore.SCRIPT_FORMAT)

    /** Local .tgascript file. Never trusted as verified. */
    fun fromFile(file: File): Candidate {
        val pkg = verifyScript(file)
        return Candidate(pkg, OtaCore.sha256(file.readBytes()), ScriptScan.scan(pkg.files), null, "file")
    }

    /** Downloads a catalog package and checks it against the sha256 pinned in the signed index. */
    fun fromEntry(e: StoreEntry): Candidate {
        require(e.url.startsWith("https://")) { "https only" }
        val blob = OtaManager.http(e.url, OtaCore.MAX_PACKAGE.toLong())
        if (OtaCore.sha256(blob) != e.sha256) throw OtaException("hash_mismatch", "package")
        val tmp = File(app.cacheDir, "store-dl.tgascript")
        tmp.writeBytes(blob)
        val src = source(e.sourceId) ?: throw OtaException("bad_manifest", "source")
        val key = if (src.official) OtaKeys.publicKey else src.keyHex?.let { OtaCore.hex(it) }
        val pkg = try { verifyScript(tmp, key) } finally { tmp.delete() }
        if (pkg.manifest.getString("id") != e.id || pkg.version != e.version) throw OtaException("bad_manifest", "id/version")
        return Candidate(pkg, e.sha256, ScriptScan.scan(pkg.files), e, "store")
    }

    // ---- install / update / rollback / delete ----
    private fun writeMeta(dir: File, c: Candidate, previous: String?) {
        val j = JSONObject().put("name", c.name).put("sourceId", c.sourceId).put("version", c.version).put("author", c.author)
            .put("verified", c.verified).put("signed", c.signed).put("sha256", c.sha256).put("source", c.source)
            .put("hosts", JSONArray(c.scan.hosts))
        for (k in listOf("title_ru", "title_en", "icon", "color", "repo", "published", "homepage")) c.m.optString(k).takeIf { it.isNotEmpty() }?.let { j.put(k, it) }
        previous?.let { j.put("previous", it) }
        val tmp = File(dir, "meta.json.tmp"); tmp.writeText(j.toString())
        val f = File(dir, "meta.json")
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Writes version files; Paths.config/<id> and Paths.data/<id> are never touched. */
    fun install(c: Candidate) {
        if (ScriptManager.state(c.id).status != RunStatus.IDLE && ScriptManager.state(c.id).status != RunStatus.ERROR)
            throw IllegalStateException(Str.get(R.string.store_stop_first))
        val old = _installed.value[c.id]
        // A script from another source must never silently replace one that came from elsewhere (id squatting).
        if (old != null && c.sourceId != "file" && old.sourceId != c.sourceId)
            throw IllegalStateException(Str.get(R.string.store_other_source, c.id))
        val dir = File(root, c.id); dir.mkdirs()
        val tmp = File(dir, "${c.version}.tmp"); tmp.deleteRecursively()
        for ((path, data) in c.pkg.files) {
            val f = File(tmp, path)
            check(f.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) { "path" }
            f.parentFile?.mkdirs(); f.writeBytes(data)
        }
        File(tmp, OtaCore.MANIFEST).writeText(c.m.toString(2))
        val prev = _installed.value[c.id]?.version?.takeIf { it != c.version }
        val dst = File(dir, c.version); dst.deleteRecursively()
        check(tmp.renameTo(dst)) { "rename" }
        writeMeta(dir, c, prev)
        dir.listFiles()?.filter { it.isDirectory && it.name != c.version && it.name != prev }?.forEach { it.deleteRecursively() }
        reloadInstalled()
        Thread({ ScriptRepository.scan() }, "scan").start()
    }

    fun rollback(id: String) {
        val cur = _installed.value[id] ?: return
        val prev = cur.previous ?: throw IllegalStateException(Str.get(R.string.store_no_prev))
        val dir = File(root, id)
        val m = runCatching { JSONObject(File(dir, "$prev/${OtaCore.MANIFEST}").readText()) }.getOrNull()
            ?: throw IllegalStateException(Str.get(R.string.store_no_prev))
        val j = JSONObject(File(dir, "meta.json").readText()).put("version", prev).put("name", m.optString("name", id))
            .put("author", m.optString("author"))
        for (k in listOf("title_ru", "title_en", "icon", "color", "repo", "published", "homepage")) {
            j.remove(k); m.optString(k).takeIf { it.isNotEmpty() }?.let { j.put(k, it) }
        }
        j.remove("previous")
        File(dir, "meta.json").writeText(j.toString())
        File(dir, cur.version).deleteRecursively()
        reloadInstalled()
        Thread({ ScriptRepository.scan() }, "scan").start()
    }

    /** Removes the installed code. Config and data are kept unless [wipeUserData]. */
    fun delete(id: String, wipeUserData: Boolean) {
        File(root, id).deleteRecursively()
        if (wipeUserData) { File(Paths.config, id).deleteRecursively(); File(Paths.data, id).deleteRecursively() }
        reloadInstalled()
        Thread({ ScriptRepository.scan() }, "scan").start()
    }

    /** ScriptInfo list for ScriptRepository. */
    fun scriptInfos(): List<ScriptInfo> = _installed.value.values.mapNotNull { s ->
        runCatching {
            val dir = File(root, "${s.id}/${s.version}")
            val m = JSONObject(File(dir, OtaCore.MANIFEST).readText())
            val entryRel = m.optString("entry", "${s.id}.py")
            val args = m.optJSONArray("args")?.let { a -> List(a.length()) { a.getString(it) } } ?: listOf("run")
            val configDir = File(Paths.config, s.id)
            val en = Str.isRu().let { ru -> m.optString(if (ru) "description_ru" else "description_en").ifBlank { m.optString("description_en") } }
            ScriptInfo(s.id, s.title(Str.isRu()), en, "store/${s.id}/$entryRel", "", "", true, File(dir, entryRel), args,
                File(configDir, m.optString("config", "${s.id}.json")), configDir, File(Paths.data, s.id),
                schemaDocId = if (File(dir, "config.schema.json").isFile) "store" else "", store = true, storeDir = dir)
        }.getOrNull()
    }

    /** Called after a catalog refresh when auto-update is on: only same-or-narrower network/risk surface. */
    fun autoUpdate(): Int {
        var n = 0
        for (e in updates()) {
            val old = _installed.value[e.id] ?: continue
            // Only the official source updates itself; community sources are manual.
            if (e.sourceId != StoreMeta.OFFICIAL_ID || !e.verified || !old.verified) continue
            val c = runCatching { fromEntry(e) }.getOrNull() ?: continue
            if (!old.hosts.containsAll(c.scan.hosts)) continue // new network access: ask the user
            if (runCatching { install(c) }.isSuccess) n++
        }
        return n
    }

    fun autoCheck() {
        if (!AppSettings.storeAutoUpdate.value) return
        val now = System.currentTimeMillis()
        if (now - (AppSettings.storeLastCheck.value.toLongOrNull() ?: 0) < 6 * 3600_000L) return
        AppSettings.storeLastCheck.set(now.toString())
        refresh()
        val n = autoUpdate()
        if (n > 0) OtaManager.notify(Str.get(R.string.store_notif_updated, n), 7002)
    }
}
