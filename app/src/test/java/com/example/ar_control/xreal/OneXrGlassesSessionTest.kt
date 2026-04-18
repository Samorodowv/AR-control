package com.example.ar_control.xreal

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OneXrGlassesSessionTest {

    @Test
    fun start_mapsAvailableFacadeStateToAvailable() = runTest {
        val facade = FakeOneXrFacade(OneXrFacade.SessionState.Unavailable)
        val session = OneXrGlassesSession(facade, this)

        facade.sessionState.emit(OneXrFacade.SessionState.Available)
        session.start()
        advanceUntilIdle()

        assertEquals(GlassesSession.State.Available, session.state.value)
        session.stop()
        advanceUntilIdle()
    }

    @Test
    fun start_collectsLiveFacadeStateUpdatesWhileRunning() = runTest {
        val facade = FakeOneXrFacade(OneXrFacade.SessionState.Unavailable)
        val session = OneXrGlassesSession(facade, this)

        session.start()
        advanceUntilIdle()
        facade.sessionState.emit(OneXrFacade.SessionState.Connecting)
        advanceUntilIdle()

        assertEquals(GlassesSession.State.Connecting, session.state.value)
        facade.sessionState.emit(OneXrFacade.SessionState.Available)
        advanceUntilIdle()
        assertEquals(GlassesSession.State.Available, session.state.value)
        session.stop()
        advanceUntilIdle()
    }

    @Test
    fun repeatedStart_isIdempotentAndCallsFacadeStartOnce() = runTest {
        val facade = FakeOneXrFacade(OneXrFacade.SessionState.Available)
        val session = OneXrGlassesSession(facade, this)

        session.start()
        advanceUntilIdle()
        session.start()
        advanceUntilIdle()

        assertEquals(1, facade.startCalls)
        assertEquals(1, facade.sessionState.activeCollectors)
        session.stop()
        advanceUntilIdle()
    }

    @Test
    fun stop_setsStateToUnavailable() = runTest {
        val facade = FakeOneXrFacade(OneXrFacade.SessionState.Available)
        val session = OneXrGlassesSession(facade, this)

        session.stop()

        assertEquals(GlassesSession.State.Unavailable, session.state.value)
        assertEquals(1, facade.stopCalls)
    }

    @Test
    fun stop_preventsPostStopFacadeEmissionsFromChangingState() = runTest {
        val facade = FakeOneXrFacade(OneXrFacade.SessionState.Available)
        val session = OneXrGlassesSession(facade, this)

        session.start()
        advanceUntilIdle()
        assertEquals(1, facade.sessionState.activeCollectors)

        session.stop()
        advanceUntilIdle()
        facade.sessionState.emit(OneXrFacade.SessionState.Connecting)
        advanceUntilIdle()

        assertEquals(GlassesSession.State.Unavailable, session.state.value)
        assertEquals(0, facade.sessionState.activeCollectors)
    }

    @Test(expected = IllegalStateException::class)
    fun start_failure_cleansUpCollector() = runTest {
        val facade = FakeOneXrFacade(
            initialState = OneXrFacade.SessionState.Unavailable,
            startFailure = IllegalStateException("boom")
        )
        val session = OneXrGlassesSession(facade, this)

        try {
            session.start()
        } finally {
            advanceUntilIdle()
            assertEquals(0, facade.sessionState.activeCollectors)
            facade.sessionState.emit(OneXrFacade.SessionState.Available)
            advanceUntilIdle()
            assertEquals(GlassesSession.State.Unavailable, session.state.value)
        }
    }
}

private class FakeOneXrFacade(
    initialState: OneXrFacade.SessionState,
    private val startFailure: Throwable? = null
) : OneXrFacade {
    override val sessionState = TrackingStateFlow(initialState)
    var startCalls = 0
    var stopCalls = 0

    override suspend fun start() {
        startCalls += 1
        startFailure?.let { throw it }
    }

    override suspend fun stop() {
        stopCalls += 1
    }
}

private class TrackingStateFlow<T>(
    initialValue: T,
    private val delegate: MutableStateFlow<T> = MutableStateFlow(initialValue)
) : StateFlow<T> by delegate {
    var activeCollectors = 0
        private set

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        activeCollectors += 1
        try {
            delegate.collect(collector)
        } finally {
            activeCollectors -= 1
        }
    }

    fun emit(value: T) {
        delegate.value = value
    }
}
