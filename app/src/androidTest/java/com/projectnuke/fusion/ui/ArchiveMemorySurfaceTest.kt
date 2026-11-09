package com.projectnuke.fusion.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnuke.fusion.data.ConversationEntity
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class ArchiveMemorySurfaceTest {
    @get:Rule val compose = createComposeRule()
    private val prefix = "archive_surface_${UUID.randomUUID()}_"
    private val names = mutableSetOf<String>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(base.cacheDir, prefix).apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names += name
            return base.getSharedPreferences(prefix + name, mode)
        }
    }

    @After fun cleanup() {
        names.forEach { base.deleteSharedPreferences(prefix + it) }
        directory.deleteRecursively()
    }

    @Test fun lockedSummaryIsAbsentAndSessionUnlockRestoresOpenAndCopySurface() {
        assertNotNull(saveConversationSummary(context, 1, "active summary"))
        assertNotNull(saveConversationSummary(context, 2, "locked secret summary"))
        val unlocked = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                MemoryManagerDialog(
                    context, LocalClipboardManager.current,
                    listOf(ConversationEntity(1, "active title", 1, 1)),
                    listOf(ConversationEntity(2, "locked secret title", 1, 1, isArchived = true)),
                    onDismiss = {}, archiveLockEnabled = true,
                    archiveUnlockedForSession = unlocked.value,
                )
            }
        }
        compose.onNodeWithText("대화 요약").performScrollTo()
        compose.onNodeWithText("locked secret title").assertDoesNotExist()
        compose.onNodeWithText("locked secret summary").assertDoesNotExist()
        compose.onNodeWithText("active summary").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { unlocked.value = true }
        compose.onNodeWithText("locked secret summary").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { unlocked.value = false }
        compose.onNodeWithText("locked secret summary").assertDoesNotExist()
    }
}
