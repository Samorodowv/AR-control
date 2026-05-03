package com.example.ar_control.ui.preview

class VolumeUpDoublePressDetector(
    private val doublePressWindowMillis: Long = DEFAULT_DOUBLE_PRESS_WINDOW_MILLIS
) {
    private var pendingPressAtMillis: Long? = null

    fun onPress(nowMillis: Long): Action? {
        val pendingAt = pendingPressAtMillis
        if (pendingAt == null) {
            pendingPressAtMillis = nowMillis
            return null
        }
        val elapsedMillis = nowMillis - pendingAt
        return if (elapsedMillis < doublePressWindowMillis) {
            pendingPressAtMillis = null
            Action.DoublePress
        } else {
            pendingPressAtMillis = nowMillis
            Action.SinglePress
        }
    }

    fun consumePendingSinglePress(nowMillis: Long): Action? {
        val pendingAt = pendingPressAtMillis ?: return null
        if (nowMillis - pendingAt < doublePressWindowMillis) {
            return null
        }
        pendingPressAtMillis = null
        return Action.SinglePress
    }

    sealed interface Action {
        data object SinglePress : Action
        data object DoublePress : Action
    }

    companion object {
        const val DEFAULT_DOUBLE_PRESS_WINDOW_MILLIS = 600L
    }
}
