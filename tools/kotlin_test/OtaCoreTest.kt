import com.v1pach.telescript.ota.OtaCore
import com.v1pach.telescript.ota.OtaException
import java.io.File

/** Plain-JVM test of the production OtaCore/Ed25519 against fixtures made by gen_fixtures.py. */
var failed = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) failed++ }
fun code(block: () -> Any?): String = try { block(); "ok" } catch (e: OtaException) { e.code }

fun main(args: Array<String>) {
    val d = args[0]
    val pub = OtaCore.hex(File("$d/pub.hex").readText().trim())
    fun v(n: String, base: String = "1.9.0", code: Int = 20, flavor: String = "stable") =
        OtaCore.verify(File("$d/$n"), pub, base, code, flavor)
    check("ok ota", code { v("ok.tgadyn") } == "ok")
    check("ota payload", String(v("ok.tgadyn").files["python/x.py"]!!) == "A = 1\n")
    check("tampered", code { v("tampered.tgadyn") } == "hash_mismatch")
    check("wrong key", code { v("wrongkey.tgadyn") } == "bad_signature")
    check("unsigned", code { v("unsigned.tgadyn") } == "unsigned")
    check("wrong base", code { v("wrongbase.tgadyn") } == "wrong_base")
    check("base too old", code { v("ok.tgadyn", base = "1.8.1") } == "wrong_base")
    check("code too old", code { v("ok.tgadyn", code = 19) } == "wrong_base")
    check("channel", code { v("nightly.tgadyn") } == "wrong_channel" && code { v("nightly.tgadyn", flavor = "nightly") } == "ok")
    check("traversal", code { v("traversal.tgadyn") } == "bad_path")
    check("unlisted", code { v("unlisted.tgadyn") } == "unlisted_file")
    check("zip bomb", code { v("bomb.tgadyn") } == "zip_bomb")
    check("not zip", code { v("notzip.tgadyn") } == "not_zip")
    check("script unsigned", v("ok.tgascript").let { !it.signed && it.isScript })
    check("script signed", v("signed.tgascript").signed)
    check("script bad sig", code { v("badsig.tgascript") } == "bad_signature")
    check("peek", OtaCore.peekFormat(File("$d/ok.tgascript")) == "tgascript/1" && OtaCore.peekFormat(File("$d/notzip.tgadyn")) == null)
    val idx = File("$d/index.json").readBytes()
    check("index sig", OtaCore.verifyIndex(idx, File("$d/index.json.sig").readBytes(), pub))
    check("index tampered", !OtaCore.verifyIndex(idx + " ".toByteArray(), File("$d/index.json.sig").readBytes(), pub))
    check("vcmp", OtaCore.vcmp("1.10.0", "1.9.9") > 0 && OtaCore.vcmp("1.9", "1.9.0") == 0 && OtaCore.vcmp("0.0.1-test", "0.0.2") < 0)
    println(if (failed == 0) "ALL OK" else "$failed FAILED")
    if (failed > 0) System.exit(1)
}
