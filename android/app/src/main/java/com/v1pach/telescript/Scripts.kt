package com.v1pach.telescript

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Immutable
data class ScriptInfo(
    val id: String,
    val title: String,
    val description: String,
    /** Path shown on the card, relative to the chosen folder (e.g. automod/automod.py). */
    val relPath: String,
    /** Folder whose files are synced for this script, relative to the chosen folder ("" = root). */
    val scopeRel: String,
    val scopeDocId: String,
    /** Package scripts sync their whole subfolder; loose .py files sync only their folder's files. */
    val recursive: Boolean,
    val entry: File,
    val args: List<String>,
    val configFile: File,
    val configDir: File,
    val dataDir: File,
    /** Document id of config.schema.json in the script's folder (package scripts only), "" if none. */
    val schemaDocId: String = "",
)

enum class RunStatus { IDLE, STARTING, RUNNING, STOPPING, ERROR }

@Immutable
data class ScriptState(
    val status: RunStatus = RunStatus.IDLE,
    val message: String = "",
    val startedAt: Long = 0L,
    /** PyPI package suggested after a ModuleNotFoundError, else "". */
    val missingPackage: String = "",
)

object Paths {
    lateinit var root: File
    val scripts get() = File(root, "scripts")
    val config get() = File(root, "config")
    val data get() = File(root, "data")
    /** Main Telegram session (path without the .session suffix, as Telethon expects). */
    val mirror get() = File(scripts, "tree")
    val syncState get() = File(scripts, "sync")
    val session get() = File(data, "account")
    val sessionFile get() = File(data, "account.session")
    /** Libraries downloaded at runtime by ts_pip; first on sys.path for every script. */
    val sitePackages get() = File(root, "site-packages-user")
    /** Where the `claude` shim is linked: on PATH and at automod's ~/.local/bin fallback. */
    val llmBin get() = File(root, ".local/bin")
    /** Requests from the shim to LlmBridge (the shim finds it as $0/../spool). */
    val llmSpool get() = File(root, ".local/spool")
}

/**
 * Scripts live in a folder on phone storage chosen through SAF. Python can't read
 * content:// URIs, so a script's files are mirrored (changed files only) into
 * filesDir/scripts/tree/<relative path> right before it starts.
 */
object ScriptRepository {
    private lateinit var app: Context
    private val _scripts = MutableStateFlow<List<ScriptInfo>>(emptyList())
    val scripts: StateFlow<List<ScriptInfo>> = _scripts
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    /** Problem with the folder (no folder, revoked permission, provider error); "" when fine. */
    private val _problem = MutableStateFlow("")
    val problem: StateFlow<String> = _problem

    private const val MAX_FILE = 20L * 1024 * 1024
    private val LOOSE_EXT = setOf("py", "json", "txt", "md", "yaml", "yml", "toml", "csv", "ini", "cfg")

    private data class Doc(val id: String, val name: String, val mime: String, val mtime: Long, val size: Long) {
        val isDir get() = mime == Document.MIME_TYPE_DIR
    }

    fun init(context: Context) {
        app = context.applicationContext
        Paths.root = app.filesDir
        Paths.config.mkdirs()
        Paths.data.mkdirs()
        Paths.mirror.mkdirs()
        Paths.syncState.mkdirs()
    }

    fun treeUri(): Uri? = AppSettings.scriptsTree.value.takeIf { it.isNotEmpty() }?.let(Uri::parse)

    fun hasAccess(): Boolean {
        val tree = treeUri() ?: return false
        return app.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
    }

    fun canWrite(): Boolean {
        val tree = treeUri() ?: return false
        return app.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
    }

    /** Stores the folder picked with ACTION_OPEN_DOCUMENT_TREE. */
    fun setFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val resolver = app.contentResolver
        try {
            resolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        treeUri()?.takeIf { it != uri }?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(old, flags) }
        }
        AppSettings.scriptsTree.set(uri.toString())
    }

    fun folderLabel(): String {
        val tree = treeUri() ?: return ""
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
        val (volume, path) = id.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val base = if (volume == "primary") Str.get(R.string.rep_phone_storage) else volume
        return if (path.isEmpty()) base else "$base/$path"
    }

    private fun list(tree: Uri, docId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)
        val out = ArrayList<Doc>()
        app.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Doc(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "",
                    if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
            }
        }
        return out
    }

    private fun skipped(name: String) = name.startsWith(".") || name == "__pycache__" ||
        (name.startsWith("test_") && name.endsWith(".py")) || name.endsWith(".pyc") ||
        name.endsWith(".session") || name.endsWith(".session-journal")

    private fun readSmall(tree: Uri, doc: Doc): String? = if (doc.size > 64 * 1024) null else runCatching {
        app.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, doc.id))
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    /** Rescans the folder; call from a background thread. */
    fun scan() {
        if (!::app.isInitialized) return
        val tree = treeUri()
        if (tree == null) {
            _scripts.value = emptyList(); _problem.value = Str.get(R.string.rep_no_folder); return
        }
        if (!hasAccess()) {
            _scripts.value = emptyList(); _problem.value = Str.get(R.string.rep_access_revoked); return
        }
        _scanning.value = true
        try {
            val found = ArrayList<ScriptInfo>()
            walk(tree, DocumentsContract.getTreeDocumentId(tree), "", 0, found)
            val ids = HashSet<String>()
            _scripts.value = found.map { s ->
                var id = s.id
                var n = 2
                while (!ids.add(id)) id = "${s.id}_${n++}"
                if (id == s.id) s else withId(s, id)
            }.sortedBy { it.relPath.lowercase() }
            _problem.value = ""
        } catch (e: Exception) {
            _problem.value = Str.get(R.string.rep_read_fail, e.message.orEmpty())
            LogBus.e("app", _problem.value)
        } finally {
            _scanning.value = false
        }
    }

    private fun walk(tree: Uri, docId: String, rel: String, depth: Int, out: MutableList<ScriptInfo>) {
        if (depth > 8) return
        val children = list(tree, docId).filterNot { skipped(it.name) }
        for (child in children.sortedBy { it.name }) {
            val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
            if (child.isDir) {
                val inner = list(tree, child.id).filterNot { skipped(it.name) }
                val entry = inner.firstOrNull { !it.isDir && it.name == "${child.name}.py" }
                    ?: inner.firstOrNull { !it.isDir && it.name == "main.py" }
                if (entry != null) {
                    out += packageScript(tree, child, childRel, entry, inner)
                } else {
                    walk(tree, child.id, childRel, depth + 1, out)
                }
            } else if (child.name.endsWith(".py")) {
                val base = child.name.removeSuffix(".py")
                out += make(id = sanitize(if (rel.isEmpty()) base else "$rel/$base"), title = base,
                    description = docstring(readSmall(tree, child)), relPath = childRel,
                    scopeRel = rel, scopeDocId = docId, recursive = false, entryRel = childRel, manifest = JSONObject())
            }
        }
    }

    private fun packageScript(tree: Uri, dir: Doc, rel: String, entry: Doc, inner: List<Doc>): ScriptInfo {
        val manifest = inner.firstOrNull { it.name == "telescript.json" }?.let { readSmall(tree, it) }
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val entryName = manifest.optString("entry", entry.name)
        val readme = inner.firstOrNull { it.name.equals("README.md", true) }?.let { readSmall(tree, it) }
        return make(id = sanitize(dir.name), title = manifest.optString("title", dir.name),
            description = manifest.optString("description", "").ifBlank {
                readme?.let(::readmeSummary) ?: docstring(readSmall(tree, entry))
            },
            relPath = "$rel/$entryName", scopeRel = rel, scopeDocId = dir.id, recursive = true,
            entryRel = "$rel/$entryName", manifest = manifest,
            schemaDocId = inner.firstOrNull { !it.isDir && it.name == "config.schema.json" }?.id.orEmpty())
    }

    private fun make(id: String, title: String, description: String, relPath: String, scopeRel: String,
                     scopeDocId: String, recursive: Boolean, entryRel: String, manifest: JSONObject,
                     schemaDocId: String = ""): ScriptInfo {
        val args = manifest.optJSONArray("args")?.let { a -> List(a.length()) { a.getString(it) } } ?: listOf("run")
        val configDir = File(Paths.config, id)
        return ScriptInfo(id, title, description, relPath, scopeRel, scopeDocId, recursive,
            File(Paths.mirror, entryRel), args, File(configDir, manifest.optString("config", "$id.json")),
            configDir, File(Paths.data, id), schemaDocId)
    }

    private fun withId(s: ScriptInfo, id: String): ScriptInfo {
        val configDir = File(Paths.config, id)
        return s.copy(id = id, configDir = configDir, dataDir = File(Paths.data, id),
            configFile = File(configDir, s.configFile.name.let { if (it == "${s.id}.json") "$id.json" else it }))
    }

    /** Text of the script's config.schema.json read straight from the chosen folder, or null. */
    fun readSchema(script: ScriptInfo): String? {
        val tree = treeUri() ?: return null
        if (script.schemaDocId.isEmpty() || !hasAccess()) return null
        return runCatching {
            app.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, script.schemaDocId))
                ?.use { it.readBytes().take(256 * 1024).toByteArray().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    private fun sanitize(name: String) = name.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').ifEmpty { "script" }

    fun byId(id: String) = _scripts.value.firstOrNull { it.id == id }

    private fun readmeSummary(text: String): String {
        val paragraph = text.lines()
            .dropWhile { it.isBlank() || it.startsWith("#") }
            .takeWhile { it.isNotBlank() && !it.startsWith("#") }
            .joinToString(" ") { it.trim() }
            .replace(Regex("[`*_]"), "")
        return if (paragraph.length > 220) paragraph.take(217).trimEnd() + "…" else paragraph
    }

    private fun docstring(text: String?): String {
        text ?: return ""
        val m = Regex("^\\s*(?:#[^\\n]*\\n\\s*)*(?:\"\"\"|''')\\s*([^\\n]+)").find(text) ?: return ""
        return m.groupValues[1].trim().removeSuffix("\"\"\"").removeSuffix("'''").take(220)
    }

    /**
     * Mirrors the script's files into app storage, copying only files whose
     * mtime/size changed since the last sync and deleting ones removed from the folder.
     */
    fun sync(script: ScriptInfo) {
        val tree = treeUri() ?: throw IllegalStateException(Str.get(R.string.rep_ex_no_folder))
        if (!hasAccess()) throw IllegalStateException(Str.get(R.string.rep_ex_revoked))
        val stateFile = File(Paths.syncState, "${script.id}.json")
        val old = runCatching { JSONObject(stateFile.readText()) }.getOrDefault(JSONObject())
        val now = JSONObject()
        var copied = 0
        fun visit(docId: String, rel: String, depth: Int) {
            for (doc in list(tree, docId)) {
                if (skipped(doc.name)) continue
                val childRel = if (rel.isEmpty()) doc.name else "$rel/${doc.name}"
                if (doc.isDir) {
                    if (script.recursive && depth < 8) visit(doc.id, childRel, depth + 1)
                    continue
                }
                if (!script.recursive && doc.name.substringAfterLast('.', "").lowercase() !in LOOSE_EXT) continue
                if (doc.size > MAX_FILE) {
                    LogBus.w(script.id, Str.get(R.string.rep_big_file, childRel, doc.size / 1024 / 1024))
                    continue
                }
                val stamp = "${doc.mtime}:${doc.size}"
                val target = File(Paths.mirror, childRel)
                now.put(childRel, stamp)
                if (old.optString(childRel) == stamp && target.isFile) continue
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, ".${target.name}.part")
                app.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, doc.id))?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                } ?: throw IllegalStateException(Str.get(R.string.rep_ex_open, childRel))
                if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
                copied++
            }
        }
        visit(script.scopeDocId, script.scopeRel, 0)
        var removed = 0
        old.keys().forEach { rel -> if (!now.has(rel) && File(Paths.mirror, rel).delete()) removed++ }
        stateFile.writeText(now.toString())
        if (!script.entry.isFile) throw IllegalStateException(Str.get(R.string.rep_ex_missing, script.relPath))
        LogBus.emit(script.id, 3, Str.get(R.string.rep_sync, copied, removed, now.length()))
    }
}


/** Runs scripts on dedicated threads through ts_host; state is observed by the UI and the service. */
object ScriptManager {
    private val _states = MutableStateFlow<Map<String, ScriptState>>(emptyMap())
    val states: StateFlow<Map<String, ScriptState>> = _states

    fun state(id: String) = _states.value[id] ?: ScriptState()
    fun runningIds() = _states.value.filterValues {
        it.status == RunStatus.RUNNING || it.status == RunStatus.STARTING || it.status == RunStatus.STOPPING
    }.keys

    private fun set(id: String, state: ScriptState) = _states.update { it + (id to state) }

    /** Returns an error message if the script cannot start, else null. */
    fun preflight(): String? = when {
        !AppSettings.hasApiCredentials() -> Str.get(R.string.rep_need_api)
        !Paths.sessionFile.isFile -> Str.get(R.string.rep_need_login)
        !ScriptRepository.hasAccess() -> Str.get(R.string.rep_no_access)
        else -> null
    }

    fun start(script: ScriptInfo) {
        val current = state(script.id).status
        if (current == RunStatus.RUNNING || current == RunStatus.STARTING || current == RunStatus.STOPPING) return
        set(script.id, ScriptState(RunStatus.STARTING, startedAt = System.currentTimeMillis()))
        Thread({ runBlocking(script) }, "script-${script.id}").start()
    }

    private fun runBlocking(script: ScriptInfo) {
        val result = try {
            ScriptRepository.sync(script)
            val env = prepare(script)
            set(script.id, state(script.id).copy(status = RunStatus.RUNNING))
            Py.json(Py.host.callAttr("run_script", script.id, script.entry.absolutePath,
                script.entry.parentFile!!.absolutePath, env.toString(), JSONArray(script.args).toString()))
        } catch (e: Throwable) {
            LogBus.e(script.id, Str.get(R.string.rep_start_fail, e.message.orEmpty()))
            JSONObject().put("status", "error").put("message", e.message ?: e.toString())
        }
        val error = result.optString("status") == "error"
        set(script.id, ScriptState(if (error) RunStatus.ERROR else RunStatus.IDLE,
            message = if (error) result.optString("message") else "",
            missingPackage = result.optString("package")))
    }

    fun stop(id: String) {
        val current = state(id).status
        if (current != RunStatus.RUNNING && current != RunStatus.STARTING) return
        set(id, state(id).copy(status = RunStatus.STOPPING))
        Thread({
            try {
                Py.host.callAttr("stop_script", id)
            } catch (e: Throwable) {
                LogBus.e(id, Str.get(R.string.rep_stop_fail, e.message.orEmpty()))
            }
        }, "stop-$id").start()
    }

    fun stopAll() = runningIds().forEach(::stop)

    /** Builds the script environment and per-script files (session copy, proxy.json, model). */
    private fun prepare(script: ScriptInfo): JSONObject {
        script.configDir.mkdirs()
        script.dataDir.mkdirs()
        // Each script gets its own copy of the main session: SQLite sessions can't be shared live.
        val session = File(script.dataDir, "account")
        Paths.sessionFile.copyTo(File(script.dataDir, "account.session"), overwrite = true)
        File(script.dataDir, "account.session-journal").delete()

        val proxy = AppSettings.proxyJson()
        val proxyFile = File(script.configDir, "proxy.json")
        if (proxy.isEmpty()) proxyFile.delete() else proxyFile.writeText(proxy)

        val model = AppSettings.model.value.trim()
        if (model.isNotEmpty() && script.configFile.isFile) {
            runCatching {
                val config = JSONObject(script.configFile.readText())
                if (config.optString("model") != model) {
                    config.put("model", model)
                    script.configFile.writeText(config.toString(2))
                }
            }
        }

        val values = linkedMapOf(
            "CONFIG_DIR" to script.configDir.absolutePath,
            "DATA_DIR" to script.dataDir.absolutePath,
            "SESSION" to session.absolutePath,
            "API_ID" to AppSettings.apiId.value.trim(),
            "API_HASH" to AppSettings.apiHash.value.trim(),
            "INSTANCE" to script.id,
            "PROXY" to proxy,
        )
        val env = JSONObject()
        val prefix = script.id.uppercase().replace(Regex("[^A-Z0-9]"), "_")
        values.forEach { (k, v) ->
            env.put("TELESCRIPT_$k", v)
            env.put("${prefix}_$k", v)
        }
        env.put("TELESCRIPT_SCRIPT_ID", script.id)
        // LLM: Claude / Gemini CLI in Termux (shims on PATH); the API key is the last fallback.
        val order = AppSettings.llmOrder.value
        LlmBridge.setup(order)
        val basePath = System.getenv("PATH") ?: "/system/bin"
        env.put("PATH", if (order == "api") basePath else "${Paths.llmBin.absolutePath}:$basePath")
        env.put("TELESCRIPT_LLM_ORDER", order)
        env.put("TELESCRIPT_LLM_PROVIDERS_FILE", Providers.path)
        env.put("ANTHROPIC_API_KEY", AppSettings.anthropicKey.value.trim())
        if (model.isNotEmpty()) env.put("ANTHROPIC_MODEL", model)
        env.put("HOME", Paths.root.absolutePath)
        env.put("TELESCRIPT_SITE_PACKAGES", Paths.sitePackages.absolutePath)
        return env
    }
}


@Immutable
data class Library(val name: String, val version: String)

/** Runtime library installer (ts_pip): installs into Paths.sitePackages. */
object LibManager {
    private val _installing = MutableStateFlow<Set<String>>(emptySet())
    /** Script ids whose dependencies are being installed. */
    val installing: StateFlow<Set<String>> = _installing
    private val _libraries = MutableStateFlow<List<Library>>(emptyList())
    val libraries: StateFlow<List<Library>> = _libraries

    private val abi: String get() = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    private val pip get() = run { Py.host; com.chaquo.python.Python.getInstance().getModule("ts_pip") }

    /** Installs requirements.txt / "# requires:" deps of the script plus [extra] packages. */
    fun installFor(script: ScriptInfo, extra: List<String> = emptyList(), onDone: (Boolean) -> Unit = {}) {
        if (script.id in _installing.value) return
        _installing.update { it + script.id }
        Thread({
            var ok = false
            try {
                if (ScriptRepository.hasAccess()) ScriptRepository.sync(script)
                val result = Py.json(pip.callAttr("install_for_script", script.id,
                    script.entry.parentFile!!.absolutePath,
                    if (script.entry.isFile) script.entry.absolutePath else "",
                    JSONArray(extra.filter { it.isNotBlank() }).toString(),
                    Paths.sitePackages.absolutePath, abi))
                ok = result.optString("status") == "ok"
            } catch (e: Throwable) {
                LogBus.e(script.id, Str.get(R.string.rep_install_fail, e.message.orEmpty()))
            } finally {
                _installing.update { it - script.id }
                refresh()
                onDone(ok)
            }
        }, "pip-${script.id}").start()
    }

    fun refresh() {
        Thread({
            _libraries.value = runCatching {
                val arr = JSONArray(pip.callAttr("list_installed", Paths.sitePackages.absolutePath).toString())
                (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Library(o.getString("name"), o.getString("version")) } }
            }.getOrDefault(emptyList())
        }, "pip-list").start()
    }

    fun remove(name: String) = Thread({
        runCatching { pip.callAttr("remove", Paths.sitePackages.absolutePath, name) }
            .onFailure { LogBus.e("app", Str.get(R.string.rep_remove_fail, name, it.message.orEmpty())) }
            .onSuccess { LogBus.i("app", Str.get(R.string.rep_lib_removed, name)) }
        refresh()
    }, "pip-rm").start()

    fun clearAll() = Thread({
        runCatching { pip.callAttr("clear", Paths.sitePackages.absolutePath) }
        LogBus.i("app", Str.get(R.string.rep_all_libs_removed))
        refresh()
    }, "pip-clear").start()
}
