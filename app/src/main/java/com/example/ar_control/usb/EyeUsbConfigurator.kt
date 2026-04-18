package com.example.ar_control.usb

interface EyeUsbConfigurator {
    sealed interface Result {
        data object Enabled : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun enableCamera(): Result
}
