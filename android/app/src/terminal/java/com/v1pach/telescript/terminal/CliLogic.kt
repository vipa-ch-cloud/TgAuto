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
    /** Command typed in a shell tab to log in. */
    val login: String,
)

object CliCatalog {
    val all = listOf(
        CliSpec("claude", "Claude Code", "@anthropic-ai/claude-code", "claude"),
        CliSpec("codex", "OpenAI Codex", "@openai/codex", "codex login --device-auth"),
        CliSpec("gemini", "Gemini CLI", "@google/gemini-cli", "gemini"),
    )

    fun byId(id: String): CliSpec? = all.firstOrNull { it.id == id }
}

/** Result of the status script. */
data class CliStatus(val installed: Boolean, val path: String = "", val version: String = "")

enum class CliError { NONE, NO_NETWORK, NO_SPACE, PERMISSION, NODE, NOT_FOUND, TIMEOUT, USERLAND, OTHER }

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
        return "export HTTP_PROXY=$q HTTPS_PROXY=$q http_proxy=$q https_proxy=$q; "
    }

    /** Prints TGA_PATH=<path> and TGA_VER=<first line of --version>; exit 0 even when missing. */
    fun statusScript(spec: CliSpec): String =
        "bin=\$(tga_cli_path ${spec.id}) || { echo TGA_MISSING=1; exit 0; }; echo \"TGA_PATH=\$bin\"; " +
        "v=\$(${spec.id} --version 2>&1 | head -n 3 | tr '\\n' ' '); echo \"TGA_VER=\$v\"; exit 0"

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
        "echo \"TGA_LATEST=\$(npm view ${shQuote(spec.npm)} version 2>/dev/null | tail -n1)\"; exit 0"

    fun parseLatest(output: String): String? =
        output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("TGA_LATEST=") }
            ?.removePrefix("TGA_LATEST=")?.let(::parseVersion)

    /** Installs or updates the CLI with the userland's npm; ends with a verification step. */
    fun installScript(spec: CliSpec, proxy: String = ""): String =
        "${proxyLines(proxy)}command -v node >/dev/null 2>&1 || { echo TGA_ERR=node; exit 20; }; " +
        "echo '[TgAuto] installing ${spec.title}'; " +
        "npm install -g ${shQuote(spec.npm + "@latest")} 2>&1 | tail -n 40; [ \${PIPESTATUS[0]} -eq 0 ] || { echo TGA_ERR=install; exit 21; }; " +
        statusScript(spec).replace("exit 0", "true") + "; " +
        "tga_cli_path ${spec.id} >/dev/null || { echo TGA_ERR=verify; exit 22; }; echo TGA_OK=1"

    fun removeScript(spec: CliSpec): String =
        "npm uninstall -g ${shQuote(spec.npm)} 2>&1 | tail -n 10; " +
        "if tga_cli_path ${spec.id} >/dev/null; then echo TGA_ERR=still; exit 23; else echo TGA_OK=1; fi"

    /** Line typed into a shell tab to log in (the user answers the prompts there; no token passes through TgAuto). */
    fun loginCommand(spec: CliSpec): String = spec.login

    fun classifyError(output: String, exitCode: Int): CliError {
        val o = output.lowercase()
        return when {
            exitCode == 0 && "tga_err=" !in o -> CliError.NONE
            exitCode == 124 -> CliError.TIMEOUT
            exitCode == 125 || exitCode == 126 || exitCode == 127 -> CliError.USERLAND
            "tga_err=node" in o -> CliError.NODE
            "enotfound" in o || "etimedout" in o || "econnreset" in o || "econnrefused" in o ||
                "could not resolve" in o || "unable to connect" in o || "network is unreachable" in o -> CliError.NO_NETWORK
            "no space left" in o || "enospc" in o -> CliError.NO_SPACE
            "eacces" in o || "permission denied" in o || "eperm" in o -> CliError.PERMISSION
            "tga_err=verify" in o || "tga_err=still" in o -> CliError.NOT_FOUND
            else -> CliError.OTHER
        }
    }

    // --- embedded userland shell ------------------------------------------------------------------

    /** Environment of every process in the embedded Termux userland as KEY=VALUE strings. */
    fun userlandEnv(root: String, usr: String, cacheDir: String, nativeLibDir: String, scripts: String, proxy: String = ""): List<String> {
        val cert = "$usr/etc/tls/cert.pem"
        val l = mutableListOf(
            "HOME=$root/home",
            "PREFIX=$usr",
            "TMPDIR=$cacheDir",
            "PATH=$usr/bin:/system/bin:/system/xbin:/vendor/bin",
            "SHELL=$usr/bin/bash",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=en_US.UTF-8",
            "LD_LIBRARY_PATH=$nativeLibDir",
            "ANDROID_ROOT=/system",
            "ANDROID_DATA=/data",
            "PYTHONHOME=$usr",
            "PYTHONDONTWRITEBYTECODE=1",
            "SSL_CERT_FILE=$cert",
            "CURL_CA_BUNDLE=$cert",
            "GIT_SSL_CAINFO=$cert",
            "GIT_EXEC_PATH=$usr/libexec/git-core",
            "GIT_TEMPLATE_DIR=$usr/share/git-core/templates",
            "GIT_CONFIG_NOSYSTEM=1",
            "NPM_CONFIG_PREFIX=$usr",
            "NPM_CONFIG_SCRIPT_SHELL=$usr/bin/bash",
            "NPM_CONFIG_UPDATE_NOTIFIER=false",
            "NODE_EXTRA_CA_CERTS=$cert",
            "TGA_ROOT=$root",
            "TGA_SCRIPTS=$scripts",
            "TGA_CONFIG=$root/config",
            "TGA_NATIVE=$nativeLibDir",
        )
        val p = proxy.trim()
        if (p.isNotEmpty() && validProxy(p)) l += listOf("HTTP_PROXY=$p", "HTTPS_PROXY=$p", "http_proxy=$p", "https_proxy=$p")
        return l
    }

    /**
     * Sourced by every script and interactive shell. Android forbids exec() of files in app data (W^X), so
     * npm-installed commands (node scripts) are started through `node <file>` by shell functions.
     */
    const val TGA_ENV =
        "tga_cli_path() { p=\"\$PREFIX/bin/\$1\"; [ -e \"\$p\" ] || return 1; readlink -f \"\$p\"; }\n" +
        "tga_run() { n=\$1; shift; f=\$(tga_cli_path \"\$n\") || { echo \"\$n: not installed (AI-CLI tab)\" >&2; return 127; }\n" +
        "  if [ \"\$(head -c 2 \"\$f\")\" = '#!' ]; then node \"\$f\" \"\$@\"; else /system/bin/linker64 \"\$f\" \"\$@\"; fi; }\n" +
        "npm() { node \"\$PREFIX/lib/node_modules/npm/bin/npm-cli.js\" \"\$@\"; }\n" +
        "npx() { node \"\$PREFIX/lib/node_modules/npm/bin/npx-cli.js\" \"\$@\"; }\n" +
        "pip() { python -m pip \"\$@\"; }\n" +
        "pip3() { python -m pip \"\$@\"; }\n" +
        "claude() { tga_run claude \"\$@\"; }\n" +
        "codex() { tga_run codex \"\$@\"; }\n" +
        "gemini() { tga_run gemini \"\$@\"; }\n"

    /** --rcfile of the interactive bash session. */
    const val BASHRC =
        "PS1='\\[\\e[1;32m\\]tga\\[\\e[0m\\]:\\w \\\$ '\n" +
        ". \"\$PREFIX/etc/tga-env.sh\"\n" +
        "alias ll='ls -la'\n" +
        "alias la='ls -A'\n" +
        "cdscripts() { cd \"\$TGA_SCRIPTS\"; }\n" +
        "tga-help() { echo 'bash/busybox/git/curl/python/pip/node/npm are built in. claude, codex, gemini: AI-CLI tab. Scripts: \$TGA_SCRIPTS (cdscripts).'; }\n"

    // --- ANSI-free helpers for the CLI log --------------------------------------------------------

    private val ANSI = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]|\u001B\][^\u0007]*\u0007""")
    fun stripAnsi(s: String): String = ANSI.replace(s, "")
}
