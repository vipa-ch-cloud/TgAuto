package com.v1pach.telescript

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Per-script settings form. A script folder may hold `config.schema.json`:
 *   { "file": "profile.json", "note": "...", "fields": [ {section}, {key, title, type, default, ...} ] }
 * `key` is a dotted path inside the JSON file (`file`, per field or at the top level) that lives
 * in the script's config dir. Types: bool, int, float, string, text, select (options), list (of strings).
 */
sealed interface CfgItem
data class CfgSection(val title: String) : CfgItem
data class CfgField(
    val key: String,
    val file: String,
    val title: String,
    val description: String,
    val type: String,
    val default: Any?,
    val min: Double?,
    val max: Double?,
    val options: List<Pair<String, String>>,
) : CfgItem

data class ConfigSchema(val note: String, val items: List<CfgItem>) {
    val fields get() = items.filterIsInstance<CfgField>()
}

/** Editable state of one field. [dirty] fields are the only ones written on save. */
class FieldForm(val field: CfgField) {
    var text by mutableStateOf("")
    var flag by mutableStateOf(false)
    val items = mutableStateListOf<String>()
    var dirty by mutableStateOf(false)
    /** Select value present in the file but not among the options (e.g. a list); kept until changed. */
    var foreign by mutableStateOf("")

    /** Parsed value or an error text. Only meaningful for dirty fields. */
    fun parse(): Pair<Any?, String?> {
        val f = field
        return when (f.type) {
            "bool" -> flag to null
            "int" -> {
                val n = text.trim().toLongOrNull() ?: return null to "Нужно целое число"
                rangeError(n.toDouble())?.let { return null to it }
                n to null
            }
            "float" -> {
                val n = text.trim().replace(',', '.').toDoubleOrNull()
                if (n == null || n.isNaN() || n.isInfinite()) return null to "Нужно число"
                rangeError(n)?.let { return null to it }
                n to null
            }
            "select" -> {
                if (f.options.none { it.first == text }) null to "Выберите значение" else text to null
            }
            "list" -> JSONArray(items.map { it.trim() }.filter { it.isNotEmpty() }) to null
            "text" -> text to null
            else -> text.trim() to null
        }
    }

    private fun rangeError(n: Double): String? {
        field.min?.let { if (n < it) return "Не меньше ${fmt(it)}" }
        field.max?.let { if (n > it) return "Не больше ${fmt(it)}" }
        return null
    }

    fun setDefault() {
        val d = field.default
        flag = (d as? Boolean) ?: false
        foreign = ""
        text = when {
            d == null || d == JSONObject.NULL -> ""
            d is Number -> fmt(d.toDouble())
            d is JSONArray || d is Boolean -> ""
            else -> d.toString()
        }
        items.clear()
        if (d is JSONArray) for (i in 0 until d.length()) items.add(d.optString(i))
        dirty = true
    }

    fun load(root: JSONObject?) {
        val raw = root?.let { lookup(it, field.key) }
        if (raw == null) { setDefault(); dirty = false; return }
        foreign = ""
        flag = false; text = ""; items.clear()
        when (field.type) {
            "bool" -> flag = raw as? Boolean ?: ((field.default as? Boolean) ?: false)
            "int", "float" -> text = (raw as? Number)?.let { fmt(it.toDouble()) } ?: field.default?.let { fmt((it as Number).toDouble()) }.orEmpty()
            "list" -> when (raw) {
                is JSONArray -> for (i in 0 until raw.length()) items.add(raw.optString(i))
                is String -> items.add(raw)
                else -> {}
            }
            "select" -> {
                val s = raw as? String
                if (s != null && field.options.any { it.first == s }) text = s
                else { foreign = raw.toString(); text = "" }
            }
            else -> text = if (raw == JSONObject.NULL) "" else raw.toString()
        }
        dirty = false
    }
}

private fun fmt(d: Double): String = if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

private fun lookup(root: JSONObject, path: String): Any? {
    var cur: Any? = root
    for (part in path.split('.')) {
        cur = (cur as? JSONObject)?.takeIf { it.has(part) }?.get(part) ?: return null
    }
    return cur
}

object ConfigStore {
    /** Secrets never appear in this UI, whatever a schema asks for. */
    private val SECRET = Regex("api_?key|api_?hash|token|secret|session|password|passwd|credential", RegexOption.IGNORE_CASE)
    private val FORBIDDEN_FILES = setOf("api.json", "proxy.json")
    private val TYPES = setOf("bool", "int", "float", "string", "text", "select", "list")

    fun parse(text: String): ConfigSchema? = runCatching {
        val root = JSONObject(text)
        val topFile = root.optString("file", "")
        val arr = root.getJSONArray("fields")
        val items = ArrayList<CfgItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!o.has("key")) {
                o.optString("section").takeIf { it.isNotBlank() }?.let { items += CfgSection(it) }
                continue
            }
            val key = o.getString("key")
            val type = o.optString("type", "string")
            val file = o.optString("file", topFile)
            if (type !in TYPES || key.isBlank() || key.split('.').any { it.isBlank() }) continue
            if (SECRET.containsMatchIn(key) || !validFile(file)) continue
            val opts = o.optJSONArray("options")?.let { a ->
                List(a.length()) { j ->
                    val e = a.get(j)
                    if (e is JSONObject) e.getString("value") to e.optString("title", e.getString("value"))
                    else e.toString() to e.toString()
                }
            }.orEmpty()
            if (type == "select" && opts.isEmpty()) continue
            items += CfgField(key, file, o.optString("title", key), o.optString("description", ""), type,
                if (o.has("default")) o.get("default") else null,
                if (o.has("min")) o.getDouble("min") else null,
                if (o.has("max")) o.getDouble("max") else null, opts)
        }
        val schema = ConfigSchema(root.optString("note", ""), items)
        if (schema.fields.isEmpty()) null else schema
    }.getOrNull()

    private fun validFile(name: String): Boolean =
        name.isNotEmpty() && name.endsWith(".json") && !name.startsWith("/") && !name.startsWith(".") &&
            name.split('/').none { it.isEmpty() || it == ".." || it == "." } && name !in FORBIDDEN_FILES

    /** The file inside the script's config dir, or null when the path would escape it. */
    fun fileOf(configDir: File, name: String): File? {
        if (!validFile(name)) return null
        val base = configDir.canonicalFile
        val f = File(base, name).canonicalFile
        return f.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    /** Reads every file the schema touches. A missing file is {}; null for a broken one (path in the message). */
    fun readAll(configDir: File, schema: ConfigSchema): Pair<Map<String, JSONObject>, String?> {
        val out = HashMap<String, JSONObject>()
        for (name in schema.fields.map { it.file }.distinct()) {
            val f = fileOf(configDir, name) ?: return out to "Недопустимый файл настроек: $name"
            if (!f.isFile) { out[name] = JSONObject(); continue }
            out[name] = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
                ?: return out to "Файл $name повреждён, сохранение отключено"
        }
        return out to null
    }

    /**
     * Writes the given values into their files: only these keys change, everything else stays.
     * Nothing is written unless every value fits; each file goes through a temp file and an atomic rename.
     */
    fun save(configDir: File, values: List<Pair<CfgField, Any?>>): String? {
        val byFile = values.groupBy { it.first.file }
        val docs = HashMap<String, JSONObject>()
        for ((name, entries) in byFile) {
            val f = fileOf(configDir, name) ?: return "Недопустимый файл настроек: $name"
            val doc = if (f.isFile) {
                runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
                    ?: return "Файл $name повреждён, сохранение отключено"
            } else JSONObject()
            for ((field, value) in entries) {
                if (!put(doc, field.key.split('.'), value)) return "${field.title}: в файле $name «${field.key}» вложено в не-объект"
            }
            docs[name] = doc
        }
        for ((name, doc) in docs) {
            val f = fileOf(configDir, name)!!
            runCatching {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, ".${f.name}.tmp")
                FileOutputStream(tmp).use { it.write(doc.toString(2).toByteArray(Charsets.UTF_8)); it.fd.sync() }
                try {
                    Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (e: Exception) {
                    Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }.onFailure { return "Не удалось записать $name: ${it.message}" }
        }
        return null
    }

    private fun put(doc: JSONObject, path: List<String>, value: Any?): Boolean {
        var cur = doc
        for (part in path.dropLast(1)) {
            val next = if (cur.has(part)) cur.get(part) else JSONObject().also { cur.put(part, it) }
            cur = next as? JSONObject ?: return false
        }
        cur.put(path.last(), value ?: JSONObject.NULL)
        return true
    }
}
