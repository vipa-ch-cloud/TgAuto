package com.v1pach.telescript.ota

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipFile

/** A verified .tgadex. [communityKey] is the raw key of a community signer; null means the official key. */
class DexPackage(val manifest: JSONObject, val dex: Map<String, ByteArray>, val communityKey: ByteArray?) {
    val official: Boolean get() = communityKey == null
    val id: String get() = manifest.getString("id")
    val version: String get() = manifest.getString("version")
    val entry: String get() = manifest.getString("entry")
}

/**
 * Format rules for .tgadex (signed dex plugin). Pure JDK + org.json, testable on a plain JVM.
 * Keep in sync with tools/tgadex_common.py. The signature domain "TGADEX1\n" is distinct from
 * .tgadyn / .tgascript / catalog index, so a signature made for one never validates as another.
 */
object DexCore {
    const val FORMAT = "tgadex/1"
    private val MAGIC = "TGADEX1\n".toByteArray()
    const val MANIFEST = "manifest.json"
    const val SIGNATURE = "signature.ed25519"
    /** Plugin API implemented by this app; keep equal to PluginHost.API_VERSION. */
    const val API_VERSION = 1
    const val MIN_API_VERSION = 1
    const val MAX_DEX_FILES = 8
    const val MAX_FILE = 8L * 1024 * 1024
    const val MAX_TOTAL = 16L * 1024 * 1024
    const val MAX_PACKAGE = 12L * 1024 * 1024
    const val MAX_MANIFEST = 64 * 1024
    private val DEX_RE = Regex("classes[2-9]?\\.dex")
    private val CLASS_RE = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_$]*)+")
    private val CHANNELS = setOf("stable", "nightly", "any")

    fun validateManifest(m: JSONObject) {
        if (m.optString("format") != FORMAT) throw OtaException("bad_format")
        for (k in listOf("name", "version", "id", "minAppVersion", "entry")) if (m.opt(k) !is String) throw OtaException("bad_manifest", k)
        for (k in listOf("apiVersion", "build")) if (m.opt(k) !is Int) throw OtaException("bad_manifest", k)
        if (m.optJSONArray("files") == null) throw OtaException("bad_manifest", "files")
        if (!Regex("[a-z0-9_]{1,40}").matches(m.getString("id"))) throw OtaException("bad_manifest", "id")
        if (!Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,39}").matches(m.getString("version"))) throw OtaException("bad_manifest", "version")
        if (!Regex("\\d+(\\.\\d+){0,3}").matches(m.getString("minAppVersion"))) throw OtaException("bad_manifest", "minAppVersion")
        if (m.getString("name").length !in 1..80) throw OtaException("bad_manifest", "name")
        if (m.getString("entry").length > 200 || !CLASS_RE.matches(m.getString("entry"))) throw OtaException("bad_manifest", "entry")
        if (m.optString("channel", "any") !in CHANNELS) throw OtaException("bad_manifest", "channel")
        val files = m.getJSONArray("files")
        if (files.length() !in 1..MAX_DEX_FILES) throw OtaException("too_many")
        val seen = HashSet<String>()
        var total = 0L
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: throw OtaException("bad_manifest", "files")
            val p = f.optString("path", "")
            if (!DEX_RE.matches(p)) throw OtaException("bad_path", p.take(80))
            if (!seen.add(p)) throw OtaException("bad_manifest", "duplicate $p")
            if (!Regex("[0-9a-f]{64}").matches(f.optString("sha256", ""))) throw OtaException("bad_manifest", "sha256 $p")
            val size = f.optLong("size", -1)
            if (size <= 0 || size > MAX_FILE) throw OtaException("too_big", p)
            total += size
        }
        if ("classes.dex" !in seen) throw OtaException("bad_manifest", "classes.dex")
        if (total > MAX_TOTAL) throw OtaException("too_big", "total")
    }

    fun checkCompat(m: JSONObject, baseName: String, flavor: String) {
        val ch = m.optString("channel", "any")
        if (ch != "any" && ch != flavor) throw OtaException("wrong_channel", ch)
        if (OtaCore.vcmp(baseName, m.getString("minAppVersion")) < 0) throw OtaException("wrong_base", "minAppVersion " + m.getString("minAppVersion"))
        if (m.getInt("apiVersion") !in MIN_API_VERSION..API_VERSION) throw OtaException("wrong_api", m.getInt("apiVersion").toString())
    }

    private fun b64(s: ByteArray): ByteArray? = try { Base64.getDecoder().decode(String(s).trim()) } catch (e: Exception) { null }

    fun verify(file: File, publicKey: ByteArray, baseName: String, flavor: String, extraKeys: List<ByteArray> = emptyList()): DexPackage {
        if (file.length() > MAX_PACKAGE) throw OtaException("too_big", "package")
        val zf = try { ZipFile(file) } catch (e: Exception) { throw OtaException("not_zip", e.message.orEmpty()) }
        zf.use { z ->
            val entries = z.entries().toList()
            if (entries.size > MAX_DEX_FILES + 2) throw OtaException("too_many")
            val names = entries.map { it.name }
            if (names.toSet().size != names.size) throw OtaException("bad_manifest", "duplicate zip entry")
            var total = 0L
            for (e in entries) {
                if (e.isDirectory) continue
                val ratio = if (e.compressedSize > 0) e.size / e.compressedSize else 0
                if (e.size > MAX_FILE || (ratio > 200 && e.size > 512 * 1024)) throw OtaException("zip_bomb", e.name)
                total += e.size
            }
            if (total > MAX_TOTAL) throw OtaException("zip_bomb", "total")
            val me = z.getEntry(MANIFEST) ?: throw OtaException("bad_manifest", "manifest")
            if (me.size > MAX_MANIFEST) throw OtaException("too_big", "manifest")
            val mb = readBounded(z, MANIFEST, me.size)
            val manifest = try { JSONObject(mb.toString(Charsets.UTF_8)) } catch (e: Exception) { throw OtaException("bad_manifest", "json") }
            if (manifest.optString("format") != FORMAT) throw OtaException("bad_format")
            val se = z.getEntry(SIGNATURE) ?: throw OtaException("unsigned")
            if (se.size > 512) throw OtaException("bad_signature")
            val sig = b64(readBounded(z, SIGNATURE, se.size)) ?: throw OtaException("bad_signature")
            val msg = MAGIC + mb
            var community: ByteArray? = null
            if (!Ed25519.verify(publicKey, msg, sig)) {
                community = extraKeys.firstOrNull { it.size == 32 && !it.contentEquals(publicKey) && Ed25519.verify(it, msg, sig) }
                    ?: throw OtaException("bad_signature")
            }
            validateManifest(manifest)
            checkCompat(manifest, baseName, flavor)
            val listed = HashMap<String, JSONObject>()
            val arr = manifest.getJSONArray("files")
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { listed[it.getString("path")] = it }
            names.firstOrNull { it !in listed && it != MANIFEST && it != SIGNATURE }?.let { throw OtaException("unlisted_file", it.take(80)) }
            val data = LinkedHashMap<String, ByteArray>()
            for ((p, f) in listed) {
                val e = z.getEntry(p) ?: throw OtaException("missing_file", p)
                val blob = readBounded(z, p, e.size)
                if (blob.size.toLong() != f.getLong("size") || OtaCore.sha256(blob) != f.getString("sha256")) throw OtaException("hash_mismatch", p)
                if (blob.size < 4 || blob[0] != 'd'.code.toByte() || blob[1] != 'e'.code.toByte() || blob[2] != 'x'.code.toByte() || blob[3] != '\n'.code.toByte())
                    throw OtaException("bad_dex", p)
                data[p] = blob
            }
            return DexPackage(manifest, data, community)
        }
    }

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
