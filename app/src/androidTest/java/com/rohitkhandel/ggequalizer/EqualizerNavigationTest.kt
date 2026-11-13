package com.rohitkhandel.ggequalizer

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EqualizerNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun presetsDockOpensThePresetLibrary() {
        composeRule.onNodeWithText("PRESETS").assertExists().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("PRESET LIBRARY").assertExists()
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
}
