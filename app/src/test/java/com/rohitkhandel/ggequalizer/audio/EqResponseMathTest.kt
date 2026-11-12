package com.rohitkhandel.ggequalizer.audio

import com.rohitkhandel.ggequalizer.audio.NativeDspBridge.FILTER_HIGH_SHELF
import com.rohitkhandel.ggequalizer.audio.NativeDspBridge.FILTER_LOW_SHELF
import com.rohitkhandel.ggequalizer.audio.NativeDspBridge.FILTER_PEAKING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.pow
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
    fun automaticHeadroomReservesTheFullPositiveEqPeak() {
        val profile = EqAudioProfile.defaults().copy(
            preampGainDb = 2f,
            bassGainDb = 6f,
            bassFrequencyHz = 100f,
            avoidOverloading = true
        )
        val headroom = EqResponseMath.automaticHeadroomDb(profile)
        val peak = (0..192).maxOf { index ->
            val frequency = (20.0 * 1_000.0.pow(index / 192.0)).toFloat()
            EqResponseMath.profileGainDb(profile, frequency, includePreamp = true)
        }
        assertTrue("Headroom must cover the complete positive peak plus its safety margin", headroom >= peak + 0.9f)
    }

    @Test
    fun automaticHeadroomCanBeDisabledWithoutDisablingTheLimiter() {
        val profile = EqAudioProfile.defaults().copy(
            bassGainDb = 8f,
            avoidOverloading = false,
            limiterEnabled = true
        )
        assertEquals(0f, EqResponseMath.automaticHeadroomDb(profile), 0.001f)
        assertTrue("Limiter remains independently enabled", profile.limiterEnabled)
    }

    @Test
    fun bandsOverlapWidensTheParametricFilterResponse() {
        val band = EqBandParameter(
            frequencyHz = 1_000f,
            gainDb = 6f,
            qFactor = 1f,
            filterType = FILTER_PEAKING
        )
        val noOverlap = EqAudioProfile.defaults().copy(
            bands = listOf(band),
            bandsOverlapEnabled = false,
            bandsOverlapPercent = 100f
        )
        val wideOverlap = noOverlap.copy(
            bandsOverlapEnabled = true,
            bandsOverlapPercent = 100f
        )
        val narrowSide = EqResponseMath.profileGainDb(noOverlap, 1_600f)
        val wideSide = EqResponseMath.profileGainDb(wideOverlap, 1_600f)
        assertTrue("Higher overlap should widen the curve away from the centre", wideSide > narrowSide)
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
