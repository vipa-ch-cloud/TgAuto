package com.v1pach.telescript.ui

import com.v1pach.telescript.R
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v1pach.telescript.*
import org.json.JSONObject

@Composable
fun SettingsTab() {
    val context = LocalContext.current
    val themeMode by AppSettings.themeMode.flow.collectAsStateWithLifecycle()
    val dynamicColor by AppSettings.dynamicColor.flow.collectAsStateWithLifecycle()
    val palette by AppSettings.palette.flow.collectAsStateWithLifecycle()
    val libraries by LibManager.libraries.collectAsStateWithLifecycle()
    var batteryOk by remember { mutableStateOf(isIgnoringBattery(context)) }
    var confirmClear by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        batteryOk = isIgnoringBattery(context)
        LibManager.refresh()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TabHeader(stringResource(R.string.settings_title))

        FlavorTopSection()

        LlmCard()

        com.v1pach.telescript.terminal.TerminalCard()

        FlavorLlmSections()

        ConfigEditorCard()

        AppSectionCard {
            SectionHeader(Icons.Default.Inventory2, stringResource(R.string.settings_libs))
            if (libraries.isEmpty()) {
                Hint(stringResource(R.string.settings_libs_none))
            } else {
                libraries.forEach { lib ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(lib.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Hint(lib.version)
                        }
                        IconButton(onClick = { LibManager.remove(lib.name) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.settings_lib_delete, lib.name),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                BigButton(stringResource(R.string.settings_clear_all), Icons.Default.DeleteSweep, onClick = { confirmClear = true }, danger = true)
            }
            Hint(stringResource(R.string.settings_libs_builtin))
        }

        AppSectionCard {
            SectionHeader(Icons.Default.Palette, stringResource(R.string.settings_appearance))
            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to stringResource(R.string.settings_theme_system), "light" to stringResource(R.string.settings_theme_light), "dark" to stringResource(R.string.settings_theme_dark)).forEach { (k, label) ->
                    ChoiceButton(label, themeMode == k, Modifier.weight(1f)) { AppSettings.themeMode.set(k) }
                }
            }
            val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            SwitchRow(stringResource(R.string.settings_dynamic), if (dynamicAvailable) stringResource(R.string.settings_dynamic_on) else stringResource(R.string.settings_dynamic_off),
                dynamicColor && dynamicAvailable, enabled = dynamicAvailable) { AppSettings.dynamicColor.set(it) }
            if (!dynamicColor || !dynamicAvailable) {
                Text(stringResource(R.string.settings_palette), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("indigo" to stringResource(R.string.settings_palette_indigo), "espresso" to stringResource(R.string.settings_palette_espresso), "forest" to stringResource(R.string.settings_palette_forest)).forEach { (k, label) ->
                        ChoiceButton(label, palette == k, Modifier.weight(1f)) { AppSettings.palette.set(k) }
                    }
                }
            }
        }

        AppSectionCard {
            SectionHeader(Icons.Default.BatteryChargingFull, stringResource(R.string.settings_background))
            if (batteryOk) {
                Hint(stringResource(R.string.settings_battery_ok))
            } else {
                Hint(stringResource(R.string.settings_battery_bad))
                BigButton(stringResource(R.string.settings_battery_btn), Icons.Default.BatterySaver, onClick = { requestIgnoreBattery(context) })
            }
        }

        OtaSection()

        AppSectionCard {
            SectionHeader(Icons.Default.Info, stringResource(R.string.settings_about))
            Text("TgAuto ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            Hint(stringResource(R.string.settings_about_text))
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_clear_title)) },
            text = { Text(stringResource(R.string.settings_clear_text)) },
            confirmButton = { TextButton(onClick = { confirmClear = false; LibManager.clearAll() }) { Text(stringResource(R.string.settings_delete)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.settings_cancel)) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigEditorCard() {
    val context = LocalContext.current
    val scripts by ScriptRepository.scripts.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf("") }
    val script = scripts.firstOrNull { it.id == selectedId } ?: scripts.firstOrNull()
    var text by remember(script?.id) { mutableStateOf(script?.let { readConfig(it) } ?: "") }
    var expanded by remember { mutableStateOf(false) }
    val error = remember(text) { validateJson(text) }

    AppSectionCard {
        SectionHeader(Icons.Default.DataObject, stringResource(R.string.settings_cfg_title))
        if (script == null) {
            Hint(stringResource(R.string.settings_cfg_noscripts))
            return@AppSectionCard
        }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = script.relPath, onValueChange = {}, readOnly = true, singleLine = true,
                label = { Text(stringResource(R.string.settings_cfg_script)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                scripts.forEach { s ->
                    DropdownMenuItem(text = { Text(s.relPath) }, onClick = { selectedId = s.id; expanded = false })
                }
            }
        }
        Hint(stringResource(R.string.settings_cfg_file, script.configFile.name))
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            isError = error != null,
            supportingText = { Text(error ?: stringResource(R.string.settings_json_ok)) },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(stringResource(R.string.settings_save), Icons.Default.Save, enabled = error == null, modifier = Modifier.weight(1f), onClick = {
                runCatching {
                    script.configFile.parentFile?.mkdirs()
                    val pretty = if (text.isBlank()) "{}" else JSONObject(text).toString(2)
                    script.configFile.writeText(pretty + "\n")
                    text = pretty + "\n"
                }.onSuccess { toast(context, context.getString(R.string.settings_cfg_saved)) }
                    .onFailure { toast(context, context.getString(R.string.settings_cfg_save_fail, it.message)) }
            })
            BigButton(stringResource(R.string.settings_reset), Icons.Default.Undo, modifier = Modifier.weight(1f), onClick = { text = readConfig(script) })
        }
    }
}

private fun readConfig(script: ScriptInfo): String =
    runCatching { if (script.configFile.isFile) script.configFile.readText() else "{\n}\n" }.getOrDefault("{\n}\n")

private fun validateJson(text: String): String? {
    if (text.isBlank()) return null
    return try { JSONObject(text); null } catch (e: Exception) { Str.get(R.string.settings_json_error, e.message ?: "") }
}

@Composable
private fun LlmCard() {
    val context = LocalContext.current
    val orderPref by AppSettings.llmOrder.flow.collectAsStateWithLifecycle()
    val order = FlavorHooks.cliOrderState(orderPref)
    val key by AppSettings.anthropicKey.flow.collectAsStateWithLifecycle()
    val model by AppSettings.model.flow.collectAsStateWithLifecycle()
    val claudeCmd by AppSettings.termuxCommand.flow.collectAsStateWithLifecycle()
    val geminiCmd by AppSettings.geminiCommand.flow.collectAsStateWithLifecycle()
    val codexCmd by AppSettings.codexCommand.flow.collectAsStateWithLifecycle()
    val codexOn = FlavorHooks.codexState(orderPref)
    var installed by remember { mutableStateOf(LlmBridge.termuxInstalled()) }
    var granted by remember { mutableStateOf(LlmBridge.hasPermission()) }
    var testing by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<Map<String, LlmBridge.Result>>(emptyMap()) }
    var showSteps by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val permDeniedMsg = stringResource(R.string.settings_perm_denied)
    val promptReply = stringResource(R.string.settings_empty_reply)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = LlmBridge.hasPermission()
        if (!granted) toast(context, permDeniedMsg)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        installed = LlmBridge.termuxInstalled()
        granted = LlmBridge.hasPermission()
    }

    @Composable
    fun Account(name: String, title: String, command: String, onCommand: (String) -> Unit, hint: String) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface)
        AppTextField(command, onCommand, stringResource(R.string.settings_termux_cmd), placeholder = name, supporting = hint)
        BigButton(stringResource(R.string.settings_check), Icons.Default.PlayArrow, loading = testing == name,
            enabled = installed && granted && testing == null, onClick = {
                testing = name
                scope.launch {
                    val prompt = "ответь одним словом: ок"
                    val r = withContext(Dispatchers.IO) {
                        if (name == "claude") {
                            val args = buildList {
                                add("-p")
                                if (model.isNotBlank()) { add("--model"); add(model) }
                                addAll(LlmBridge.TOOLLESS)
                            }
                            LlmBridge.run(name, args, prompt, 120)
                        } else if (name == "codex") {
                            LlmBridge.run(name, listOf("exec", "--sandbox", "read-only", "--ephemeral", "--skip-git-repo-check", "-"), prompt, 120)
                        } else {
                            LlmBridge.run(name, listOf("-p", prompt), "", 120)
                        }
                    }
                    results = results + (name to r)
                    testing = null
                }
            })
        results[name]?.let { r ->
            val ok = r.exitCode == 0 && r.stdout.isNotBlank()
            StatusPill(if (ok) stringResource(R.string.settings_ok) else stringResource(R.string.settings_err_code, r.exitCode),
                if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            LlmBridge.explain(r, name).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            val text = listOf(r.stdout.trim(), r.stderr.trim()).filter { it.isNotEmpty() }.joinToString("\n")
                .ifEmpty { promptReply }.take(1500)
            SelectionContainer {
                Text(text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }

    AppSectionCard {
        SectionHeader(Icons.Default.AutoAwesome, "LLM")
        Hint(stringResource(R.string.settings_llm_hint))
        if (!FlavorHooks.managesBackend) Text(stringResource(R.string.settings_order), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!FlavorHooks.managesBackend) listOf(
            "both" to stringResource(R.string.settings_order_both),
            "claude" to stringResource(R.string.settings_order_claude),
            "gemini" to stringResource(R.string.settings_order_gemini),
            "api" to stringResource(R.string.settings_order_api),
        ).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (k, label) -> ChoiceButton(label, order == k, Modifier.weight(1f)) { AppSettings.llmOrder.set(k) } }
            }
        }

        if (order != "api" || codexOn) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(if (installed) stringResource(R.string.settings_termux_installed) else stringResource(R.string.settings_termux_missing),
                    if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                StatusPill(if (granted) stringResource(R.string.settings_access_yes) else stringResource(R.string.settings_access_no),
                    if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            if (installed && !granted) {
                BigButton(stringResource(R.string.settings_allow_termux), Icons.Default.Key,
                    onClick = { permission.launch(LlmBridge.PERMISSION) })
            }
            if (order == "both" || order == "claude") Account("claude", stringResource(R.string.settings_claude_acc), claudeCmd,
                { AppSettings.termuxCommand.set(it) }, stringResource(R.string.settings_claude_hint))
            if (order == "both" || order == "gemini") Account("gemini", stringResource(R.string.settings_gemini_acc), geminiCmd,
                { AppSettings.geminiCommand.set(it) }, stringResource(R.string.settings_gemini_hint))
            if (codexOn) Account("codex", stringResource(R.string.settings_codex_acc), codexCmd,
                { AppSettings.codexCommand.set(it) }, stringResource(R.string.settings_codex_hint))
            TextButton(onClick = { showSteps = !showSteps }) {
                Text(if (showSteps) stringResource(R.string.settings_hide_steps) else stringResource(R.string.settings_show_steps))
            }
            if (showSteps) TermuxSteps()
        }

        AppTextField(key, { AppSettings.anthropicKey.set(it.trim()) }, stringResource(R.string.settings_api_key),
            secret = true, placeholder = "sk-ant-…",
            supporting = stringResource(R.string.settings_api_key_hint))
        AppTextField(model, { AppSettings.model.set(it.trim()) }, stringResource(R.string.settings_model),
            placeholder = stringResource(R.string.settings_model_ph),
            supporting = stringResource(R.string.settings_model_hint))
        Hint(stringResource(R.string.settings_applies_next))
    }
}

@Composable
private fun TermuxSteps() {
    val codexStep = stringResource(R.string.settings_step_codex)
    val steps = listOf(
        stringResource(R.string.settings_step1) to null,
        stringResource(R.string.settings_step2) to
            "pkg install -y libc++ nodejs\nDISABLE_AUTOUPDATER=1 npm i -g @anthropic-ai/claude-code@2.1.112",
        stringResource(R.string.settings_step3) to "claude",
        stringResource(R.string.settings_step4) to
            "pkg install -y python make clang\nnpm install -g @google/gemini-cli --ignore-scripts\ngemini",
        codexStep to
            "pkg install -y codex   # or: npm i -g @openai/codex\ncodex login   # codex login --device-auth without a browser",
        stringResource(R.string.settings_step5) to
            "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties\ntermux-reload-settings",
        stringResource(R.string.settings_step6) to null,
    ).filterNot { it.first == codexStep && !FlavorHooks.managesBackend }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEachIndexed { i, (text, code) ->
            Text("${i + 1}. $text", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (code != null) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(code, Modifier.padding(10.dp),
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
