package com.v1pach.telescript.ui

import com.v1pach.telescript.R
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v1pach.telescript.*
import com.v1pach.telescript.ota.*
import com.v1pach.telescript.store.StoreActivity

/** Settings > Updates (OTA). */
@Composable
fun OtaSection() {
    val context = LocalContext.current
    val autoCheck by AppSettings.otaAutoCheck.flow.collectAsStateWithLifecycle()
    val autoApply by AppSettings.otaAutoApply.flow.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val current = remember(refresh) { OtaManager.current() }
    val otaVersion = remember(refresh) { OtaManager.manifestOf(current)?.optString("version") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) context.startActivity(Intent(context, OtaInstallActivity::class.java).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    AppSectionCard {
        SectionHeader(Icons.Default.SystemUpdate, stringResource(R.string.ota_title))
        Hint(stringResource(R.string.ota_base_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.FLAVOR))
        Hint(stringResource(R.string.ota_current, otaVersion ?: stringResource(R.string.ota_none)))
        OtaManager.rolledBack.takeIf { it.isNotEmpty() }?.let { Hint(stringResource(R.string.ota_rolled_back, it), MaterialTheme.colorScheme.error) }
        BigButton(stringResource(R.string.ota_from_file), Icons.Default.FileOpen, onClick = { picker.launch(arrayOf("*/*")) })
        BigButton(stringResource(R.string.ota_check), Icons.Default.CloudDownload, loading = busy, onClick = {
            busy = true; status = ""
            Thread({
                status = try {
                    val r = OtaManager.checkGithub()
                    if (r == null) Str.get(R.string.ota_up_to_date) else {
                        val f = OtaManager.download(r.url)
                        context.startActivity(Intent(context, OtaInstallActivity::class.java).putExtra(OtaInstallActivity.EXTRA_DOWNLOADED, true))
                        Str.get(R.string.ota_found, r.version)
                    }
                } catch (e: Throwable) { OtaManager.errorText(e) }
                busy = false
            }, "ota-check").start()
        })
        if (status.isNotEmpty()) Hint(status)
        SwitchRow(stringResource(R.string.ota_auto_check), stringResource(R.string.ota_auto_check_hint), autoCheck) { AppSettings.otaAutoCheck.set(it) }
        SwitchRow(stringResource(R.string.ota_auto_apply), stringResource(R.string.ota_auto_apply_hint), autoApply) { AppSettings.otaAutoApply.set(it) }
        if (OtaManager.previous() != null) BigButton(stringResource(R.string.ota_rollback), Icons.Default.Undo, onClick = {
            OtaManager.rollback(); refresh++; status = Str.get(R.string.ota_restart_note)
        })
        if (current != null) BigButton(stringResource(R.string.ota_reset), Icons.Default.RestartAlt, danger = true, onClick = { confirmReset = true })
        BigButton(stringResource(R.string.store_title), Icons.Default.Store, onClick = { context.startActivity(Intent(context, StoreActivity::class.java)) })
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text(stringResource(R.string.ota_reset)) }, text = { Text(stringResource(R.string.ota_reset_text)) },
        confirmButton = { TextButton({ confirmReset = false; OtaManager.reset(); refresh++; status = Str.get(R.string.ota_restart_note) }) { Text(stringResource(R.string.ota_reset)) } },
        dismissButton = { TextButton({ confirmReset = false }) { Text(stringResource(R.string.ota_cancel)) } })
}
