package com.v1pach.telescript.ota

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** A community author's Ed25519 public key the user chose to trust for OTA packages. */
data class TrustedKey(val name: String, val keyHex: String) {
    val raw: ByteArray get() = OtaCore.hex(keyHex)
    val fingerprint: String get() = TrustedKeys.fingerprint(raw)
}

/**
 * Pure logic for the user-managed list of community OTA keys (JSON in a preference). The official key
 * is not part of this list: it is built in, cannot be removed or replaced, and is always checked first.
 */
object TrustedKeys {
    const val MAX_KEYS = 20

    /** Full sha256 of the raw public key, lowercase hex. */
    fun fingerprint(raw: ByteArray): String = OtaCore.sha256(raw)

    /** "ab:cd:ef:12 ..." grouping of the first 16 bytes for display. */
    fun shortFingerprint(raw: ByteArray): String = fingerprint(raw).take(32).chunked(4).joinToString(" ")

    /** Accepts 64 hex chars, base64/base64url of 32 bytes, optionally prefixed "ed25519:" ; whitespace ignored. Null if invalid. */
    fun parseKey(text: String): ByteArray? {
        var s = text.filterNot(Char::isWhitespace)
        if (s.startsWith("ed25519:", ignoreCase = true)) s = s.substring(8)
        if (s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return OtaCore.hex(s.lowercase())
        val b = runCatching {
            if ('-' in s || '_' in s) Base64.getUrlDecoder().decode(s) else Base64.getDecoder().decode(s)
        }.getOrNull()
        return b?.takeIf { it.size == 32 }
    }

    /** Adds a key. Throws IllegalArgumentException with code "key", "name", "official", "dup", "limit". */
    fun add(list: List<TrustedKey>, name: String, keyText: String, official: ByteArray): List<TrustedKey> {
        val raw = parseKey(keyText) ?: throw IllegalArgumentException("key")
        val n = name.trim().take(60)
        if (n.isEmpty()) throw IllegalArgumentException("name")
        if (raw.contentEquals(official)) throw IllegalArgumentException("official")
        val hex = raw.joinToString("") { "%02x".format(it) }
        if (list.any { it.keyHex == hex }) throw IllegalArgumentException("dup")
        if (list.size >= MAX_KEYS) throw IllegalArgumentException("limit")
        return list + TrustedKey(n, hex)
    }

    fun remove(list: List<TrustedKey>, keyHex: String): List<TrustedKey> = list.filter { it.keyHex != keyHex }

    fun serialize(list: List<TrustedKey>): String =
        JSONArray(list.map { JSONObject().put("name", it.name).put("key", it.keyHex) }).toString()

    fun parse(json: String): List<TrustedKey> = try {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull {
            val o = a.optJSONObject(it) ?: return@mapNotNull null
            val k = o.optString("key"); val n = o.optString("name")
            if (Regex("[0-9a-f]{64}").matches(k) && n.isNotBlank()) TrustedKey(n.take(60), k) else null
        }.distinctBy { it.keyHex }
    } catch (e: Exception) { emptyList() }
}
