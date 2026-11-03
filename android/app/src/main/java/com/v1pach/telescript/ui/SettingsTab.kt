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
    val backend by AppSettings.llmBackend.flow.collectAsStateWithLifecycle()
    val key by AppSettings.anthropicKey.flow.collectAsStateWithLifecycle()
    val model by AppSettings.model.flow.collectAsStateWithLifecycle()
    val command by AppSettings.termuxCommand.flow.collectAsStateWithLifecycle()
    var installed by remember { mutableStateOf(LlmBridge.termuxInstalled()) }
    var granted by remember { mutableStateOf(LlmBridge.hasPermission()) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<LlmBridge.Result?>(null) }
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

    AppSectionCard {
        SectionHeader(Icons.Default.AutoAwesome, "LLM")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceButton("Claude-аккаунт (Termux)", backend == "termux", Modifier.weight(1.4f)) {
                AppSettings.llmBackend.set("termux")
            }
            ChoiceButton("API-ключ", backend == "api", Modifier.weight(1f)) { AppSettings.llmBackend.set("api") }
        }

        if (backend == "termux") {
            Hint("Скрипты вызывают настоящий Claude Code, установленный и авторизованный в Termux. " +
                "Запросы идут через ваш аккаунт Claude (Pro/Max), ключ не нужен. " +
                "Команда запускается без инструментов, MCP и сохранения сессий.")
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
            AppTextField(command, { AppSettings.termuxCommand.set(it) }, "Команда в Termux",
                placeholder = "claude",
                supporting = "Для proot Ubuntu: proot-distro login ubuntu -- claude")
            BigButton("Проверить", Icons.Default.PlayArrow, loading = testing, enabled = installed && granted, onClick = {
                testing = true
                testResult = null
                scope.launch {
                    val args = buildList {
                        add("-p")
                        if (model.isNotBlank()) { add("--model"); add(model) }
                        addAll(LlmBridge.TOOLLESS)
                    }
                    testResult = withContext(Dispatchers.IO) { LlmBridge.run(args, "ответь одним словом: ок", 120) }
                    testing = false
                }
            })
            testResult?.let { r ->
                val ok = r.exitCode == 0 && r.stdout.isNotBlank()
                StatusPill(if (ok) "Работает" else "Ошибка (код ${r.exitCode})",
                    if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                val text = listOf(r.stdout.trim(), r.stderr.trim()).filter { it.isNotEmpty() }.joinToString("\n")
                    .ifEmpty { "(пустой ответ)" }.take(1500)
                SelectionContainer {
                    Text(text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
            TextButton(onClick = { showSteps = !showSteps }) {
                Text(if (showSteps) "Скрыть инструкцию" else "Как настроить Termux")
            }
            if (showSteps) TermuxSteps()
        } else {
            AppTextField(key, { AppSettings.anthropicKey.set(it.trim()) }, "API-ключ", secret = true,
                placeholder = "sk-ant-…")
            Hint("Ключ передаётся скриптам в переменной ANTHROPIC_API_KEY и хранится только на телефоне.")
        }
        AppTextField(model, { AppSettings.model.set(it.trim()) }, "Модель",
            placeholder = "по умолчанию из конфига скрипта",
            supporting = "Передаётся скриптам как ANTHROPIC_MODEL и записывается в поле «model» конфига")
        Hint("Изменения применяются при следующем запуске скрипта.")
    }
}

@Composable
private fun TermuxSteps() {
    val steps = listOf(
        "Установите Termux из F-Droid или с GitHub (termux/termux-app, версия 0.118+). " +
            "Сборка из Google Play устарела и не смешивается с ними." to null,
        "В Termux установите Node.js:" to "pkg update && pkg install nodejs",
        "Установите Claude Code. Новые версии npm-пакета (2.1.113 и выше) в Termux не запускаются " +
            "(нет нативной сборки для Android), поэтому нужна 2.1.112 с отключённым автообновлением:" to
            "DISABLE_AUTOUPDATER=1 npm i -g @anthropic-ai/claude-code@2.1.112\n" +
            "mkdir -p ~/.claude && echo '{\"env\":{\"DISABLE_AUTOUPDATER\":\"1\"}}' > ~/.claude/settings.json",
        "Альтернатива — свежая версия в Ubuntu через proot (тогда в поле «Команда в Termux» укажите " +
            "proot-distro login ubuntu -- claude):" to
            "pkg install proot-distro && proot-distro install ubuntu\n" +
            "proot-distro login ubuntu -- bash -c 'apt update && apt install -y curl && curl -fsSL https://claude.ai/install.sh | bash'",
        "Запустите claude и войдите: откройте ссылку в браузере, войдите в аккаунт Claude (Pro/Max) " +
            "и вставьте код обратно в Termux. Затем выйдите (/exit)." to "claude",
        "Разрешите другим приложениям запускать команды в Termux:" to
            "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties\ntermux-reload-settings",
        "Нажмите выше «Разрешить запуск команд в Termux» (или: Настройки Android → Приложения → TeleScript → " +
            "Разрешения → Дополнительные → «Выполнение команд в Termux»)." to null,
        "Отключите оптимизацию батареи для Termux, затем нажмите «Проверить» — должен прийти ответ «ок»." to null,
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
