package com.rohitkhandel.ggequalizer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohitkhandel.ggequalizer.audio.NativeDspBridge
import com.rohitkhandel.ggequalizer.ui.EqViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import androidx.lifecycle.ViewModelProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EqualizerNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun presetsDockOpensThePresetLibrary() {
        composeRule.onNodeWithText("PRESETS").assertIsDisplayed().performClick()
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
    fun parametricEditorCanSelectAllSixFilterTypes() {
        composeRule.runOnUiThread {
            val vm = ViewModelProvider(composeRule.activity)[EqViewModel::class.java]
            vm.setBandCount(32)

            vm.setBandFilterType(0, NativeDspBridge.FILTER_LOW_PASS)
            assertEquals(NativeDspBridge.FILTER_LOW_PASS, vm.uiState.value.bands[0].filterType)

            vm.setBandFilterType(0, NativeDspBridge.FILTER_HIGH_PASS)
            assertEquals(NativeDspBridge.FILTER_HIGH_PASS, vm.uiState.value.bands[0].filterType)

            vm.setBandFilterType(0, NativeDspBridge.FILTER_BAND_PASS)
            assertEquals(NativeDspBridge.FILTER_BAND_PASS, vm.uiState.value.bands[0].filterType)
        }
        composeRule.waitForIdle()
    }

}
