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
 * run in the embedded Termux userland (Userland.run) and stream their output live. Lives in the app process, so closing the overlay does not stop a command.
 */
object ConsoleSession {
    private const val MAX_LINES = 3000
    /** CRT effect on the console overlay (default on). */
    val crt = AppSettings.Pref("console_crt", true)

    val lines = mutableStateListOf<ConsoleLine>()
    var running by mutableStateOf(false)
        private set
    val history = ArrayList<String>()

    private var nextLine = 0L
        @Volatile private var proc: Process? = null
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
        scope.launch {
            val ctx = com.v1pach.telescript.terminal.TerminalHost.context()
            val r = com.v1pach.telescript.terminal.Userland.run(ctx, cmd, 1800, "", onProc = { proc = it }) { line ->
                emit(LineKind.OUT, line + "\n")
            }
            val wasInterrupted = interrupted
            proc = null
            if (wasInterrupted) info("^C")
            else if (r.exit != 0) info("[exit ${r.exit}]")
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

    /** Ctrl+C: kills the running process. */
    fun interrupt() {
        if (!running) return
        interrupted = true
        proc?.destroyForcibly()
    }
}
