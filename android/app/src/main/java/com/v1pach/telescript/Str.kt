package com.v1pach.telescript

import android.content.Context
import androidx.annotation.StringRes

/** Localized strings for non-Compose code (services, repositories). In Compose use stringResource(). */
object Str {
    private lateinit var app: Context
    fun init(context: Context) { app = context.applicationContext }
    fun get(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)
    /** True when the UI language is Russian. */
    fun isRu(): Boolean = app.resources.configuration.locales[0].language == "ru"
}
