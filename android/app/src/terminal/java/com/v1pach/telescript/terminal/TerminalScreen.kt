package com.v1pach.telescript.terminal

import android.graphics.Color as AColor
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import com.v1pach.telescript.R
import com.v1pach.telescript.ui.Hint
import com.v1pach.telescript.ui.toast

@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TerminalHost.ensureTab() }
    val tick = TerminalHost.tick // re-read titles and exit state
    val tabs = TerminalHost.tabs
    val sel = TerminalHost.selected.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    val tab = tabs.getOrNull(sel)

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { i, t ->
                val running = t.session.isRunning
                Surface(shape = RoundedCornerShape(50),
                    color = if (i == sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { TerminalHost.selected = i }) {
                    Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text((t.session.mSessionName ?: "sh") + if (running) "" else " (x)", fontSize = 13.sp)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.Close, stringResource(R.string.term_close), Modifier.size(18.dp).clickable { TerminalHost.close(i) })
                    }
                }
            }
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { TerminalHost.newTab() }) {
                Icon(Icons.Default.Add, stringResource(R.string.term_new_tab), Modifier.padding(6.dp).size(20.dp))
            }
        }
        Hint(stringResource(R.string.term_mode_local))

        if (tab != null) {
            val size = TerminalPrefs.fontSize.value
            key(tab.id) {
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            setBackgroundColor(AColor.BLACK)
                            setTerminalViewClient(TerminalHost.viewClient)
                            setTypeface(Typeface.MONOSPACE)
                            setTextSize(size)
                            isFocusable = true
                            isFocusableInTouchMode = true
                            attachSession(tab.session)
                            TerminalHost.view = this
                            requestFocus()
                            post { (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0) }
                        }
                    },
                    onRelease = { if (TerminalHost.view === it) TerminalHost.view = null },
                )
            }
            if (!tab.session.isRunning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.term_exited, tab.session.exitStatus), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Key(stringResource(R.string.term_restart)) { TerminalHost.close(sel); TerminalHost.newTab() }
                }
            }
        } else Spacer(Modifier.weight(1f))

        ExtraKeys(onCopy = {
            val text = TerminalHost.copyScreen()
            if (text.isNotEmpty()) { TerminalHost.setClipboard(text); toast(context, context.getString(R.string.term_copied)) }
        })
        if (tick < 0) Spacer(Modifier.size(0.dp))
    }
}

@Composable
private fun ExtraKeys(onCopy: () -> Unit) {
    val context = LocalContext.current
    val h = TerminalHost
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Key("ESC") { h.sendText("\u001b") }
            Key("TAB") { h.sendKey(KeyEvent.KEYCODE_TAB) }
            Key("CTRL", h.ctrl) { h.ctrl = !h.ctrl }
            Key("ALT", h.alt) { h.alt = !h.alt }
            Key("←") { h.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            Key("↓") { h.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
            Key("↑") { h.sendKey(KeyEvent.KEYCODE_DPAD_UP) }
            Key("→") { h.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            Key("HOME") { h.sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
            Key("END") { h.sendKey(KeyEvent.KEYCODE_MOVE_END) }
            Key("PGUP") { h.sendKey(KeyEvent.KEYCODE_PAGE_UP) }
            Key("PGDN") { h.sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Key("^C") { h.current()?.session?.write("\u0003") }
            Key("^D") { h.current()?.session?.write("\u0004") }
            Key("^Z") { h.current()?.session?.write("\u001a") }
            Key("-") { h.sendText("-") }
            Key("/") { h.sendText("/") }
            Key("|") { h.sendText("|") }
            Key("~") { h.sendText("~") }
            Key("$") { h.sendText("$") }
            Key(stringResource(R.string.term_paste)) { h.paste() }
            Key(stringResource(R.string.term_copy)) { onCopy() }
            Key(stringResource(R.string.term_keyboard)) {
                h.view?.let { v -> (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0) }
            }
        }
    }
}

@Composable
private fun Key(label: String, active: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (active) c.primary else c.surfaceVariant)
            .border(BorderStroke(1.dp, c.outline.copy(alpha = 0.3f)), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .defaultMinSize(minWidth = 40.dp, minHeight = 36.dp).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = if (active) c.onPrimary else c.onSurfaceVariant)
    }
}
