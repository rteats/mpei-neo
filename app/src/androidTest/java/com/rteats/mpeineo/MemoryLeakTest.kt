package com.rteats.mpeineo

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import leakcanary.LeakAssertions
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryLeakTest {

    @Test
    fun destroyedMainActivityIsNotRetained() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.close()

        LeakAssertions.assertNoLeaks()
    }
}
