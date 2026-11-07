package com.v1pach.telescript

import android.content.Context
import androidx.annotation.StringRes

/** Localized strings for non-Compose code (services, repositories). In Compose use stringResource(). */
object Str {
    private lateinit var app: Context
    fun init(context: Context) { app = context.applicationContext }
    fun get(@StringRes id: Int, vararg args: Any): String {
        // OTA strings overlay (res/strings.json) wins over the built-in resource of the same name.
        com.v1pach.telescript.ota.OtaManager.overlayString(runCatching { app.resources.getResourceEntryName(id) }.getOrDefault(""))
            ?.let { return runCatching { String.format(it, *args) }.getOrDefault(it) }
        return app.getString(id, *args)
    }
    /** True when the UI language is Russian. */
    fun isRu(): Boolean = app.resources.configuration.locales[0].language == "ru"
}
