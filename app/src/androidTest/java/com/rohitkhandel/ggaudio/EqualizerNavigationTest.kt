package com.rohitkhandel.ggaudio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EqualizerNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeRendersTenBandControls() {
        composeRule.onNodeWithTag("nav_equalizer").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("31 Hz gain").fetchSemanticsNode()
        composeRule.onNodeWithContentDescription("16K Hz gain").assertExists()
    }

    @Test
    fun playerScreenExplainsLocalDspPlayback() {
        composeRule.onNodeWithTag("nav_player").performClick()
        composeRule.onNodeWithText("LOCAL PCM PLAYBACK").assertIsDisplayed()
        composeRule.onNodeWithText("OPEN AUDIO FILE").assertIsDisplayed()
        composeRule.onNodeWithText("These controls process audio played inside GG Audio. They do not capture or modify unrelated apps.")
            .assertExists()
    }

    @Test
    fun bottomNavigationOpensEffectsAndReturnsHome() {
        composeRule.onNodeWithTag("nav_effects").performClick()
        composeRule.onNodeWithText("Sound effects").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_equalizer").performClick()
        composeRule.onNodeWithContentDescription("31 Hz gain").assertExists()
    }
}
