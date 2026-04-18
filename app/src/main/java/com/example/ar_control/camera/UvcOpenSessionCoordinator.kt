package com.example.ar_control.camera

class UvcOpenSessionCoordinator {
    private val lock = Any()
    private var activeSession: Session? = null
    private var nextSessionId = 0L

    fun beginSession(): Session {
        val staleSession = synchronized(lock) {
            val previous = activeSession
            activeSession = Session(++nextSessionId)
            previous
        }
        staleSession?.cancel()
        return synchronized(lock) { checkNotNull(activeSession) }
    }

    fun cancelCurrent() {
        val current = synchronized(lock) {
            val previous = activeSession
            activeSession = null
            previous
        }
        current?.cancel()
    }

    inner class Session(
        private val id: Long
    ) {
        @Volatile
        private var cancelled = false
        private var cancellationHandler: ((UvcSessionClosedException) -> Unit)? = null

        fun isActive(): Boolean = synchronized(lock) {
            isActiveLocked()
        }

        fun throwIfInactive() {
            if (!isActive()) {
                throw UvcSessionClosedException()
            }
        }

        fun registerCancellationHandler(handler: (UvcSessionClosedException) -> Unit) {
            val immediateFailure = synchronized(lock) {
                if (isActiveLocked()) {
                    cancellationHandler = handler
                    null
                } else {
                    UvcSessionClosedException()
                }
            }
            immediateFailure?.let(handler)
        }

        fun clearCancellationHandler() {
            synchronized(lock) {
                if (activeSession === this) {
                    cancellationHandler = null
                }
            }
        }

        internal fun cancel() {
            val handler = synchronized(lock) {
                if (cancelled) {
                    return
                }
                cancelled = true
                val currentHandler = cancellationHandler
                cancellationHandler = null
                currentHandler
            }
            handler?.invoke(UvcSessionClosedException())
        }

        private fun isActiveLocked(): Boolean {
            return !cancelled && activeSession?.id == id
        }
    }
}

class UvcSessionClosedException : IllegalStateException("uvc_session_closed")
