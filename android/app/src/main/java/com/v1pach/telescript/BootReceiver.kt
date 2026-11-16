package com.v1pach.telescript

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts autostart scripts after boot and after the app was updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Preferences live in credential-protected storage: before the first unlock they are not readable,
                // BOOT_COMPLETED follows once the user unlocks, so a locked-boot call is simply ignored.
                if (Intent.ACTION_LOCKED_BOOT_COMPLETED == intent.action &&
                    !(context.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked ?: true)) return
                KeepAlive.ensureService(context.applicationContext)
            }
        }
    }
}
