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
    val anthropicKey = Pref("anthropic_key", "")
    val model = Pref("model", "")

    // Appearance
    val themeMode = Pref("theme_mode", "system") // system | light | dark
    val dynamicColor = Pref("dynamic_color", true)
    val palette = Pref("palette", "indigo")

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
        if (type == "mtproxy") json.put("secret", proxySecret.value.trim())
        return json.toString()
    }

    fun hasApiCredentials(): Boolean =
        apiId.value.trim().toIntOrNull() != null && apiHash.value.trim().length >= 16
}
