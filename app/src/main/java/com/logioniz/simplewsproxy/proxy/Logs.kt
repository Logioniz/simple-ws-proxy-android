package com.logioniz.simplewsproxy.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory ring buffer of the most recent log lines, observable from Compose. */
object Logs {

    private const val MAX_LINES = 500
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Synchronized
    fun add(message: String) {
        val line = "${timeFormat.format(Date())} $message"
        val next = _lines.value + line
        _lines.value = if (next.size > MAX_LINES) next.subList(next.size - MAX_LINES, next.size) else next
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
    }
}
