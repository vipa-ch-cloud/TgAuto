package com.v1pach.telescript

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import org.json.JSONObject

/** Thin access to the Python modules. Every call blocks: use from a background thread. */
object Py {
    @Volatile private var hostReady = false

    val host: PyObject
        get() {
            val module = Python.getInstance().getModule("ts_host")
            // Tell the Python layer which language to use for user-visible messages.
            Python.getInstance().getModule("os").get("environ")
                ?.callAttr("__setitem__", "TGAUTO_LANG", if (Str.isRu()) "ru" else "en")
            if (!hostReady) synchronized(this) {
                if (!hostReady) {
                    module.callAttr("init", LogBus)
                    hostReady = true
                }
            }
            return module
        }

    val login: PyObject get() {
        host // routes Python output to the log console first
        return Python.getInstance().getModule("ts_login")
    }

    fun json(result: PyObject?): JSONObject = try {
        JSONObject(result?.toString() ?: "{}")
    } catch (e: Exception) {
        JSONObject().put("status", "error").put("message", e.message ?: "bad response")
    }

    /** Runs a login call, converting Java-side exceptions into the same JSON shape. */
    fun loginCall(name: String, vararg args: Any?): JSONObject = try {
        json(login.callAttr(name, *args))
    } catch (e: Throwable) {
        LogBus.e("app", "$name: ${e.message}")
        JSONObject().put("status", "error").put("message", e.message ?: e.toString())
    }
}
