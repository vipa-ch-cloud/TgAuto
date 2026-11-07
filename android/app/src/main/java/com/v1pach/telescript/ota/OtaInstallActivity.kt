package com.v1pach.telescript.ota

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v1pach.telescript.R
import com.v1pach.telescript.Str
import com.v1pach.telescript.store.Candidate
import com.v1pach.telescript.store.ScriptInstallContent
import com.v1pach.telescript.store.StoreManager
import com.v1pach.telescript.ui.*
import org.json.JSONObject
import java.io.File

/** Opened for .tgadyn / .tgascript files (VIEW/SEND, content:// and file://). Shows details, installs on confirmation. */
class OtaInstallActivity : ComponentActivity() {
    private sealed interface UiState {
        data object Loading : UiState
        data class Error(val text: String) : UiState
        data class Ota(val pkg: OtaPackage) : UiState
        data class Dex(val pkg: DexPackage, val file: File) : UiState
        data class Script(val c: Candidate) : UiState
        data class Done(val text: String) : UiState
    }

    companion object { const val EXTRA_DOWNLOADED = "downloaded"; const val EXTRA_DEX_DOWNLOADED = "downloaded_dex" }

    private var state by mutableStateOf<UiState>(UiState.Loading)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = if (intent.action == Intent.ACTION_SEND)
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM) else intent.data
        setAppScreen { Screen() }
        val dexDownloaded = intent.getBooleanExtra(EXTRA_DEX_DOWNLOADED, false)
        val downloaded = intent.getBooleanExtra(EXTRA_DOWNLOADED, false) || dexDownloaded
        if (uri == null && !downloaded) { state = UiState.Error(Str.get(R.string.ota_err_no_file)); return }
        Thread({
            state = try {
                // EXTRA_DOWNLOADED only ever points at the app's own, already size-capped download.
                val f = if (dexDownloaded) File(cacheDir, "dex-download.tgadex") else if (downloaded) File(cacheDir, "ota-download.tgadyn") else copyIncoming(this, uri!!, OtaCore.MAX_PACKAGE)
                when (OtaCore.peekFormat(f)) {
                    OtaCore.OTA_FORMAT -> UiState.Ota(OtaManager.verifyFile(f))
                    DexCore.FORMAT -> UiState.Dex(DexManager.verifyFile(f), f)
                    OtaCore.SCRIPT_FORMAT -> UiState.Script(StoreManager.fromFile(f))
                    else -> throw OtaException("bad_format", "manifest")
                }
            } catch (e: Throwable) { UiState.Error(OtaManager.errorText(e)) }
        }, "ota-open").start()
    }

    @Composable
    private fun Screen() {
        when (val s = state) {
            UiState.Loading -> Text(stringResource(R.string.ota_checking), color = MaterialTheme.colorScheme.onSurface)
            is UiState.Error -> ScrollColumn {
                TabHeader(stringResource(R.string.ota_install_title))
                AppSectionCard {
                    Text(stringResource(R.string.ota_rejected), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Hint(s.text)
                }
                BigButton(stringResource(R.string.ota_close), Icons.Default.Close, onClick = { finish() })
            }
            is UiState.Ota -> OtaDetails(s.pkg)
            is UiState.Dex -> DexDetails(s.pkg, s.file)
            is UiState.Script -> ScrollColumn {
                TabHeader(stringResource(R.string.store_install_title))
                ScriptInstallContent(s.c,
                    onInstall = {
                        Thread({
                            state = try { StoreManager.install(s.c); UiState.Done(Str.get(R.string.store_installed, s.c.name, s.c.version)) }
                            catch (e: Throwable) { UiState.Error(OtaManager.errorText(e)) }
                        }, "store-install").start()
                    }, onCancel = { finish() })
            }
            is UiState.Done -> ScrollColumn {
                TabHeader(stringResource(R.string.ota_install_title))
                AppSectionCard { Text(s.text, color = MaterialTheme.colorScheme.onSurface) }
                BigButton(stringResource(R.string.ota_close), Icons.Default.Close, onClick = { finish() })
            }
        }
    }

    @Composable
    private fun OtaDetails(pkg: OtaPackage) {
        val m: JSONObject = pkg.manifest
        val ru = Str.isRu()
        val change = m.optString(if (ru) "changelog_ru" else "changelog_en").ifBlank { m.optString("changelog_en") }
        var busy by remember { mutableStateOf(false) }
        val community = pkg.communityKey
        var accepted by remember { mutableStateOf(false) }
        ScrollColumn {
            TabHeader(stringResource(R.string.ota_install_title))
            AppSectionCard {
                Text(m.optString("name"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Hint(stringResource(R.string.ota_field_version, pkg.version, m.optInt("build")))
                Hint(stringResource(R.string.ota_field_channel, m.optString("channel", "any")))
                Hint(stringResource(R.string.ota_field_base, baseText(m)))
                Hint(stringResource(R.string.ota_field_installed, OtaManager.installedVersion()))
                Hint(stringResource(R.string.ota_field_files, pkg.files.size, pkg.files.keys.joinToString().take(300)))
                if (community == null) Hint(stringResource(R.string.ota_sig_ok), MaterialTheme.colorScheme.primary)
                else {
                    val author = OtaManager.communityAuthor(pkg).orEmpty()
                    Text(stringResource(R.string.ota_community_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Hint(stringResource(R.string.ota_community_text, author, TrustedKeys.fingerprint(community)), MaterialTheme.colorScheme.error)
                    Hint(stringResource(R.string.ota_sig_community, author))
                    SwitchRow(stringResource(R.string.ota_community_confirm), null, accepted) { accepted = it }
                }
                if (change.isNotBlank()) { Text(stringResource(R.string.ota_changelog), color = MaterialTheme.colorScheme.onSurface); Hint(change) }
            }
            Hint(stringResource(R.string.ota_restart_note))
            BigButton(stringResource(R.string.ota_install), Icons.Default.SystemUpdate, loading = busy, enabled = community == null || accepted, onClick = {
                busy = true
                Thread({
                    state = try { OtaManager.install(pkg); UiState.Done(Str.get(R.string.ota_installed_restart, pkg.version)) }
                    catch (e: Throwable) { UiState.Error(OtaManager.errorText(e)) }
                }, "ota-install").start()
            })
            BigButton(stringResource(R.string.ota_cancel), Icons.Default.Close, onClick = { finish() }, danger = true)
        }
    }

    @Composable
    private fun DexDetails(pkg: DexPackage, file: File) {
        val m = pkg.manifest
        val ru = Str.isRu()
        val change = m.optString(if (ru) "changelog_ru" else "changelog_en").ifBlank { m.optString("changelog_en") }
        var busy by remember { mutableStateOf(false) }
        var accepted by remember { mutableStateOf(false) }
        val community = pkg.communityKey
        ScrollColumn {
            TabHeader(stringResource(R.string.dex_install_title))
            AppSectionCard {
                Text(m.optString("name"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Hint(stringResource(R.string.dex_field_id, pkg.id, pkg.version, m.optInt("build")))
                Hint(stringResource(R.string.dex_field_req, m.optString("minAppVersion"), m.optInt("apiVersion"), m.optString("channel", "any")))
                Hint(stringResource(R.string.dex_field_entry, pkg.entry))
                Hint(stringResource(R.string.dex_field_installed, DexManager.installedVersion() ?: "-"))
                Text(stringResource(R.string.dex_code_warning_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Hint(stringResource(R.string.dex_code_warning), MaterialTheme.colorScheme.error)
                if (community == null) Hint(stringResource(R.string.ota_sig_ok), MaterialTheme.colorScheme.primary)
                else {
                    val author = OtaManager.communityAuthor(OtaPackage(m, emptyMap(), true, community)).orEmpty()
                    Text(stringResource(R.string.ota_community_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Hint(stringResource(R.string.ota_community_text, author, TrustedKeys.fingerprint(community)), MaterialTheme.colorScheme.error)
                    SwitchRow(stringResource(R.string.dex_community_confirm), null, accepted) { accepted = it }
                }
                if (change.isNotBlank()) { Text(stringResource(R.string.ota_changelog), color = MaterialTheme.colorScheme.onSurface); Hint(change) }
            }
            Hint(stringResource(R.string.ota_restart_note))
            BigButton(stringResource(R.string.ota_install), Icons.Default.SystemUpdate, loading = busy, enabled = community == null || accepted, onClick = {
                busy = true
                Thread({
                    state = try { DexManager.install(pkg, file); UiState.Done(Str.get(R.string.dex_installed_restart, pkg.version)) }
                    catch (e: Throwable) { UiState.Error(OtaManager.errorText(e)) }
                }, "dex-install").start()
            })
            BigButton(stringResource(R.string.ota_cancel), Icons.Default.Close, onClick = { finish() }, danger = true)
        }
    }

    private fun baseText(m: JSONObject): String {
        val rb = m.optJSONObject("requires_base") ?: return "-"
        fun r(k: String) = rb.optJSONArray(k)?.let { "${it.optString(0, "")}..${it.optString(1, "").let { s -> if (s == "null") "" else s }}" }
        return listOfNotNull(r("version_name")?.let { "versionName $it" }, r("version_code")?.let { "versionCode $it" }).joinToString("; ")
    }
}
