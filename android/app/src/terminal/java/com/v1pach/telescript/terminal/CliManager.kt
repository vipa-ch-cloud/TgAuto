package com.v1pach.telescript.terminal

import android.content.Context
import com.v1pach.telescript.LogBus
import com.v1pach.telescript.R
import com.v1pach.telescript.Str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CliState(
    val status: CliStatus? = null,
    val latest: String? = null,
    /** "check" | "install" | "remove" while a script is running. */
    val busy: String? = null,
    val error: CliError = CliError.NONE,
    val log: String = "",
)

/**
 * Installs and inspects claude / codex / gemini inside the embedded userland (npm). Everything starts
 * from a button tap in the console; nothing here is reachable from intents or scripts.
 */
object CliManager {
    private const val SOURCE = "CLI"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<Map<String, CliState>>(emptyMap())
    val state: StateFlow<Map<String, CliState>> = _state
    private lateinit var app: Context

    fun init(context: Context) { app = context.applicationContext }

    private fun update(id: String, f: (CliState) -> CliState) {
        _state.value = _state.value + (id to f(_state.value[id] ?: CliState()))
    }

    fun proxy(): String = TerminalPrefs.httpProxy.value.trim().takeIf { CliLogic.validProxy(it) }.orEmpty()

    fun check(spec: CliSpec) {
        if (_state.value[spec.id]?.busy != null) return
        update(spec.id) { it.copy(busy = "check", error = CliError.NONE) }
        scope.launch {
            val r = Userland.run(app, CliLogic.statusScript(spec), 90, proxy())
            val err = if (r.exit == 0) CliError.NONE else CliLogic.classifyError(r.output, r.exit)
            val st = if (err == CliError.NONE) CliLogic.parseStatus(r.output) else null
            var latest: String? = null
            if (st?.installed == true) {
                latest = CliLogic.parseLatest(Userland.run(app, CliLogic.latestVersionScript(spec), 60, proxy()).output)
            }
            update(spec.id) { it.copy(busy = null, status = st ?: it.status, latest = latest, error = err,
                log = if (err != CliError.NONE) CliLogic.redact(r.output) else it.log) }
        }
    }

    fun install(spec: CliSpec) = runScript(spec, "install", CliLogic.installScript(spec, proxy()), 1500)

    fun remove(spec: CliSpec) = runScript(spec, "remove", CliLogic.removeScript(spec), 300)

    private fun runScript(spec: CliSpec, op: String, script: String, timeout: Long) {
        if (_state.value[spec.id]?.busy != null) return
        update(spec.id) { it.copy(busy = op, error = CliError.NONE, log = "") }
        LogBus.i(SOURCE, "${spec.id}: $op")
        scope.launch {
            val live = StringBuilder()
            val r = Userland.run(app, script, timeout, proxy()) { line ->
                live.appendLine(CliLogic.redact(CliLogic.stripAnsi(line)))
                val t = live.toString().takeLast(6000)
                update(spec.id) { it.copy(log = t) }
            }
            val out = CliLogic.redact(CliLogic.stripAnsi(r.output))
            val err = CliLogic.classifyError(out, r.exit)
            val ok = err == CliError.NONE
            val st = if (op == "remove") (if (ok) CliStatus(false) else null) else if (ok) CliLogic.parseStatus(out) else null
            update(spec.id) { it.copy(busy = null, error = err, log = out.takeLast(6000), status = st ?: it.status,
                latest = if (ok && op == "install") st?.version else it.latest) }
            LogBus.i(SOURCE, "${spec.id}: $op -> " + if (ok) "ok" else "error ${r.exit} $err")
        }
    }

    fun errorText(e: CliError): String = when (e) {
        CliError.NONE -> ""
        CliError.NO_NETWORK -> Str.get(R.string.cli_err_network)
        CliError.NO_SPACE -> Str.get(R.string.cli_err_space)
        CliError.PERMISSION -> Str.get(R.string.cli_err_perm)
        CliError.NODE -> Str.get(R.string.cli_err_node)
        CliError.NOT_FOUND -> Str.get(R.string.cli_err_notfound)
        CliError.TIMEOUT -> Str.get(R.string.cli_err_timeout)
        CliError.USERLAND -> Str.get(R.string.cli_err_userland)
        CliError.OTHER -> Str.get(R.string.cli_err_other)
    }
}
