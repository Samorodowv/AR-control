package com.example.ar_control.xreal

import android.content.Context
import io.onexr.OneXrClient
import io.onexr.XrSessionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted

class ProductionOneXrFacade(
    context: Context,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : OneXrFacade {
    private val client = OneXrClient(context.applicationContext)

    override val sessionState: StateFlow<OneXrFacade.SessionState> = client.sessionState
        .map(::mapSessionState)
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = mapSessionState(client.sessionState.value)
        )

    override suspend fun start() {
        client.start()
    }

    override suspend fun stop() {
        client.stop()
    }

    private fun mapSessionState(state: XrSessionState): OneXrFacade.SessionState {
        return when (state) {
            XrSessionState.Connecting -> OneXrFacade.SessionState.Connecting
            is XrSessionState.Calibrating, is XrSessionState.Streaming -> OneXrFacade.SessionState.Available
            XrSessionState.Idle, XrSessionState.Stopped, is XrSessionState.Error -> OneXrFacade.SessionState.Unavailable
        }
    }
}
