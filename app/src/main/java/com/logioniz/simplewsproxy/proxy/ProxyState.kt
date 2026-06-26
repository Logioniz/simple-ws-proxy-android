package com.logioniz.simplewsproxy.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Severity of the status message shown on the Play screen. */
enum class StatusLevel { NONE, SUCCESS, ERROR }

data class StatusMessage(val text: String = "", val level: StatusLevel = StatusLevel.NONE)

/** Global, observable proxy run state shared between the service and the UI. */
object ProxyState {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _status = MutableStateFlow(StatusMessage())
    val status: StateFlow<StatusMessage> = _status.asStateFlow()

    fun setRunning(running: Boolean) {
        _running.value = running
    }

    fun setStatus(text: String, level: StatusLevel) {
        _status.value = StatusMessage(text, level)
    }
}
