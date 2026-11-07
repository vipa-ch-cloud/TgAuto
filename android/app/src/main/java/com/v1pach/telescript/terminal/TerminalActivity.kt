package com.v1pach.telescript.terminal

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v1pach.telescript.R
import com.v1pach.telescript.ui.BigButton
import com.v1pach.telescript.ui.ChoiceButton
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v1pach.telescript.AppSettings
import com.v1pach.telescript.ui.TeleScriptTheme

/**
 * Built-in terminal and AI-CLI manager. Not exported and without intent filters: it can only be started by
 * the user's own tap in Settings (TerminalPolicy.grant), and it never reads a command from the intent.
 */
class TerminalActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_PAGE = "page" // 0 = terminal, 1 = AI CLIs; a UI hint only, never a command
        @Volatile private var unlocked = false
        @Volatile private var lastActive = 0L

        /** Called from a button handler in the app UI. */
        fun open(context: Context, page: Int = 0) {
            if (!TerminalPrefs.enabled.value) return
            TerminalPolicy.grant(System.currentTimeMillis())
            context.startActivity(Intent(context, TerminalActivity::class.java).putExtra(EXTRA_PAGE, page))
        }

        fun deviceSecure(context: Context): Boolean =
            context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }

    private var locked by mutableStateOf(true)
    private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) { unlocked = true; lastActive = System.currentTimeMillis(); locked = false } else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && !TerminalPolicy.consume(System.currentTimeMillis())) { finish(); return }
        if (!TerminalPrefs.enabled.value) { finish(); return }
        // The screen can show tokens and secrets: no screenshots, no recent-apps thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        TerminalHost.init(this)
        val startPage = intent.getIntExtra(EXTRA_PAGE, 0).coerceIn(0, 1)
        enableEdgeToEdge()
        setContent {
            val themeMode by AppSettings.themeMode.flow.collectAsStateWithLifecycle()
            val dynamicColor by AppSettings.dynamicColor.flow.collectAsStateWithLifecycle()
            val palette by AppSettings.palette.flow.collectAsStateWithLifecycle()
            TeleScriptTheme(themeMode = themeMode, dynamicColor = dynamicColor, themePalette = palette) {
              Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Surface(Modifier.fillMaxSize(), color = Color.Transparent) { Screen(startPage) }
              }
            }
        }
    }

    @Composable
    private fun Screen(startPage: Int) {
        run {
            var page by rememberSaveable { mutableIntStateOf(startPage) }
            if (locked) LockedView() else Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceButton(stringResource(R.string.term_page_terminal), page == 0, Modifier.weight(1f)) { page = 0 }
                    ChoiceButton(stringResource(R.string.term_page_cli), page == 1, Modifier.weight(1f)) { page = 1 }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (page == 0) TerminalScreen() else CliScreen()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        val now = System.currentTimeMillis()
        val secure = deviceSecure(this)
        if (TerminalPolicy.needsUnlock(TerminalPrefs.lock.value, secure, lastActive, now, unlocked)) {
            locked = true
            promptCredential()
        } else locked = false
    }

    override fun onStop() {
        super.onStop()
        lastActive = System.currentTimeMillis()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) unlocked = false
    }

    private fun promptCredential() {
        val intent = getSystemService(KeyguardManager::class.java)
            ?.createConfirmDeviceCredentialIntent(getString(R.string.term_unlock_title), getString(R.string.term_unlock_desc))
        if (intent == null) { locked = false; return }
        credential.launch(intent)
    }

    @Composable
    private fun LockedView() {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.term_locked), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            BigButton(stringResource(R.string.term_unlock), Icons.Default.Lock, onClick = { promptCredential() })
        }
    }
}
