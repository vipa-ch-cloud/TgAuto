package com.v1pach.telescript

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.v1pach.telescript.ota.OtaManager
import com.v1pach.telescript.store.StoreManager

class TeleScriptApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Str.init(this)
        AppSettings.init(this)
        LlmBridge.init(this)
        com.v1pach.telescript.terminal.TerminalHost.init(this)
        com.v1pach.telescript.terminal.CliManager.init(this)
        Providers.init(this)
        com.v1pach.telescript.ota.ApkUpdater.init(this)
        OtaManager.init(this) // boot guard + strings overlay, before anything imports OTA-able code
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        runCatching { OtaManager.applyPythonPath() }
        ScriptRepository.init(this)
        StoreManager.init(this)
        // Warm up Python (log routing, telethon import) off the main thread.
        Thread({
            if (runCatching { Py.host }.isSuccess) OtaManager.confirmBoot()
            runCatching { OtaManager.autoCheck() }
            runCatching { com.v1pach.telescript.ota.ApkUpdater.autoCheck() }
            runCatching { StoreManager.autoCheck() }
        }, "py-warmup").start()
    }
}
