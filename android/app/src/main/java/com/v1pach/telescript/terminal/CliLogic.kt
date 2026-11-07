package com.v1pach.telescript.terminal

/**
 * Pure logic of the terminal / AI-CLI feature (no Android imports, covered by tools/kotlin_test):
 * the CLI catalog, version parsing, the shell scripts that are sent to Termux, error
 * classification, redaction of secrets and the environment of the built-in shell.
 */
data class CliSpec(
    val id: String,
    val title: String,
    /** npm package installed with `npm install -g`. */
    val npm: String,
    /** Termux package tried first (a native build), or null. */
    val pkg: String?,
    /** Command typed in a Termux session to log in. */
    val login: String,
)

object CliCatalog {
    val all = listOf(
        CliSpec("claude", "Claude Code", "@anthropic-ai/claude-code", null, "claude"),
        CliSpec("codex", "OpenAI Codex", "@openai/codex", "codex", "codex login --device-auth"),
        CliSpec("gemini", "Gemini CLI", "@google/gemini-cli", null, "gemini"),
    )

    fun byId(id: String): CliSpec? = all.firstOrNull { it.id == id }
}

/** Result of the status script. */
data class CliStatus(val installed: Boolean, val path: String = "", val version: String = "")

enum class CliError { NONE, NO_NETWORK, NO_SPACE, PERMISSION, NODE, NOT_FOUND, TIMEOUT, TERMUX, OTHER }

object CliLogic {
    private val SEMVER = Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?""")

    /** First semantic version in [text] ("2.1.3 (Claude Code)", "codex-cli 0.46.0"), or null. */
    fun parseVersion(text: String): String? = SEMVER.find(text)?.value

    /** Compares dotted versions numerically; a pre-release sorts below the same release. */
    fun compareVersions(a: String, b: String): Int {
        fun split(v: String): Pair<List<Int>, String> {
            val core = v.substringBefore('-')
            val pre = v.substringAfter('-', "")
            return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
        }
        val (ca, pa) = split(a)
        val (cb, pb) = split(b)
        for (i in 0 until maxOf(ca.size, cb.size)) {
            val d = (ca.getOrElse(i) { 0 }).compareTo(cb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return when {
            pa == pb -> 0
            pa.isEmpty() -> 1
            pb.isEmpty() -> -1
            else -> pa.compareTo(pb)
        }
    }

    fun isNewer(latest: String, installed: String): Boolean = compareVersions(latest, installed) > 0

    /** Single-quotes [s] for a POSIX shell. */
    fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private val PROXY_OK = Regex("""^https?://[^\s'"`$;&|<>\\]+$""")

    /** An http(s) proxy URL that is safe to put into a shell script; blank is allowed (no proxy). */
    fun validProxy(url: String): Boolean = url.isBlank() || PROXY_OK.matches(url.trim())

    /** Hides `user:password@` in URLs and token-looking strings, so logs and previews carry no secrets. */
    fun redact(text: String): String {
        var t = text.replace(Regex("""(?i)(\b[a-z][a-z0-9+.-]*://)[^\s/@:]+(?::[^\s/@]*)?@"""), "$1***@")
        t = t.replace(Regex("""\bsk-[A-Za-z0-9_-]{16,}"""), "sk-***")
        t = t.replace(Regex("""\b(?:ghp|gho|ghs|github_pat)_[A-Za-z0-9_]{16,}"""), "gh-***")
        t = t.replace(Regex("""\bAIza[0-9A-Za-z_-]{20,}"""), "AIza***")
        t = t.replace(Regex("""(?i)\b(bearer|token|api[_-]?key|authorization)(\s*[=:]\s*)\S{12,}"""), "$1$2***")
        return t
    }

    private fun proxyLines(proxy: String): String {
        val p = proxy.trim()
        if (p.isEmpty() || !validProxy(p)) return ""
        val q = shQuote(p)
        return "export HTTP_PROXY=$q HTTPS_PROXY=$q http_proxy=$q https_proxy=$q; npm config set proxy $q >/dev/null 2>&1; npm config set https-proxy $q >/dev/null 2>&1; "
    }

    /** Shell functions so npm CLIs with a `#!/usr/bin/env node` shebang start inside Termux. */
    const val PRELUDE =
        "__tg() { n=\$1; shift; b=\$(type -P \"\$n\") || { echo \"\$n: command not found\" >&2; return 127; }; " +
        "if head -n1 \"\$b\" | grep -q node; then node \"\$b\" \"\$@\"; else \"\$b\" \"\$@\"; fi; }; "

    /** Prints TGA_PATH=<path> and TGA_VER=<first line of --version>; exit 0 even when missing. */
    fun statusScript(spec: CliSpec): String =
        "bin=\$(command -v ${spec.id}) || { echo TGA_MISSING=1; exit 0; }; echo \"TGA_PATH=\$bin\"; " +
        "v=\$(if head -n1 \"\$bin\" | grep -q node; then node \"\$bin\" --version; else \"\$bin\" --version; fi 2>&1 | head -n 3 | tr '\\n' ' '); " +
        "echo \"TGA_VER=\$v\"; exit 0"

    fun parseStatus(output: String): CliStatus {
        var path = ""
        var ver = ""
        for (line in output.lineSequence()) {
            val l = line.trim()
            if (l.startsWith("TGA_PATH=")) path = l.removePrefix("TGA_PATH=")
            if (l.startsWith("TGA_VER=")) ver = parseVersion(l.removePrefix("TGA_VER=")) ?: l.removePrefix("TGA_VER=").trim()
        }
        return if (path.isEmpty()) CliStatus(false) else CliStatus(true, path, ver)
    }

    fun latestVersionScript(spec: CliSpec): String =
        "command -v npm >/dev/null 2>&1 || { echo TGA_LATEST=; exit 0; }; echo \"TGA_LATEST=\$(npm view ${shQuote(spec.npm)} version 2>/dev/null | tail -n1)\"; exit 0"

    fun parseLatest(output: String): String? =
        output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("TGA_LATEST=") }
            ?.removePrefix("TGA_LATEST=")?.let(::parseVersion)

    /**
     * Installs or updates the CLI: nodejs first (pkg), then the Termux package when the spec has one,
     * otherwise (or on failure) npm. Ends with a verification step that prints TGA_PATH / TGA_VER.
     */
    fun installScript(spec: CliSpec, proxy: String = ""): String {
        val npmCmd = "npm install -g ${shQuote(spec.npm + "@latest")} 2>&1 | tail -n 40; [ \${PIPESTATUS[0]} -eq 0 ]"
        val install = if (spec.pkg != null)
            "{ yes | pkg install -y ${spec.pkg} 2>&1 | tail -n 20; [ \${PIPESTATUS[1]} -eq 0 ] && command -v ${spec.id} >/dev/null 2>&1; } || { echo '[TgAuto] pkg ${spec.pkg} unavailable, using npm'; $npmCmd; }"
        else npmCmd
        return "export DEBIAN_FRONTEND=noninteractive; ${proxyLines(proxy)}" +
            "if ! command -v npm >/dev/null 2>&1; then echo '[TgAuto] installing nodejs'; yes | pkg install -y nodejs 2>&1 | tail -n 20; " +
            "command -v npm >/dev/null 2>&1 || { echo TGA_ERR=node; exit 20; }; fi; " +
            "echo '[TgAuto] installing ${spec.title}'; $install || { echo TGA_ERR=install; exit 21; }; " +
            statusScript(spec).replace("exit 0", "true") + "; " +
            "command -v ${spec.id} >/dev/null 2>&1 || { echo TGA_ERR=verify; exit 22; }; echo TGA_OK=1"
    }

    fun removeScript(spec: CliSpec): String {
        val pkg = if (spec.pkg != null) "pkg uninstall -y ${spec.pkg} 2>&1 | tail -n 10; " else ""
        return "command -v npm >/dev/null 2>&1 && npm uninstall -g ${shQuote(spec.npm)} 2>&1 | tail -n 10; $pkg" +
            "if command -v ${spec.id} >/dev/null 2>&1; then echo TGA_ERR=still; exit 23; else echo TGA_OK=1; fi"
    }

    /** Script for an interactive Termux session: runs the login command, then keeps the shell open. */
    fun loginScript(spec: CliSpec, proxy: String = ""): String {
        val cmd = if (spec.login.startsWith(spec.id)) "__tg " + spec.login else spec.login
        return PRELUDE + proxyLines(proxy).replace(Regex("npm config set[^;]*;\\s*"), "") +
            "$cmd; echo; echo '[TgAuto] session stays open: exit to close'; exec bash -l"
    }

    fun classifyError(output: String, exitCode: Int): CliError {
        val o = output.lowercase()
        return when {
            exitCode == 0 && "tga_err=" !in o -> CliError.NONE
            exitCode == 124 -> CliError.TIMEOUT
            exitCode == 125 || exitCode == 126 -> CliError.TERMUX
            "tga_err=node" in o -> CliError.NODE
            "enotfound" in o || "etimedout" in o || "econnreset" in o || "econnrefused" in o ||
                "could not resolve" in o || "unable to connect" in o || "network is unreachable" in o -> CliError.NO_NETWORK
            "no space left" in o || "enospc" in o -> CliError.NO_SPACE
            "eacces" in o || "permission denied" in o || "eperm" in o -> CliError.PERMISSION
            "tga_err=verify" in o || "tga_err=still" in o -> CliError.NOT_FOUND
            else -> CliError.OTHER
        }
    }

    // --- built-in shell ---------------------------------------------------------------------------

    /** Environment of the built-in /system/bin/sh session as KEY=VALUE strings. */
    fun shellEnv(root: String, cacheDir: String, llmBin: String, scripts: String, nativeLibDir: String): List<String> = listOf(
        "HOME=$root/home",
        "TMPDIR=$cacheDir",
        "PATH=$llmBin:/system/bin:/system/xbin:/vendor/bin",
        "SHELL=/system/bin/sh",
        "TERM=xterm-256color",
        "COLORTERM=truecolor",
        "LANG=en_US.UTF-8",
        "ANDROID_ROOT=/system",
        "ANDROID_DATA=/data",
        "ENV=$root/home/.mkshrc",
        "TGA_ROOT=$root",
        "TGA_SCRIPTS=$scripts",
        "TGA_CONFIG=$root/config",
        "TGA_NATIVE=$nativeLibDir",
    )

    /** Startup file of the interactive built-in shell (mksh reads $ENV). */
    const val MKSHRC =
        "PS1='tga:\$PWD \\\$ '\n" +
        "alias ll='ls -la'\n" +
        "alias la='ls -A'\n" +
        "cdscripts() { cd \"\$TGA_SCRIPTS\"; }\n" +
        "tga-help() { echo 'Built-in shell: Android sh + toybox. Scripts: \$TGA_SCRIPTS (cdscripts). claude/gemini/codex run through Termux.'; }\n"

    // --- ANSI-free helpers for the CLI log --------------------------------------------------------

    private val ANSI = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]|\u001B\][^\u0007]*\u0007""")
    fun stripAnsi(s: String): String = ANSI.replace(s, "")
}

/**
 * Who may open the terminal. Only the user's own tap in the app grants it (a short-lived grant that the
 * activity consumes), so no intent, script, store package or Telegram message can start a session.
 */
object TerminalPolicy {
    private const val GRANT_MS = 15_000L
    private var grantedAt = 0L

    /** Called from the Settings button handler only. */
    @Synchronized fun grant(now: Long) { grantedAt = now }

    /** Single use: true when a grant exists and is fresh. */
    @Synchronized fun consume(now: Long): Boolean {
        val ok = grantedAt != 0L && now - grantedAt in 0..GRANT_MS
        grantedAt = 0L
        return ok
    }

    /** Re-ask for the device credential after the app was in the background this long. */
    const val RELOCK_MS = 60_000L

    fun needsUnlock(lockEnabled: Boolean, deviceSecure: Boolean, lastActive: Long, now: Long, unlocked: Boolean): Boolean {
        if (!lockEnabled || !deviceSecure) return false
        return !unlocked || now - lastActive > RELOCK_MS
    }
}
