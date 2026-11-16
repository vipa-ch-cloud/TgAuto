package com.v1pach.telescript.ota

import com.v1pach.telescript.AppSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remote hotfix flags and kill-switch. The release `latest` of the app repo may carry two extra assets:
 * `flags.json` and `flags.json.sig` (base64 Ed25519 over `TGAFLAGS1\n` + the exact bytes of flags.json, made with an
 * official key). Only a valid signature and a higher `seq` than the cached one are accepted (no replay of old flags).
 *
 * Fields (all optional): seq (int), ota_pause / dex_pause / apk_pause / store_pause (stop automatic download and apply),
 * ota_kill / dex_kill (additionally drop the installed overlay / plugin at the next start, back to built-in code),
 * revoked_ota / revoked_dex / revoked_apk (version lists: an installed revoked overlay or plugin is rolled back, a
 * revoked APK is never auto-downloaded), message_en / message_ru (shown in Settings > Updates).
 * The verified flags are cached, so they also apply offline at the next start.
 */
object RemoteFlags {
    private val MAGIC = "TGAFLAGS1\n".toByteArray()
    private const val MAX = 64 * 1024L

    class Flags(private val j: JSONObject) {
        val seq: Int get() = j.optInt("seq", 0)
        fun on(key: String) = j.optBoolean(key, false)
        fun revoked(key: String): Set<String> = j.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        fun message(ru: Boolean): String = j.optString(if (ru) "message_ru" else "message_en").ifBlank { j.optString("message_en") }
    }

    /** Verifies and parses flags; null when the signature, size or structure is wrong. */
    fun parse(data: ByteArray, sigB64: ByteArray): Flags? = try {
        if (data.size > MAX) null else {
            val sig = java.util.Base64.getDecoder().decode(String(sigB64).trim())
            if (OtaKeys.all.none { Ed25519.verify(it, MAGIC + data, sig) }) null else Flags(JSONObject(String(data)))
        }
    } catch (e: Throwable) { null }

    /** Cached verified flags (empty when none). Safe to call early at start. */
    fun cached(): Flags = runCatching { Flags(JSONObject(AppSettings.remoteFlags.value.ifBlank { "{}" })) }.getOrDefault(Flags(JSONObject()))

    fun otaPaused() = cached().let { it.on("ota_pause") || it.on("ota_kill") }
    fun dexPaused() = cached().let { it.on("dex_pause") || it.on("dex_kill") }
    fun apkPaused() = cached().on("apk_pause")
    fun storePaused() = cached().on("store_pause")

    /** Fetches flags from releases/latest (at most hourly). Blocking; failures are silent. */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - (AppSettings.remoteFlagsCheck.value.toLongOrNull() ?: 0) < 3600_000L) return
        AppSettings.remoteFlagsCheck.set(now.toString())
        try {
            val rel = JSONObject(String(OtaManager.http("https://api.github.com/repos/${OtaManager.REPO}/releases/latest", 2L * 1024 * 1024)))
            val assets = rel.optJSONArray("assets") ?: return
            fun url(name: String): String? = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == name }?.optString("browser_download_url")?.takeIf { it.startsWith("https://") }
            val data = OtaManager.http(url("flags.json") ?: return, MAX)
            val sig = OtaManager.http(url("flags.json.sig") ?: return, 4096)
            val f = parse(data, sig)
            if (f == null) { OtaManager.log("Remote flags rejected (bad signature or format)"); return }
            if (f.seq <= cached().seq && AppSettings.remoteFlags.value.isNotBlank()) return
            AppSettings.remoteFlags.set(String(data))
            OtaManager.log("Remote flags applied (seq ${f.seq})")
        } catch (e: Throwable) { /* offline or no flags: keep the cached ones */ }
    }
}
