package com.v1pach.telescript.store

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Pure (JDK + org.json) store metadata: display fields, catalog entries, sources, colour helpers.
 * No Android classes, so tools/kotlin_test can exercise it on a plain JVM.
 */

/** Optional display fields shared by index.json entries and an installed package's manifest. All lenient. */
data class ModuleMeta(
    val titleRu: String = "", val titleEn: String = "", val icon: String = "script",
    val color: String? = null, val repo: String? = null, val published: String? = null, val homepage: String? = null,
) {
    fun title(ru: Boolean, fallback: String): String =
        (if (ru) titleRu.ifBlank { titleEn } else titleEn.ifBlank { titleRu }).ifBlank { fallback }

    companion object {
        fun from(o: JSONObject) = ModuleMeta(
            StoreMeta.text(o, "title_ru", 80), StoreMeta.text(o, "title_en", 80), StoreMeta.iconKey(o.optString("icon")),
            StoreMeta.normColor(o.optString("color")), StoreMeta.safeUrl(o.optString("repo")),
            StoreMeta.dateOnly(o.optString("published")), StoreMeta.safeUrl(o.optString("homepage")))
    }
}

/** A catalog source. The official one is built in; user sources never get verified/official labels. */
data class StoreSource(val id: String, val name: String, val url: String, val keyHex: String?) {
    val official: Boolean get() = id == StoreMeta.OFFICIAL_ID
    val hasKey: Boolean get() = !keyHex.isNullOrEmpty()
}

/** One catalog entry from index.json. verified/official can be true only for the official source. */
data class StoreEntry(
    val id: String, val name: String, val author: String, val version: String,
    val descEn: String, val descRu: String, val changelogEn: String, val changelogRu: String,
    val tags: List<String>, val permissions: JSONObject, val sha256: String, val size: Long, val url: String,
    val verified: Boolean, val official: Boolean,
    val meta: ModuleMeta = ModuleMeta(), val sourceId: String = StoreMeta.OFFICIAL_ID,
) {
    fun desc(ru: Boolean) = (if (ru) descRu else descEn).ifBlank { descEn }
    fun changelog(ru: Boolean) = (if (ru) changelogRu else changelogEn).ifBlank { changelogEn }
    fun title(ru: Boolean) = meta.title(ru, name)
}

object StoreMeta {
    const val OFFICIAL_ID = "official"
    const val MAX_ENTRIES = 500
    const val MAX_SOURCES = 20
    val ICONS = setOf("shield", "chat_reply", "bot", "script", "tool", "star", "bell", "lock")
    private val ID_RE = Regex("[a-z0-9_]{1,40}")
    private val VER_RE = Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,39}")
    private val SHA_RE = Regex("[0-9a-fA-F]{64}")
    private val COLOR_RE = Regex("#?([0-9a-fA-F]{6}|[0-9a-fA-F]{3})")
    private val DATE_RE = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val KEY_RE = Regex("[0-9a-fA-F]{64}")

    fun text(o: JSONObject, key: String, max: Int): String = o.optString(key).trim().take(max)

    fun iconKey(s: String?): String = s?.trim()?.lowercase()?.takeIf { it in ICONS } ?: "script"

    /** "#rrggbb" / "rrggbb" / "#rgb" -> "#RRGGBB", otherwise null (theme colour is used). */
    fun normColor(s: String?): String? {
        val m = COLOR_RE.matchEntire(s?.trim().orEmpty()) ?: return null
        var h = m.groupValues[1]
        if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
        return "#" + h.uppercase()
    }

    /** Colour as 0xRRGGBB int, or null. */
    fun rgb(s: String?): Int? = normColor(s)?.substring(1)?.toInt(16)

    private fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
    fun luminance(rgb: Int): Double =
        0.2126 * lin(rgb shr 16 and 255) + 0.7152 * lin(rgb shr 8 and 255) + 0.0722 * lin(rgb and 255)

    /** True when dark text reads better than white on this background (higher WCAG contrast wins). */
    fun darkTextOn(rgb: Int): Boolean {
        val l = luminance(rgb)
        val withWhite = 1.05 / (l + 0.05)
        val withBlack = (l + 0.05) / (luminance(0x14141A) + 0.05)
        return withBlack >= withWhite
    }

    /** Only http(s) links are kept (they are opened in the browser, never fetched). */
    fun safeUrl(s: String?): String? = s?.trim()?.takeIf {
        it.length <= 300 && (it.startsWith("https://") || it.startsWith("http://")) && it.none { c -> c.isWhitespace() }
    }

    /** "2026-11-07" or "2026-11-07T12:00:00Z" -> "2026-11-07". */
    fun dateOnly(s: String?): String? = s?.trim()?.take(10)?.takeIf { DATE_RE.matches(it) }

    fun validKey(hex: String): Boolean = KEY_RE.matches(hex.trim())

    private fun sha256Hex(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Validates user input for a new source. Returns the source, or throws IllegalArgumentException(code). */
    fun newSource(name: String, url: String, keyHex: String): StoreSource {
        val u = url.trim()
        require(u.startsWith("https://") && u.length <= 300 && u.none { it.isWhitespace() }) { "url" }
        val k = keyHex.trim().lowercase()
        require(k.isEmpty() || validKey(k)) { "key" }
        val n = name.trim().ifEmpty { u.removePrefix("https://").substringBefore('/') }.take(40)
        return StoreSource("u" + sha256Hex(u).take(10), n, u, k.ifEmpty { null })
    }

    fun parseSources(json: String): List<StoreSource> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            runCatching { newSource(o.optString("name"), o.getString("url"), o.optString("key")) }.getOrNull()
        }.distinctBy { it.id }.take(MAX_SOURCES)
    }.getOrDefault(emptyList())

    fun serializeSources(list: List<StoreSource>): String = JSONArray().also { a ->
        list.filter { !it.official }.forEach { s ->
            a.put(JSONObject().put("name", s.name).put("url", s.url).put("key", s.keyHex ?: ""))
        }
    }.toString()

    /**
     * Parses and sanitises an index.json "scripts" array. Entries from any source other than the official
     * one are forced to verified=false/official=false whatever the index claims. Bad entries are skipped.
     */
    fun parseEntries(arr: JSONArray, source: StoreSource): List<StoreEntry> {
        val out = ArrayList<StoreEntry>()
        for (i in 0 until minOf(arr.length(), MAX_ENTRIES)) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching {
                val id = o.getString("id"); val ver = o.getString("version")
                val sha = o.getString("sha256"); val url = o.getString("url")
                require(ID_RE.matches(id) && VER_RE.matches(ver) && SHA_RE.matches(sha) && url.startsWith("https://"))
                val trusted = source.official
                out += StoreEntry(id, text(o, "name", 80).ifEmpty { id }, text(o, "author", 80), ver,
                    text(o, "description_en", 2000), text(o, "description_ru", 2000),
                    text(o, "changelog_en", 4000), text(o, "changelog_ru", 4000),
                    o.optJSONArray("tags")?.let { a -> List(minOf(a.length(), 20)) { a.optString(it).take(30) } } ?: emptyList(),
                    o.optJSONObject("permissions") ?: JSONObject(), sha.lowercase(), o.optLong("size"), url,
                    trusted && o.optBoolean("verified"), trusted && o.optBoolean("official"),
                    ModuleMeta.from(o), source.id)
            }
        }
        return out
    }
}
