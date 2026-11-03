package com.v1pach.telescript

import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

@Immutable
data class LogEntry(
    val key: Long,
    val source: String,
    val message: String,
    val count: Int,
    val priority: Int, // 3=DEBUG, 4=INFO, 5=WARN, 6=ERROR
    val time: String,
)

/**
 * Log console backend. Python calls [emit] from any thread; lines are queued and
 * merged into [logs] in batches every 150 ms (consecutive duplicates are counted).
 */
object LogBus {
    private const val CAP = 1500
    private val queue = ConcurrentLinkedQueue<LogEntry>()
    private val keys = AtomicLong()
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs
    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (true) {
                delay(150)
                flush()
            }
        }
    }

    @JvmStatic
    fun emit(source: String, level: Int, message: String) {
        Log.println(level.coerceIn(Log.VERBOSE, Log.ASSERT), "TS/$source", message)
        queue.add(LogEntry(keys.incrementAndGet(), source, message, 1, level,
            timeFormat.get()!!.format(Date())))
    }

    fun i(source: String, message: String) = emit(source, 4, message)
    fun w(source: String, message: String) = emit(source, 5, message)
    fun e(source: String, message: String) = emit(source, 6, message)

    fun clear() {
        queue.clear()
        _logs.value = emptyList()
    }

    private fun flush() {
        if (queue.isEmpty()) return
        val list = ArrayList(_logs.value)
        while (true) {
            val entry = queue.poll() ?: break
            val depth = minOf(10, list.size)
            var merged = false
            for (i in list.size - 1 downTo list.size - depth) {
                val old = list[i]
                if (old.source == entry.source && old.message == entry.message && old.priority == entry.priority) {
                    if (i == list.size - 1) {
                        list[i] = old.copy(count = old.count + 1, time = entry.time)
                        merged = true
                    }
                    break
                }
            }
            if (!merged) list.add(entry)
        }
        while (list.size > CAP) list.removeAt(0)
        _logs.value = list
    }
}
