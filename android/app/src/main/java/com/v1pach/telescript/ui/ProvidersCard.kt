package com.v1pach.telescript.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v1pach.telescript.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProvidersCard() {
    val list by Providers.list.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Provider?>(null) }
    var testing by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<Map<String, Providers.Result>>(emptyMap()) }
    val scope = rememberCoroutineScope()

    AppSectionCard {
        SectionHeader(Icons.Default.Hub, "Провайдеры")
        Hint("API-совместимые сервисы: DeepSeek, OpenAI или свой адрес (например, tokenlavka). " +
            "Скрипты пробуют их по порядку списка. Ключи хранятся только на телефоне и после сохранения не показываются.")
        list.forEachIndexed { i, p ->
            Text(p.title.ifBlank { p.baseUrl }, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Hint("${p.baseUrl} · ${p.model.ifBlank { "модель не задана" }} · ключ ${if (p.apiKey.isBlank()) "не задан" else "••••"}")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Providers.move(p.id, -1) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, "Выше") }
                IconButton(onClick = { Providers.move(p.id, 1) }, enabled = i < list.size - 1) { Icon(Icons.Default.ArrowDownward, "Ниже") }
                IconButton(onClick = { editing = p }) { Icon(Icons.Default.Edit, "Изменить") }
                IconButton(onClick = { Providers.delete(p.id) }) { Icon(Icons.Default.Delete, "Удалить") }
            }
            BigButton("Проверить", Icons.Default.PlayArrow, loading = testing == p.id, enabled = testing == null, onClick = {
                testing = p.id
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Providers.test(p) }
                    results = results + (p.id to r)
                    testing = null
                }
            })
            results[p.id]?.let { r ->
                StatusPill(if (r.ok) "Работает" else "Ошибка",
                    if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Hint(r.text, if (r.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
            SectionDivider()
        }
        Text("Добавить", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Providers.presets.forEach { pr ->
                ChoiceButton(pr.title, false, Modifier.weight(1f)) { editing = pr.copy(id = Providers.newId()) }
            }
        }
    }

    editing?.let { start ->
        ProviderDialog(start, isNew = list.none { it.id == start.id }, onDismiss = { editing = null })
    }
}

@Composable
private fun ProviderDialog(start: Provider, isNew: Boolean, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(start.title) }
    var url by remember { mutableStateOf(start.baseUrl) }
    var key by remember { mutableStateOf("") }          // never pre-filled with the saved key
    var model by remember { mutableStateOf(start.model) }
    var timeout by remember { mutableStateOf(start.timeout.toString()) }
    var extra by remember { mutableStateOf(start.extraBody) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var info by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hasSaved = start.apiKey.isNotBlank()

    fun current() = start.copy(title = title.trim(), baseUrl = url.trim(), model = model.trim(),
        apiKey = key.trim().ifEmpty { start.apiKey }, timeout = timeout.toIntOrNull()?.coerceIn(5, 600) ?: 60, extraBody = extra.trim())

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (isNew) "Новый провайдер" else "Провайдер", style = MaterialTheme.typography.titleLarge)
                AppTextField(title, { title = it }, "Название")
                AppTextField(url, { url = it }, "Адрес (base URL)", placeholder = "https://api.example.com/v1")
                AppTextField(key, { key = it }, "API-ключ", secret = true,
                    supporting = if (hasSaved) "Сохранён. Оставьте пустым, чтобы не менять" else null)
                AppTextField(model, { model = it }, "Модель")
                if (url.isNotBlank()) {
                    BigButton("Загрузить список моделей", Icons.Default.Refresh, loading = loading, onClick = {
                        loading = true
                        scope.launch {
                            val (m, err) = withContext(Dispatchers.IO) { Providers.listModels(current()) }
                            models = m; info = err; loading = false
                        }
                    })
                }
                info?.let { Hint(it, MaterialTheme.colorScheme.error) }
                models.take(60).forEach { m ->
                    TextButton(onClick = { model = m }) { Text(m, maxLines = 1) }
                }
                AppTextField(timeout, { timeout = it.filter(Char::isDigit) }, "Таймаут, с")
                val extraBad = extra.isNotBlank() && runCatching { org.json.JSONObject(extra) }.isFailure
                AppTextField(extra, { extra = it }, "Доп. параметры запроса (JSON, необязательно)",
                    placeholder = "{\"temperature\": 0.2}", isError = extraBad)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Отмена") }
                    Button(onClick = { Providers.upsert(current()); onDismiss() },
                        enabled = url.isNotBlank() && model.isNotBlank() && !extraBad, modifier = Modifier.weight(1f)) { Text("Сохранить") }
                }
            }
        }
    }
}
