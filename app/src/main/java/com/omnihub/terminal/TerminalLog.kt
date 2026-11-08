package com.omnihub.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared log of every command that ran through OmniTerminal — typed by the
 * user in the Terminal screen, executed by the AI through its tools, or
 * proxied from an outside MCP client through the device tunnel. One scrollback,
 * fully attributed, so nothing the AI runs is ever invisible.
 */
object TerminalLog {

    enum class Origin { USER, AI, TUNNEL }

    data class Entry(
        val id: Long,
        val ts: Long,
        val origin: Origin,
        val command: String,
        val output: String,
        val exitCode: Int
    )

    private val buf = ArrayDeque<Entry>()
    private val seq = java.util.concurrent.atomic.AtomicLong(1)
    private const val MAX = 300

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun record(origin: Origin, command: String, output: String, exitCode: Int) {
        synchronized(buf) {
            buf.addLast(Entry(seq.getAndIncrement(), System.currentTimeMillis(), origin, command, output, exitCode))
            while (buf.size > MAX) buf.removeFirst()
        }
        _entries.value = synchronized(buf) { buf.toList() }
    }

    fun clear() {
        synchronized(buf) { buf.clear() }
        _entries.value = emptyList()
    }
}
