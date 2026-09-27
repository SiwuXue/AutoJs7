package org.autojs.autojs.mcp

/** The switch represents user intent; the summary reports the actual service state. */
internal object McpSwitchState {

    enum class Summary { STOPPED, STARTING, RUNNING, FAILED }

    fun nextEnabled(enabledIntent: Boolean): Boolean = !enabledIntent

    fun summary(
        enabledIntent: Boolean,
        socketListening: Boolean,
        foregroundServiceRunning: Boolean,
        failure: String?,
    ): Summary = when {
        !enabledIntent -> Summary.STOPPED
        failure != null -> Summary.FAILED
        socketListening && foregroundServiceRunning -> Summary.RUNNING
        else -> Summary.STARTING
    }
}
