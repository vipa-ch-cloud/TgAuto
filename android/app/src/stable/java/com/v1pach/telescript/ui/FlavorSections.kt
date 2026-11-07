package com.v1pach.telescript.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.v1pach.telescript.R
import com.v1pach.telescript.terminal.ConsoleDialog

/** Stable build: no extra settings sections (Nightly adds CLI console, providers and AI backend). */
@Composable fun FlavorTopSection() {}
@Composable fun FlavorLlmSections() {}

/** One button that opens the console (shell + AI-CLI) in theme colours. */
@Composable fun FlavorTerminalSection() {
    var open by remember { mutableStateOf(false) }
    AppSectionCard {
        SectionHeader(Icons.Default.Terminal, stringResource(R.string.console_card_title))
        Hint(stringResource(R.string.console_card_hint))
        BigButton(stringResource(R.string.console_open_btn), Icons.Default.Terminal, onClick = { open = true })
    }
    if (open) ConsoleDialog { open = false }
}
