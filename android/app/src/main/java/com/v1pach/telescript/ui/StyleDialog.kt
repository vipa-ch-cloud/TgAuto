package com.v1pach.telescript.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v1pach.telescript.*
import com.v1pach.telescript.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private data class StyleChat(val id: Long, val name: String, val inFolder: Boolean)

/** Runs the script once with other arguments (same id, so it cannot overlap the running bot). Returns an error or null. */
private suspend fun runOnce(script: ScriptInfo, arg: String): String? {
    ScriptManager.preflight()?.let { return it }
    ScriptManager.start(script.copy(args = listOf(arg)))
    delay(300)
    while (ScriptManager.state(script.id).status.let {
            it == RunStatus.STARTING || it == RunStatus.RUNNING || it == RunStatus.STOPPING }) delay(500)
    val st = ScriptManager.state(script.id)
    return if (st.status == RunStatus.ERROR) st.message.ifBlank { "error" } else null
}

/** Old autoresponder (<1.2.0) answers unknown commands with its usage line. */
private fun isOldScriptError(e: String?): Boolean =
    e != null && Regex("использование|usage|unknown command|invalid choice|неизвестн", RegexOption.IGNORE_CASE).containsMatchIn(e)

private fun readJson(file: File): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

/** Choose chats -> the script reads the user's messages and has the model describe the style -> edit -> save to style.json. */
@Composable
fun StyleDialog(script: ScriptInfo, onClose: () -> Unit) {
    val states by ScriptManager.states.collectAsState()
    var busy by remember { mutableStateOf("") }
    val running = busy.isEmpty() && ScriptManager.runningIds().contains(script.id)
    var chats by remember { mutableStateOf<List<StyleChat>>(emptyList()) }
    val picked = remember { mutableStateListOf<Long>() }
    var error by remember { mutableStateOf("") }
    var oldScript by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var draft by remember { mutableStateOf<JSONObject?>(null) }
    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    val hasSaved = remember { File(script.dataDir, "style.json").isFile }
    val loadingText = stringResource(R.string.style_loading)
    val analyzingText = stringResource(R.string.style_analyzing)
    val oldText = stringResource(R.string.style_old)
    val failedFmt = stringResource(R.string.style_failed, "%s")
    val scope = rememberCoroutineScope()

    fun fail(e: String) {
        oldScript = isOldScriptError(e) || e == oldText
        error = if (oldScript) oldText else failedFmt.replace("%s", e)
    }

    fun loadChats() {
        error = ""; oldScript = false; busy = loadingText
        scope.launch {
            val e = withContext(Dispatchers.IO) {
                File(script.dataDir, "style_chats.json").delete()
                val r = runOnce(script, "style-chats")
                val body = readJson(File(script.dataDir, "style_chats.json"))
                if (r != null) r else if (body == null) oldText else {
                    val arr = JSONArray(body)
                    chats = List(arr.length()) { arr.getJSONObject(it).let { o -> StyleChat(o.getLong("id"), o.optString("name"), o.optBoolean("in_folder")) } }
                    null
                }
            }
            busy = ""; e?.let(::fail)
        }
    }

    fun analyze() {
        error = ""; oldScript = false; busy = analyzingText; draft = null; saved = false
        scope.launch {
            val e = withContext(Dispatchers.IO) {
                File(script.dataDir, "style_draft.json").delete()
                File(script.dataDir, "style_request.json").writeText(
                    JSONObject().put("chats", JSONArray(picked.toList())).put("per_chat", 200).toString())
                val r = runOnce(script, "style-draft")
                val body = readJson(File(script.dataDir, "style_draft.json"))
                if (r != null) r else if (body == null) oldText else {
                    JSONObject(body).also { draft = it; text = it.optString("guide") }
                    null
                }
            }
            busy = ""; e?.let(::fail)
        }
    }

    fun save() {
        val d = draft ?: return
        val out = JSONObject().put("stats", d.optJSONObject("stats") ?: JSONObject()).put("guide", text.trim())
        script.dataDir.mkdirs()
        val f = File(script.dataDir, "style.json")
        f.writeText(out.toString())
        saved = true
    }

    Dialog(onDismissRequest = { if (busy.isEmpty()) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 16.dp)) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.style_title), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose, enabled = busy.isEmpty()) { Icon(Icons.Default.Close, stringResource(R.string.style_close)) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Hint(stringResource(R.string.style_intro))
                    if (running) Hint(stringResource(R.string.style_running), MaterialTheme.colorScheme.error)
                    if (hasSaved) Hint(stringResource(R.string.style_has_saved))
                    if (error.isNotEmpty()) Hint(error, MaterialTheme.colorScheme.error)
                    if (oldScript) OutlinedButton({
                        context.startActivity(android.content.Intent(context, com.v1pach.telescript.store.StoreActivity::class.java))
                    }) { Text(stringResource(R.string.store_title)) }
                    if (busy.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Text(busy)
                    }
                    val d = draft
                    if (d != null) {
                        Hint(stringResource(R.string.style_stats, d.optInt("messages"), d.optInt("chats")))
                        AppTextField(text, { text = it; saved = false }, stringResource(R.string.style_result),
                            modifier = Modifier.weight(1f, fill = true), singleLine = false)
                    } else if (chats.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ picked.clear(); picked.addAll(chats.filter { it.inFolder }.map { it.id }) }) { Text(stringResource(R.string.style_all_folder)) }
                            OutlinedButton({ picked.clear(); picked.addAll(chats.map { it.id }) }) { Text(stringResource(R.string.style_all)) }
                            OutlinedButton({ picked.clear() }) { Text(stringResource(R.string.style_none)) }
                        }
                        Hint(stringResource(R.string.style_selected, picked.size))
                        LazyColumn(Modifier.weight(1f)) {
                            items(chats, key = { it.id }) { c ->
                                Row(Modifier.fillMaxWidth().clickable { if (c.id in picked) picked.remove(c.id) else picked.add(c.id) },
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(c.id in picked, { if (it) picked.add(c.id) else picked.remove(c.id) })
                                    Text(c.name, maxLines = 1)
                                }
                            }
                        }
                    } else Spacer(Modifier.weight(1f))
                }
                if (saved) Hint(stringResource(R.string.style_saved))
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (draft != null) {
                        BigButton(stringResource(R.string.style_save), Icons.Default.Save, onClick = ::save, enabled = text.isNotBlank() && busy.isEmpty())
                        BigButton(stringResource(R.string.style_load), Icons.Default.Refresh, onClick = { draft = null; saved = false }, enabled = busy.isEmpty())
                    } else if (chats.isEmpty()) {
                        BigButton(stringResource(R.string.style_load), Icons.Default.Refresh, onClick = ::loadChats,
                            enabled = !running && busy.isEmpty(), loading = busy.isNotEmpty())
                    } else {
                        BigButton(stringResource(R.string.style_analyze), Icons.Default.Psychology, onClick = ::analyze,
                            enabled = !running && picked.isNotEmpty() && busy.isEmpty(), loading = busy.isNotEmpty())
                    }
                }
            }
        }
    }
}
