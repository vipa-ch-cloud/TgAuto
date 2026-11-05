package com.v1pach.telescript

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.v1pach.telescript.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class LineKind { CMD, OUT, ERR, INFO }

class ConsoleLine(val id: Long, val kind: LineKind, val spans: List<AnsiSpan>)

/**
 * The console's own state: scrollback, history and the running command. Commands go to Termux
 * through LlmBridge.runRaw (RUN_COMMAND); Termux returns the output when the command ends, so it is
 * printed then, not live. Lives in the app process, so closing the overlay does not stop a command.
 */
object ConsoleSession {
    private const val MAX_LINES = 3000
    /** Shell functions so `claude` / `gemini` / `codex` (npm scripts with a node shebang) work like in the shims. */
    private const val PRELUDE =
        "__tg() { n=\$1; shift; b=\$(type -P \"\$n\") || { echo \"\$n: command not found\" >&2; return 127; }; " +
        "if head -n1 \"\$b\" | grep -q node; then node \"\$b\" \"\$@\"; else \"\$b\" \"\$@\"; fi; }; " +
        "claude() { __tg claude \"\$@\"; }; gemini() { __tg gemini \"\$@\"; }; codex() { __tg codex \"\$@\"; }; cd ~; "

    /** CRT effect on the console overlay (default on). */
    val crt = AppSettings.Pref("console_crt", true)

    val lines = mutableStateListOf<ConsoleLine>()
    var running by mutableStateOf(false)
        private set
    val history = ArrayList<String>()

    private var nextLine = 0L
    private var runId = 0
    private var requestId = 0
    @Volatile private var interrupted = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun add(kind: LineKind, spans: List<AnsiSpan>) {
        lines.add(ConsoleLine(nextLine++, kind, spans))
        if (lines.size > MAX_LINES) lines.removeRange(0, lines.size - MAX_LINES)
    }

    fun info(text: String) = add(LineKind.INFO, listOf(AnsiSpan(text, AnsiStyle())))

    fun clear() = lines.clear()

    fun submit(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty() || running) return
        if (history.lastOrNull() != cmd) history.add(cmd)
        add(LineKind.CMD, listOf(AnsiSpan(cmd, AnsiStyle())))
        if (cmd == "clear" || cmd == "cls") { clear(); return }
        running = true
        interrupted = false
        val tag = "tgauto-console-${++runId}"
        scope.launch {
            val r = LlmBridge.runRaw(listOf("-lc", LlmBridge.TERMUX_ENV + PRELUDE + cmd, tag), "", "TgAuto console", 600) { requestId = it }
            val wasInterrupted = interrupted
            emit(LineKind.OUT, r.stdout)
            emit(LineKind.ERR, r.stderr)
            if (wasInterrupted) info("^C")
            else if (r.exitCode != 0) info("[exit ${r.exitCode}]")
            running = false
        }
    }

    private fun emit(kind: LineKind, text: String) {
        if (text.isEmpty()) return
        val b = AnsiLineBuilder()
        b.feed(text)
        b.flush()
        b.lines.forEach { add(kind, it) }
    }

    /** Ctrl+C: stops waiting and asks Termux to kill the shell (best effort). */
    fun interrupt() {
        if (!running) return
        interrupted = true
        val id = requestId
        val tag = "tgauto-console-$runId"
        LlmBridge.abort(id)
        scope.launch {
            // [t] keeps the pattern from matching this killer's own command line.
            val pat = "[t]" + tag.substring(1)
            LlmBridge.runRaw(listOf("-c", "pkill -TERM -P \$(pgrep -f '$pat' | head -n1) 2>/dev/null; pkill -f '$pat'", "tgauto-kill"), "", "TgAuto console", 15)
        }
    }
}
