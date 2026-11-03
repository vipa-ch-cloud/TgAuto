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
    fun setup(enabled: Boolean) {
        val link = File(Paths.llmBin, "claude")
        if (!enabled) {
            link.delete()
            return
        }
        Paths.llmBin.mkdirs()
        Paths.llmSpool.mkdirs()
        val target = File(app.applicationInfo.nativeLibraryDir, "libclaude.so").absolutePath
        val current = runCatching { android.system.Os.readlink(link.absolutePath) }.getOrNull()
        if (current != target) {
            link.delete()
            android.system.Os.symlink(target, link.absolutePath)
        }
        startPoller()
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
            run(args, stdin)
        } catch (e: Throwable) {
            Result(127, "", "TeleScript: ${e.message}")
        }
        if (!dir.isDirectory) return // the script gave up (timeout) and removed it
        runCatching {
            File(dir, "stdout").writeText(result.stdout)
            File(dir, "stderr").writeText(result.stderr)
            File(dir, "exit").writeText(result.exitCode.toString())
            File(dir, "done").createNewFile()
        }
    }

    /** Runs `claude <args>` in Termux with [stdin] as its standard input. Blocking. */
    fun run(args: List<String>, stdin: String, timeoutS: Long = TIMEOUT_S): Result {
        if (!termuxInstalled()) return Result(127, "", "Termux не установлен")
        if (!hasPermission()) return Result(126, "", "Нет разрешения «Выполнение команд в Termux»")
        val id = nextId.incrementAndGet()
        val future = CompletableFuture<Result>()
        pending[id] = future
        try {
            val cmd = AppSettings.termuxCommand.value.trim().ifEmpty { "claude" }
            // An empty working directory, as automod's own sandbox; $CMD is split on spaces
            // so a wrapper such as `proot-distro login ubuntu -- claude` works too.
            val script = "mkdir -p ~/.telescript-sandbox && cd ~/.telescript-sandbox && exec $cmd \"\$@\""
            val resultIntent = Intent(app, LlmResultReceiver::class.java).putExtra(LlmResultReceiver.EXTRA_ID, id)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
            val pi = PendingIntent.getBroadcast(app, id, resultIntent, flags)
            val intent = Intent("com.termux.RUN_COMMAND")
                .setClassName(TERMUX, "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", (listOf("-c", script, "claude") + args).toTypedArray())
                .putExtra("com.termux.RUN_COMMAND_STDIN", stdin)
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "TeleScript: claude")
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
