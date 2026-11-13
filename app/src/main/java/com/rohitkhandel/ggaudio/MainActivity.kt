package com.rohitkhandel.ggaudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.rohitkhandel.ggaudio.ui.GgAudioApp
import com.rohitkhandel.ggaudio.ui.theme.GgAudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GgAudioTheme {
                GgAudioApp()
            }
        }
    }
}
