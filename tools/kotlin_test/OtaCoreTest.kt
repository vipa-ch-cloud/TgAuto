import com.v1pach.telescript.ota.*
import com.v1pach.telescript.ui.CrtPalette
import com.v1pach.telescript.ota.OtaException
import com.v1pach.telescript.store.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Plain-JVM test of the production OtaCore/Ed25519 against fixtures made by gen_fixtures.py. */
var failed = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) failed++ }
fun code2(block: () -> Any?): String = try { block(); "ok" } catch (e: IllegalArgumentException) { e.message ?: "" }
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
    // ---- store metadata / sources (1.9.1) ----
    check("script keyless: signature ignored", v("signed.tgascript").signed && OtaCore.verify(File("$d/signed.tgascript"), null, "1.9.0", 20, "stable").let { !it.signed })
    check("ota needs key", code { OtaCore.verify(File("$d/ok.tgadyn"), null, "1.9.0", 20, "stable") } == "unsigned")
    check("icon", StoreMeta.iconKey("Shield") == "shield" && StoreMeta.iconKey("bogus") == "script" && StoreMeta.iconKey(null) == "script")
    check("color", StoreMeta.normColor("#3f7d2a") == "#3F7D2A" && StoreMeta.normColor("abc") == "#AABBCC" && StoreMeta.normColor("red") == null && StoreMeta.normColor("") == null)
    check("contrast", StoreMeta.darkTextOn(0xFFFF00) && !StoreMeta.darkTextOn(0x0F6B7A) && !StoreMeta.darkTextOn(0x3F7D2A) && StoreMeta.darkTextOn(0xFFFFFF))
    check("urls/dates", StoreMeta.safeUrl("javascript:alert(1)") == null && StoreMeta.safeUrl("https://x.y/z") != null && StoreMeta.dateOnly("2026-11-07T10:00:00Z") == "2026-11-07" && StoreMeta.dateOnly("yesterday") == null)
    val old = ModuleMeta.from(JSONObject("{}"))
    check("old catalog fallback", old.title(true, "automod") == "automod" && old.icon == "script" && old.color == null)
    val nm = ModuleMeta.from(JSONObject("""{"title_ru":"Автомодератор","title_en":"Auto Moderator","icon":"shield","color":"#3F7D2A","repo":"https://github.com/a/b","published":"2026-11-07"}"""))
    check("titles", nm.title(true, "x") == "Автомодератор" && nm.title(false, "x") == "Auto Moderator" && nm.icon == "shield")
    val sha = "a".repeat(64)
    val arr = JSONArray("""[{"id":"s1","name":"S","version":"1.0","sha256":"$sha","url":"https://x/y.tgascript","verified":true,"official":true},
        {"id":"BAD ID","version":"1.0","sha256":"$sha","url":"https://x"},{"id":"s2","version":"1.0","sha256":"zz","url":"https://x"}]""")
    val off = StoreSource(StoreMeta.OFFICIAL_ID, "o", "https://o", "00")
    val com = StoreMeta.newSource("c", "https://c.example/index.json", "")
    val eo = StoreMeta.parseEntries(arr, off); val ec = StoreMeta.parseEntries(arr, com)
    check("entries filtered", eo.size == 1 && ec.size == 1)
    check("official keeps labels", eo[0].verified && eo[0].official)
    check("community never verified", !ec[0].verified && !ec[0].official && ec[0].sourceId == com.id)
    check("source input", code2 { StoreMeta.newSource("", "http://x/i.json", "") } == "url" && code2 { StoreMeta.newSource("", "https://x/i.json", "12") } == "key" && com.keyHex == null && com.name == "c" && StoreMeta.newSource("", "https://c.example/i.json", "").name == "c.example")
    val rt = StoreMeta.parseSources(StoreMeta.serializeSources(listOf(off, com, StoreMeta.newSource("k", "https://k/i.json", "AB".repeat(32)))))
    check("sources roundtrip", rt.size == 2 && rt[1].keyHex == "ab".repeat(32) && rt.none { it.official })
    check("sources garbage", StoreMeta.parseSources("not json").isEmpty())
    // --- version comparison ---
    check("vcmp newer", OtaCore.vcmp("1.9.2", "1.9.1") > 0 && OtaCore.vcmp("1.10.0", "1.9.9") > 0)
    check("vcmp equal/older", OtaCore.vcmp("1.9.2", "1.9.2") == 0 && OtaCore.vcmp("1.9.1", "1.9.2") < 0)
    // --- community keys ---
    val comm = OtaCore.hex(File("$d/comm.hex").readText().trim())
    check("community rejected without trust", code { OtaCore.verify(File("$d/community.tgadyn"), pub, "1.9.0", 20, "stable") } == "bad_signature")
    val cp = OtaCore.verify(File("$d/community.tgadyn"), pub, "1.9.0", 20, "stable", extraKeys = listOf(comm))
    check("community accepted when trusted, not official", cp.communityKey != null && !cp.official && cp.signed)
    check("official stays official with extra keys", OtaCore.verify(File("$d/ok.tgadyn"), pub, "1.9.0", 20, "stable", extraKeys = listOf(comm)).official)
    check("other community key still rejected", code { OtaCore.verify(File("$d/community.tgadyn"), pub, "1.9.0", 20, "stable", extraKeys = listOf(ByteArray(32) { 7 })) } == "bad_signature")
    val hexComm = comm.joinToString("") { "%02x".format(it) }
    val b64 = java.util.Base64.getEncoder().encodeToString(comm)
    check("parseKey hex/b64/prefix", TrustedKeys.parseKey(hexComm)!!.contentEquals(comm) && TrustedKeys.parseKey(b64)!!.contentEquals(comm) &&
        TrustedKeys.parseKey("ed25519:" + hexComm.uppercase())!!.contentEquals(comm) && TrustedKeys.parseKey("zz") == null && TrustedKeys.parseKey("abcd") == null)
    fun addCode(l: List<TrustedKey>, n: String, k: String) = try { TrustedKeys.add(l, n, k, pub); "ok" } catch (e: IllegalArgumentException) { e.message }
    check("add official refused", addCode(emptyList(), "x", pub.joinToString("") { "%02x".format(it) }) == "official")
    check("add bad/name/dup", addCode(emptyList(), "x", "nope") == "key" && addCode(emptyList(), " ", hexComm) == "name" &&
        addCode(TrustedKeys.add(emptyList(), "a", hexComm, pub), "b", b64) == "dup")
    val lst = TrustedKeys.add(emptyList(), "Alice", hexComm, pub)
    check("serialize roundtrip + fingerprint", TrustedKeys.parse(TrustedKeys.serialize(lst)).let { it.size == 1 && it[0].name == "Alice" && it[0].fingerprint == OtaCore.sha256(comm) } &&
        TrustedKeys.parse("garbage").isEmpty() && TrustedKeys.remove(lst, hexComm).isEmpty())
    // --- APK update rules ---
    check("apk asset names", ApkCore.versionOf("TgAuto-1.9.2.apk", "stable") == "1.9.2" && ApkCore.versionOf("TgAuto-nightly-1.9.2.apk", "stable") == null &&
        ApkCore.versionOf("TgAuto-nightly-1.9.2.apk", "nightly") == "1.9.2" && ApkCore.versionOf("TgAuto-1.9.2.apk", "nightly") == null)
    val rel = JSONObject("""{"tag_name":"v1.9.2","assets":[{"name":"TgAuto-1.9.2.apk","browser_download_url":"https://x/a.apk","size":5,"digest":"sha256:${"a".repeat(64)}"},
        {"name":"TgAuto-nightly-1.9.2.apk","browser_download_url":"https://x/n.apk","size":6},{"name":"TgAuto-1.9.3.apk","browser_download_url":"http://x/insecure.apk","size":6}]}""")
    check("pick stable newer + digest", ApkCore.pick(rel, "stable", "1.9.1")?.let { it.version == "1.9.2" && it.sha256 == "a".repeat(64) } == true)
    check("pick nightly", ApkCore.pick(rel, "nightly", "1.9.1")?.name == "TgAuto-nightly-1.9.2.apk")
    check("pick none when current", ApkCore.pick(rel, "stable", "1.9.2") == null)
    check("checksum parse", ApkCore.parseChecksum("${"B".repeat(64)}  TgAuto-1.9.2.apk\n", "TgAuto-1.9.2.apk") == "b".repeat(64))
    // --- signing certificate match ---
    check("certs equal", ApkCore.certsMatch(setOf("AA:BB"), setOf("aabb")))
    check("certs differ/empty/subset", !ApkCore.certsMatch(setOf("aabb"), setOf("ccdd")) && !ApkCore.certsMatch(emptySet(), emptySet()) && !ApkCore.certsMatch(setOf("aa", "bb"), setOf("aa")))
    fun idc(pk: String?, cn: Long, ci: Set<String>, cnw: Set<String>) = code { ApkCore.checkIdentity("a.b", pk, 21, cn, ci, cnw) }
    check("identity ok", idc("a.b", 22, setOf("aa"), setOf("aa")) == "ok")
    check("identity wrong package/downgrade/cert", idc("a.b.nightly", 22, setOf("aa"), setOf("aa")) == "apk_package" && idc("a.b", 21, setOf("aa"), setOf("aa")) == "apk_not_newer" && idc("a.b", 22, setOf("aa"), setOf("bb")) == "apk_cert")
    // --- CRT palette ---
    val seeds = listOf(0xFF6750A4.toInt(), 0xFF00BCD4.toInt(), 0xFFFFEB3B.toInt(), 0xFF4CAF50.toInt(), 0xFFFF0000.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF8D6E63.toInt())
    var palOk = true; var screenDark = true; var hier = true; var errWarn = true
    for (s in seeds) for (dk in listOf(true, false)) {
        val p = CrtPalette.from(s, 0xFFB3261E.toInt(), dk)
        for (fg in listOf(p.text, p.dim, p.prompt, p.error, p.warning)) for (bg in listOf(p.screen, p.panel))
            if (CrtPalette.contrast(fg, bg) < 4.5) palOk = false
        if (CrtPalette.luminance(p.screen) > 0.02) screenDark = false
        if (!(CrtPalette.luminance(p.screen) < CrtPalette.luminance(p.panel) && CrtPalette.luminance(p.panel) < CrtPalette.luminance(p.text))) hier = false
        val eh = CrtPalette.toHsl(p.error).first; val wh = CrtPalette.toHsl(p.warning).first
        if (!(eh < 15f || eh > 335f) || kotlin.math.abs(wh - 40f) > 5f || p.error == p.warning || p.error == p.text) errWarn = false
    }
    check("crt contrast >= 4.5 for all seeds/themes", palOk)
    check("crt screen clearly darker than panels, text lightest", screenDark && hier)
    check("crt error reddish, warning amber, distinct", errWarn)
    val pa = CrtPalette.from(0xFF3F51B5.toInt(), 0xFFB3261E.toInt(), true); val pb = CrtPalette.from(0xFFE91E63.toInt(), 0xFFB3261E.toInt(), true)
    check("crt follows accent", pa.screen != pb.screen && pa.glow != pb.glow && pa.scanline != pb.scanline && (pa.vignette ushr 24) > 0)
    check("crt hsl roundtrip", CrtPalette.toHsl(CrtPalette.hsl(210f, 0.5f, 0.4f)).let { kotlin.math.abs(it.first - 210f) < 1.5f && kotlin.math.abs(it.second - 0.5f) < 0.02f })
    args.getOrNull(1)?.let { File(it) }?.takeIf { it.exists() }?.let { f ->
        val e = StoreMeta.parseEntries(JSONObject(f.readText()).getJSONArray("scripts"), off)
        check("real catalog", e.isNotEmpty() && e.all { it.meta.color != null && it.meta.icon in StoreMeta.ICONS && it.meta.title(true, "").isNotEmpty() })
    }
    println(if (failed == 0) "ALL OK" else "$failed FAILED")
    if (failed > 0) System.exit(1)
}
