package com.v1pach.telescript.ota

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile

/** Verification failure. [code] maps to a localized message (see OtaManager.errorText). */
class OtaException(val code: String, val detail: String = "") : Exception(if (detail.isEmpty()) code else "$code: $detail")

/** A verified package. [signed] is false only for .tgascript files without a signature. */
class OtaPackage(val manifest: JSONObject, val files: Map<String, ByteArray>, val signed: Boolean) {
    val format: String get() = manifest.getString("format")
    val isScript: Boolean get() = format == OtaCore.SCRIPT_FORMAT
    val version: String get() = manifest.getString("version")
}

/**
 * Format rules for .tgadyn (OTA) and .tgascript (script package). Pure JDK + org.json, no Android
 * classes, so it can be tested on a plain JVM. Keep in sync with tools/ota_common.py.
 */
object OtaCore {
    const val OTA_FORMAT = "tgadyn/1"
    const val SCRIPT_FORMAT = "tgascript/1"
    const val INDEX_FORMAT = "tgastore/1"
    private val MAGIC_OTA = "TGADYN1\n".toByteArray()
    private val MAGIC_SCRIPT = "TGASCRIPT1\n".toByteArray()
    private val MAGIC_INDEX = "TGASTORE1\n".toByteArray()
    const val MANIFEST = "manifest.json"
    const val SIGNATURE = "signature.ed25519"
    const val RES_STRINGS = "res/strings.json"
    const val MAX_ENTRIES = 500
    const val MAX_FILE = 8L * 1024 * 1024
    const val MAX_TOTAL = 32L * 1024 * 1024
    const val MAX_PACKAGE = 16L * 1024 * 1024
    const val MAX_MANIFEST = 256 * 1024
    private const val MAX_RATIO = 200
    private val CHANNELS = setOf("stable", "nightly", "any")
    private val OTA_PREFIXES = mapOf(
        "python/" to setOf("py", "json", "txt"), "res/" to setOf("json"),
        "schemas/" to setOf("json"), "docs/" to setOf("md", "txt"),
    )
    private val SCRIPT_EXTS = setOf("py", "json", "md", "txt", "yaml", "yml", "toml", "csv", "ini", "cfg")

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Numeric dotted version compare ("1.10.0" > "1.9.9"; a "-suffix" is ignored). */
    fun vcmp(a: String, b: String): Int {
        fun t(v: String) = Regex("\\d+").findAll(v.substringBefore('-')).map { it.value.toLong() }.toList()
        val x = t(a); val y = t(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun safePath(path: String?, format: String): Boolean {
        if (path.isNullOrEmpty() || path.length > 200) return false
        if (path.startsWith("/") || '\\' in path || ':' in path || '\u0000' in path) return false
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }) return false
        val name = parts.last()
        val ext = if ('.' in name) name.substringAfterLast('.').lowercase() else ""
        if (format == SCRIPT_FORMAT) return path != MANIFEST && path != SIGNATURE && ext in SCRIPT_EXTS
        val prefix = OTA_PREFIXES.keys.firstOrNull { path.startsWith(it) } ?: return false
        return ext in OTA_PREFIXES.getValue(prefix) && (prefix != "res/" || path == RES_STRINGS)
    }

    /** Reads just the "format" field of the manifest to route a file (not trusted until [verify]). */
    fun peekFormat(file: File): String? = try {
        ZipFile(file).use { z ->
            val e = z.getEntry(MANIFEST) ?: return null
            if (e.size > MAX_MANIFEST) return null
            JSONObject(z.getInputStream(e).readNBytes(MAX_MANIFEST).toString(Charsets.UTF_8)).optString("format").ifEmpty { null }
        }
    } catch (e: Exception) { null }

    private fun range(rb: JSONObject, key: String): JSONArray? {
        if (!rb.has(key) || rb.isNull(key)) return null
        val a = rb.optJSONArray(key)
        if (a == null || a.length() != 2) throw OtaException("bad_manifest", "requires_base.$key")
        return a
    }

    fun checkBase(m: JSONObject, baseName: String, baseCode: Int, flavor: String) {
        val ch = m.optString("channel", "any")
        if (ch !in CHANNELS) throw OtaException("bad_manifest", "channel")
        if (ch != "any" && ch != flavor) throw OtaException("wrong_channel", ch)
        val rb = m.optJSONObject("requires_base") ?: return
        range(rb, "version_name")?.let { r ->
            val lo = r.optString(0, ""); val hi = r.optString(1, "")
            if ((!r.isNull(0) && lo.isNotEmpty() && vcmp(baseName, lo) < 0) ||
                (!r.isNull(1) && hi.isNotEmpty() && vcmp(baseName, hi) > 0)) throw OtaException("wrong_base", "$lo..$hi")
        }
        range(rb, "version_code")?.let { r ->
            if ((!r.isNull(0) && baseCode < r.getInt(0)) || (!r.isNull(1) && baseCode > r.getInt(1)))
                throw OtaException("wrong_base", "code ${r.opt(0)}..${r.opt(1)}")
        }
    }

    fun validateManifest(m: JSONObject) {
        val fmt = m.optString("format")
        if (fmt != OTA_FORMAT && fmt != SCRIPT_FORMAT) throw OtaException("bad_format")
        if (m.opt("name") !is String || m.opt("version") !is String || m.optJSONArray("files") == null || m.opt("build") !is Number)
            throw OtaException("bad_manifest", "fields")
        if (!Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,39}").matches(m.getString("version"))) throw OtaException("bad_manifest", "version")
        if (m.getString("name").length !in 1..80) throw OtaException("bad_manifest", "name")
        val files = m.getJSONArray("files")
        if (files.length() > MAX_ENTRIES) throw OtaException("too_many")
        val seen = HashSet<String>()
        var total = 0L
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: throw OtaException("bad_manifest", "files")
            val p = f.optString("path", "")
            if (!safePath(p, fmt)) throw OtaException("bad_path", p.take(80))
            if (!seen.add(p)) throw OtaException("bad_manifest", "duplicate $p")
            if (!Regex("[0-9a-f]{64}").matches(f.optString("sha256", ""))) throw OtaException("bad_manifest", "sha256 $p")
            val size = f.optLong("size", -1)
            if (size < 0 || size > MAX_FILE) throw OtaException("too_big", p)
            total += size
        }
        if (total > MAX_TOTAL) throw OtaException("too_big", "total")
        if (fmt == SCRIPT_FORMAT) {
            if (!Regex("[a-z0-9_]{1,40}").matches(m.optString("id", ""))) throw OtaException("bad_manifest", "id")
            if (m.optString("entry") !in seen) throw OtaException("bad_manifest", "entry")
        }
    }

    private fun b64(s: ByteArray): ByteArray? = try { Base64.getDecoder().decode(String(s).trim()) } catch (e: Exception) { null }

    /** True when the catalog index has a valid owner signature. */
    fun verifyIndex(index: ByteArray, sigB64: ByteArray, publicKey: ByteArray): Boolean {
        val sig = b64(sigB64) ?: return false
        return Ed25519.verify(publicKey, MAGIC_INDEX + index, sig)
    }

    /**
     * Full check of a package file: size caps, zip-bomb guard, signature (mandatory for .tgadyn,
     * optional but then valid for .tgascript), manifest, compatibility, paths and sha256 of every file.
     * A null [publicKey] (script packages from a keyless community source) means a signature cannot be
     * checked, so it is ignored and the package counts as unsigned.
     */
    fun verify(file: File, publicKey: ByteArray?, baseName: String, baseCode: Int, flavor: String, expect: String? = null): OtaPackage {
        if (file.length() > MAX_PACKAGE) throw OtaException("too_big", "package")
        val zf = try { ZipFile(file) } catch (e: Exception) { throw OtaException("not_zip", e.message.orEmpty()) }
        zf.use { z ->
            val entries = z.entries().toList()
            if (entries.size > MAX_ENTRIES + 2) throw OtaException("too_many")
            val names = entries.map { it.name }
            if (names.toSet().size != names.size) throw OtaException("bad_manifest", "duplicate zip entry")
            var total = 0L
            for (e in entries) {
                if (e.isDirectory) continue
                val ratio = if (e.compressedSize > 0) e.size / e.compressedSize else 0
                if (e.size > MAX_FILE || (ratio > MAX_RATIO && e.size > 64 * 1024)) throw OtaException("zip_bomb", e.name)
                total += e.size
            }
            if (total > MAX_TOTAL) throw OtaException("zip_bomb", "total")
            val me = z.getEntry(MANIFEST) ?: throw OtaException("bad_manifest", "manifest")
            if (me.size > MAX_MANIFEST) throw OtaException("too_big", "manifest")
            val mb = readBounded(z, me.name, me.size)
            val manifest = try { JSONObject(mb.toString(Charsets.UTF_8)) } catch (e: Exception) { throw OtaException("bad_manifest", "json") }
            val fmt = manifest.optString("format")
            if ((fmt != OTA_FORMAT && fmt != SCRIPT_FORMAT) || (expect != null && fmt != expect)) throw OtaException("bad_format")
            val signed = SIGNATURE in names && publicKey != null
            if (!signed && fmt == OTA_FORMAT) throw OtaException("unsigned")
            if (signed) {
                val sigEntry = z.getEntry(SIGNATURE)!!
                if (sigEntry.size > 512) throw OtaException("bad_signature")
                val sig = b64(readBounded(z, SIGNATURE, sigEntry.size))
                val magic = if (fmt == OTA_FORMAT) MAGIC_OTA else MAGIC_SCRIPT
                if (sig == null || !Ed25519.verify(publicKey!!, magic + mb, sig)) throw OtaException("bad_signature")
            }
            validateManifest(manifest)
            checkBase(manifest, baseName, baseCode, flavor)
            val listed = HashMap<String, JSONObject>()
            val arr = manifest.getJSONArray("files")
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { listed[it.getString("path")] = it }
            names.firstOrNull { it !in listed && it != MANIFEST && it != SIGNATURE && !it.endsWith("/") }
                ?.let { throw OtaException("unlisted_file", it.take(80)) }
            val data = LinkedHashMap<String, ByteArray>()
            for ((p, f) in listed) {
                val e = z.getEntry(p) ?: throw OtaException("missing_file", p)
                val blob = readBounded(z, p, e.size)
                if (blob.size.toLong() != f.getLong("size") || sha256(blob) != f.getString("sha256")) throw OtaException("hash_mismatch", p)
                data[p] = blob
            }
            data[RES_STRINGS]?.let { runCatching { JSONObject(it.toString(Charsets.UTF_8)) }.getOrElse { throw OtaException("bad_manifest", RES_STRINGS) } }
            return OtaPackage(manifest, data, signed)
        }
    }

    /** Reads an entry, refusing to inflate beyond the declared size (central-directory sizes can lie). */
    private fun readBounded(z: ZipFile, name: String, declared: Long): ByteArray {
        val limit = declared.coerceAtLeast(0)
        val out = ByteArrayOutputStream()
        z.getInputStream(z.getEntry(name)).use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) throw OtaException("zip_bomb", name)
            }
        }
        return out.toByteArray()
    }
}
