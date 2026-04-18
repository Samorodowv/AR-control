package com.example.ar_control.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UvcOpenSessionCoordinatorTest {

    @Test
    fun cancelCurrent_cancelsRegisteredWaiter() {
        val coordinator = UvcOpenSessionCoordinator()
        val session = coordinator.beginSession()
        var cancellation: Throwable? = null

        session.registerCancellationHandler { error ->
            cancellation = error
        }

        coordinator.cancelCurrent()

        assertTrue(cancellation is UvcSessionClosedException)
        assertFalse(session.isActive())
    }

    @Test
    fun beginSession_cancelsPreviousSessionWaiter() {
        val coordinator = UvcOpenSessionCoordinator()
        val first = coordinator.beginSession()
        var cancellation: Throwable? = null
        first.registerCancellationHandler { error ->
            cancellation = error
        }

        val second = coordinator.beginSession()

        assertTrue(cancellation is UvcSessionClosedException)
        assertFalse(first.isActive())
        assertTrue(second.isActive())
    }

    @Test
    fun registerCancellationHandler_notifiesImmediatelyForClosedSession() {
        val coordinator = UvcOpenSessionCoordinator()
        val session = coordinator.beginSession()
        coordinator.cancelCurrent()
        var cancellation: Throwable? = null

        session.registerCancellationHandler { error ->
            cancellation = error
        }

        assertTrue(cancellation is UvcSessionClosedException)
    }
}
