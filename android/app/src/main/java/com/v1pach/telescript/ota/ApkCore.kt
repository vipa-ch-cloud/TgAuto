package com.v1pach.telescript.ota

import org.json.JSONObject

/** A newer APK found in a GitHub release. [sha256] comes from the asset "digest" field or a checksum asset (may be null). */
data class ApkRelease(val tag: String, val version: String, val name: String, val url: String, val size: Long,
                      val sha256: String?, val checksumUrl: String?, val notes: String)

/** Pure (JVM-testable) rules for self-updating from GitHub releases. */
object ApkCore {
    private val STABLE = Regex("^TgAuto-(\\d+(?:\\.\\d+){1,3})\\.apk$")
    private val NIGHTLY = Regex("^TgAuto-nightly-(\\d+(?:\\.\\d+){1,3})\\.apk$")
    const val MAX_APK = 300L * 1024 * 1024

    /** Version in an asset name for this channel, or null (a stable build never matches a nightly asset and vice versa). */
    fun versionOf(name: String, flavor: String): String? =
        (if (flavor == "nightly") NIGHTLY else STABLE).matchEntire(name)?.groupValues?.get(1)

    fun assetName(flavor: String, version: String) = if (flavor == "nightly") "TgAuto-nightly-$version.apk" else "TgAuto-$version.apk"

    /** "sha256:HEX" (GitHub asset digest) -> HEX, else null. */
    fun digestHex(digest: String?): String? =
        digest?.takeIf { it.startsWith("sha256:", true) }?.substring(7)?.lowercase()?.takeIf { Regex("[0-9a-f]{64}").matches(it) }

    /** Finds the hash for [file] in "HEX  name" / "HEX *name" lines, or a lone 64-hex file. */
    fun parseChecksum(text: String, file: String): String? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (l in lines) {
            val m = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$").matchEntire(l) ?: continue
            if (m.groupValues[2].trim().substringAfterLast('/') == file) return m.groupValues[1].lowercase()
        }
        return lines.singleOrNull()?.takeIf { Regex("[0-9a-fA-F]{64}(\\s.*)?").matches(it) }?.take(64)?.lowercase()
    }

    /** Newest-for-this-channel APK in a `releases/latest` JSON that is newer than [installed], else null. */
    fun pick(release: JSONObject, flavor: String, installed: String): ApkRelease? {
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val assets = release.optJSONArray("assets") ?: return null
        var best: ApkRelease? = null
        val list = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        for (a in list) {
            val n = a.optString("name")
            val v = versionOf(n, flavor) ?: continue
            val url = a.optString("browser_download_url")
            if (!url.startsWith("https://")) continue
            if (OtaCore.vcmp(v, installed) <= 0) continue
            if (best != null && OtaCore.vcmp(v, best.version) <= 0) continue
            val sums = list.firstOrNull { c -> c.optString("name").let { it == "$n.sha256" || it == "SHA256SUMS" || it == "SHA256SUMS.txt" || it == "checksums.txt" } }
            best = ApkRelease(release.optString("tag_name"), v, n, url, a.optLong("size", -1),
                digestHex(a.optString("digest").ifEmpty { null }), sums?.optString("browser_download_url")?.takeIf { it.startsWith("https://") },
                release.optString("body"))
        }
        return best
    }

    /** Signing identity check: both sets of certificate sha256 must be non-empty and equal. Otherwise the update is refused. */
    fun certsMatch(installed: Set<String>, candidate: Set<String>): Boolean {
        val a = installed.map { it.lowercase().replace(":", "") }.toSet()
        val b = candidate.map { it.lowercase().replace(":", "") }.toSet()
        return a.isNotEmpty() && a == b
    }

    /** Throws OtaException(code) when the downloaded APK must not be installed. */
    fun checkIdentity(pkgInstalled: String, pkgNew: String?, codeInstalled: Long, codeNew: Long,
                      certsInstalled: Set<String>, certsNew: Set<String>) {
        if (pkgNew == null || pkgNew != pkgInstalled) throw OtaException("apk_package", pkgNew.orEmpty())
        if (codeNew <= codeInstalled) throw OtaException("apk_not_newer")
        if (!certsMatch(certsInstalled, certsNew)) throw OtaException("apk_cert")
    }

    /** Mode semantics: does this mode download in the background? */
    fun downloads(mode: String) = mode == "download" || mode == "auto"
}
