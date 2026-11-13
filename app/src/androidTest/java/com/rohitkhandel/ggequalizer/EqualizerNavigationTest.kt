package com.rohitkhandel.ggequalizer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohitkhandel.ggequalizer.audio.NativeDspBridge
import com.rohitkhandel.ggequalizer.ui.EqViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.lifecycle.ViewModelProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class EqualizerNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun presetsDockOpensThePresetLibrary() {
        composeRule.onAllNodesWithText("PRESETS").onLast().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("PRESET LIBRARY").assertIsDisplayed()
    }

    @Test
    fun appUsesComposeHeaderWithoutDuplicatePlatformActionBar() {
        composeRule.runOnUiThread {
            assertNull(
                "The native ActionBar must be removed so it does not duplicate the Compose header",
                composeRule.activity.actionBar
            )
        }
    }

    @Test
    fun graphicModeChipsSelectTheirExactBandCounts() {
        val viewModel = ViewModelProvider(composeRule.activity)[EqViewModel::class.java]

        composeRule.onNodeWithText("15", useUnmergedTree = true).performClick()
        composeRule.runOnIdle {
            assertEquals("15-band chip must select exactly 15 bands", 15, viewModel.uiState.value.bandCount)
        }

        composeRule.onNodeWithText("10", useUnmergedTree = true).performClick()
        composeRule.runOnIdle {
            assertEquals("10-band chip must select exactly 10 bands", 10, viewModel.uiState.value.bandCount)
        }

        composeRule.onNodeWithText("PARA", useUnmergedTree = true).performClick()
        composeRule.runOnIdle {
            assertEquals("Parametric chip must select the 32-slot editor", 32, viewModel.uiState.value.bandCount)
        }
    }

    @Test
    fun parametricEditorCanSelectAllSixFilterTypes() {
        composeRule.runOnUiThread {
            val viewModel = ViewModelProvider(composeRule.activity)[EqViewModel::class.java]
            viewModel.setBandCount(32)

            val filterTypes = listOf(
                NativeDspBridge.FILTER_PEAKING,
                NativeDspBridge.FILTER_LOW_SHELF,
                NativeDspBridge.FILTER_HIGH_SHELF,
                NativeDspBridge.FILTER_LOW_PASS,
                NativeDspBridge.FILTER_HIGH_PASS,
                NativeDspBridge.FILTER_BAND_PASS
            )

            filterTypes.forEach { filterType ->
                viewModel.setBandFilterType(0, filterType)
                assertEquals(filterType, viewModel.uiState.value.bands[0].filterType)
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun nativeDspEngineProcessesAudioWithAllSixFilterTypes() {
        val handle = NativeDspBridge.nativeCreate(48_000)
        assertTrue("Native DSP engine should create a valid handle", handle > 0L)

        try {
            val filterTypes = listOf(
                NativeDspBridge.FILTER_PEAKING,
                NativeDspBridge.FILTER_LOW_SHELF,
                NativeDspBridge.FILTER_HIGH_SHELF,
                NativeDspBridge.FILTER_LOW_PASS,
                NativeDspBridge.FILTER_HIGH_PASS,
                NativeDspBridge.FILTER_BAND_PASS
            )

            filterTypes.forEach { filterType ->
                NativeDspBridge.nativeReset(handle)
                assertTrue(
                    "Native DSP should accept filter type $filterType",
                    NativeDspBridge.nativeSetBand(
                        handle = handle,
                        index = 0,
                        type = filterType,
                        frequencyHz = 1_000f,
                        gainDb = 6f,
                        qFactor = 1f,
                        enabled = true
                    )
                )

                val samples = FloatArray(2_048)
                samples[0] = 1f
                samples[1] = -1f

                assertTrue(
                    "Native DSP should process stereo frames for filter type $filterType",
                    NativeDspBridge.nativeProcess(handle, samples, 1_024)
                )
                assertTrue("Filter output must remain finite", samples.all { it.isFinite() })
                assertTrue("Filter should produce a non-zero impulse response", samples.any { abs(it) > 1e-6f })
            }
        } finally {
            NativeDspBridge.nativeDestroy(handle)
        }
    }
}
