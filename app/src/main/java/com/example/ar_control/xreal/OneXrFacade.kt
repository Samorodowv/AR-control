package com.example.ar_control.xreal

import kotlinx.coroutines.flow.StateFlow

interface OneXrFacade {
    enum class SessionState {
        Unavailable,
        Connecting,
        Available
    }

    val sessionState: StateFlow<SessionState>

    suspend fun start()

    suspend fun stop()
}
