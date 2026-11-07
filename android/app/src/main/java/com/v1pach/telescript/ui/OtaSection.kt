package com.v1pach.telescript.ui

import com.v1pach.telescript.R
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

@Composable
fun ApkUpdateSection() {
    val context = LocalContext.current
    val mode by AppSettings.apkMode.flow.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<ApkRelease?>(null) }
    var pct by remember { mutableIntStateOf(-1) }
    var needPerm by remember { mutableStateOf(false) }
    AppSectionCard {
        SectionHeader(Icons.Default.Android, stringResource(R.string.apk_title))
        Hint(stringResource(R.string.apk_hint, BuildConfig.FLAVOR))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("off" to R.string.apk_mode_off, "notify" to R.string.apk_mode_notify,
                "download" to R.string.apk_mode_download, "auto" to R.string.apk_mode_auto).forEach { (m, label) ->
                ChoiceButton(stringResource(label), mode == m, Modifier.weight(1f)) { AppSettings.apkMode.set(m) }
            }
        }
        if (mode == "auto") Hint(stringResource(R.string.apk_mode_auto_hint))
        BigButton(stringResource(R.string.apk_check), Icons.Default.CloudDownload, loading = busy, onClick = {
            busy = true; status = ""; found = null; needPerm = false
            Thread({
                status = try {
                    val r = ApkUpdater.check()
                    if (r == null) Str.get(R.string.ota_up_to_date) else { found = r; Str.get(R.string.apk_found, r.version, r.name) }
                } catch (e: Throwable) { OtaManager.errorText(e) }
                busy = false
            }, "apk-check").start()
        })
        found?.let { r ->
            BigButton(stringResource(R.string.apk_download_install, r.version), Icons.Default.SystemUpdate, loading = busy, onClick = {
                if (!ApkUpdater.canInstall()) { needPerm = true; return@BigButton }
                busy = true; pct = 0
                Thread({
                    status = try {
                        val f = ApkUpdater.download(r) { pct = (it * 100).toInt() }
                        ApkUpdater.install(f, silent = AppSettings.apkMode.value == "auto")
                        Str.get(R.string.apk_installing)
                    } catch (e: Throwable) { OtaManager.errorText(e) }
                    pct = -1; busy = false
                }, "apk-install").start()
            })
        }
        if (pct >= 0) { LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth()); Hint(stringResource(R.string.apk_downloading, pct)) }
        if (needPerm) {
            Hint(stringResource(R.string.apk_need_perm), MaterialTheme.colorScheme.error)
            BigButton(stringResource(R.string.apk_open_perm), Icons.Default.Settings, onClick = { context.startActivity(ApkUpdater.unknownSourcesIntent()) })
        }
        if (status.isNotEmpty()) Hint(status)
    }
}

@Composable
fun TrustedKeysSection() {
    val raw by AppSettings.otaTrustedKeys.flow.collectAsStateWithLifecycle()
    val keys = remember(raw) { TrustedKeys.parse(raw) }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AppSectionCard {
        SectionHeader(Icons.Default.VpnKey, stringResource(R.string.ota_trusted_title))
        Hint(stringResource(R.string.ota_trusted_hint))
        Hint(stringResource(R.string.ota_trusted_official, TrustedKeys.fingerprint(OtaKeys.publicKey)))
        if (keys.isEmpty()) Hint(stringResource(R.string.ota_trusted_none))
        keys.forEach { k ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Hint(stringResource(R.string.ota_trusted_entry, k.name, k.fingerprint))
                TextButton({ OtaManager.removeTrustedKey(k.keyHex) }) { Text(stringResource(R.string.ota_trusted_remove)) }
            }
        }
        BigButton(stringResource(R.string.ota_trusted_add), Icons.Default.Add, onClick = { adding = true; error = "" })
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false },
        title = { Text(stringResource(R.string.ota_trusted_add)) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(stringResource(R.string.ota_trusted_warn), MaterialTheme.colorScheme.error)
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ota_trusted_name)) }, singleLine = true)
                OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.ota_trusted_key)) })
                if (error.isNotEmpty()) Hint(error, MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton({
            try { OtaManager.addTrustedKey(name, key); adding = false; name = ""; key = "" }
            catch (e: IllegalArgumentException) {
                error = Str.get(when (e.message) { "name" -> R.string.ota_err_key_name; "official" -> R.string.ota_err_key_official
                    "dup" -> R.string.ota_err_key_dup; "limit" -> R.string.ota_err_key_limit; else -> R.string.ota_err_key_bad })
            }
        }) { Text(stringResource(R.string.ota_trusted_add)) } },
        dismissButton = { TextButton({ adding = false }) { Text(stringResource(R.string.ota_cancel)) } })
}

@Composable
fun OtaLog() {
    val log by AppSettings.otaLog.flow.collectAsStateWithLifecycle()
    AppSectionCard {
        SectionHeader(Icons.Default.History, stringResource(R.string.ota_log_title))
        Hint(if (log.isBlank()) stringResource(R.string.ota_log_empty) else log.lines().takeLast(12).reversed().joinToString("\n"))
    }
}
