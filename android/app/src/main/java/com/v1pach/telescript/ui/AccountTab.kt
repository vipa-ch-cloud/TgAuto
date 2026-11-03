package com.v1pach.telescript.ui

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

    private fun creds(): Array<Any?> = arrayOf(
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
        LogBus.i("app", "Вход выполнен: ${r.optString("name")}")
    }

    private fun failed(r: JSONObject): Boolean {
        if (r.optString("status") == "ok") return false
        set { copy(error = r.optString("message", "ошибка")) }
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
            set { copy(error = "Сначала остановите все скрипты") }
            return@call
        }
        if (AppSettings.hasApiCredentials()) Py.loginCall("logout", *creds())
        Paths.sessionFile.delete()
        set { Ui(step = Step.LOGGED_OUT) }
        LogBus.i("app", "Выход из аккаунта")
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
        TabHeader("Аккаунт")

        // --- Login card
        AppSectionCard {
            SectionHeader(Icons.Default.Person, "Telegram")
            LoginSection(ui, credsOk)
            if (ui.error.isNotBlank()) Hint(ui.error, MaterialTheme.colorScheme.error)
        }

        // --- API credentials
        AppSectionCard {
            SectionHeader(Icons.Default.Key, "API Telegram")
            Hint("Получите api_id и api_hash на my.telegram.org → API development tools.")
            AppTextField(apiId, { AppSettings.apiId.set(it.filter(Char::isDigit)) }, "api_id",
                keyboardType = KeyboardType.Number, enabled = ui.step != AccountState.Step.LOGGED_IN || !credsOk)
            AppTextField(apiHash, { AppSettings.apiHash.set(it.trim()) }, "api_hash", secret = true,
                enabled = ui.step != AccountState.Step.LOGGED_IN || !credsOk)
        }

        // --- Proxy
        AppSectionCard {
            SectionHeader(Icons.Default.VpnLock, "Прокси")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("none" to "Нет", "socks5" to "SOCKS5", "mtproxy" to "MTProxy").forEach { (key, label) ->
                    ChoiceButton(label, proxyType == key, Modifier.weight(1f)) { AppSettings.proxyType.set(key) }
                }
            }
            if (proxyType != "none") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextField(proxyHost, { AppSettings.proxyHost.set(it.trim()) }, "Хост", Modifier.weight(2f))
                    AppTextField(proxyPort, { AppSettings.proxyPort.set(it.filter(Char::isDigit).take(5)) }, "Порт",
                        Modifier.weight(1f), keyboardType = KeyboardType.Number)
                }
                if (proxyType == "mtproxy") {
                    AppTextField(proxySecret, { AppSettings.proxySecret.set(it.trim()) }, "Secret", secret = true)
                    val fakeTls = proxySecret.trim().lowercase().startsWith("ee")
                    Hint(
                        if (fakeTls) "Secret начинается с «ee» (fake-TLS): Telethon такие прокси не поддерживает. " +
                            "Используйте secret с префиксом «dd» или обычный, либо SOCKS5."
                        else "Поддерживаются обычные и «dd» секреты. Fake-TLS («ee…») Telethon не умеет.",
                        if (fakeTls) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Hint("Прокси используется и для входа, и для запущенных скриптов (proxy.json / TELESCRIPT_PROXY).")
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
            Spacer(Modifier.width(12.dp)); Text("Проверка сессии…")
        }
        AccountState.Step.LOGGED_IN -> {
            StatusPill("вход выполнен", if (dark) AppColors.connectedDark else AppColors.connected)
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
                Hint("Сессия сохранена. Скрипты запускаются от этого аккаунта.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Обновить", Icons.Default.Refresh, onClick = { AccountState.check() },
                    modifier = Modifier.weight(1f), enabled = credsOk, loading = ui.busy)
                BigButton("Выйти", Icons.AutoMirrored.Filled.Logout, onClick = { AccountState.logout() },
                    modifier = Modifier.weight(1f), danger = true, enabled = !ui.busy)
            }
        }
        AccountState.Step.LOGGED_OUT -> {
            if (!credsOk) Hint("Сначала заполните api_id и api_hash ниже.", MaterialTheme.colorScheme.error)
            AppTextField(phone, { phone = it.filter { c -> c.isDigit() || c == '+' } }, "Номер телефона",
                placeholder = "+79991234567", keyboardType = KeyboardType.Phone, enabled = credsOk && !ui.busy)
            BigButton("Получить код", Icons.AutoMirrored.Filled.Send, onClick = { AccountState.sendCode(phone) },
                enabled = credsOk && phone.length >= 8, loading = ui.busy)
            TextButton(onClick = { AccountState.startQr() }, enabled = credsOk && !ui.busy,
                modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Войти по QR-коду")
            }
        }
        AccountState.Step.CODE -> {
            Hint("Код отправлен в Telegram (или по SMS) на $phone.")
            AppTextField(code, { code = it.filter(Char::isDigit).take(8) }, "Код из Telegram",
                keyboardType = KeyboardType.Number, enabled = !ui.busy)
            BigButton("Войти", Icons.AutoMirrored.Filled.Login, onClick = { AccountState.signInCode(code) },
                enabled = code.length >= 5, loading = ui.busy)
            TextButton(onClick = { code = ""; AccountState.cancel() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Отмена")
            }
        }
        AccountState.Step.PASSWORD -> {
            Hint("На аккаунте включена двухэтапная аутентификация. Введите облачный пароль.")
            AppTextField(password, { password = it }, "Пароль 2FA", secret = true, enabled = !ui.busy)
            BigButton("Подтвердить", Icons.Default.Lock, onClick = { AccountState.signInPassword(password) },
                enabled = password.isNotEmpty(), loading = ui.busy)
            TextButton(onClick = { password = ""; AccountState.cancel() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Отмена")
            }
        }
        AccountState.Step.QR -> {
            Hint("Telegram на другом устройстве → Настройки → Устройства → Подключить устройство, и отсканируйте код.")
            val bitmap = remember(ui.qrUrl) { if (ui.qrUrl.isBlank()) null else qrBitmap(ui.qrUrl, 600) }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), contentDescription = "QR-код входа",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(240.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(12.dp))
                } else CircularProgressIndicator()
            }
            Hint("Код обновляется автоматически.")
            TextButton(onClick = { AccountState.cancel() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Отмена")
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
