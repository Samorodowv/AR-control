package com.example.ar_control.xreal

import kotlinx.coroutines.flow.StateFlow

interface GlassesSession {
    enum class State {
        Unavailable,
        Connecting,
        Available
    }

    val state: StateFlow<State>

    suspend fun start()

    suspend fun stop()
}
