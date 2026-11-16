package com.v1pach.telescript.ota

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.v1pach.telescript.R
import com.v1pach.telescript.ui.*

/** In-app APK install screen: opened by a tap on the update notification. Shows the ready APK, permission state and the last attempt log. */
class ApkInstallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreen { Screen() }
    }

    @Composable
    private fun Screen() = ScrollColumn {
        TabHeader(stringResource(R.string.apk_install_title))
        ApkReadyCard()
        BigButton(stringResource(R.string.ota_close), Icons.Default.Close, onClick = { finish() })
    }
}
