package com.v1pach.telescript

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and the Python threads) alive while scripts run.
 * START_STICKY: if the system kills the process the service is recreated and autostart scripts come back.
 * The watchdog (KeepAlive) restarts crashed autostart scripts with backoff while the keep-running switch is on.
 */
class ScriptService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        goForeground(buildNotification(emptySet(), emptyMap()))
        watcher = scope.launch {
            var seenRunning = false
            combine(ScriptManager.states, KeepAlive.watch, AppSettings.keepAlive.flow) { s, w, _ -> s to w }.collect { (states, watch) ->
                KeepAlive.observe(states)
                val running = ScriptManager.runningIds()
                if (running.isNotEmpty()) seenRunning = true
                val hold = running.isNotEmpty() || KeepAlive.holding()
                updateLocks(hold, KeepAlive.enabled)
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(running, watch))
                if (!hold && seenRunning) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_START -> id?.let(ScriptRepository::byId)?.let { ScriptManager.start(it) }
            ACTION_STOP -> id?.let(ScriptManager::stop)
            ACTION_STOP_ALL -> ScriptManager.stopAll()
            // null intent = the system recreated the sticky service after killing the process
            ACTION_AUTOSTART, null -> KeepAlive.startWanted()
        }
        val auto = intent == null || intent.action == ACTION_AUTOSTART
        if (auto) {
            if (KeepAlive.wantedIds().isEmpty()) stopSelf()
            // autostart that failed to launch anything must not leave an idle service behind
            else scope.launch {
                kotlinx.coroutines.delay(30_000)
                if (ScriptManager.runningIds().isEmpty() && !KeepAlive.holding()) stopSelf()
            }
        } else if (intent?.action != ACTION_START && ScriptManager.runningIds().isEmpty() && !KeepAlive.holding()) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        updateLocks(false, false)
        super.onDestroy()
    }

    /** Wake lock while something runs or is waiting for a restart; wifi lock additionally in keep-running mode. */
    private fun updateLocks(hold: Boolean, keep: Boolean) {
        if (hold) {
            if (wakeLock == null) wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgAuto:scripts").apply { setReferenceCounted(false) }
            wakeLock?.let { if (!it.isHeld) it.acquire() }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        if (hold && keep) {
            if (wifiLock == null) wifiLock = runCatching {
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
                (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(mode, "TgAuto:scripts")
                    .apply { setReferenceCounted(false) }
            }.getOrNull()
            runCatching { wifiLock?.let { if (!it.isHeld) it.acquire() } }
        } else {
            runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        }
    }

    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(running: Set<String>, watch: Map<String, KeepAlive.Watch>): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopAll = PendingIntent.getService(this, 1,
            Intent(this, ScriptService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        var text = if (running.isEmpty()) getString(R.string.svc_starting) else getString(R.string.svc_running, running.sorted().joinToString(", "))
        val pending = watch.filterValues { it.retryAt > 0 }
        if (pending.isNotEmpty()) text += "\n" + getString(R.string.svc_retry, pending.keys.sorted().joinToString(", "))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_plane)
            .setContentTitle("TgAuto")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.svc_stop_all), stopAll)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "scripts"
        private const val NOTIFICATION_ID = 7
        const val ACTION_START = "com.v1pach.telescript.START"
        const val ACTION_STOP = "com.v1pach.telescript.STOP"
        const val ACTION_STOP_ALL = "com.v1pach.telescript.STOP_ALL"
        const val ACTION_AUTOSTART = "com.v1pach.telescript.AUTOSTART"
        const val EXTRA_ID = "id"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.svc_channel), NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        fun start(context: Context, id: String) {
            ContextCompat.startForegroundService(context,
                Intent(context, ScriptService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id))
        }

        fun stop(id: String) = ScriptManager.stop(id)
    }
}
