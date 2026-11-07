package com.v1pach.telescript.ui

import android.content.ClipData
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v1pach.telescript.*
import com.v1pach.telescript.R
import kotlinx.coroutines.launch

private val MONO = FontFamily.Monospace

/** Terminal colors from the current Material theme; the 16 ANSI colors are tuned for light or dark. */
private class TermColors(
    val bg: Color, val fg: Color, val prompt: Color, val err: Color, val dim: Color,
    val selection: Color, val bar: Color, val ansi: List<Color>,
) {
    fun resolve(code: Int, default: Color): Color = when {
        code < 0 -> default
        code and RGB_FLAG != 0 -> Color(0xFF000000.toInt() or (code and 0xFFFFFF))
        else -> ansi[code.coerceIn(0, 15)]
    }
}

@Composable
private fun termColors(): TermColors {
    val c = MaterialTheme.colorScheme
    val dark = c.background.luminance() < 0.5f
    val ansi = if (dark) listOf(
        0xFF45475A, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF, 0xFF89B4FA, 0xFFCBA6F7, 0xFF94E2D5, 0xFFBAC2DE,
        0xFF6C7086, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF, 0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFFFFFFF,
    ) else listOf(
        0xFF3B3B3B, 0xFFC62828, 0xFF2E7D32, 0xFF9A6700, 0xFF1565C0, 0xFF7B1FA2, 0xFF00796B, 0xFF757575,
        0xFF616161, 0xFFD32F2F, 0xFF388E3C, 0xFFB26A00, 0xFF1976D2, 0xFF8E24AA, 0xFF00897B, 0xFF212121,
    )
    return TermColors(
        bg = c.surfaceContainerLowest, fg = c.onSurface, prompt = c.primary, err = c.error,
        dim = c.onSurfaceVariant, selection = c.primaryContainer.copy(alpha = 0.75f), bar = c.surfaceContainer,
        ansi = ansi.map { Color(it.toInt()) },
    )
}

/**
 * CRT look drawn above the console: a refresh band rolling top to bottom, scanlines, a vignette.
 * Scanlines and vignette are built once per size (a 1xN tile shader and a radial brush); each frame
 * only the band is translated, and the animated phase is read in the draw phase, so nothing
 * recomposes or allocates per frame.
 */
@Composable
private fun Modifier.crtOverlay(on: Boolean, tint: Color): Modifier {
    if (!on) return this
    val phase = rememberInfiniteTransition(label = "crt").animateFloat(
        0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "band")
    return drawWithCache {
        val w = size.width
        val h = size.height
        val period = (3.dp.toPx()).toInt().coerceAtLeast(2)
        val tile = ImageBitmap(1, period)
        Canvas(tile).drawRect(0f, 0f, 1f, 1f, Paint().apply { color = Color.Black.copy(alpha = 0.22f) })
        val scan = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
        val vignette = Brush.radialGradient(
            0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f),
            center = Offset(w / 2f, h / 2f), radius = kotlin.math.hypot(w, h) / 2f)
        val bandH = h * 0.16f
        val band = Brush.verticalGradient(
            listOf(Color.Transparent, tint.copy(alpha = 0.07f), tint.copy(alpha = 0.16f), Color.Transparent), 0f, bandH)
        val bandSize = Size(w, bandH)
        onDrawWithContent {
            drawContent()
            drawRect(scan)
            translate(top = -bandH + (h + bandH) * phase.value) { drawRect(band, size = bandSize) }
            drawRect(vignette)
        }
    }
}

/** Soft glow hugging the edge of a button or hitbox (inside its bounds, so parents do not clip it). */
private fun Modifier.crtGlow(on: Boolean, color: Color, corner: Dp = 8.dp): Modifier =
    if (!on) this else drawWithCache {
        val c = CornerRadius(corner.toPx())
        val wide = Stroke(5.dp.toPx())
        val mid = Stroke(3.dp.toPx())
        val thin = Stroke(1.dp.toPx())
        val a = color.copy(alpha = 0.06f)
        val b = color.copy(alpha = 0.12f)
        val d = color.copy(alpha = 0.28f)
        onDrawBehind {
            drawRoundRect(a, cornerRadius = c, style = wide)
            drawRoundRect(b, cornerRadius = c, style = mid)
            drawRoundRect(d, cornerRadius = c, style = thin)
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleOverlay(onClose: () -> Unit) {
    val t = termColors()
    val crt by ConsoleSession.crt.flow.collectAsStateWithLifecycle()
    val glow = if (crt) Shadow(t.fg.copy(alpha = 0.35f), Offset.Zero, 6f) else null
    val lines = ConsoleSession.lines
    val running = ConsoleSession.running
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var histPos by remember { mutableIntStateOf(-1) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val hello = stringResource(R.string.console_hello)
    val copied = stringResource(R.string.console_copied)
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) { if (ConsoleSession.lines.isEmpty()) ConsoleSession.info(hello) }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }

    fun send(cmd: String) {
        if (cmd.isBlank()) return
        ConsoleSession.submit(cmd)
        input = TextFieldValue("")
        histPos = -1
        selected = emptySet()
    }
    fun setInput(s: String) { input = TextFieldValue(s, TextRange(s.length)) }
    fun copy() {
        val src = if (selected.isEmpty()) lines.toList() else lines.filter { it.id in selected }
        val text = src.joinToString("\n") { l -> l.spans.joinToString("") { it.text } }
        scope.launch { clipboard.setClipEntry(ClipData.newPlainText("console", text).toClipEntry()) }
        toast(context, copied)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = t.bg) {
            Column(Modifier.fillMaxSize().crtOverlay(crt, t.prompt).systemBarsPadding().imePadding()) {
                // title bar
                Row(Modifier.fillMaxWidth().background(t.bar).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, null, tint = t.prompt, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.console_title), color = t.fg, fontFamily = MONO, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { ConsoleSession.crt.set(!crt) }, modifier = Modifier.crtGlow(crt, t.prompt, 20.dp)) {
                        Icon(Icons.Default.Tv, stringResource(R.string.console_crt), tint = if (crt) t.prompt else t.dim)
                    }
                    IconButton(onClick = { copy() }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.console_copy), tint = t.fg) }
                    IconButton(onClick = { ConsoleSession.clear(); selected = emptySet() }) {
                        Icon(Icons.Default.DeleteSweep, stringResource(R.string.console_clear), tint = t.fg)
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.console_close), tint = t.fg) }
                }
                // scrollback: each line is a full-width hitbox, a tap marks it like a block selection
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(lines, key = { it.id }) { line ->
                        val sel = line.id in selected
                        val base = when (line.kind) {
                            LineKind.ERR -> t.err
                            LineKind.INFO -> t.dim
                            else -> t.fg
                        }
                        val text = buildAnnotatedString {
                            if (line.kind == LineKind.CMD) {
                                withStyle(SpanStyle(color = t.prompt, fontWeight = FontWeight.Bold)) { append("$ ") }
                            }
                            for (s in line.spans) {
                                val st = s.style
                                withStyle(SpanStyle(
                                    color = if (line.kind == LineKind.CMD) t.fg else t.resolve(st.fg, base),
                                    background = if (st.bg >= 0) t.resolve(st.bg, t.bg) else Color.Unspecified,
                                    fontWeight = if (st.bold || line.kind == LineKind.CMD) FontWeight.Bold else null,
                                    textDecoration = if (st.underline) TextDecoration.Underline else null,
                                )) { append(s.text) }
                            }
                            if (line.spans.isEmpty()) append(" ")
                        }
                        Text(
                            text, fontFamily = MONO, fontSize = 13.sp, lineHeight = 18.sp,
                            style = if (glow != null) TextStyle(shadow = glow) else TextStyle.Default,
                            modifier = Modifier.fillMaxWidth()
                                .background(if (sel) t.selection else Color.Transparent)
                                .crtGlow(crt && sel, t.prompt, 2.dp)
                                .clickable { selected = if (sel) selected - line.id else selected + line.id }
                                .padding(horizontal = 12.dp, vertical = 1.dp)
                        )
                    }
                }
                // quick commands
                Row(Modifier.fillMaxWidth().background(t.bar).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        stringResource(R.string.console_q_claude) to "claude --version",
                        stringResource(R.string.console_q_gemini) to "gemini --version",
                        stringResource(R.string.console_q_codex) to "codex --version",
                        stringResource(R.string.console_q_node) to "node -v; npm -v",
                    ).forEach { (label, cmd) ->
                        AssistChip(onClick = { send(cmd) }, enabled = !running, modifier = Modifier.crtGlow(crt, t.prompt, 8.dp), label = { Text(label, fontFamily = MONO) })
                    }
                }
                // keys: Ctrl+C and history
                Row(Modifier.fillMaxWidth().background(t.bar).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { ConsoleSession.interrupt() }, enabled = running) {
                        Text("Ctrl+C", fontFamily = MONO, color = if (running) t.err else t.dim)
                    }
                    TextButton(onClick = {
                        val h = ConsoleSession.history
                        if (h.isNotEmpty()) { histPos = if (histPos < 0) h.size - 1 else (histPos - 1).coerceAtLeast(0); setInput(h[histPos]) }
                    }) { Text("↑", fontFamily = MONO, color = t.fg) }
                    TextButton(onClick = {
                        val h = ConsoleSession.history
                        if (histPos >= 0) {
                            histPos++
                            if (histPos >= h.size) { histPos = -1; setInput("") } else setInput(h[histPos])
                        }
                    }) { Text("↓", fontFamily = MONO, color = t.fg) }
                    Spacer(Modifier.weight(1f))
                    if (running) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = t.prompt)
                        Text(stringResource(R.string.console_running), color = t.dim, fontFamily = MONO, fontSize = 12.sp)
                    }
                }
                // prompt line with a cursor
                Row(Modifier.fillMaxWidth().background(t.bar).crtGlow(crt, t.prompt, 0.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("$ ", color = t.prompt, fontFamily = MONO, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    BasicTextField(
                        value = input, onValueChange = { input = it },
                        enabled = !running, singleLine = true,
                        textStyle = TextStyle(color = t.fg, fontFamily = MONO, fontSize = 14.sp),
                        cursorBrush = SolidColor(t.prompt),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send(input.text) }),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { send(input.text) }, enabled = !running && input.text.isNotBlank(), modifier = Modifier.crtGlow(crt && !running, t.prompt, 20.dp)) {
                        Icon(Icons.Default.Send, stringResource(R.string.console_run), tint = if (running) t.dim else t.prompt)
                    }
                }
            }
        }
    }
}
