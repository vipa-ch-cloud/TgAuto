package com.v1pach.telescript.terminal

import android.content.Context
import android.content.Intent
import android.os.Build
import com.v1pach.telescript.AppSettings
import com.v1pach.telescript.FlavorHooks
import com.v1pach.telescript.LlmBridge
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
    /** "check" | "install" | "remove" while a Termux script is running. */
    val busy: String? = null,
    val error: CliError = CliError.NONE,
    val log: String = "",
)

/**
 * Installs and inspects claude / codex / gemini in Termux. Scripts go through LlmBridge.runRaw
 * (RUN_COMMAND, background); the output arrives when the script ends. Login opens a real Termux session.
 * Everything starts from a button tap in the UI; nothing here is reachable from intents or scripts.
 */
object CliManager {
    private const val SOURCE = "CLI"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<Map<String, CliState>>(emptyMap())
    val state: StateFlow<Map<String, CliState>> = _state

    private fun update(id: String, f: (CliState) -> CliState) {
        _state.value = _state.value + (id to f(_state.value[id] ?: CliState()))
    }

    fun proxy(): String = TerminalPrefs.httpProxy.value.trim().takeIf { CliLogic.validProxy(it) }.orEmpty()

    fun termuxReady(): Boolean = LlmBridge.termuxInstalled() && LlmBridge.hasPermission()

    private fun termuxScript(script: String) = listOf("-lc", LlmBridge.TERMUX_ENV + script)

    /** Checks installation and, if installed, the latest npm version. */
    fun check(spec: CliSpec) {
        if (_state.value[spec.id]?.busy != null) return
        if (!termuxReady()) { update(spec.id) { it.copy(error = CliError.TERMUX) }; return }
        update(spec.id) { it.copy(busy = "check", error = CliError.NONE) }
        scope.launch {
            val r = LlmBridge.runRaw(termuxScript(CliLogic.statusScript(spec)), "", "TgAuto: ${spec.id} status", 90)
            val err = if (r.exitCode == 0) CliError.NONE else CliLogic.classifyError(r.stderr + r.stdout, r.exitCode)
            val st = if (err == CliError.NONE) CliLogic.parseStatus(r.stdout) else null
            var latest: String? = null
            if (st?.installed == true) {
                val l = LlmBridge.runRaw(termuxScript(CliLogic.latestVersionScript(spec) ), "", "TgAuto: ${spec.id} latest", 60)
                latest = CliLogic.parseLatest(l.stdout)
            }
            update(spec.id) { it.copy(busy = null, status = st ?: it.status, latest = latest, error = err,
                log = if (err != CliError.NONE) CliLogic.redact(r.stderr.ifBlank { r.stdout }) else it.log) }
            if (st?.installed == true) relink()
        }
    }

    /** Install or update. */
    fun install(spec: CliSpec) = runScript(spec, "install", CliLogic.installScript(spec, proxy()), 900)

    fun remove(spec: CliSpec) = runScript(spec, "remove", CliLogic.removeScript(spec), 300)

    private fun runScript(spec: CliSpec, op: String, script: String, timeout: Long) {
        if (_state.value[spec.id]?.busy != null) return
        if (!termuxReady()) { update(spec.id) { it.copy(error = CliError.TERMUX) }; return }
        update(spec.id) { it.copy(busy = op, error = CliError.NONE, log = "") }
        LogBus.i(SOURCE, "${spec.id}: $op")
        scope.launch {
            val r = LlmBridge.runRaw(termuxScript(script), "", "TgAuto: $op ${spec.id}", timeout)
            val out = CliLogic.redact(CliLogic.stripAnsi(listOf(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString("\n")))
            val err = CliLogic.classifyError(out, r.exitCode)
            val ok = err == CliError.NONE
            val st = if (op == "remove") (if (ok) CliStatus(false) else null) else if (ok) CliLogic.parseStatus(out) else null
            update(spec.id) { it.copy(busy = null, error = err, log = out.takeLast(6000), status = st ?: it.status,
                latest = if (ok && op == "install") st?.version else it.latest) }
            LogBus.i(SOURCE, "${spec.id}: $op -> " + if (ok) "ok" else "error ${r.exitCode} $err")
            if (ok) relink()
        }
    }

    /** Re-links the claude/gemini/codex shims so the AI backend picks up the CLI right away. */
    private fun relink() {
        runCatching { LlmBridge.setup(FlavorHooks.cliOrder(AppSettings.llmOrder.value), FlavorHooks.codexOn()) }
    }

    /** Nightly "AI backend": switch Codex on (same preference key as AiBackend.codexOn; stable ignores it). */
    fun enableCodexInBackend() {
        AppSettings.Pref("ai_codex_on", false).set(true)
        relink()
    }

    fun codexInBackend(): Boolean = FlavorHooks.codexOn()

    fun errorText(e: CliError): String = when (e) {
        CliError.NONE -> ""
        CliError.NO_NETWORK -> Str.get(R.string.cli_err_network)
        CliError.NO_SPACE -> Str.get(R.string.cli_err_space)
        CliError.PERMISSION -> Str.get(R.string.cli_err_perm)
        CliError.NODE -> Str.get(R.string.cli_err_node)
        CliError.NOT_FOUND -> Str.get(R.string.cli_err_notfound)
        CliError.TIMEOUT -> Str.get(R.string.cli_err_timeout)
        CliError.TERMUX -> Str.get(R.string.cli_err_termux)
        CliError.OTHER -> Str.get(R.string.cli_err_other)
    }

    /**
     * Opens a real Termux session (visible in the Termux app) that runs [script]. The user answers the
     * login prompts there; nothing is read back, so no token passes through TgAuto.
     */
    fun openTermux(context: Context, script: String, label: String): Boolean {
        if (!LlmBridge.termuxInstalled() || !LlmBridge.hasPermission()) return false
        val intent = Intent("com.termux.RUN_COMMAND")
            .setClassName("com.termux", "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", LlmBridge.TERMUX_ENV + script))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label)
        return runCatching {
            try { context.startService(intent) } catch (e: IllegalStateException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else throw e
            }
            // The foreground app may open Termux itself; the service alone can be blocked on Android 10+.
            context.packageManager.getLaunchIntentForPackage("com.termux")?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(it)
            }
            true
        }.getOrDefault(false)
    }

    fun login(context: Context, spec: CliSpec): Boolean =
        openTermux(context, CliLogic.loginScript(spec, proxy()), "TgAuto: ${spec.id} login")

    fun termuxShell(context: Context): Boolean = openTermux(context, "exec bash -l", "TgAuto shell")
}
