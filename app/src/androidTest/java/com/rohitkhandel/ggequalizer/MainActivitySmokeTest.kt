package com.rohitkhandel.ggequalizer

import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @Test
    fun mainActivityLaunchesAndAttachesComposeContent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)

                assertNotNull("The activity content root must exist", contentRoot)
                assertTrue(
                    "The Compose view must be attached to the activity",
                    contentRoot.childCount > 0
                )
                assertTrue(
                    "The activity window must be visible",
                    activity.window.decorView.isShown
                )
            }
        }
    }
}
