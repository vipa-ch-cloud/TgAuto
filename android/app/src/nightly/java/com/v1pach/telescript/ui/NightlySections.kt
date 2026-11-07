package com.v1pach.telescript.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v1pach.telescript.*
import com.v1pach.telescript.R

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp))
}

/** Nightly: the CLI section at the very top of settings. */
@Composable
fun FlavorTopSection() {
    var open by remember { mutableStateOf(false) }
    val crt by ConsoleSession.crt.flow.collectAsStateWithLifecycle()
    val installed = remember { LlmBridge.termuxInstalled() }
    val granted = remember { LlmBridge.hasPermission() }
    SectionLabel(stringResource(R.string.sec_cli))
    AppSectionCard {
        SectionHeader(Icons.Default.Terminal, stringResource(R.string.cli_title))
        Hint(stringResource(R.string.cli_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(stringResource(if (installed) R.string.cli_termux_ok else R.string.cli_termux_no),
                if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            StatusPill(stringResource(if (granted) R.string.cli_perm_ok else R.string.cli_perm_no),
                if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        SwitchRow(stringResource(R.string.console_crt), stringResource(R.string.console_crt_hint), crt) { ConsoleSession.crt.set(it) }
        BigButton(stringResource(R.string.cli_open), Icons.Default.Terminal, onClick = { open = true })
    }
    if (open) ConsoleOverlay { open = false }
}

/** Nightly: providers and AI backend sections, below the Claude/Gemini card. */
@Composable
fun FlavorLlmSections() {
    SectionLabel(stringResource(R.string.sec_providers))
    ProvidersCard()
    SectionLabel(stringResource(R.string.sec_backend))
    AiBackendCard()
}

@Composable
private fun AiBackendCard() {
    LaunchedEffect(Unit) { AiBackend.ensureChosen() }
    val mode by AiBackend.mode.flow.collectAsStateWithLifecycle()
    val provider by AiBackend.provider.flow.collectAsStateWithLifecycle()
    val chain by AiBackend.chain.flow.collectAsStateWithLifecycle()
    val off by AiBackend.off.flow.collectAsStateWithLifecycle()
    val legacy by AppSettings.llmOrder.flow.collectAsStateWithLifecycle()
    val providers by Providers.list.collectAsStateWithLifecycle()
    val codexOn by AiBackend.codexOn.flow.collectAsStateWithLifecycle()
    val ids = providers.map { it.id }
    val plan = AiBackend.plan(legacy, mode, chain, off, provider, ids, codexOn)
    val kindNames = mapOf(
        "claude" to stringResource(R.string.ai_k_claude), "gemini" to stringResource(R.string.ai_k_gemini),
        "codex" to stringResource(R.string.ai_k_codex),
        "providers" to stringResource(R.string.ai_k_providers), "api" to stringResource(R.string.ai_k_api),
    )
    fun name(k: String) = if (k.startsWith("provider:")) providers.firstOrNull { it.id == k.removePrefix("provider:") }
        ?.let { it.title.ifBlank { it.baseUrl } } ?: k else kindNames[k] ?: k

    AppSectionCard {
        SectionHeader(Icons.Default.AutoAwesome, stringResource(R.string.ai_title))
        Hint(stringResource(R.string.ai_hint))
        val m = mode.ifEmpty { "auto" }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceButton(stringResource(R.string.ai_m_auto), m == "auto", Modifier.weight(1f)) { AiBackend.select("auto") }
            ChoiceButton(stringResource(R.string.ai_m_claude), m == "claude", Modifier.weight(1f)) { AiBackend.select("claude") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceButton(stringResource(R.string.ai_m_gemini), m == "gemini", Modifier.weight(1f)) { AiBackend.select("gemini") }
            ChoiceButton(stringResource(R.string.ai_m_provider), m == "provider", Modifier.weight(1f), enabled = providers.isNotEmpty()) {
                AiBackend.select("provider")
                if (provider !in ids) providers.firstOrNull()?.let { AiBackend.provider.set(it.id) }
            }
        }
        ChoiceButton(stringResource(R.string.ai_m_codex), m == "codex", Modifier.fillMaxWidth()) { AiBackend.select("codex") }
        if (m == "provider") {
            providers.forEach { p ->
                ChoiceButton(p.title.ifBlank { p.baseUrl }, p.id == provider, Modifier.fillMaxWidth()) { AiBackend.provider.set(p.id) }
            }
        }
        if (m == "auto") {
            Text(stringResource(R.string.ai_order), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val disabled = off.split(',').toSet().let { if (codexOn) it else it + "codex" }
            val order = AiBackend.normalizedChain(chain)
            order.forEachIndexed { i, k ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SwitchRow("${i + 1}. ${name(k)}", null, k !in disabled) { AiBackend.setEnabled(k, it) }
                    }
                    IconButton(onClick = { AiBackend.move(k, -1) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.prov_up)) }
                    IconButton(onClick = { AiBackend.move(k, 1) }, enabled = i < order.lastIndex) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.prov_down)) }
                }
            }
        }
        Text(stringResource(R.string.ai_now, if (plan.isEmpty()) stringResource(R.string.ai_none) else plan.joinToString(" → ") { name(it) }),
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Hint(stringResource(R.string.ai_enforced))
    }
}
