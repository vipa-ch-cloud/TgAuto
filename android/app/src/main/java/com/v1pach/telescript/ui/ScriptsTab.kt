package com.v1pach.telescript.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v1pach.telescript.*
import kotlinx.coroutines.delay

private fun rescan() = Thread({ ScriptRepository.scan() }, "scan").start()

@Composable
fun ScriptsTab(onOpenAccount: () -> Unit, onOpenLogs: () -> Unit) {
    val context = LocalContext.current
    val scripts by ScriptRepository.scripts.collectAsStateWithLifecycle()
    val scanning by ScriptRepository.scanning.collectAsStateWithLifecycle()
    val problem by ScriptRepository.problem.collectAsStateWithLifecycle()
    val tree by AppSettings.scriptsTree.flow.collectAsStateWithLifecycle()
    val states by ScriptManager.states.collectAsStateWithLifecycle()
    val installing by LibManager.installing.collectAsStateWithLifecycle()
    var confirmTemplate by remember { mutableStateOf("") }
    var configFor by remember { mutableStateOf("") }
    var batteryOk by remember { mutableStateOf(isIgnoringBattery(context)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            ScriptRepository.setFolder(uri)
            rescan()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        batteryOk = isIgnoringBattery(context)
        rescan()
    }

    fun addTemplate(name: String, overwrite: Boolean) = Thread({
        val error = ScriptRepository.addTemplate(name, overwrite)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            when (error) {
                null -> { toast(context, "Пример $name добавлен в папку"); rescan() }
                "EXISTS" -> confirmTemplate = name
                else -> toast(context, error)
            }
        }
    }, "template").start()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            TabHeader("Скрипты") {
                if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                IconButton(onClick = ::rescan, enabled = tree.isNotEmpty()) {
                    Icon(Icons.Default.Refresh, contentDescription = "Пересканировать", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            AppSectionCard {
                SectionHeader(Icons.Default.FolderOpen, "Папка со скриптами")
                if (tree.isNotEmpty()) {
                    Text(ScriptRepository.folderLabel(), style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                } else {
                    Hint("Выберите папку на телефоне, где лежат ваши .py скрипты. Каждый .py в корне — отдельный " +
                        "скрипт; подпапка с main.py или <имя_папки>.py — скрипт-пакет.")
                }
                if (problem.isNotEmpty() && tree.isNotEmpty()) Hint(problem, MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton(if (tree.isEmpty()) "Выбрать папку" else "Сменить папку", Icons.Default.Folder,
                        onClick = { picker.launch(null) }, modifier = Modifier.weight(1f))
                    BigButton("Пример: automod", Icons.Default.Add, onClick = { addTemplate("automod", false) },
                        modifier = Modifier.weight(1f), enabled = tree.isNotEmpty() && problem.isEmpty())
                }
                BigButton("Пример: автоответчик", Icons.Default.Add, onClick = { addTemplate("autoresponder", false) },
                    modifier = Modifier.fillMaxWidth(), enabled = tree.isNotEmpty() && problem.isEmpty())
                if (tree.isNotEmpty()) Hint("Примеры копируют automod или autoresponder (скрипт, README.md и схему настроек) в выбранную папку.")
            }
        }
        if (!batteryOk) item {
            AppSectionCard {
                SectionHeader(Icons.Default.BatteryAlert, "Работа в фоне")
                Hint("Чтобы Android не усыплял скрипты, отключите для TeleScript оптимизацию батареи.")
                BigButton("Отключить оптимизацию", Icons.Default.BatterySaver, onClick = {
                    requestIgnoreBattery(context)
                })
            }
        }
        if (tree.isNotEmpty() && problem.isEmpty() && scripts.isEmpty() && !scanning) item {
            AppSectionCard {
                Hint("В папке нет скриптов. Положите туда .py файлы или нажмите «Пример».")
            }
        }
        items(scripts, key = { it.id }) { script ->
            ScriptCard(
                script = script,
                state = states[script.id] ?: ScriptState(),
                installing = script.id in installing,
                onStart = {
                    val error = ScriptManager.preflight()
                    if (error != null) {
                        toast(context, error)
                        if (!AppSettings.hasApiCredentials() || !Paths.sessionFile.isFile) onOpenAccount()
                    } else {
                        ScriptService.start(context, script.id)
                    }
                },
                onStop = { ScriptService.stop(script.id) },
                onConfig = { configFor = script.id },
                onInstall = { extra ->
                    LibManager.installFor(script, extra)
                    onOpenLogs()
                }
            )
        }
    }

    scripts.firstOrNull { it.id == configFor }?.let { script ->
        val st = states[script.id]?.status
        ConfigDialog(script, running = st == RunStatus.RUNNING || st == RunStatus.STARTING, onClose = { configFor = "" })
    }

    if (confirmTemplate.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { confirmTemplate = "" },
            title = { Text("Папка $confirmTemplate уже есть") },
            text = { Text("Перезаписать файлы примера ($confirmTemplate.py, README.md, config.schema.json) версией из приложения? Другие файлы в папке не тронуты.") },
            confirmButton = { TextButton(onClick = { val n = confirmTemplate; confirmTemplate = ""; addTemplate(n, true) }) { Text("Перезаписать") } },
            dismissButton = { TextButton(onClick = { confirmTemplate = "" }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun ScriptCard(
    script: ScriptInfo,
    state: ScriptState,
    installing: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onConfig: () -> Unit,
    onInstall: (List<String>) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.22f
    val (label, color) = when (state.status) {
        RunStatus.RUNNING -> "работает" to (if (dark) AppColors.connectedDark else AppColors.connected)
        RunStatus.STARTING -> "запуск…" to (if (dark) AppColors.warningDark else AppColors.warning)
        RunStatus.STOPPING -> "остановка…" to (if (dark) AppColors.warningDark else AppColors.warning)
        RunStatus.ERROR -> "ошибка" to MaterialTheme.colorScheme.error
        RunStatus.IDLE -> "остановлен" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    var uptime by remember { mutableStateOf("") }
    LaunchedEffect(state.status, state.startedAt) {
        while (state.status == RunStatus.RUNNING) {
            uptime = formatUptime(System.currentTimeMillis() - state.startedAt)
            delay(1000)
        }
        uptime = ""
    }

    AppSectionCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(script.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(script.relPath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(if (uptime.isNotEmpty()) "$label · $uptime" else label, color)
        }
        if (script.description.isNotBlank()) {
            Text(script.description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (state.status == RunStatus.ERROR && state.message.isNotBlank()) {
            Hint(state.message, MaterialTheme.colorScheme.error)
        }
        if (state.status == RunStatus.ERROR && state.missingPackage.isNotBlank()) {
            BigButton("Установить «${state.missingPackage}»", Icons.Default.Download,
                onClick = { onInstall(listOf(state.missingPackage)) }, loading = installing, enabled = !installing)
        }
        val active = state.status == RunStatus.RUNNING || state.status == RunStatus.STARTING
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (active || state.status == RunStatus.STOPPING) {
                BigButton("Остановить", Icons.Default.Stop, onClick = onStop, modifier = Modifier.weight(1f),
                    danger = true, enabled = state.status != RunStatus.STOPPING,
                    loading = state.status == RunStatus.STOPPING)
            } else {
                BigButton("Запустить", Icons.Default.PlayArrow, onClick = onStart, modifier = Modifier.weight(1f),
                    enabled = !installing)
            }
            BigButton("Зависимости", Icons.Default.Download, onClick = { onInstall(emptyList()) },
                modifier = Modifier.weight(1f), enabled = !installing && !active, loading = installing)
        }
        if (script.schemaDocId.isNotEmpty()) {
            BigButton("Конфиг", Icons.Default.Tune, onClick = onConfig)
        }
    }
}

private fun formatUptime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s >= 86400 -> "%dд %dч".format(s / 86400, s % 86400 / 3600)
        s >= 3600 -> "%dч %02dм".format(s / 3600, s % 3600 / 60)
        else -> "%d:%02d".format(s / 60, s % 60)
    }
}

internal fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

internal fun isIgnoringBattery(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

@SuppressLint("BatteryLife")
internal fun requestIgnoreBattery(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")))
    }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
