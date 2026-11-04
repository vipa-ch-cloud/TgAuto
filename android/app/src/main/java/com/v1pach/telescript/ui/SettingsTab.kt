package com.v1pach.telescript.ui

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
        TabHeader("Настройки")

        LlmCard()

        ConfigEditorCard()

        AppSectionCard {
            SectionHeader(Icons.Default.Inventory2, "Библиотеки")
            if (libraries.isEmpty()) {
                Hint("Дополнительных библиотек нет. Их ставит кнопка «Зависимости» на карточке скрипта " +
                    "(из requirements.txt или заголовка «# requires: …»).")
            } else {
                libraries.forEach { lib ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(lib.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Hint(lib.version)
                        }
                        IconButton(onClick = { LibManager.remove(lib.name) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить ${lib.name}",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                BigButton("Очистить всё", Icons.Default.DeleteSweep, onClick = { confirmClear = true }, danger = true)
            }
            Hint("Встроены: telethon, httpx, python-socks, pyaes, rsa. Чистые Python-пакеты берутся с PyPI, " +
                "нативные — из репозитория Chaquopy.")
        }

        AppSectionCard {
            SectionHeader(Icons.Default.Palette, "Оформление")
            Text("Тема", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "Системная", "light" to "Светлая", "dark" to "Тёмная").forEach { (k, label) ->
                    ChoiceButton(label, themeMode == k, Modifier.weight(1f)) { AppSettings.themeMode.set(k) }
                }
            }
            val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            SwitchRow("Динамические цвета", if (dynamicAvailable) "Цвета из обоев (Material You)" else "Нужен Android 12+",
                dynamicColor && dynamicAvailable, enabled = dynamicAvailable) { AppSettings.dynamicColor.set(it) }
            if (!dynamicColor || !dynamicAvailable) {
                Text("Палитра", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("indigo" to "Индиго", "espresso" to "Эспрессо", "forest" to "Лес").forEach { (k, label) ->
                        ChoiceButton(label, palette == k, Modifier.weight(1f)) { AppSettings.palette.set(k) }
                    }
                }
            }
        }

        AppSectionCard {
            SectionHeader(Icons.Default.BatteryChargingFull, "Работа в фоне")
            if (batteryOk) {
                Hint("Оптимизация батареи для TeleScript отключена — скрипты могут работать в фоне.")
            } else {
                Hint("Android может усыплять скрипты. Отключите оптимизацию батареи для TeleScript.")
                BigButton("Отключить оптимизацию", Icons.Default.BatterySaver, onClick = { requestIgnoreBattery(context) })
            }
        }

        AppSectionCard {
            SectionHeader(Icons.Default.Info, "О приложении")
            Text("TeleScript 1.0", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            Hint("Запуск Python-скриптов Telethon на телефоне. Python 3.12 (Chaquopy), Telethon, httpx, python-socks.")
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Удалить все библиотеки?") },
            text = { Text("Будут удалены все пакеты, установленные кнопкой «Зависимости». Встроенные останутся.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; LibManager.clearAll() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } }
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
        SectionHeader(Icons.Default.DataObject, "Конфиг скрипта (JSON)")
        if (script == null) {
            Hint("Скриптов пока нет — выберите папку на вкладке «Скрипты».")
            return@AppSectionCard
        }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = script.relPath, onValueChange = {}, readOnly = true, singleLine = true,
                label = { Text("Скрипт") },
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
        Hint("Файл ${script.configFile.name} в папке TELESCRIPT_CONFIG_DIR скрипта.")
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            isError = error != null,
            supportingText = { Text(error ?: "JSON корректен") },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Сохранить", Icons.Default.Save, enabled = error == null, modifier = Modifier.weight(1f), onClick = {
                runCatching {
                    script.configFile.parentFile?.mkdirs()
                    val pretty = if (text.isBlank()) "{}" else JSONObject(text).toString(2)
                    script.configFile.writeText(pretty + "\n")
                    text = pretty + "\n"
                }.onSuccess { toast(context, "Конфиг сохранён. Перезапустите скрипт, чтобы применить.") }
                    .onFailure { toast(context, "Не удалось сохранить: ${it.message}") }
            })
            BigButton("Сбросить", Icons.Default.Undo, modifier = Modifier.weight(1f), onClick = { text = readConfig(script) })
        }
    }
}

private fun readConfig(script: ScriptInfo): String =
    runCatching { if (script.configFile.isFile) script.configFile.readText() else "{\n}\n" }.getOrDefault("{\n}\n")

private fun validateJson(text: String): String? {
    if (text.isBlank()) return null
    return try { JSONObject(text); null } catch (e: Exception) { "Ошибка JSON: ${e.message}" }
}

@Composable
private fun LlmCard() {
    val context = LocalContext.current
    val order by AppSettings.llmOrder.flow.collectAsStateWithLifecycle()
    val key by AppSettings.anthropicKey.flow.collectAsStateWithLifecycle()
    val model by AppSettings.model.flow.collectAsStateWithLifecycle()
    val claudeCmd by AppSettings.termuxCommand.flow.collectAsStateWithLifecycle()
    val geminiCmd by AppSettings.geminiCommand.flow.collectAsStateWithLifecycle()
    var installed by remember { mutableStateOf(LlmBridge.termuxInstalled()) }
    var granted by remember { mutableStateOf(LlmBridge.hasPermission()) }
    var testing by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<Map<String, LlmBridge.Result>>(emptyMap()) }
    var showSteps by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = LlmBridge.hasPermission()
        if (!granted) toast(context, "Разрешение не выдано. Его можно включить в настройках приложения → Разрешения")
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        installed = LlmBridge.termuxInstalled()
        granted = LlmBridge.hasPermission()
    }

    @Composable
    fun Account(name: String, title: String, command: String, onCommand: (String) -> Unit, hint: String) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface)
        AppTextField(command, onCommand, "Команда в Termux", placeholder = name, supporting = hint)
        BigButton("Проверить", Icons.Default.PlayArrow, loading = testing == name,
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
            StatusPill(if (ok) "Работает" else "Ошибка (код ${r.exitCode})",
                if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            LlmBridge.explain(r).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            val text = listOf(r.stdout.trim(), r.stderr.trim()).filter { it.isNotEmpty() }.joinToString("\n")
                .ifEmpty { "(пустой ответ)" }.take(1500)
            SelectionContainer {
                Text(text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }

    AppSectionCard {
        SectionHeader(Icons.Default.AutoAwesome, "LLM")
        Hint("Скрипты вызывают настоящие Claude Code и Gemini CLI, установленные и авторизованные в Termux. " +
            "Запросы идут через ваши аккаунты. Команды запускаются без инструментов и MCP.")
        Text("Порядок", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(
            "both" to "Claude, потом Gemini",
            "claude" to "Только Claude",
            "gemini" to "Только Gemini",
            "api" to "Только API-ключ",
        ).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (k, label) -> ChoiceButton(label, order == k, Modifier.weight(1f)) { AppSettings.llmOrder.set(k) } }
            }
        }

        if (order != "api") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(if (installed) "Termux установлен" else "Termux не найден",
                    if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                StatusPill(if (granted) "Доступ есть" else "Нет доступа",
                    if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            if (installed && !granted) {
                BigButton("Разрешить запуск команд в Termux", Icons.Default.Key,
                    onClick = { permission.launch(LlmBridge.PERMISSION) })
            }
            if (order != "gemini") Account("claude", "Claude-аккаунт (Termux)", claudeCmd,
                { AppSettings.termuxCommand.set(it) }, "Для proot Ubuntu: proot-distro login ubuntu -- claude")
            if (order != "claude") Account("gemini", "Gemini-аккаунт (Termux)", geminiCmd,
                { AppSettings.geminiCommand.set(it) }, "Обычно gemini. Настройки «без инструментов» пишутся в ~/.telescript-sandbox/.gemini")
            TextButton(onClick = { showSteps = !showSteps }) {
                Text(if (showSteps) "Скрыть инструкцию" else "Как настроить Termux")
            }
            if (showSteps) TermuxSteps()
        }

        AppTextField(key, { AppSettings.anthropicKey.set(it.trim()) }, "API-ключ Anthropic (последний запас)",
            secret = true, placeholder = "sk-ant-…",
            supporting = "Нужен, только если Claude и Gemini недоступны; хранится на телефоне")
        AppTextField(model, { AppSettings.model.set(it.trim()) }, "Модель",
            placeholder = "по умолчанию из конфига скрипта",
            supporting = "Передаётся скриптам как ANTHROPIC_MODEL и записывается в поле «model» конфига")
        Hint("Изменения применяются при следующем запуске скрипта.")
    }
}

@Composable
private fun TermuxSteps() {
    val steps = listOf(
        "Установите Termux из F-Droid или с GitHub (termux/termux-app). Сборка из Google Play устарела." to null,
        "Claude: установите Node.js и Claude Code. Версии 2.1.113 и новее в Termux не запускаются, нужна 2.1.112:" to
            "pkg install -y libc++ nodejs\nDISABLE_AUTOUPDATER=1 npm i -g @anthropic-ai/claude-code@2.1.112",
        "Запустите claude и войдите через аккаунт Claude (ссылка в браузер, код обратно в Termux)." to "claude",
        "Gemini: установите и войдите через «Login with Google»:" to
            "pkg install -y python make clang\nnpm install -g @google/gemini-cli --ignore-scripts\ngemini",
        "Разрешите другим приложениям запускать команды в Termux:" to
            "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties\ntermux-reload-settings",
        "Нажмите выше «Разрешить запуск команд в Termux», отключите оптимизацию батареи для Termux и нажмите «Проверить»." to null,
    )
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
