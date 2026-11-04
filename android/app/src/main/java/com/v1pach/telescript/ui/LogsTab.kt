package com.v1pach.telescript.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v1pach.telescript.AppSettings
import com.v1pach.telescript.LogBus
import com.v1pach.telescript.LogEntry

@Composable
fun LogsTab() {
    val context = LocalContext.current
    val logs by LogBus.logs.collectAsStateWithLifecycle()
    val showDebug by AppSettings.logDebug.flow.collectAsStateWithLifecycle()
    val showInfo by AppSettings.logInfo.flow.collectAsStateWithLifecycle()
    val showWarn by AppSettings.logWarn.flow.collectAsStateWithLifecycle()
    val showError by AppSettings.logError.flow.collectAsStateWithLifecycle()
    var source by rememberSaveable { mutableStateOf("") } // "" = all

    val sources = remember(logs) { logs.map { it.source }.distinct().sorted() }
    if (source.isNotEmpty() && source !in sources) source = ""
    val filtered = remember(logs, showDebug, showInfo, showWarn, showError, source) {
        logs.filter { e ->
            (source.isEmpty() || e.source == source) && when (e.priority) {
                3 -> showDebug
                4 -> showInfo
                5 -> showWarn
                else -> e.priority < 3 && showInfo || e.priority >= 6 && showError
            }
        }
    }

    val listState = rememberLazyListState()
    var initialScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(filtered.size, filtered.lastOrNull()?.count) {
        if (filtered.isNotEmpty()) {
            if (!initialScrolled) { listState.scrollToItem(filtered.size - 1); initialScrolled = true }
            else listState.animateScrollToItem(filtered.size - 1)
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Журнал событий", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface)
            Row {
                IconButton(onClick = { LogBus.clear() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Очистить", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = {
                    val text = filtered.joinToString("\n") {
                        "${it.time} [${it.source}] ${it.message}" + if (it.count > 1) " (x${it.count})" else ""
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("TgAuto logs", text))
                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Копировать", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogFilterChip("DEBUG", showDebug, Modifier.weight(1f)) { AppSettings.logDebug.set(!showDebug) }
            LogFilterChip("INFO", showInfo, Modifier.weight(1f)) { AppSettings.logInfo.set(!showInfo) }
            LogFilterChip("WARN", showWarn, Modifier.weight(1f)) { AppSettings.logWarn.set(!showWarn) }
            LogFilterChip("ERROR", showError, Modifier.weight(1f)) { AppSettings.logError.set(!showError) }
        }
        if (sources.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = source.isEmpty(), onClick = { source = "" }, label = { Text("Все") },
                    shape = RoundedCornerShape(16.dp))
                sources.forEach { s ->
                    FilterChip(selected = source == s, onClick = { source = if (source == s) "" else s },
                        label = { Text(s) }, shape = RoundedCornerShape(16.dp))
                }
            }
        }

        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.22f
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))
                .background(if (isDark) AppColors.terminalBgDark else AppColors.terminalBg)
        ) {
            if (filtered.isEmpty()) {
                Text("Пока пусто. Запустите скрипт — здесь появится его вывод.",
                    color = AppColors.terminalText.copy(alpha = 0.5f), fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(16.dp))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(filtered, key = { it.key }) { entry -> LogLine(entry, showSource = source.isEmpty()) }
            }
        }
    }
}

@Composable
private fun LogFilterChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.height(52.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun LogLine(entry: LogEntry, showSource: Boolean) {
    val color = when (entry.priority) {
        6 -> AppColors.terminalRed
        5 -> AppColors.terminalOrange
        4 -> AppColors.terminalGreen
        3 -> AppColors.terminalBlue
        else -> AppColors.terminalText
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Surface(
            color = AppColors.terminalCounter.copy(alpha = 0.2f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 18.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 5.dp)) {
                Text("${entry.count}", color = AppColors.terminalBlue, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        Spacer(Modifier.width(6.dp))
        val icon = when (entry.priority) {
            6 -> Icons.Default.Error
            5 -> Icons.Default.Warning
            3 -> Icons.Default.BugReport
            else -> Icons.Default.Info
        }
        Icon(icon, contentDescription = null, tint = color.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 2.dp).size(14.dp))
        Spacer(Modifier.width(6.dp))
        val prefix = buildString {
            append(entry.time).append(' ')
            if (showSource) append('[').append(entry.source).append("] ")
        }
        Text(
            text = prefix + entry.message,
            color = color,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (entry.priority >= 6) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

