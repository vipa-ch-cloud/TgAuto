package com.v1pach.telescript

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** SharedPreferences exposed as StateFlows. Initialised from TeleScriptApp. */
object AppSettings {
    private lateinit var prefs: SharedPreferences
    private val flows = HashMap<String, MutableStateFlow<Any>>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("telescript", Context.MODE_PRIVATE)
    }

    class Pref<T : Any>(val key: String, val default: T) {
        @Suppress("UNCHECKED_CAST")
        val flow: StateFlow<T>
            get() = synchronized(flows) {
                flows.getOrPut(key) { MutableStateFlow(read(this) as Any) }
            } as StateFlow<T>

        val value: T get() = flow.value

        fun set(value: T) {
            val editor = prefs.edit()
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                else -> error("unsupported pref type")
            }
            editor.apply()
            @Suppress("UNCHECKED_CAST")
            (flow as MutableStateFlow<T>).value = value
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> read(pref: Pref<T>): T = when (val d = pref.default) {
        is String -> (prefs.getString(pref.key, d) ?: d) as T
        is Boolean -> prefs.getBoolean(pref.key, d) as T
        is Int -> prefs.getInt(pref.key, d) as T
        else -> d
    }

    // Telegram
    val apiId = Pref("api_id", "")
    val apiHash = Pref("api_hash", "")
    val proxyType = Pref("proxy_type", "none") // none | socks5 | mtproxy
    val proxyHost = Pref("proxy_host", "")
    val proxyPort = Pref("proxy_port", "")
    val proxySecret = Pref("proxy_secret", "")

    // Scripts folder chosen through SAF (tree URI)
    val scriptsTree = Pref("scripts_tree", "")

    // LLM
    // Which CLIs scripts may use (via Termux): both = Claude first, Gemini as backup | claude | gemini | api
    val llmOrder = Pref("llm_order", "both")
    val termuxCommand = Pref("termux_command", "claude")
    val geminiCommand = Pref("gemini_command", "gemini")
    val codexCommand = Pref("codex_command", "codex")
    val anthropicKey = Pref("anthropic_key", "")
    val model = Pref("model", "")

    // Appearance
    val themeMode = Pref("theme_mode", "system") // system | light | dark
    val dynamicColor = Pref("dynamic_color", true)
    val palette = Pref("palette", "indigo")

    // Updates (OTA) and script store
    val otaAutoCheck = Pref("ota_auto_check", true)
    /** Download and apply OTA automatically: default ON, but only for packages signed by the official key (community keys never auto-apply). */
    val otaAutoApply = Pref("ota_auto_apply", true)
    /** Community OTA signer keys the user trusts (JSON array of {name,key}). */
    val otaTrustedKeys = Pref("ota_trusted_keys", "[]")
    /** Last OTA/APK update events, newline separated, newest last. */
    val otaLog = Pref("ota_log", "")
    /** APK self-update: off | notify | download | auto. */
    val apkMode = Pref("apk_update_mode", "notify")
    val apkLastCheck = Pref("apk_last_check", "0")
    val apkNotified = Pref("apk_notified", "")
    val otaLastCheck = Pref("ota_last_check", "0")
    val otaNotified = Pref("ota_notified", "")
    val storeAutoUpdate = Pref("store_auto_update", false)
    val storeLastCheck = Pref("store_last_check", "0")
    /** Community catalog sources added by the user (JSON array; the official one is built in). */
    val storeSources = Pref("store_sources", "[]")

    // Logs
    val logDebug = Pref("log_debug", false)
    val logInfo = Pref("log_info", true)
    val logWarn = Pref("log_warn", true)
    val logError = Pref("log_error", true)

    /** Proxy settings as the JSON object scripts expect in proxy.json; "" when no proxy. */
    fun proxyJson(): String {
        val type = proxyType.value
        val port = proxyPort.value.trim().toIntOrNull()
        if (type == "none" || proxyHost.value.isBlank() || port == null) return ""
        val json = JSONObject().put("type", type).put("host", proxyHost.value.trim()).put("port", port)
        if (type == "mtproxy") json.put("secret", normalizeProxySecret(proxySecret.value))
        return json.toString()
    }

    /** Removes all whitespace (pasted secrets often carry spaces or line breaks). */
    fun normalizeProxySecret(raw: String): String = raw.filterNot(Char::isWhitespace)

    /**
     * Validates an MTProxy secret the way Telethon reads it: hex (even length, optional dd/ee prefix)
     * or urlsafe base64. Returns a string resource id with the error, or null when the secret is fine.
     */
    fun proxySecretError(raw: String): Int? {
        val s = normalizeProxySecret(raw)
        if (s.isEmpty()) return R.string.account_secret_empty
        if (s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return if (s.length % 2 == 0) null else R.string.account_secret_hex_odd
        }
        val body = s.trimEnd('=')
        val b64 = body.isNotEmpty() && s.length - body.length <= 2 &&
            body.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' || it == '+' || it == '/' }
        return if (b64 && body.length % 4 != 1) null else R.string.account_secret_bad
    }

    fun hasApiCredentials(): Boolean =
        apiId.value.trim().toIntOrNull() != null && apiHash.value.trim().length >= 16
}
