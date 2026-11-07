package com.v1pach.telescript.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v1pach.telescript.R
import com.v1pach.telescript.ui.*

/** Settings card: the terminal is off by default; switching it on needs an explicit confirmation. */
@Composable
fun TerminalCard() {
    val context = LocalContext.current
    val enabled by TerminalPrefs.enabled.flow.collectAsStateWithLifecycle()
    val lock by TerminalPrefs.lock.flow.collectAsStateWithLifecycle()
    var ask by remember { mutableStateOf(false) }
    val secure = TerminalActivity.deviceSecure(context)
    AppSectionCard {
        SectionHeader(Icons.Default.Terminal, stringResource(R.string.term_title))
        SwitchRow(stringResource(R.string.term_enable), null, enabled) { if (it) ask = true else TerminalPrefs.enabled.set(false) }
        if (enabled) {
            SwitchRow(stringResource(R.string.term_lock), stringResource(if (secure) R.string.term_lock_hint else R.string.term_lock_nosecure), lock, secure) { TerminalPrefs.lock.set(it) }
            BigButton(stringResource(R.string.term_open), Icons.Default.Terminal, { TerminalActivity.open(context, 0) })
            BigButton(stringResource(R.string.term_open_cli), Icons.Default.Terminal, { TerminalActivity.open(context, 1) })
        }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text(stringResource(R.string.term_confirm_title)) },
        text = { Text(stringResource(R.string.term_confirm_body)) },
        confirmButton = { TextButton({ ask = false; TerminalPrefs.enabled.set(true) }) { Text(stringResource(R.string.term_confirm_ok)) } },
        dismissButton = { TextButton({ ask = false }) { Text(stringResource(R.string.cli_cancel)) } },
    )
}
