package com.v1pach.telescript.terminal

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v1pach.telescript.AppSettings
import com.v1pach.telescript.FlavorHooks
import com.v1pach.telescript.LlmBridge
import com.v1pach.telescript.R
import com.v1pach.telescript.ui.*

/** claude / codex / gemini: status, install, update, remove, login (login runs in a real Termux session). */
@Composable
fun CliScreen() {
    val context = LocalContext.current
    val states by CliManager.state.collectAsStateWithLifecycle()
    var termuxOk by remember { mutableStateOf(CliManager.termuxReady()) }
    var confirm by remember { mutableStateOf<Pair<CliSpec, String>?>(null) }
    val proxy by TerminalPrefs.httpProxy.flow.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { termuxOk = CliManager.termuxReady() }
    LaunchedEffect(termuxOk) { if (termuxOk) CliCatalog.all.forEach { if (states[it.id]?.status == null) CliManager.check(it) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppSectionCard {
            SectionHeader(Icons.Default.Terminal, stringResource(R.string.cli_title))
            Hint(stringResource(R.string.cli_mode))
            if (!LlmBridge.termuxInstalled()) {
                Hint(stringResource(R.string.cli_no_termux), MaterialTheme.colorScheme.error)
                BigButton(stringResource(R.string.cli_get_termux), Icons.Default.Download, {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
            } else if (!LlmBridge.hasPermission()) {
                Hint(stringResource(R.string.cli_no_perm), MaterialTheme.colorScheme.error)
            } else {
                Hint(stringResource(R.string.cli_termux_hint))
                BigButton(stringResource(R.string.cli_open_termux), Icons.Default.Terminal, {
                    if (!CliManager.termuxShell(context)) toast(context, context.getString(R.string.cli_err_termux))
                })
            }
            AppTextField(proxy, { TerminalPrefs.httpProxy.set(it.trim()) }, stringResource(R.string.cli_proxy), placeholder = "http://127.0.0.1:8080")
            if (proxy.isNotBlank() && !CliLogic.validProxy(proxy)) Hint(stringResource(R.string.cli_proxy_bad), MaterialTheme.colorScheme.error)
            else Hint(stringResource(R.string.cli_proxy_hint))
        }

        CliCatalog.all.forEach { spec ->
            val st = states[spec.id] ?: CliState()
            CliCard(spec, st, termuxOk,
                onCheck = { CliManager.check(spec) },
                onInstall = { confirm = spec to CliLogic.redact(CliLogic.installScript(spec, CliManager.proxy())) },
                onRemove = { CliManager.remove(spec) },
                onLogin = { if (!CliManager.login(context, spec)) toast(context, context.getString(R.string.cli_err_termux)) })
        }

        if (FlavorHooks.managesBackend) {
            AppSectionCard {
                val on = CliManager.codexInBackend()
                Hint(stringResource(R.string.cli_backend_hint))
                BigButton(stringResource(if (on) R.string.cli_backend_on else R.string.cli_backend_enable), Icons.Default.Link,
                    { CliManager.enableCodexInBackend(); toast(context, context.getString(R.string.cli_backend_done)) }, enabled = !on)
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    confirm?.let { (spec, script) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.cli_confirm_title, spec.title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.cli_confirm_body))
                    SelectionContainer { Text(script, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            },
            confirmButton = { TextButton({ confirm = null; CliManager.install(spec) }) { Text(stringResource(R.string.cli_install_go)) } },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.cli_cancel)) } },
        )
    }
}

@Composable
private fun CliCard(spec: CliSpec, st: CliState, termuxOk: Boolean, onCheck: () -> Unit, onInstall: () -> Unit, onRemove: () -> Unit, onLogin: () -> Unit) {
    val status = st.status
    val busy = st.busy != null
    val outdated = status?.installed == true && st.latest != null && status.version.isNotEmpty() && CliLogic.compareVersions(status.version, st.latest) < 0
    AppSectionCard {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(spec.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            when {
                busy -> StatusPill(stringResource(R.string.cli_busy), MaterialTheme.colorScheme.tertiary)
                status == null -> StatusPill(stringResource(R.string.cli_unknown), MaterialTheme.colorScheme.outline)
                !status.installed -> StatusPill(stringResource(R.string.cli_not_installed), MaterialTheme.colorScheme.error)
                outdated -> StatusPill(stringResource(R.string.cli_outdated), MaterialTheme.colorScheme.tertiary)
                else -> StatusPill(stringResource(R.string.cli_installed), MaterialTheme.colorScheme.primary)
            }
        }
        Hint(spec.npm)
        if (status?.installed == true) {
            SelectionContainer { Hint(stringResource(R.string.cli_version, status.version.ifEmpty { "?" }) + (st.latest?.let { "  /  " + stringResource(R.string.cli_latest, it) } ?: "") + "\n" + status.path) }
        }
        if (st.error != CliError.NONE) Hint(CliManager.errorText(st.error), MaterialTheme.colorScheme.error)
        if (st.log.isNotBlank()) {
            SelectionContainer {
                Text(st.log.takeLast(1500), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()))
            }
        }
        val ok = termuxOk && !busy
        BigButton(stringResource(if (status?.installed == true) R.string.cli_update else R.string.cli_install), Icons.Default.Download, onInstall, enabled = ok, loading = st.busy == "install")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceButton(stringResource(R.string.cli_check), false, Modifier.weight(1f), enabled = ok, onClick = onCheck)
            ChoiceButton(stringResource(R.string.cli_login), false, Modifier.weight(1f), enabled = ok && status?.installed == true, onClick = onLogin)
            ChoiceButton(stringResource(R.string.cli_remove), false, Modifier.weight(1f), enabled = ok && status?.installed == true, onClick = onRemove)
        }
    }
}
