package com.v1pach.telescript.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v1pach.telescript.AppSettings
import com.v1pach.telescript.R
import com.v1pach.telescript.Str
import com.v1pach.telescript.ota.DexManager
import com.v1pach.telescript.ota.OtaInstallActivity
import com.v1pach.telescript.ota.OtaManager
import com.v1pach.telescript.plugin.PluginScreen

/** Settings > Updates (OTA) > Plugin (.tgadex): status, install, check, apply/rollback, plugin screens. */
@Composable
fun DexSection() {
    val context = LocalContext.current
    val autoApply by AppSettings.dexAutoApply.flow.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    var openScreen by remember { mutableStateOf<PluginScreen?>(null) }
    val current = remember(refresh) { DexManager.current() }
    val manifest = remember(refresh) { DexManager.manifestOf(current) }
    val official = remember(refresh) { DexManager.isOfficial(current) }
    val loaded = DexManager.loadedManifest

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) context.startActivity(Intent(context, OtaInstallActivity::class.java).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    AppSectionCard {
        SectionHeader(Icons.Default.Extension, stringResource(R.string.dex_title))
        Hint(stringResource(R.string.dex_hint))
        if (manifest == null) Hint(stringResource(R.string.dex_none))
        else {
            Hint(stringResource(R.string.dex_installed, manifest.optString("name"), manifest.optString("version")))
            if (official == false) Hint(stringResource(R.string.dex_unofficial), MaterialTheme.colorScheme.error)
            val state = when {
                loaded != null && loaded.optString("version") == manifest.optString("version") -> stringResource(R.string.dex_state_loaded)
                DexManager.loadError.isNotEmpty() -> stringResource(R.string.dex_state_error, DexManager.loadError)
                else -> stringResource(R.string.dex_state_pending)
            }
            Hint(state, if (DexManager.loadError.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DexManager.rolledBack.takeIf { it.isNotEmpty() }?.let { Hint(stringResource(R.string.dex_rolled_back, it), MaterialTheme.colorScheme.error) }
        DexManager.screens().forEach { sc ->
            BigButton(sc.title, Icons.Default.Widgets, onClick = { openScreen = sc })
        }
        BigButton(stringResource(R.string.dex_from_file), Icons.Default.FileOpen, onClick = { picker.launch(arrayOf("*/*")) })
        BigButton(stringResource(R.string.dex_check), Icons.Default.CloudDownload, loading = busy, onClick = {
            busy = true; status = ""
            Thread({
                status = try {
                    val r = DexManager.checkGithub()
                    if (r == null) Str.get(R.string.ota_up_to_date) else {
                        context.startActivity(Intent(context, OtaInstallActivity::class.java).putExtra(OtaInstallActivity.EXTRA_DEX_DOWNLOADED, true))
                        Str.get(R.string.ota_found, r.second.version)
                    }
                } catch (e: Throwable) { OtaManager.errorText(e) }
                busy = false
            }, "dex-check").start()
        })
        if (status.isNotEmpty()) Hint(status)
        SwitchRow(stringResource(R.string.dex_auto_apply), stringResource(R.string.dex_auto_apply_hint), autoApply) { AppSettings.dexAutoApply.set(it) }
        if (DexManager.previous() != null) BigButton(stringResource(R.string.dex_rollback), Icons.Default.Undo, onClick = {
            DexManager.rollback(); refresh++; status = Str.get(R.string.ota_restart_note)
        })
        if (current != null) BigButton(stringResource(R.string.dex_remove), Icons.Default.RestartAlt, danger = true, onClick = { confirmReset = true })
    }
    openScreen?.let { sc ->
        Dialog(onDismissRequest = { openScreen = null }) {
            Surface(shape = MaterialTheme.shapes.large) {
                androidx.compose.foundation.layout.Column {
                    Text(sc.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding16())
                    val v = remember(sc.id) { DexManager.createView(context, sc.id) }
                    if (v != null) AndroidView(factory = { v }, modifier = Modifier.fillMaxWidth()) else Hint(stringResource(R.string.dex_screen_failed))
                    TextButton({ openScreen = null }) { Text(stringResource(R.string.ota_close)) }
                }
            }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text(stringResource(R.string.dex_remove)) }, text = { Text(stringResource(R.string.dex_remove_text)) },
        confirmButton = { TextButton({ confirmReset = false; DexManager.reset(); refresh++; status = Str.get(R.string.ota_restart_note) }) { Text(stringResource(R.string.dex_remove)) } },
        dismissButton = { TextButton({ confirmReset = false }) { Text(stringResource(R.string.ota_cancel)) } })
}

private fun Modifier.padding16(): Modifier = this.then(Modifier.padding(16.dp))
