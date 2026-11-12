package com.rohitkhandel.ggequalizer.audio

/**
 * JNI access to the native stereo DSP engine.
 *
 * nativeProcess() expects interleaved Float32 stereo samples:
 * [left0, right0, left1, right1, ...].
 * A valid handle returned by nativeCreate() should be destroyed exactly once.
 */
object NativeDspBridge {
    const val FILTER_PEAKING = 0
    const val FILTER_LOW_SHELF = 1
    const val FILTER_HIGH_SHELF = 2
    const val MAX_PARAMETRIC_BANDS = 32

    init {
        System.loadLibrary("ggequalizer_dsp")
    }

    external fun nativeVersion(): String

    external fun nativeCreate(sampleRateHz: Int): Long
    external fun nativeDestroy(handle: Long)

    external fun nativeSetSampleRate(handle: Long, sampleRateHz: Int): Boolean

    external fun nativeSetBand(
        handle: Long,
        index: Int,
        type: Int,
        frequencyHz: Float,
        gainDb: Float,
        qFactor: Float,
        enabled: Boolean
    ): Boolean

    external fun nativeSetBandEnabled(
        handle: Long,
        index: Int,
        enabled: Boolean
    ): Boolean

    external fun nativeSetTone(
        handle: Long,
        bassGainDb: Float,
        trebleGainDb: Float
    ): Boolean

    external fun nativeSetPreampGain(handle: Long, gainDb: Float): Boolean

    external fun nativeSetLimiter(
        handle: Long,
        enabled: Boolean,
        thresholdDb: Float,
        kneeDb: Float,
        lookAheadMs: Float,
        releaseMs: Float
    ): Boolean

    /** DVC is a linear output multiplier constrained to 0.0..1.0. */
    external fun nativeSetDvcVolume(handle: Long, linearGain: Float): Boolean

    /** Processes frameCount stereo frames in place; array length must be >= 2 * frameCount. */
    external fun nativeProcess(
        handle: Long,
        interleavedStereo: FloatArray,
        frameCount: Int
    ): Boolean

    external fun nativeReset(handle: Long)
    external fun nativeGetLatencyFrames(handle: Long): Int
}
