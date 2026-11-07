package com.v1pach.telescript.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.v1pach.telescript.AppSettings
import com.v1pach.telescript.Paths
import java.io.File

object TerminalPrefs {
    /** The terminal is off until the user switches it on and accepts the warning. */
    val enabled = AppSettings.Pref("terminal_enabled", false)
    /** Ask for the device credential (PIN / pattern / fingerprint) when opening the terminal. */
    val lock = AppSettings.Pref("terminal_lock", true)
    val fontSize = AppSettings.Pref("terminal_font", 14)
    /** Optional http(s) proxy used only by CLI install/update scripts. */
    val httpProxy = AppSettings.Pref("cli_http_proxy", "")
}

class TermTab(val id: Int, val session: TerminalSession)

/**
 * Terminal sessions of the app process: a real PTY (libtermux.so forkpty) running /system/bin/sh with
 * the app's environment. Sessions outlive the activity, but not the process.
 */
object TerminalHost {
    const val SHELL = "/system/bin/sh"

    private lateinit var app: Context
    val tabs = mutableStateListOf<TermTab>()
    var selected by mutableIntStateOf(0)
    /** Bumped whenever a title or exit state changes, so Compose re-reads it. */
    var tick by mutableIntStateOf(0)
        private set
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    @Volatile var view: TerminalView? = null
    private var counter = 0

    fun init(context: Context) { app = context.applicationContext }

    fun home(): File = File(Paths.root, "home").also { it.mkdirs() }

    private fun prepareHome() {
        val rc = File(home(), ".mkshrc")
        if (!rc.exists() || rc.readText() != CliLogic.MKSHRC) rc.writeText(CliLogic.MKSHRC)
    }

    fun newTab(): TermTab {
        prepareHome()
        val env = CliLogic.shellEnv(Paths.root.absolutePath, app.cacheDir.absolutePath, Paths.llmBin.absolutePath,
            Paths.mirror.absolutePath, app.applicationInfo.nativeLibraryDir).toTypedArray()
        val s = TerminalSession(SHELL, home().absolutePath, arrayOf("sh"), env, 4000, sessionClient)
        s.mSessionName = "sh ${++counter}"
        val tab = TermTab(counter, s)
        tabs.add(tab)
        selected = tabs.lastIndex
        return tab
    }

    fun ensureTab() { if (tabs.isEmpty()) newTab() }

    fun close(index: Int) {
        val t = tabs.getOrNull(index) ?: return
        t.session.finishIfRunning()
        tabs.removeAt(index)
        selected = selected.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        tick++
    }

    fun current(): TermTab? = tabs.getOrNull(selected)

    // --- extra keys -------------------------------------------------------------------------------

    private fun mod(): Int = (if (ctrl) com.termux.terminal.KeyHandler.KEYMOD_CTRL else 0) or
        (if (alt) com.termux.terminal.KeyHandler.KEYMOD_ALT else 0)

    fun sendKey(keyCode: Int) {
        val v = view ?: return
        v.handleKeyCode(keyCode, mod())
        ctrl = false; alt = false
    }

    fun sendText(text: String) {
        val s = current()?.session ?: return
        s.write(if (ctrl && text.length == 1) ctrlChar(text[0]) else text)
        ctrl = false; alt = false
    }

    private fun ctrlChar(c: Char): String = when (c.lowercaseChar()) {
        in 'a'..'z' -> (c.lowercaseChar() - 'a' + 1).toChar().toString()
        '[' -> "\u001b"; '\\' -> "\u001c"; ']' -> "\u001d"
        else -> c.toString()
    }

    fun paste() {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString().orEmpty()
        if (text.isNotEmpty()) current()?.session?.emulator?.paste(text)
    }

    fun copyScreen(): String {
        val e = current()?.session?.emulator ?: return ""
        return e.screen.transcriptTextWithoutJoinedLines.orEmpty().trimEnd()
    }

    fun setClipboard(text: String) {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    // --- clients ----------------------------------------------------------------------------------

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { view?.takeIf { it.currentSession === changedSession }?.onScreenUpdated() }
        override fun onTitleChanged(changedSession: TerminalSession) { tick++ }
        override fun onSessionFinished(finishedSession: TerminalSession) { tick++ }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) { setClipboard(text) }
        override fun onPasteTextFromClipboard(session: TerminalSession?) { paste() }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) { view?.invalidate() }
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    /** Touch / key behaviour of the TerminalView. */
    val viewClient = object : TerminalViewClient {
        private var scale = 1f
        override fun onScale(scale: Float): Float {
            this.scale *= scale
            if (this.scale < 0.9f || this.scale > 1.1f) {
                val size = (TerminalPrefs.fontSize.value + if (this.scale > 1f) 1 else -1).coerceIn(8, 32)
                TerminalPrefs.fontSize.set(size)
                view?.setTextSize(size)
                this.scale = 1f
            }
            return this.scale
        }
        override fun onSingleTapUp(e: MotionEvent) {
            val v = view ?: return
            (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0)
        }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
        override fun onLongPress(event: MotionEvent) = false
        override fun readControlKey() = ctrl
        override fun readAltKey() = alt
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            ctrl = false; alt = false // one-shot toggles of the extra-keys row
            return false
        }
        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }
}
