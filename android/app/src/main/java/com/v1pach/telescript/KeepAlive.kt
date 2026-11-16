package com.v1pach.telescript

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Autostart and continuous operation of scripts.
 *  - per-script "autostart": started on boot / after an app update / when the app is opened;
 *  - global "keep running": scripts with autostart that exit or crash are restarted with backoff (watchdog),
 *    the service holds wake/wifi locks and is sticky.
 * A script the user stopped is never restarted until the user starts it again.
 */
object KeepAlive {
    /** Restart state of one script, shown on its card. */
    data class Watch(val attempt: Int = 0, val retryAt: Long = 0L, val note: String = "")

    private val _watch = MutableStateFlow<Map<String, Watch>>(emptyMap())
    val watch: StateFlow<Map<String, Watch>> = _watch

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val userStopped = HashSet<String>()
    private val startedAt = HashMap<String, Long>()
    private val timers = HashMap<String, Job>()
    private var last: Map<String, RunStatus> = emptyMap()

    const val STABLE_MS = 60_000L
    private const val BASE_DELAY_MS = 5_000L
    const val MAX_DELAY_MS = 5 * 60_000L

    val enabled: Boolean get() = AppSettings.keepAlive.value

    fun autostartIds(): Set<String> = AppSettings.autostartIds.value.split('\n').filter { it.isNotBlank() }.toSet()
    fun isAutostart(id: String) = id in autostartIds()
    fun setAutostart(id: String, on: Boolean) {
        val now = autostartIds()
        AppSettings.autostartIds.set((if (on) now + id else now - id).sorted().joinToString("\n"))
        if (!on) cancel(id)
    }

    /** Scripts that must run now: autostart ones the user did not stop. */
    fun wantedIds(): Set<String> = synchronized(userStopped) { autostartIds() - userStopped }

    /** True while the service has a reason to stay alive although no script is running right now. */
    fun holding(): Boolean = enabled && wantedIds().isNotEmpty()

    /** Backoff before restart number [attempt] (1-based): 5 s, 10 s, 20 s ... capped at 5 min. */
    fun backoff(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 16)
        return (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
    }

    fun userStart(id: String) {
        synchronized(userStopped) { userStopped.remove(id) }
        cancel(id)
    }

    fun userStop(id: String) {
        synchronized(userStopped) { userStopped.add(id) }
        cancel(id)
    }

    private fun cancel(id: String) {
        synchronized(timers) { timers.remove(id)?.cancel() }
        _watch.update { it - id }
    }

    /** Called by the service for every state change: detects exits and schedules restarts. */
    fun observe(now: Map<String, ScriptState>) {
        val prevMap = last
        last = now.mapValues { it.value.status }
        for ((id, st) in now) {
            val prev = prevMap[id]
            if (st.status == RunStatus.RUNNING && prev != RunStatus.RUNNING) {
                startedAt[id] = System.currentTimeMillis()
            } else if ((st.status == RunStatus.ERROR || st.status == RunStatus.IDLE) &&
                (prev == RunStatus.RUNNING || prev == RunStatus.STARTING)) {
                onExit(id, st)
            }
        }
    }

    private fun onExit(id: String, st: ScriptState) {
        if (!enabled || id !in wantedIds()) { cancel(id); return }
        val ran = System.currentTimeMillis() - (startedAt.remove(id) ?: 0L)
        val attempt = if (ran >= STABLE_MS) 1 else (_watch.value[id]?.attempt ?: 0) + 1
        val delayMs = backoff(attempt)
        val note = st.message.take(160)
        LogBus.w(id, Str.get(R.string.keep_log_restart, delayMs / 1000, attempt))
        synchronized(timers) {
            timers.remove(id)?.cancel()
            _watch.update { it + (id to Watch(attempt, System.currentTimeMillis() + delayMs, note)) }
            timers[id] = scope.launch {
                delay(delayMs)
                _watch.update { it + (id to (it[id] ?: Watch()).copy(retryAt = 0L)) }
                if (enabled && id in wantedIds() && ScriptManager.state(id).status.let { it == RunStatus.IDLE || it == RunStatus.ERROR }) {
                    startOne(id)
                }
            }
        }
    }

    /** Starts one wanted script; a failed preflight is recorded on the card instead of looping. */
    private fun startOne(id: String) {
        val script = ScriptRepository.byId(id) ?: return
        val problem = ScriptManager.preflight()
        if (problem != null) {
            _watch.update { it + (id to (it[id] ?: Watch()).copy(note = problem)) }
            return
        }
        ScriptManager.start(script, auto = true)
    }

    /** Boot / sticky restart / app open: starts every wanted script that is not running. Safe to call repeatedly. */
    fun startWanted() {
        Thread({
            val ids = wantedIds()
            if (ids.isEmpty()) return@Thread
            if (ids.any { ScriptRepository.byId(it) == null }) runCatching { ScriptRepository.scan() }
            for (id in ids) {
                val st = ScriptManager.state(id).status
                if (st == RunStatus.IDLE || st == RunStatus.ERROR) startOne(id)
            }
        }, "keepalive-start").start()
    }

    /** Starts the service if something is wanted and it is not running yet (the caller must be in the foreground or boot). */
    fun ensureService(context: Context) {
        if (wantedIds().isEmpty()) return
        runCatching {
            ContextCompat.startForegroundService(context,
                Intent(context, ScriptService::class.java).setAction(ScriptService.ACTION_AUTOSTART))
        }
    }
}
