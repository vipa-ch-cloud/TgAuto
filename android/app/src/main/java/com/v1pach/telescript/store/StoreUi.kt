package com.v1pach.telescript.store

import com.v1pach.telescript.R
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v1pach.telescript.*
import com.v1pach.telescript.ota.OtaCore
import com.v1pach.telescript.ota.OtaManager
import com.v1pach.telescript.ui.*

const val SUBMIT_URL = "https://github.com/${StoreManager.REPO}/issues/new?template=submit-script.yml"

/**
 * Install confirmation. Unverified or unsigned packages get a red warning with permissions,
 * hosts, hash and author; nothing is installed without pressing the button.
 */
@Composable
fun ScriptInstallContent(c: Candidate, onInstall: () -> Unit, onCancel: () -> Unit) {
    val ru = Str.isRu()
    val warn = c.needsWarning
    var ack by remember { mutableStateOf(!warn) }
    var busy by remember { mutableStateOf(false) }
    val installedVer = StoreManager.installed.value[c.id]?.version
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AppSectionCard {
            Text(c.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Hint("id: ${c.id}  v${c.version}" + (installedVer?.let { "  (" + stringResource(R.string.store_installed_ver, it) + ")" } ?: ""))
            Hint(stringResource(R.string.store_author, c.author.ifBlank { "?" }))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.verified) StatusPill(stringResource(if (c.entry?.official == true) R.string.store_official else R.string.store_verified), MaterialTheme.colorScheme.primary)
                else StatusPill(stringResource(R.string.store_unverified), MaterialTheme.colorScheme.error)
                StatusPill(stringResource(if (c.signed) R.string.store_signed else R.string.store_unsigned),
                    if (c.signed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
            }
            val d = c.m.optString(if (ru) "description_ru" else "description_en").ifBlank { c.m.optString("description_en") }
            if (d.isNotBlank()) Text(d, color = MaterialTheme.colorScheme.onSurface)
            Hint("sha256: ${c.sha256}")
        }
        if (warn) AppSectionCard {
            Text(stringResource(R.string.store_warn_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Hint(stringResource(R.string.store_warn_text), MaterialTheme.colorScheme.error)
        }
        AppSectionCard {
            Text(stringResource(R.string.store_perms), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            val declared = c.m.optJSONObject("permissions")
            Hint(stringResource(R.string.store_perms_declared) + ": " + (declared?.toString()?.takeIf { it != "{}" } ?: "-"))
            Hint(stringResource(R.string.store_perms_net) + ": " + c.scan.hosts.joinToString().ifEmpty { "-" })
            Hint(stringResource(R.string.store_perms_tg) + ": " + c.scan.telegram.joinToString().ifEmpty { "-" })
            Hint(stringResource(R.string.store_perms_risky) + ": " + c.scan.risky.joinToString().ifEmpty { "-" },
                if (c.scan.risky.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Hint(stringResource(R.string.store_perms_note))
            Hint(stringResource(R.string.store_files, c.pkg.files.size))
        }
        val cl = c.m.optString(if (ru) "changelog_ru" else "changelog_en").ifBlank { c.m.optString("changelog_en") }
        if (cl.isNotBlank()) AppSectionCard { Text(stringResource(R.string.ota_changelog), color = MaterialTheme.colorScheme.onSurface); Hint(cl) }
        if (warn) Row(Modifier.fillMaxWidth().clickable { ack = !ack }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(ack, { ack = it })
            Text(stringResource(R.string.store_ack), color = MaterialTheme.colorScheme.onSurface)
        }
        BigButton(stringResource(if (installedVer != null) R.string.store_update else R.string.store_install), Icons.Default.Download,
            enabled = ack, loading = busy, onClick = { busy = true; onInstall() })
        BigButton(stringResource(R.string.ota_cancel), Icons.Default.Close, onClick = onCancel, danger = true)
    }
}

class StoreActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreen { StoreScreen(onBack = { finish() }) }
        Thread({ StoreManager.refresh() }, "store-refresh").start()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by StoreManager.entries.collectAsStateWithLifecycle()
    val installed by StoreManager.installed.collectAsStateWithLifecycle()
    val loading by StoreManager.loading.collectAsStateWithLifecycle()
    val error by StoreManager.error.collectAsStateWithLifecycle()
    val auto by AppSettings.storeAutoUpdate.flow.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<StoreEntry?>(null) }
    var candidate by remember { mutableStateOf<Candidate?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val ru = Str.isRu()

    fun run(block: () -> Unit) {
        busy = true; message = ""
        Thread({
            try { block() } catch (e: Throwable) { message = OtaManager.errorText(e); candidate = null } finally { busy = false }
        }, "store-op").start()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) run {
            val f = copyIncoming(context, uri, OtaCore.MAX_PACKAGE)
            candidate = StoreManager.fromFile(f)
        }
    }
    BackHandler { when { candidate != null -> candidate = null; selected != null -> selected = null; else -> onBack() } }

    val cand = candidate
    if (cand != null) {
        ScrollColumn {
            TabHeader(stringResource(R.string.store_install_title))
            ScriptInstallContent(cand, onInstall = {
                run { StoreManager.install(cand); message = Str.get(R.string.store_installed, cand.name, cand.version); candidate = null }
            }, onCancel = { candidate = null })
            if (message.isNotBlank()) Hint(message, MaterialTheme.colorScheme.error)
        }
        return
    }

    val sel = selected
    if (sel != null) {
        val inst = installed[sel.id]
        ScrollColumn {
            TabHeader(sel.name)
            AppSectionCard {
                Hint("id: ${sel.id}  v${sel.version}  ·  " + stringResource(R.string.store_author, sel.author.ifBlank { "?" }))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sel.verified) StatusPill(stringResource(if (sel.official) R.string.store_official else R.string.store_verified), MaterialTheme.colorScheme.primary)
                    else StatusPill(stringResource(R.string.store_unverified), MaterialTheme.colorScheme.error)
                    if (inst != null) StatusPill(stringResource(R.string.store_installed_ver, inst.version), MaterialTheme.colorScheme.secondary)
                }
                Text(sel.desc(ru), color = MaterialTheme.colorScheme.onSurface)
                if (sel.tags.isNotEmpty()) Hint(sel.tags.joinToString(" · ") { "#$it" })
                Hint(stringResource(R.string.store_perms_declared) + ": " + (sel.permissions.toString().takeIf { it != "{}" } ?: "-"))
                Hint("sha256: ${sel.sha256}")
            }
            if (sel.changelog(ru).isNotBlank()) AppSectionCard {
                Text(stringResource(R.string.ota_changelog), color = MaterialTheme.colorScheme.onSurface); Hint(sel.changelog(ru))
            }
            val upd = inst != null && OtaCore.vcmp(sel.version, inst.version) > 0
            if (inst == null || upd) BigButton(stringResource(if (upd) R.string.store_update else R.string.store_install), Icons.Default.Download,
                loading = busy, onClick = { run { candidate = StoreManager.fromEntry(sel) } })
            if (inst?.previous != null) BigButton(stringResource(R.string.store_rollback, inst.previous), Icons.Default.Undo, loading = busy,
                onClick = { run { StoreManager.rollback(sel.id); message = Str.get(R.string.store_rolled_back) } })
            if (inst != null) BigButton(stringResource(R.string.store_delete), Icons.Default.Delete, danger = true, onClick = { confirmDelete = true })
            if (message.isNotBlank()) Hint(message, MaterialTheme.colorScheme.error)
            BigButton(stringResource(R.string.ota_close), Icons.Default.Close, onClick = { selected = null })
        }
        if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.store_delete)) }, text = { Text(stringResource(R.string.store_delete_text)) },
            confirmButton = { TextButton({ confirmDelete = false; run { StoreManager.delete(sel.id, false) } }) { Text(stringResource(R.string.store_delete_keep)) } },
            dismissButton = { TextButton({ confirmDelete = false; run { StoreManager.delete(sel.id, true) } }) { Text(stringResource(R.string.store_delete_wipe)) } })
        return
    }

    val allTags = entries.flatMap { it.tags }.distinct().sorted()
    val shown = entries.filter { e ->
        (tag.isEmpty() || tag in e.tags) && (query.isBlank() ||
            listOf(e.name, e.id, e.author, e.descEn, e.descRu, e.tags.joinToString(" ")).any { it.contains(query.trim(), true) })
    }
    ScrollColumn {
        TabHeader(stringResource(R.string.store_title)) {
            IconButton(onClick = { Thread({ StoreManager.refresh() }, "store-refresh").start() }) {
                Icon(Icons.Default.Refresh, stringResource(R.string.store_refresh))
            }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.store_search)) }, leadingIcon = { Icon(Icons.Default.Search, null) })
        if (allTags.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tag.isEmpty(), { tag = "" }, label = { Text(stringResource(R.string.store_all)) })
            allTags.forEach { t -> FilterChip(tag == t, { tag = if (tag == t) "" else t }, label = { Text(t) }) }
        }
        val upds = StoreManager.updates()
        if (upds.isNotEmpty()) Hint(stringResource(R.string.store_updates_n, upds.size), MaterialTheme.colorScheme.primary)
        SwitchRow(stringResource(R.string.store_auto), stringResource(R.string.store_auto_hint), auto) { AppSettings.storeAutoUpdate.set(it) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotEmpty()) Hint(error, MaterialTheme.colorScheme.error)
        if (!loading && error.isEmpty() && shown.isEmpty()) Hint(stringResource(R.string.store_empty))
        shown.forEach { e ->
            val inst = installed[e.id]
            val upd = inst != null && OtaCore.vcmp(e.version, inst.version) > 0
            AppSectionCard(Modifier.clickable { selected = e }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(e.name + "  v" + e.version, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Hint(e.desc(ru))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (e.verified) StatusPill(stringResource(if (e.official) R.string.store_official else R.string.store_verified), MaterialTheme.colorScheme.primary)
                    else StatusPill(stringResource(R.string.store_unverified), MaterialTheme.colorScheme.error)
                    if (upd) StatusPill(stringResource(R.string.store_update_avail, inst!!.version), MaterialTheme.colorScheme.tertiary)
                    else if (inst != null) StatusPill(stringResource(R.string.store_installed_ver, inst.version), MaterialTheme.colorScheme.secondary)
                }
            }
        }
        if (message.isNotBlank()) Hint(message, MaterialTheme.colorScheme.error)
        BigButton(stringResource(R.string.store_from_file), Icons.Default.FileOpen, loading = busy,
            onClick = { picker.launch(arrayOf("*/*")) })
        BigButton(stringResource(R.string.store_submit), Icons.Default.Upload, onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SUBMIT_URL))) }
        })
        Hint(stringResource(R.string.store_submit_hint))
        Spacer(Modifier.height(12.dp))
    }
}
