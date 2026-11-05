package com.v1pach.telescript.ui

import com.v1pach.telescript.R
import com.v1pach.telescript.Str
import androidx.compose.ui.res.stringResource
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.v1pach.telescript.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Login state machine; lives outside composition so it survives tab switches. */
object AccountState {
    enum class Step { CHECKING, LOGGED_OUT, CODE, PASSWORD, QR, LOGGED_IN }

    data class Ui(
        val step: Step = Step.CHECKING,
        val busy: Boolean = false,
        val error: String = "",
        val name: String = "",
        val username: String = "",
        val phone: String = "",
        val userId: Long = 0,
        val qrUrl: String = "",
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var qrJob: Job? = null

    private fun set(transform: Ui.() -> Ui) { _ui.value = _ui.value.transform() }

    private fun creds(): Array<Any?> {
        if (AppSettings.proxyType.value == "mtproxy" && AppSettings.proxyHost.value.isNotBlank()) {
            AppSettings.proxySecretError(AppSettings.proxySecret.value)?.let { throw IllegalArgumentException(Str.get(it)) }
        }
        return credsArray()
    }

    private fun credsArray(): Array<Any?> = arrayOf(
        Paths.sessionFile.absolutePath,
        AppSettings.apiId.value.trim().toInt(),
        AppSettings.apiHash.value.trim(),
        AppSettings.proxyJson(),
    )

    private fun call(block: suspend () -> Unit) {
        if (_ui.value.busy) return
        set { copy(busy = true, error = "") }
        scope.launch {
            try { block() } catch (e: Throwable) { set { copy(error = e.message ?: e.toString()) } }
            finally { set { copy(busy = false) } }
        }
    }

    private fun loggedIn(r: JSONObject) {
        set { copy(step = Step.LOGGED_IN, name = r.optString("name"), username = r.optString("username"),
            phone = r.optString("phone"), userId = r.optLong("id"), qrUrl = "") }
        // Free the session file so scripts can open it.
        Py.loginCall("release")
        LogBus.i("app", Str.get(R.string.account_login_done, r.optString("name")))
    }

    private fun failed(r: JSONObject): Boolean {
        if (r.optString("status") == "ok") return false
        set { copy(error = r.optString("message", Str.get(R.string.account_error_default))) }
        return true
    }

    fun check() {
        if (!AppSettings.hasApiCredentials()) {
            set { copy(step = if (Paths.sessionFile.isFile) Step.LOGGED_IN else Step.LOGGED_OUT) }
            return
        }
        if (!Paths.sessionFile.isFile) {
            if (_ui.value.step == Step.CHECKING || _ui.value.step == Step.LOGGED_IN) set { copy(step = Step.LOGGED_OUT) }
            return
        }
        if (_ui.value.step in setOf(Step.CODE, Step.PASSWORD, Step.QR)) return
        if (ScriptManager.runningIds().isNotEmpty()) {
            // The session is in use by scripts; don't open it concurrently.
            set { copy(step = Step.LOGGED_IN) }
            return
        }
        call {
            val r = Py.loginCall("account_info", *creds())
            if (failed(r)) {
                set { copy(step = Step.LOGGED_IN) } // session exists; we just couldn't reach Telegram
                return@call
            }
            if (r.optBoolean("authorized")) loggedIn(r) else {
                Py.loginCall("release")
                set { copy(step = Step.LOGGED_OUT) }
            }
        }
    }

    fun sendCode(phone: String) = call {
        val normalized = phone.trim().replace(" ", "").replace("-", "")
        val r = Py.loginCall("send_code", *creds(), normalized)
        if (!failed(r)) set { copy(step = Step.CODE) }
    }

    fun signInCode(code: String) = call {
        val r = Py.loginCall("sign_in_code", code)
        if (failed(r)) return@call
        when (r.optString("step")) {
            "password" -> set { copy(step = Step.PASSWORD) }
            "done" -> loggedIn(r)
        }
    }

    fun signInPassword(password: String) = call {
        val r = Py.loginCall("sign_in_password", password)
        if (!failed(r)) loggedIn(r)
    }

    fun startQr() = call {
        val r = Py.loginCall("qr_start", *creds())
        if (failed(r)) return@call
        set { copy(step = Step.QR, qrUrl = r.optString("url")) }
        qrJob?.cancel()
        qrJob = scope.launch {
            while (isActive && _ui.value.step == Step.QR) {
                val w = Py.loginCall("qr_wait", 25)
                if (_ui.value.step != Step.QR) break
                if (w.optString("status") != "ok") { set { copy(error = w.optString("message")) }; break }
                when (w.optString("step")) {
                    "timeout" -> set { copy(qrUrl = w.optString("url")) }
                    "password" -> { set { copy(step = Step.PASSWORD, qrUrl = "") }; break }
                    "done" -> { loggedIn(w); break }
                }
            }
        }
    }

    fun cancel() {
        qrJob?.cancel()
        set { copy(step = Step.LOGGED_OUT, qrUrl = "", error = "") }
        scope.launch { Py.loginCall("release") }
    }

    fun logout() = call {
        if (ScriptManager.runningIds().isNotEmpty()) {
            set { copy(error = Str.get(R.string.account_stop_scripts_first)) }
            return@call
        }
        if (AppSettings.hasApiCredentials()) Py.loginCall("logout", *creds())
        Paths.sessionFile.delete()
        set { Ui(step = Step.LOGGED_OUT) }
        LogBus.i("app", Str.get(R.string.account_logged_out_log))
    }
}

@Composable
fun AccountTab() {
    val ui by AccountState.ui.collectAsStateWithLifecycle()
    val apiId by AppSettings.apiId.flow.collectAsStateWithLifecycle()
    val apiHash by AppSettings.apiHash.flow.collectAsStateWithLifecycle()
    val proxyType by AppSettings.proxyType.flow.collectAsStateWithLifecycle()
    val proxyHost by AppSettings.proxyHost.flow.collectAsStateWithLifecycle()
    val proxyPort by AppSettings.proxyPort.flow.collectAsStateWithLifecycle()
    val proxySecret by AppSettings.proxySecret.flow.collectAsStateWithLifecycle()
    val credsOk = AppSettings.hasApiCredentials()
    LaunchedEffect(Unit) { AccountState.check() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TabHeader(stringResource(R.string.account_title))

        // --- Login card
        AppSectionCard {
            SectionHeader(Icons.Default.Person, "Telegram")
            LoginSection(ui, credsOk)
            if (ui.error.isNotBlank()) Hint(ui.error, MaterialTheme.colorScheme.error)
        }

        // --- API credentials
        AppSectionCard {
            SectionHeader(Icons.Default.Key, "API Telegram")
            Hint(stringResource(R.string.account_api_hint))
            AppTextField(apiId, { AppSettings.apiId.set(it.filter(Char::isDigit)) }, "api_id",
                keyboardType = KeyboardType.Number, enabled = ui.step != AccountState.Step.LOGGED_IN || !credsOk)
            AppTextField(apiHash, { AppSettings.apiHash.set(it.trim()) }, "api_hash", secret = true,
                enabled = ui.step != AccountState.Step.LOGGED_IN || !credsOk)
        }

        // --- Proxy
        AppSectionCard {
            SectionHeader(Icons.Default.VpnLock, stringResource(R.string.account_proxy))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("none" to stringResource(R.string.account_proxy_none), "socks5" to "SOCKS5", "mtproxy" to "MTProxy").forEach { (key, label) ->
                    ChoiceButton(label, proxyType == key, Modifier.weight(1f)) { AppSettings.proxyType.set(key) }
                }
            }
            if (proxyType != "none") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextField(proxyHost, { AppSettings.proxyHost.set(it.trim()) }, stringResource(R.string.account_host), Modifier.weight(2f))
                    AppTextField(proxyPort, { AppSettings.proxyPort.set(it.filter(Char::isDigit).take(5)) }, stringResource(R.string.account_port),
                        Modifier.weight(1f), keyboardType = KeyboardType.Number)
                }
                if (proxyType == "mtproxy") {
                    AppTextField(proxySecret, { AppSettings.proxySecret.set(AppSettings.normalizeProxySecret(it)) }, "Secret", secret = true)
                    AppSettings.proxySecretError(proxySecret).takeIf { proxySecret.isNotEmpty() }?.let {
                        Hint(stringResource(it), MaterialTheme.colorScheme.error)
                    }
                    val fakeTls = proxySecret.trim().lowercase().startsWith("ee")
                    Hint(
                        if (fakeTls) stringResource(R.string.account_fake_tls_bad)
                        else stringResource(R.string.account_fake_tls_ok),
                        if (fakeTls) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Hint(stringResource(R.string.account_proxy_hint))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ColumnScope.LoginSection(ui: AccountState.Ui, credsOk: Boolean) {
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val dark = MaterialTheme.colorScheme.background.luminanceValue() < 0.22f
    when (ui.step) {
        AccountState.Step.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.account_checking))
        }
        AccountState.Step.LOGGED_IN -> {
            StatusPill(stringResource(R.string.account_signed_in_pill), if (dark) AppColors.connectedDark else AppColors.connected)
            if (ui.name.isNotBlank()) {
                Text(ui.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                val details = listOfNotNull(
                    ui.username.takeIf { it.isNotBlank() }?.let { "@$it" },
                    ui.phone.takeIf { it.isNotBlank() },
                    ui.userId.takeIf { it != 0L }?.let { "id $it" }
                ).joinToString(" · ")
                if (details.isNotBlank()) Hint(details)
            } else {
                Hint(stringResource(R.string.account_session_saved))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(stringResource(R.string.account_refresh), Icons.Default.Refresh, onClick = { AccountState.check() },
                    modifier = Modifier.weight(1f), enabled = credsOk, loading = ui.busy)
                BigButton(stringResource(R.string.account_logout), Icons.AutoMirrored.Filled.Logout, onClick = { AccountState.logout() },
                    modifier = Modifier.weight(1f), danger = true, enabled = !ui.busy)
            }
        }
        AccountState.Step.LOGGED_OUT -> {
            if (!credsOk) Hint(stringResource(R.string.account_fill_api), MaterialTheme.colorScheme.error)
            AppTextField(phone, { phone = it.filter { c -> c.isDigit() || c == '+' } }, stringResource(R.string.account_phone),
                placeholder = "+79991234567", keyboardType = KeyboardType.Phone, enabled = credsOk && !ui.busy)
            BigButton(stringResource(R.string.account_get_code), Icons.AutoMirrored.Filled.Send, onClick = { AccountState.sendCode(phone) },
                enabled = credsOk && phone.length >= 8, loading = ui.busy)
            TextButton(onClick = { AccountState.startQr() }, enabled = credsOk && !ui.busy,
                modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.account_qr_login))
            }
        }
        AccountState.Step.CODE -> {
            Hint(stringResource(R.string.account_code_sent, phone))
            AppTextField(code, { code = it.filter(Char::isDigit).take(8) }, stringResource(R.string.account_code_field),
                keyboardType = KeyboardType.Number, enabled = !ui.busy)
            BigButton(stringResource(R.string.account_sign_in), Icons.AutoMirrored.Filled.Login, onClick = { AccountState.signInCode(code) },
                enabled = code.length >= 5, loading = ui.busy)
            TextButton(onClick = { code = ""; AccountState.cancel() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.common_cancel))
            }
        }
        AccountState.Step.PASSWORD -> {
            Hint(stringResource(R.string.account_2fa_hint))
            AppTextField(password, { password = it }, stringResource(R.string.account_2fa_password), secret = true, enabled = !ui.busy)
            BigButton(stringResource(R.string.account_confirm), Icons.Default.Lock, onClick = { AccountState.signInPassword(password) },
                enabled = password.isNotEmpty(), loading = ui.busy)
            TextButton(onClick = { password = ""; AccountState.cancel() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.common_cancel))
            }
        }
        AccountState.Step.QR -> {
            Hint(stringResource(R.string.account_qr_hint))
            val bitmap = remember(ui.qrUrl) { if (ui.qrUrl.isBlank()) null else qrBitmap(ui.qrUrl, 600) }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), contentDescription = stringResource(R.string.account_qr_desc),
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(240.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(12.dp))
                } else CircularProgressIndicator()
            }
            Hint(stringResource(R.string.account_qr_auto))
            TextButton(onClick = { AccountState.cancel() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

private fun Color.luminanceValue(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private fun qrBitmap(text: String, size: Int): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}.getOrNull()
