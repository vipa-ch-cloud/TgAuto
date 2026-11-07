package com.v1pach.telescript

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** One OpenAI-compatible endpoint. The key lives only in the app's private file. */
data class Provider(
    val id: String,
    val kind: String = "openai",
    val title: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val headers: Map<String, String> = emptyMap(),
    val timeout: Int = 60,
    /** Optional JSON object merged into the request body by scripts (kept as text). */
    val extraBody: String = "",
)

object Providers {
    val presets = listOf(
        Provider("", "openai", "DeepSeek", "https://api.deepseek.com", "", "deepseek-chat"),
        Provider("", "openai", "OpenAI", "https://api.openai.com/v1", "", "gpt-4o-mini"),
        Provider("", "openai", "Свой API", "", "", ""),
    )

    private lateinit var file: File
    private val _list = MutableStateFlow<List<Provider>>(emptyList())
    val list: StateFlow<List<Provider>> get() = _list

    /** Path exported to scripts as TELESCRIPT_LLM_PROVIDERS_FILE. */
    val path: String get() = file.absolutePath

    fun init(context: Context) {
        file = File(context.filesDir, "llm_providers.json")
        _list.value = load()
        if (file.exists()) restrict(file)
    }

    fun newId() = UUID.randomUUID().toString().take(8)

    fun upsert(p: Provider) {
        val cur = _list.value
        _list.value = if (cur.any { it.id == p.id }) cur.map { if (it.id == p.id) p else it } else cur + p
        save()
    }

    fun delete(id: String) { _list.value = _list.value.filter { it.id != id }; save() }

    fun move(id: String, delta: Int) {
        val cur = _list.value.toMutableList()
        val i = cur.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in cur.indices) return
        cur.add(j, cur.removeAt(i))
        _list.value = cur
        save()
    }

    private fun load(): List<Provider> = try {
        val arr = JSONObject(file.readText()).optJSONArray("providers") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val h = o.optJSONObject("headers")
            Provider(o.getString("id"), o.optString("kind", "openai"), o.optString("title"),
                o.optString("base_url"), o.optString("api_key"), o.optString("model"),
                h?.keys()?.asSequence()?.associateWith { h.optString(it) } ?: emptyMap(),
                o.optInt("timeout", 60), o.optJSONObject("extra_body")?.toString() ?: "")
        }
    } catch (e: Exception) { emptyList() }

    private fun restrict(f: File) {
        f.setReadable(false, false); f.setWritable(false, false); f.setExecutable(false, false)
        f.setReadable(true, true); f.setWritable(true, true)
    }

    private fun toJson(list: List<Provider>, order: List<String>): JSONObject {
        val root = JSONObject()
        root.put("providers", JSONArray(list.map { p ->
            JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title)
                .put("base_url", p.baseUrl).put("api_key", p.apiKey).put("model", p.model)
                .put("headers", JSONObject(p.headers)).put("timeout", p.timeout)
                .also { o -> if (p.extraBody.isNotBlank()) runCatching { o.put("extra_body", JSONObject(p.extraBody)) } }
        }))
        root.put("order", JSONArray(order))
        return root
    }

    /** Writes [root] to [target] without ever leaving the key world-readable. */
    private fun writePrivate(target: File, root: JSONObject) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText("")           // create empty, restrict, then write: the key is never world-readable
        restrict(tmp)
        tmp.writeText(root.toString())
        tmp.renameTo(target)
        restrict(target)
    }

    @Synchronized
    private fun save() {
        val list = _list.value
        writePrivate(file, toJson(list, list.map { it.id }))
    }

    /**
     * Same format as the main file, but "order" is [ids] (ids not in the list are dropped).
     * Nightly's AI backend selection hands this file to scripts instead of the main one.
     */
    @Synchronized
    internal fun writeEffective(ids: List<String>): String {
        val list = _list.value
        val known = list.map { it.id }.toSet()
        val target = File(file.parentFile, "llm_providers.effective.json")
        writePrivate(target, toJson(list, ids.filter { it in known }))
        return target.absolutePath
    }

    class Result(val ok: Boolean, val text: String)

    private fun open(p: Provider, path: String, method: String): HttpURLConnection {
        val base = p.baseUrl.trim().trimEnd('/')
        val c = URL("$base$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = p.timeout.coerceIn(5, 120) * 1000
        c.readTimeout = p.timeout.coerceIn(5, 120) * 1000
        if (p.apiKey.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
        c.setRequestProperty("Accept", "application/json")
        p.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }

    private fun readBody(c: HttpURLConnection): String =
        (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""

    /** Short chat completion. Never returns or logs the key. */
    fun test(p: Provider): Result = try {
        val c = open(p, "/chat/completions", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("model", p.model).put("max_tokens", 16)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ответь одним словом: ок")))
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = readBody(c)
        if (code in 200..299) {
            val reply = try { JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content") } catch (e: Exception) { "" }
            Result(true, reply.trim().take(200).ifEmpty { Str.get(R.string.prov_empty_reply) })
        } else Result(false, "HTTP $code: " + text.replace(p.apiKey.ifBlank { "\u0000" }, "***").take(300))
    } catch (e: Exception) { Result(false, e.javaClass.simpleName + ": " + (e.message ?: "").take(200)) }

    fun listModels(p: Provider): Pair<List<String>, String?> = try {
        val c = open(p, "/models", "GET")
        val code = c.responseCode
        val text = readBody(c)
        if (code !in 200..299) emptyList<String>() to "HTTP $code"
        else {
            val arr = JSONObject(text).getJSONArray("data")
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }.filter { it.isNotEmpty() }.sorted() to null
        }
    } catch (e: Exception) { emptyList<String>() to (e.javaClass.simpleName + ": " + (e.message ?: "").take(150)) }
}
