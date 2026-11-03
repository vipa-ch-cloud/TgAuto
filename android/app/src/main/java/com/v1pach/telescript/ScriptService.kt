package com.v1pach.telescript

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import kotlinx.coroutines.launch

/** Foreground service that keeps the process (and the Python threads) alive while scripts run. */
class ScriptService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        goForeground(buildNotification(emptySet()))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TeleScript:scripts")
            .apply { setReferenceCounted(false); acquire() }
        watcher = scope.launch {
            var seenRunning = false
            ScriptManager.states.collect {
                val running = ScriptManager.runningIds()
                if (running.isNotEmpty()) seenRunning = true
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(running))
                if (running.isEmpty() && seenRunning) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_START -> id?.let(ScriptRepository::byId)?.let(ScriptManager::start)
            ACTION_STOP -> id?.let(ScriptManager::stop)
            ACTION_STOP_ALL -> ScriptManager.stopAll()
        }
        if (ScriptManager.runningIds().isEmpty() && intent?.action != ACTION_START) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
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

    private fun buildNotification(running: Set<String>): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopAll = PendingIntent.getService(this, 1,
            Intent(this, ScriptService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = if (running.isEmpty()) "Запуск…" else "Работают: " + running.sorted().joinToString(", ")
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_plane)
            .setContentTitle("TeleScript")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Остановить всё", stopAll)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "scripts"
        private const val NOTIFICATION_ID = 7
        const val ACTION_START = "com.v1pach.telescript.START"
        const val ACTION_STOP = "com.v1pach.telescript.STOP"
        const val ACTION_STOP_ALL = "com.v1pach.telescript.STOP_ALL"
        const val EXTRA_ID = "id"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Работающие скрипты", NotificationManager.IMPORTANCE_LOW)
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
