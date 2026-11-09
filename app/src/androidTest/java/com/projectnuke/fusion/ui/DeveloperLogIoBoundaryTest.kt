package com.projectnuke.fusion.ui

import android.content.Context
import android.os.StrictMode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeveloperLogIoBoundaryTest {
    @Test
    fun developerLogSnapshotDoesNotReadOrWriteDiskOnMainThread() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("developer-log-strict-mode", Context.MODE_PRIVATE)
        withContext(Dispatchers.Main.immediate) {
            val previousPolicy = StrictMode.getThreadPolicy()
            val violations = mutableListOf<Throwable>()
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder(previousPolicy)
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyListener(Executor { it.run() }) { violations += it }
                    .build(),
            )
            try {
                val snapshot = buildFusionDeveloperLogSnapshotAsync(
                    context = context,
                    prefs = prefs,
                    benchmarkResults = emptyList(),
                    events = emptyList(),
                )
                assertFalse(snapshot.fullLogText.isBlank())
                assertTrue("developer log work must not perform main-thread disk IO: $violations", violations.isEmpty())
            } finally {
                StrictMode.setThreadPolicy(previousPolicy)
            }
        }
    }
}
