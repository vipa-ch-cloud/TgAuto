package com.v1pach.telescript.terminal

import android.graphics.Typeface
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.termux.view.TerminalView
import com.v1pach.telescript.R
import com.v1pach.telescript.Str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val MONO = FontFamily.Monospace

/** Colours of the console, shared by the shell tab, the AI-CLI tab and the log. */
class ConsoleLook(
    val bg: Color, val fg: Color, val prompt: Color, val err: Color, val dim: Color, val bar: Color, val ansi: List<Color>,
) {
    fun toTerm() = TermLook(bg.toArgb(), fg.toArgb(), prompt.toArgb(), IntArray(16) { ansi[it].toArgb() })

    companion object {
        /** Plain theme colours (stable, no CRT). */
        @Composable
        fun fromTheme(): ConsoleLook {
            val c = MaterialTheme.colorScheme
            val dark = c.background.luminance() < 0.5f
            val ansi = if (dark) listOf(
                0xFF45475A, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF, 0xFF89B4FA, 0xFFCBA6F7, 0xFF94E2D5, 0xFFBAC2DE,
                0xFF6C7086, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF, 0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFFFFFFF,
            ) else listOf(
                0xFF3B3B3B, 0xFFC62828, 0xFF2E7D32, 0xFF9A6700, 0xFF1565C0, 0xFF7B1FA2, 0xFF00796B, 0xFF757575,
                0xFF616161, 0xFFD32F2F, 0xFF388E3C, 0xFFB26A00, 0xFF1976D2, 0xFF8E24AA, 0xFF00897B, 0xFF212121,
            )
            return ConsoleLook(c.surfaceContainerLowest, c.onSurface, c.primary, c.error, c.onSurfaceVariant, c.surfaceContainer,
                ansi.map { Color(it.toInt()) })
        }
    }
}

/** Unpacks the embedded userland on first use; [content] is shown once it is ready (or unavailable). */
@Composable
fun UserlandGate(look: ConsoleLook, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var ready by remember { mutableStateOf(Userland.ready(ctx) || !Userland.supported(ctx)) }
    var failed by remember { mutableStateOf(false) }
    if (!ready) {
        LaunchedEffect(Unit) {
            val ok = withContext(Dispatchers.IO) { Userland.ensure(ctx) }
            failed = !ok
            ready = true
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = look.prompt)
                Text(stringResource(R.string.userland_unpacking), color = look.dim, fontFamily = MONO, fontSize = 13.sp)
            }
        }
    } else {
        if (failed) Text(stringResource(R.string.userland_failed), color = look.err, fontFamily = MONO, fontSize = 12.sp,
            modifier = Modifier.padding(8.dp))
        else if (!Userland.supported(ctx)) Text(stringResource(R.string.userland_unsupported), color = look.dim, fontFamily = MONO,
            fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        content()
    }
}

/** Tab strip; [tabs] are labels, [onSelect] gets the index. */
@Composable
fun ConsoleTabs(look: ConsoleLook, tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(look.bar).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tabs.forEachIndexed { i, t ->
            Text(t, color = if (i == selected) look.prompt else look.dim, fontFamily = MONO,
                fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

/** Stable console: tabs Shell | AI-CLI in theme colours, no CRT. */
@Composable
fun ConsoleDialog(onClose: () -> Unit) {
    val look = ConsoleLook.fromTheme()
    var tab by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = look.bg) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().background(look.bar).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, null, tint = look.prompt, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.console_dlg_title), color = look.fg, fontFamily = MONO, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.term_close), tint = look.fg) }
                }
                ConsoleTabs(look, listOf(stringResource(R.string.console_tab_shell), stringResource(R.string.console_tab_cli)), tab) { tab = it }
                Box(Modifier.weight(1f).fillMaxWidth()) { ConsolePanels(look, tab, 0) }
            }
        }
    }
}

/** Shell and AI-CLI bodies. [tab] 0 = shell, 1 = AI-CLI; [offset] shifts for hosts with extra leading tabs. */
@Composable
fun ConsolePanels(look: ConsoleLook, tab: Int, offset: Int) {
    UserlandGate(look) {
        when (tab - offset) {
            0 -> ShellPanel(look)
            1 -> CliPanel(look, onLogin = { spec -> TerminalHost.newTab(CliLogic.loginCommand(spec)) })
        }
    }
}

@Composable
fun ShellPanel(look: ConsoleLook) {
    val context = LocalContext.current
    TerminalHost.look = remember(look.bg, look.fg, look.prompt) { look.toTerm() }
    LaunchedEffect(Unit) { TerminalHost.ensureTab() }
    val tick = TerminalHost.tick
    val tabs = TerminalHost.tabs
    val sel = TerminalHost.selected.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    val tab = tabs.getOrNull(sel)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(look.bar).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { i, t ->
                Row(Modifier.clip(RoundedCornerShape(50)).background(if (i == sel) look.bg else Color.Transparent)
                    .clickable { TerminalHost.selected = i }.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text((t.session.mSessionName ?: "sh") + if (t.session.isRunning) "" else " (x)", fontSize = 13.sp,
                        fontFamily = MONO, color = if (i == sel) look.prompt else look.dim)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.Close, stringResource(R.string.term_close), tint = look.dim,
                        modifier = Modifier.size(18.dp).clickable { TerminalHost.close(i) })
                }
            }
            Icon(Icons.Default.Add, stringResource(R.string.term_new_tab), tint = look.fg,
                modifier = Modifier.padding(6.dp).size(22.dp).clickable { TerminalHost.newTab() })
        }
        if (tab != null) {
            val size = TerminalPrefs.fontSize.value
            key(tab.id) {
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            setBackgroundColor(look.bg.toArgb())
                            setTerminalViewClient(TerminalHost.viewClient)
                            setTypeface(Typeface.MONOSPACE)
                            setTextSize(size)
                            isFocusable = true
                            isFocusableInTouchMode = true
                            attachSession(tab.session)
                            TerminalHost.view = this
                            TerminalHost.applyLook(tab.session)
                            requestFocus()
                            post { (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0) }
                        }
                    },
                    update = { TerminalHost.applyLook(tab.session); it.onScreenUpdated() },
                    onRelease = { if (TerminalHost.view === it) TerminalHost.view = null },
                )
            }
            if (!tab.session.isRunning) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.term_exited, tab.session.exitStatus), color = look.err, fontFamily = MONO, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    Key(look, stringResource(R.string.term_restart)) { TerminalHost.close(sel); TerminalHost.newTab() }
                }
            }
        } else Spacer(Modifier.weight(1f))

        ExtraKeys(look, onCopy = {
            val text = TerminalHost.copyScreen()
            if (text.isNotEmpty()) { TerminalHost.setClipboard(text); android.widget.Toast.makeText(context, Str.get(R.string.term_copied), android.widget.Toast.LENGTH_SHORT).show() }
        })
        if (tick < 0) Spacer(Modifier.size(0.dp))
    }
}

@Composable
private fun ExtraKeys(look: ConsoleLook, onCopy: () -> Unit) {
    val context = LocalContext.current
    val h = TerminalHost
    Column(Modifier.fillMaxWidth().background(look.bar).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Key(look, "ESC") { h.sendText("\u001b") }
            Key(look, "TAB") { h.sendKey(KeyEvent.KEYCODE_TAB) }
            Key(look, "CTRL", h.ctrl) { h.ctrl = !h.ctrl }
            Key(look, "ALT", h.alt) { h.alt = !h.alt }
            Key(look, "←") { h.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            Key(look, "↓") { h.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
            Key(look, "↑") { h.sendKey(KeyEvent.KEYCODE_DPAD_UP) }
            Key(look, "→") { h.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            Key(look, "HOME") { h.sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
            Key(look, "END") { h.sendKey(KeyEvent.KEYCODE_MOVE_END) }
            Key(look, "PGUP") { h.sendKey(KeyEvent.KEYCODE_PAGE_UP) }
            Key(look, "PGDN") { h.sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Key(look, "^C") { h.current()?.session?.write("\u0003") }
            Key(look, "^D") { h.current()?.session?.write("\u0004") }
            Key(look, "^Z") { h.current()?.session?.write("\u001a") }
            Key(look, "-") { h.sendText("-") }
            Key(look, "/") { h.sendText("/") }
            Key(look, "|") { h.sendText("|") }
            Key(look, "~") { h.sendText("~") }
            Key(look, "$") { h.sendText("$") }
            Key(look, stringResource(R.string.term_paste)) { h.paste() }
            Key(look, stringResource(R.string.term_copy)) { onCopy() }
            Key(look, stringResource(R.string.term_keyboard)) {
                h.view?.let { v -> (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0) }
            }
        }
    }
}

@Composable
private fun Key(look: ConsoleLook, label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (active) look.prompt else look.bg)
            .clickable(onClick = onClick)
            .defaultMinSize(minWidth = 40.dp, minHeight = 36.dp).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontFamily = MONO, color = if (active) look.bg else look.fg)
    }
}

/** AI-CLI tab: install / update / login / remove claude, codex and gemini, plus an optional proxy. */
@Composable
fun CliPanel(look: ConsoleLook, onLogin: (CliSpec) -> Unit) {
    val states by CliManager.state.collectAsState()
    var proxy by remember { mutableStateOf(TerminalPrefs.httpProxy.value) }
    LaunchedEffect(Unit) { CliCatalog.all.forEach { CliManager.check(it) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.cli_mode), color = look.dim, fontFamily = MONO, fontSize = 12.sp)
        for (spec in CliCatalog.all) {
            val st = states[spec.id] ?: CliState()
            val installed = st.status?.installed == true
            val outdated = installed && st.latest != null && st.status?.version?.let { CliLogic.isNewer(st.latest, it) } == true
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(look.bar).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.title, color = look.fg, fontFamily = MONO, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    val label = when {
                        st.busy != null -> stringResource(R.string.cli_busy)
                        st.status == null -> stringResource(R.string.cli_unknown)
                        !installed -> stringResource(R.string.cli_not_installed)
                        outdated -> stringResource(R.string.cli_outdated)
                        else -> stringResource(R.string.cli_installed)
                    }
                    Text(label, color = if (installed && !outdated) look.prompt else look.dim, fontFamily = MONO, fontSize = 12.sp)
                }
                if (installed && st.status?.version?.isNotBlank() == true) {
                    Text(stringResource(R.string.cli_version, st.status.version) + (st.latest?.let { "  " + stringResource(R.string.cli_latest, it) } ?: ""),
                        color = look.dim, fontFamily = MONO, fontSize = 12.sp)
                }
                if (st.error != CliError.NONE) Text(CliManager.errorText(st.error), color = look.err, fontFamily = MONO, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val idle = st.busy == null
                    CliButton(look, stringResource(if (installed) R.string.cli_update else R.string.cli_install), idle) { CliManager.install(spec) }
                    CliButton(look, stringResource(R.string.cli_login), idle && installed) { onLogin(spec) }
                    CliButton(look, stringResource(R.string.cli_check), idle) { CliManager.check(spec) }
                    CliButton(look, stringResource(R.string.cli_remove), idle && installed) { CliManager.remove(spec) }
                }
                if (st.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = look.prompt, trackColor = look.bg)
                if (st.log.isNotBlank()) Text(st.log.takeLast(1500), color = look.dim, fontFamily = MONO, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.cli_proxy), color = look.fg, fontFamily = MONO, fontSize = 13.sp)
            BasicTextField(
                value = proxy, onValueChange = { proxy = it; if (CliLogic.validProxy(it)) TerminalPrefs.httpProxy.set(it.trim()) },
                singleLine = true, textStyle = TextStyle(color = look.fg, fontFamily = MONO, fontSize = 14.sp),
                cursorBrush = SolidColor(look.prompt),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(look.bar).padding(10.dp),
            )
            if (!CliLogic.validProxy(proxy)) Text(stringResource(R.string.cli_proxy_bad), color = look.err, fontFamily = MONO, fontSize = 12.sp)
            else Text(stringResource(R.string.cli_proxy_hint), color = look.dim, fontFamily = MONO, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CliButton(look: ConsoleLook, label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(label, color = if (enabled) look.prompt else look.dim.copy(alpha = 0.5f), fontFamily = MONO, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(look.bg).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp))
}
