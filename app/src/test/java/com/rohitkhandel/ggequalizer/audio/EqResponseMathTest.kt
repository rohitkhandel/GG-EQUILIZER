package com.rohitkhandel.ggequalizer.audio

import com.rohitkhandel.ggequalizer.audio.NativeDspBridge.FILTER_HIGH_SHELF
import com.rohitkhandel.ggequalizer.audio.NativeDspBridge.FILTER_LOW_SHELF
import com.rohitkhandel.ggequalizer.audio.NativeDspBridge.FILTER_PEAKING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqResponseMathTest {
    @Test
    fun flatBandHasZeroResponse() {
        val result = EqResponseMath.filterGainDb(
            frequencyHz = 1_000f,
            centerFrequencyHz = 1_000f,
            gainDb = 0f,
            qFactor = 1f,
            filterType = FILTER_PEAKING
        )
        assertEquals(0.0, result, 1.0e-6)
    }

    @Test
    fun peakingFilterReachesRequestedGainAtItsCenter() {
        val boost = EqResponseMath.filterGainDb(
            frequencyHz = 1_000f,
            centerFrequencyHz = 1_000f,
            gainDb = 6f,
            qFactor = 1f,
            filterType = FILTER_PEAKING
        )
        val cut = EqResponseMath.filterGainDb(
            frequencyHz = 1_000f,
            centerFrequencyHz = 1_000f,
            gainDb = -6f,
            qFactor = 1f,
            filterType = FILTER_PEAKING
        )
        assertEquals(6.0, boost, 0.02)
        assertEquals(-6.0, cut, 0.02)
    }

    @Test
    fun lowShelfHasLowFrequencyGainAndReturnsToFlatAtHighFrequency() {
        val low = EqResponseMath.filterGainDb(
            frequencyHz = 25f,
            centerFrequencyHz = 120f,
            gainDb = 6f,
            qFactor = 0.8f,
            filterType = FILTER_LOW_SHELF
        )
        val high = EqResponseMath.filterGainDb(
            frequencyHz = 12_000f,
            centerFrequencyHz = 120f,
            gainDb = 6f,
            qFactor = 0.8f,
            filterType = FILTER_LOW_SHELF
        )
        assertTrue("Low shelf should boost the low end", low > 4.5)
        assertTrue("Low shelf should be near flat at high frequency", kotlin.math.abs(high) < 0.15)
    }

    @Test
    fun highShelfHasHighFrequencyGainAndReturnsToFlatAtLowFrequency() {
        val low = EqResponseMath.filterGainDb(
            frequencyHz = 80f,
            centerFrequencyHz = 8_000f,
            gainDb = 5f,
            qFactor = 0.8f,
            filterType = FILTER_HIGH_SHELF
        )
        val high = EqResponseMath.filterGainDb(
            frequencyHz = 18_000f,
            centerFrequencyHz = 8_000f,
            gainDb = 5f,
            qFactor = 0.8f,
            filterType = FILTER_HIGH_SHELF
        )
        assertTrue("High shelf should be near flat at low frequency", kotlin.math.abs(low) < 0.15)
        assertTrue("High shelf should boost the high end", high > 3.5)
    }

    @Test
    fun profileCombinesParametricAndToneFilters() {
        val profile = EqAudioProfile.defaults().copy(
            bands = listOf(
                EqBandParameter(
                    frequencyHz = 1_000f,
                    gainDb = 3f,
                    qFactor = 1f,
                    filterType = FILTER_PEAKING
                )
            ),
            bassGainDb = 4f,
            bassFrequencyHz = 100f,
            trebleGainDb = -2f,
            trebleFrequencyHz = 9_000f
        ).sanitized()

        val center = EqResponseMath.profileGainDb(profile, 1_000f)
        assertTrue("Parametric centre should contain the 3 dB peak", center > 2.5f)
        val lows = EqResponseMath.profileGainDb(profile, 30f)
        assertTrue("Bass shelf cut/boost should affect the low end", lows > 2.5f)
    }
}
