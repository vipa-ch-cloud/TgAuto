import com.v1pach.telescript.terminal.*

private var fails = 0
private fun check(name: String, ok: Boolean) { if (!ok) { fails++; println("FAIL $name") } else println("ok   $name") }

fun main() {
    check("parse plain", CliLogic.parseVersion("1.2.3") == "1.2.3")
    check("parse banner", CliLogic.parseVersion("codex-cli 0.46.0 (build x)") == "0.46.0")
    check("parse claude", CliLogic.parseVersion("2.0.14 (Claude Code)") == "2.0.14")
    check("parse prerelease", CliLogic.parseVersion("v1.0.0-beta.2") == "1.0.0-beta.2")
    check("parse none", CliLogic.parseVersion("command not found") == null)
    check("cmp lt", CliLogic.compareVersions("1.2.3", "1.10.0") < 0)
    check("cmp eq", CliLogic.compareVersions("1.2", "1.2.0") == 0)
    check("cmp pre", CliLogic.compareVersions("1.0.0-beta", "1.0.0") < 0)
    check("newer", CliLogic.isNewer("2.0.0", "1.9.9"))

    check("proxy ok", CliLogic.validProxy("http://127.0.0.1:8080"))
    check("proxy blank", CliLogic.validProxy(""))
    check("proxy inj", !CliLogic.validProxy("http://a;rm -rf /"))
    check("proxy quote", !CliLogic.validProxy("http://a'b"))
    check("proxy scheme", !CliLogic.validProxy("socks5://a:1"))

    check("quote", CliLogic.shQuote("a'b") == "'a'\\''b'")
    val claude = CliCatalog.byId("claude")!!
    val inst = CliLogic.installScript(claude, "")
    check("install npm pkg", "@anthropic-ai/claude-code@latest" in inst)
    check("install verifies", "TGA_OK=1" in inst && "TGA_ERR=" in inst)
    check("install no proxy", "HTTP_PROXY" !in inst)
    check("install proxy", "HTTPS_PROXY='http://h:1'" in CliLogic.installScript(claude, "http://h:1"))
    check("install bad proxy dropped", "evil" !in CliLogic.installScript(claude, "http://h;evil"))
    check("codex pkg first", "pkg install" in CliLogic.installScript(CliCatalog.byId("codex")!!, ""))
    check("remove", "npm uninstall -g" in CliLogic.removeScript(claude))
    check("login uses wrapper", "__tg claude" in CliLogic.loginScript(claude))
    check("login codex", "codex login" in CliLogic.loginScript(CliCatalog.byId("codex")!!))

    check("status ok", CliLogic.parseStatus("TGA_PATH=/x/claude\nTGA_VER=2.0.14 (Claude Code) \n") == CliStatus(true, "/x/claude", "2.0.14"))
    check("status missing", !CliLogic.parseStatus("TGA_MISSING=1").installed)
    check("latest", CliLogic.parseLatest("noise\nTGA_LATEST=3.1.4\n") == "3.1.4")
    check("latest empty", CliLogic.parseLatest("TGA_LATEST=\n") == null)

    check("err ok", CliLogic.classifyError("done TGA_OK=1", 0) == CliError.NONE)
    check("err net", CliLogic.classifyError("npm ERR! code ENOTFOUND", 1) == CliError.NO_NETWORK)
    check("err space", CliLogic.classifyError("ENOSPC: no space left", 1) == CliError.NO_SPACE)
    check("err perm", CliLogic.classifyError("EACCES", 1) == CliError.PERMISSION)
    check("err node", CliLogic.classifyError("TGA_ERR=node", 20) == CliError.NODE)
    check("err verify", CliLogic.classifyError("TGA_ERR=verify", 22) == CliError.NOT_FOUND)
    check("err timeout", CliLogic.classifyError("", 124) == CliError.TIMEOUT)
    check("err other", CliLogic.classifyError("boom", 3) == CliError.OTHER)

    val r = CliLogic.redact("proxy http://user:pa55@h:1 key sk-abcdefghijklmnopqrstuv token=abcdefghijklmnop")
    check("redact url", "pa55" !in r && "user" !in r)
    check("redact sk", "abcdefghijklmnopqrstuv" !in r)
    check("redact token", "abcdefghijklmnop" !in r.replace("sk-***", ""))
    check("ansi", CliLogic.stripAnsi("\u001B[31mred\u001B[0m") == "red")

    val env = CliLogic.shellEnv("/r", "/c", "/r/bin", "/r/s", "/n")
    check("env home", "HOME=/r/home" in env)
    check("env path first", env.first { it.startsWith("PATH=") }.startsWith("PATH=/r/bin:/system/bin"))
    check("env scripts", "TGA_SCRIPTS=/r/s" in env)

    check("grant none", !TerminalPolicy.consume(1000))
    TerminalPolicy.grant(1000)
    check("grant fresh", TerminalPolicy.consume(2000))
    check("grant single use", !TerminalPolicy.consume(2001))
    TerminalPolicy.grant(1000)
    check("grant stale", !TerminalPolicy.consume(1000 + 20_000))
    check("unlock first", TerminalPolicy.needsUnlock(true, true, 0, 10, false))
    check("unlock fresh", !TerminalPolicy.needsUnlock(true, true, 0, 10, true))
    check("unlock relock", TerminalPolicy.needsUnlock(true, true, 0, 70_000, true))
    check("unlock off", !TerminalPolicy.needsUnlock(false, true, 0, 10, false))
    check("unlock insecure", !TerminalPolicy.needsUnlock(true, false, 0, 10, false))

    if (fails > 0) { println("$fails FAILED"); kotlin.system.exitProcess(1) }
    println("ALL OK")
}
