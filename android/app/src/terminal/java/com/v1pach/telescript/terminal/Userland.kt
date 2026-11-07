package com.v1pach.telescript.terminal

import android.content.Context
import android.os.Build
import android.system.Os
import com.v1pach.telescript.Paths
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Embedded Termux userland (arm64 only). Native binaries live in nativeLibraryDir as lib*.so (the only
 * place Android lets an app exec from); filesDir/usr is a symlink farm onto them plus the data files
 * (python stdlib, npm, CA certificates) unpacked from assets/userland-arm64.zip. See docs/TERMINAL.md.
 */
object Userland {
    private const val ASSET = "userland-arm64.zip"

    fun supported(context: Context): Boolean =
        Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" &&
            runCatching { context.assets.open(ASSET).close(); true }.getOrDefault(false)

    fun prefix(context: Context): File = File(context.filesDir, "usr")
    fun bash(context: Context): File = File(prefix(context), "bin/bash")

    private fun stamp(context: Context) = File(prefix(context), ".stamp")
    private fun wantStamp(context: Context): String {
        val v = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)
        return "data=$v\nlibs=${context.applicationInfo.nativeLibraryDir}\n"
    }

    fun ready(context: Context): Boolean = supported(context) && stamp(context).takeIf { it.exists() }?.readText() == wantStamp(context)

    /** Unpacks the data files (once per app version) and (re)creates the links into nativeLibraryDir. */
    @Synchronized
    fun ensure(context: Context, progress: (String) -> Unit = {}): Boolean {
        if (!supported(context)) return false
        val usr = prefix(context)
        val want = wantStamp(context)
        val have = stamp(context).takeIf { it.exists() }?.readText()
        if (have == want) return true
        val dataSame = have?.lineSequence()?.firstOrNull() == want.lineSequence().first() && File(usr, ".manifest").exists()
        return runCatching {
            usr.mkdirs()
            if (!dataSame) {
                progress("unpack")
                unpack(context, usr)
            }
            progress("link")
            link(context, usr)
            writeEnvFiles(context)
            stamp(context).writeText(want)
            true
        }.getOrElse { lastError = it.toString(); false }
    }

    @Volatile var lastError: String? = null

    private fun unpack(context: Context, usr: File) {
        val symlinks = ArrayList<Pair<File, String>>()
        ZipInputStream(context.assets.open(ASSET).buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                when {
                    name.startsWith("files/") -> {
                        val f = File(usr, name.removePrefix("files/"))
                        f.parentFile?.mkdirs()
                        if (f.exists() || isLink(f)) f.delete()
                        f.outputStream().use { zin.copyTo(it) }
                        f.setExecutable(true, false)
                    }
                    name.startsWith("symlinks/") -> symlinks.add(File(usr, name.removePrefix("symlinks/")) to zin.readBytes().decodeToString())
                    name == "manifest.txt" -> File(usr, ".manifest").also { it.parentFile?.mkdirs() }.writeBytes(zin.readBytes())
                }
            }
        }
        for ((f, target) in symlinks) replaceLink(f, target)
    }

    private fun isLink(f: File): Boolean = runCatching { Os.lstat(f.path); true }.getOrDefault(false)

    private fun replaceLink(f: File, target: String) {
        f.parentFile?.mkdirs()
        if (isLink(f)) f.delete()
        Os.symlink(target, f.path)
    }

    /** rel path -> name of the lib*.so in nativeLibraryDir. */
    fun readManifest(text: String): Map<String, String> =
        text.lineSequence().filter { '\t' in it }.associate { it.substringBefore('\t') to it.substringAfter('\t').trim() }

    private fun link(context: Context, usr: File) {
        val native = context.applicationInfo.nativeLibraryDir
        val manifest = readManifest(File(usr, ".manifest").readText())
        for ((rel, jni) in manifest) replaceLink(File(usr, rel), "$native/$jni")
        // busybox applets that no other package provides
        val bb = manifest["bin/busybox"]
        if (bb != null) {
            val r = runCatching {
                val p = ProcessBuilder("$native/$bb", "--list").redirectErrorStream(true)
                p.environment()["LD_LIBRARY_PATH"] = native
                val proc = p.start()
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                out.lines().map { it.trim() }.filter { it.isNotEmpty() }
            }.getOrDefault(emptyList())
            for (a in r) {
                val f = File(usr, "bin/$a")
                if (!isLink(f)) replaceLink(f, "$native/$bb")
            }
        }
        manifest["bin/bash"]?.let { replaceLink(File(usr, "bin/sh"), "$native/$it") }
        File(usr, "tmp").mkdirs()
    }

    private fun writeEnvFiles(context: Context) {
        val etc = File(prefix(context), "etc").also { it.mkdirs() }
        File(etc, "tga-env.sh").writeText(CliLogic.TGA_ENV)
        File(etc, "tga-bashrc").writeText(CliLogic.BASHRC)
        File(Paths.root, "home").mkdirs()
    }

    /** Environment of every process started in the userland. */
    fun env(context: Context, proxy: String = ""): List<String> {
        val usr = prefix(context).absolutePath
        return CliLogic.userlandEnv(
            root = Paths.root.absolutePath, usr = usr, cacheDir = context.cacheDir.absolutePath,
            nativeLibDir = context.applicationInfo.nativeLibraryDir, scripts = Paths.mirror.absolutePath, proxy = proxy,
        )
    }

    data class Result(val exit: Int, val output: String)

    /** Runs a bash script in the userland (no tty), merging stdout and stderr; [onLine] gets live output. */
    fun run(context: Context, script: String, timeoutSec: Long = 900, proxy: String = "", onProc: (Process) -> Unit = {}, onLine: (String) -> Unit = {}): Result {
        if (!ready(context) && !ensure(context)) return Result(127, "userland not available")
        val pb = ProcessBuilder(bash(context).path, "-c", ". \"\$PREFIX/etc/tga-env.sh\"; $script")
            .directory(File(Paths.root, "home").also { it.mkdirs() }).redirectErrorStream(true)
        pb.environment().clear()
        for (kv in env(context, proxy)) pb.environment()[kv.substringBefore('=')] = kv.substringAfter('=')
        return runCatching {
            val p = pb.start()
            onProc(p)
            val sb = StringBuilder()
            val reader = Thread { p.inputStream.bufferedReader().forEachLine { sb.appendLine(it); onLine(it) } }.apply { isDaemon = true; start() }
            val done = p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) { p.destroyForcibly(); reader.join(2000); return Result(124, sb.toString()) }
            reader.join(3000)
            Result(p.exitValue(), sb.toString())
        }.getOrElse { Result(126, it.message.orEmpty()) }
    }
}
