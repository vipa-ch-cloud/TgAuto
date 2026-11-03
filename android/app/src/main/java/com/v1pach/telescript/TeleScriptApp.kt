package com.v1pach.telescript

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class TeleScriptApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        LlmBridge.init(this)
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        ScriptRepository.init(this)
        // Warm up Python (log routing, telethon import) off the main thread.
        Thread({ runCatching { Py.host } }, "py-warmup").start()
    }
}
