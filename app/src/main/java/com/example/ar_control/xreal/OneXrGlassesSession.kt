package com.example.ar_control.xreal

import com.example.ar_control.diagnostics.NoOpSessionLog
import com.example.ar_control.diagnostics.SessionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class OneXrGlassesSession(
    private val facade: OneXrFacade,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val sessionLog: SessionLog = NoOpSessionLog
) : GlassesSession {
    private val _state = MutableStateFlow(mapState(facade.sessionState.value))
    private var sessionStateJob: Job? = null

    override val state: StateFlow<GlassesSession.State> = _state

    override suspend fun start() {
        if (sessionStateJob != null) {
            return
        }
        sessionLog.record("OneXrGlassesSession", "Starting one-xr session")

        val collectorJob = scope.launch {
            facade.sessionState.collectLatest { state ->
                sessionLog.record("OneXrGlassesSession", "Facade session state: ${mapState(state)}")
                _state.value = mapState(state)
            }
        }
        sessionStateJob = collectorJob

        try {
            facade.start()
            _state.value = mapState(facade.sessionState.value)
            sessionLog.record("OneXrGlassesSession", "one-xr session started")
        } catch (error: Throwable) {
            sessionStateJob = null
            collectorJob.cancelAndJoin()
            sessionLog.record(
                "OneXrGlassesSession",
                "one-xr session start failed: ${error.message ?: "unknown_error"}"
            )
            throw error
        }
    }

    override suspend fun stop() {
        sessionLog.record("OneXrGlassesSession", "Stopping one-xr session")
        val collectorJob = sessionStateJob
        sessionStateJob = null
        collectorJob?.cancelAndJoin()
        try {
            facade.stop()
        } finally {
            _state.value = GlassesSession.State.Unavailable
            sessionLog.record("OneXrGlassesSession", "one-xr session stopped")
        }
    }

    private fun mapState(state: OneXrFacade.SessionState): GlassesSession.State {
        return when (state) {
            OneXrFacade.SessionState.Unavailable -> GlassesSession.State.Unavailable
            OneXrFacade.SessionState.Connecting -> GlassesSession.State.Connecting
            OneXrFacade.SessionState.Available -> GlassesSession.State.Available
        }
    }
}
