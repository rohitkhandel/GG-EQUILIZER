package com.rohitkhandel.ggequalizer.audio

/**
 * Loads the native C++17 library and exposes its build identity to the Kotlin layer.
 */
internal object NativeDspBridge {
    init {
        System.loadLibrary("ggequalizer_dsp")
    }

    external fun nativeVersion(): String
}
