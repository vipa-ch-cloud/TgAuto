package com.v1pach.telescript.ui

import androidx.compose.runtime.Composable

/** Stable build: no extra settings sections (Nightly adds CLI console, providers and AI backend). */
@Composable fun FlavorTopSection() {}
@Composable fun FlavorLlmSections() {}

/** Terminal and AI CLI are nightly-only in 1.9.2 (stable gets them in 1.9.3). */
@Composable fun FlavorTerminalSection() {}
