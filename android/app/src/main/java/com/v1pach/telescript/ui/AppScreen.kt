package com.v1pach.telescript.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v1pach.telescript.AppSettings
import java.io.File

/** Themed full-screen content for the secondary activities (OTA install, script store). */
fun ComponentActivity.setAppScreen(content: @Composable () -> Unit) {
    enableEdgeToEdge()
    setContent {
        val themeMode by AppSettings.themeMode.flow.collectAsStateWithLifecycle()
        val dynamicColor by AppSettings.dynamicColor.flow.collectAsStateWithLifecycle()
        val palette by AppSettings.palette.flow.collectAsStateWithLifecycle()
        TeleScriptTheme(themeMode = themeMode, dynamicColor = dynamicColor, themePalette = palette) {
            Surface(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) { content() }
            }
        }
    }
}

@Composable
fun ScrollColumn(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)

/** Copies a content:// or file:// URI into the cache, refusing anything over [max] bytes. */
fun copyIncoming(context: android.content.Context, uri: android.net.Uri, max: Long): File {
    val out = File(context.cacheDir, "incoming.bin")
    var total = 0L
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { o ->
            val buf = ByteArray(16384)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                total += n
                if (total > max) { out.delete(); throw com.v1pach.telescript.ota.OtaException("too_big", "package") }
                o.write(buf, 0, n)
            }
        }
    } ?: throw java.io.IOException("cannot open")
    return out
}
