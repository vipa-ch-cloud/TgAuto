package com.v1pach.telescript.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import com.v1pach.telescript.AppSettings
import com.v1pach.telescript.KeepAlive
import com.v1pach.telescript.R

/** Vendor autostart screens (best effort: the first one that opens wins, then app details). */
private val OEM_SCREENS: Map<String, List<ComponentName>> = mapOf(
    "xiaomi" to listOf(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
    "redmi" to listOf(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
    "poco" to listOf(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
    "huawei" to listOf(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
    "honor" to listOf(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
    "oppo" to listOf(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
    "realme" to listOf(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
    "oneplus" to listOf(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
    "vivo" to listOf(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
    "iqoo" to listOf(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
    "samsung" to listOf(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
    "asus" to listOf(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")),
    "meizu" to listOf(ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")),
    "tecno" to listOf(ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity")),
    "infinix" to listOf(ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity")),
)

private fun oemKey(): String? = Build.MANUFACTURER.lowercase().let { m -> OEM_SCREENS.keys.firstOrNull { it in m } }

fun isAggressiveOem(): Boolean = oemKey() != null

fun oemName(): String = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }

/** Opens the vendor autostart screen, or the app details page when none of the known ones exists. */
fun openOemAutostart(context: Context) {
    for (c in OEM_SCREENS[oemKey()].orEmpty()) {
        val ok = runCatching { context.startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (ok) return
    }
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Card with the global keep-running switch, watchdog status, battery exemption and the vendor hint. */
@Composable
fun KeepAliveCard(batteryOk: Boolean) {
    val context = LocalContext.current
    val on by AppSettings.keepAlive.flow.collectAsStateWithLifecycle()
    val ids by AppSettings.autostartIds.flow.collectAsStateWithLifecycle()
    val guarded = ids.split('\n').count { it.isNotBlank() }
    AppSectionCard {
        SectionHeader(Icons.Default.BatteryAlert, stringResource(R.string.keep_title))
        SwitchRow(stringResource(R.string.keep_switch), stringResource(R.string.keep_switch_sub), on) {
            AppSettings.keepAlive.set(it)
            if (it) {
                if (!isIgnoringBattery(context)) requestIgnoreBattery(context)
                KeepAlive.ensureService(context)
            }
        }
        Hint(if (guarded > 0) stringResource(R.string.keep_status_guarded, guarded) else stringResource(R.string.keep_status_none))
        if (batteryOk) {
            Hint(stringResource(R.string.keep_battery_ok))
        } else {
            Hint(stringResource(R.string.keep_battery_bad), MaterialTheme.colorScheme.error)
            Hint(stringResource(R.string.scripts_battery_hint))
            BigButton(stringResource(R.string.scripts_battery_btn), Icons.Default.BatteryAlert, onClick = { requestIgnoreBattery(context) })
        }
        if (isAggressiveOem()) {
            Hint(stringResource(R.string.keep_oem_title, oemName()) + ". " + stringResource(R.string.keep_oem_hint))
            BigButton(stringResource(R.string.keep_oem_btn), Icons.Default.Settings, onClick = { openOemAutostart(context) })
        }
    }
}
