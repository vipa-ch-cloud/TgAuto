package com.v1pach.telescript

import androidx.compose.runtime.Composable
import org.json.JSONObject

/** Stable build: the LLM settings are exactly the pre-1.8 ones. The nightly source set has the real implementation. */
object FlavorHooks {
    const val managesBackend = false
    fun cliOrder(pref: String): String = pref
    @Composable fun cliOrderState(pref: String): String = pref
    fun codexOn(): Boolean = false
    @Composable fun codexState(pref: String): Boolean = false
    fun providersFile(): String = Providers.path
    fun apiKey(raw: String): String = raw
    fun putEnv(env: JSONObject) {}
}
