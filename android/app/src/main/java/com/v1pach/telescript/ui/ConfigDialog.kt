package com.v1pach.telescript.ui

import com.v1pach.telescript.R
import com.v1pach.telescript.Str
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v1pach.telescript.*

/** "Конфиг" form of a script, built from its config.schema.json. */
@Composable
fun ConfigDialog(script: ScriptInfo, running: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var schema by remember { mutableStateOf<ConfigSchema?>(null) }
    var loadError by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saveError by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    val forms = remember { mutableStateListOf<FieldForm>() }
    var styleOpen by remember { mutableStateOf(false) }

    LaunchedEffect(script.id) {
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val text = ScriptRepository.readSchema(script)
            val parsed = text?.let { ConfigStore.parse(it) }
            if (parsed == null) return@withContext Triple(null, emptyMap<String, org.json.JSONObject>(), Str.get(R.string.cfg_schema_unreadable))
            val (docs, err) = ConfigStore.readAll(script.configDir, parsed)
            Triple(parsed, docs, err ?: "")
        }
        val (parsed, docs, err) = result
        schema = parsed
        loadError = err
        forms.clear()
        parsed?.fields?.forEach { f -> forms.add(FieldForm(f).also { it.load(docs[f.file]) }) }
        loading = false
    }

    val errors = forms.associate { it.field to if (it.dirty) it.parse().second else null }
    val anyDirty = forms.any { it.dirty }
    val hasError = errors.values.any { it != null }

    StyleHost(script, styleOpen) { styleOpen = false }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                .padding(horizontal = 16.dp)) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.cfg_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(script.title, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cfg_close)) }
                }
                if (loading) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (schema == null) {
                    Hint(loadError.ifEmpty { stringResource(R.string.cfg_schema_missing) }, MaterialTheme.colorScheme.error)
                    Spacer(Modifier.weight(1f))
                } else {
                    val blocked = loadError.isNotEmpty()
                    val sch = schema!!
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)) {
                        item {
                            val note = sch.note.ifBlank { stringResource(R.string.cfg_note_default) }
                            Hint(note + if (running) stringResource(R.string.cfg_running_suffix) else "")
                            if (blocked) Hint(loadError, MaterialTheme.colorScheme.error)
                        }
                        var formIndex = 0
                        for (entry in sch.items) {
                            if (entry is CfgSection) {
                                item(key = "s:" + entry.title) {
                                    Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                                }
                            } else {
                                val form = forms.getOrNull(formIndex++) ?: continue
                                item(key = "f:" + form.field.file + ":" + form.field.key) {
                                    FieldEditor(form, errors[form.field], enabled = !blocked)
                                }
                            }
                        }
                    }
                    if (saveError.isNotEmpty()) Hint(saveError, MaterialTheme.colorScheme.error)
                    if (saved && !anyDirty) Hint(
                        stringResource(if (running) R.string.cfg_saved_restart else R.string.cfg_saved))
                    if (script.id == "autoresponder") {
                        BigButton(stringResource(R.string.style_open), Icons.Default.Psychology, onClick = { styleOpen = true },
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigButton(stringResource(R.string.cfg_reset), Icons.Default.RestartAlt, modifier = Modifier.weight(1f),
                            enabled = !blocked, onClick = { forms.forEach { it.setDefault() }; saved = false; saveError = "" })
                        BigButton(stringResource(R.string.common_save), Icons.Default.Save, modifier = Modifier.weight(1f),
                            enabled = !blocked && anyDirty && !hasError, onClick = {
                                val values = forms.filter { it.dirty }.map { it.field to it.parse().first }
                                val error = ConfigStore.save(script.configDir, values)
                                if (error == null) {
                                    forms.forEach { it.dirty = false }
                                    saved = true; saveError = ""
                                } else {
                                    saveError = error
                                    toast(context, error)
                                }
                            })
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleHost(script: ScriptInfo, open: Boolean, onClose: () -> Unit) { if (open) StyleDialog(script, onClose) }

@Composable
private fun FieldEditor(form: FieldForm, error: String?, enabled: Boolean) {
    val f = form.field
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (f.type) {
            "bool" -> SwitchRow(f.title, f.description.ifBlank { null }, form.flag, enabled) {
                form.flag = it; form.dirty = true
            }
            "int", "float" -> AppTextField(form.text, { form.text = it; form.dirty = true }, f.title,
                keyboardType = if (f.type == "int") KeyboardType.Number else KeyboardType.Decimal,
                enabled = enabled, isError = error != null, supporting = error ?: f.description.ifBlank { null })
            "text" -> AppTextField(form.text, { form.text = it; form.dirty = true }, f.title, singleLine = false,
                enabled = enabled, supporting = f.description.ifBlank { null })
            "select" -> {
                Text(f.title, style = MaterialTheme.typography.bodyLarge)
                var open by remember { mutableStateOf(false) }
                val current = f.options.firstOrNull { it.first == form.text }?.second
                    ?: if (form.foreign.isNotEmpty()) stringResource(R.string.cfg_current, form.foreign) else stringResource(R.string.cfg_not_selected)
                Box {
                    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(current, modifier = Modifier.weight(1f), maxLines = 1)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        f.options.forEach { (value, title) ->
                            DropdownMenuItem(text = { Text(title) }, onClick = {
                                form.text = value; form.foreign = ""; form.dirty = true; open = false
                            })
                        }
                    }
                }
                if (f.description.isNotBlank()) Hint(f.description)
            }
            "list" -> {
                Text(f.title, style = MaterialTheme.typography.bodyLarge)
                if (f.description.isNotBlank()) Hint(f.description)
                form.items.forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppTextField(item, { form.items[i] = it; form.dirty = true }, stringResource(R.string.cfg_item, i + 1),
                            modifier = Modifier.weight(1f), enabled = enabled)
                        IconButton(onClick = { form.items.removeAt(i); form.dirty = true }, enabled = enabled) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cfg_delete))
                        }
                    }
                }
                OutlinedButton(onClick = { form.items.add(""); form.dirty = true }, enabled = enabled) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.cfg_add))
                }
            }
            else -> AppTextField(form.text, { form.text = it; form.dirty = true }, f.title,
                enabled = enabled, supporting = f.description.ifBlank { null })
        }
    }
}
