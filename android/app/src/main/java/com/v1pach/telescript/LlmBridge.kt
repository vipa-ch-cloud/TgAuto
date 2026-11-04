package com.v1pach.telescript

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the real Claude Code CLI inside Termux through com.termux.RUN_COMMAND, so scripts
 * use the Claude account the user logged into there. Scripts call the `claude` shim
 * (jniLibs/libclaude.so, linked into Paths.llmBin); the shim drops each call into
 * Paths.llmSpool and waits; this bridge forwards it to Termux and writes the result back.
 */
object LlmBridge {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val TERMUX = "com.termux"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TIMEOUT_S = 300L
    private const val SOURCE = "LLM"

    /** The flags automod uses: no tools, no MCP, no settings, no saved sessions. */
    val TOOLLESS = listOf("--tools", "", "--strict-mcp-config", "--setting-sources", "", "--no-session-persistence")

    data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    private lateinit var app: Context
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Result>>()
    private val claimed = ConcurrentHashMap.newKeySet<String>()
    private val nextId = AtomicInteger((System.currentTimeMillis() % 100000).toInt())
    @Volatile private var poller: Thread? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun termuxInstalled(): Boolean = runCatching {
        app.packageManager.getPackageInfo(TERMUX, 0); true
    }.getOrDefault(false)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Links the shim into Paths.llmBin (as `claude`) and starts the spool poller.
     * Removes the link in API-key mode so automod's "auto" backend picks the API.
     */
    fun setup(order: String) {
        Paths.llmBin.mkdirs()
        Paths.llmSpool.mkdirs()
        val target = File(app.applicationInfo.nativeLibraryDir, "libclaude.so").absolutePath
        // Only the CLIs the user allowed get a link, so scripts fall back claude -> gemini -> API key.
        for (name in listOf("claude", "gemini")) {
            val link = File(Paths.llmBin, name)
            if (!(order == "both" || order == name)) { link.delete(); continue }
            val current = runCatching { android.system.Os.readlink(link.absolutePath) }.getOrNull()
            if (current != target) {
                link.delete()
                android.system.Os.symlink(target, link.absolutePath)
            }
        }
        if (order != "api") startPoller()
    }

    @Synchronized
    private fun startPoller() {
        if (poller?.isAlive == true) return
        // Requests left over from a previous process can never be answered.
        Paths.llmSpool.listFiles()?.forEach { it.deleteRecursively() }
        poller = Thread({
            while (true) {
                runCatching { scan() }.onFailure { LogBus.e(SOURCE, "Мост: ${it.message}") }
                Thread.sleep(300)
            }
        }, "llm-bridge").apply { isDaemon = true; start() }
    }

    private fun scan() {
        val dirs = Paths.llmSpool.listFiles { f -> f.isDirectory && f.name.endsWith(".req") } ?: return
        claimed.retainAll(dirs.map { it.name }.toSet())
        for (dir in dirs) {
            if (!claimed.add(dir.name) || File(dir, "done").exists()) continue
            Thread({ serve(dir) }, "llm-${dir.name}").start()
        }
    }

    private fun serve(dir: File) {
        val result = try {
            val argc = File(dir, "argc").readText().trim().toInt()
            val args = (0 until argc).map { File(dir, "arg.$it").readText() }
            val stdin = File(dir, "stdin").readText()
            val cmd = File(dir, "cmd").takeIf { it.isFile }?.readText()?.trim().orEmpty().ifEmpty { "claude" }
            run(cmd, args, stdin)
        } catch (e: Throwable) {
            Result(127, "", "TgAuto: ${e.message}")
        }
        if (!dir.isDirectory) return // the script gave up (timeout) and removed it
        runCatching {
            File(dir, "stdout").writeText(result.stdout)
            File(dir, "stderr").writeText(result.stderr)
            File(dir, "exit").writeText(result.exitCode.toString())
            File(dir, "done").createNewFile()
        }
    }

    /**
     * Gemini CLI settings for the sandbox, the same keys as automod's GEMINI_SETTINGS: no built-in
     * tool (empty allowlist plus every known tool excluded) and no MCP. No approval flags are ever passed.
     */
    private val GEMINI_SETTINGS = """{"tools":{"core":[],"exclude":["run_shell_command","read_file","read_many_files","write_file","replace","glob","search_file_content","grep_search","list_directory","web_fetch","google_web_search","save_memory","write_todos","activate_skill","ask_user","enter_plan_mode","exit_plan_mode","delegate_to_agent","get_internal_docs"]},"mcp":{"allowed":[]},"mcpServers":{},"telemetry":{"enabled":false}}"""

    /** Runs `claude`/`gemini <args>` in Termux with [stdin] as its standard input. Blocking. */
    fun run(name: String, args: List<String>, stdin: String, timeoutS: Long = TIMEOUT_S): Result {
        require(name == "claude" || name == "gemini") { "unknown command $name" }
        if (!termuxInstalled()) return Result(127, "", "Termux не установлен")
        if (!hasPermission()) return Result(126, "", "Нет разрешения «Выполнение команд в Termux»")
        val id = nextId.incrementAndGet()
        val future = CompletableFuture<Result>()
        pending[id] = future
        try {
            val pref = if (name == "gemini") AppSettings.geminiCommand else AppSettings.termuxCommand
            val cmd = pref.value.trim().ifEmpty { name }
            // An empty working directory, as automod's own sandbox; $CMD is split on spaces
            // so a wrapper such as `proot-distro login ubuntu -- claude` works too.
            // Gemini: write the no-tools settings first; the trust flag lets the sandbox settings load.
            val prepare = if (name == "gemini")
                "mkdir -p .gemini && printf '%s' '$GEMINI_SETTINGS' > .gemini/settings.json && export GEMINI_CLI_TRUST_WORKSPACE=true && "
            else ""
            // Termux's own environment: without termux-exec in LD_PRELOAD a `#!/usr/bin/env node`
            // shebang fails ("bad interpreter"), so npm CLIs are started as `node <script>`.
            val pfx = "/data/data/com.termux/files/usr"
            val env = "export PATH=$pfx/bin:\$PATH; " +
                "for l in libtermux-exec-ld-preload.so libtermux-exec.so; do " +
                "[ -e $pfx/lib/\$l ] && { export LD_PRELOAD=$pfx/lib/\$l; break; }; done; "
            val run = if (cmd == name)
                "bin=\$(command -v $name) || { echo '$name: не установлен в Termux' >&2; exit 127; }; " +
                "if head -n1 \"\$bin\" | grep -q node; then exec node \"\$bin\" \"\$@\"; else exec \"\$bin\" \"\$@\"; fi"
            else "exec $cmd \"\$@\""
            val script = "${env}mkdir -p ~/.telescript-sandbox && cd ~/.telescript-sandbox && ${prepare}$run"
            val resultIntent = Intent(app, LlmResultReceiver::class.java).putExtra(LlmResultReceiver.EXTRA_ID, id)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
            val pi = PendingIntent.getBroadcast(app, id, resultIntent, flags)
            val intent = Intent("com.termux.RUN_COMMAND")
                .setClassName(TERMUX, "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", (listOf("-lc", script, name) + args).toTypedArray())
                .putExtra("com.termux.RUN_COMMAND_STDIN", stdin)
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "TgAuto: $name")
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            try {
                app.startService(intent)
            } catch (e: IllegalStateException) {
                // Termux is in the background: RunCommandService promotes itself to foreground.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else throw e
            }
            return future.get(timeoutS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            LogBus.w(SOURCE, "Termux не ответил за $timeoutS с")
            return Result(124, "", "Termux не ответил за $timeoutS с")
        } catch (e: Throwable) {
            LogBus.e(SOURCE, "RUN_COMMAND: ${e.message}")
            return Result(125, "", "Не удалось запустить команду в Termux: ${e.message}")
        } finally {
            pending.remove(id)
        }
    }

    /** Human-readable cause of a failed run, for the "Проверить" button. */
    fun explain(r: Result): String {
        val e = r.stderr
        return when {
            r.exitCode == 0 && r.stdout.isNotBlank() -> ""
            "bad interpreter" in e -> "Не найден интерпретатор скрипта (bad interpreter): в Termux выполните pkg install nodejs libc++ и переустановите CLI."
            "не установлен" in e || "not found" in e || r.exitCode == 127 -> "Команда не найдена в Termux: CLI не установлен (см. инструкцию)."
            "login" in e.lowercase() || "auth" in e.lowercase() -> "Нужен вход: запустите CLI в Termux и войдите в аккаунт."
            r.exitCode == 126 || r.exitCode == 125 || r.exitCode == 124 -> e.lineSequence().firstOrNull().orEmpty()
            r.exitCode == 0 -> "Пустой ответ."
            else -> "Команда завершилась с кодом ${r.exitCode}."
        }
    }

    internal fun complete(id: Int, bundle: Bundle?) {
        val future = pending[id] ?: return
        if (bundle == null) {
            future.complete(Result(125, "", "Termux вернул пустой результат"))
            return
        }
        val stdout = bundle.getString("stdout").orEmpty()
        var stderr = bundle.getString("stderr").orEmpty()
        var code = bundle.getInt("exitCode", 1)
        val err = bundle.getInt("err", -1)
        if (err != -1) { // Termux itself failed to run the command
            val msg = bundle.getString("errmsg").orEmpty()
            stderr = listOf(stderr, "Termux: $msg").filter { it.isNotBlank() }.joinToString("\n")
            if (code == 0) code = 125
        }
        future.complete(Result(code, stdout, stderr))
    }
}

/** Receives the RUN_COMMAND result through the PendingIntent given to Termux. */
class LlmResultReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_ID = "llm_request_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        LlmBridge.complete(intent.getIntExtra(EXTRA_ID, -1), intent.getBundleExtra("result"))
    }
}
