package com.projectnuke.fusion

import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CancellationException
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.graphics.toArgb
import com.projectnuke.fusion.handoff.ExternalDocumentHandoff
import com.projectnuke.fusion.handoff.ExternalDocumentHandoffSource
import com.projectnuke.fusion.handoff.ExternalHandoff
import com.projectnuke.fusion.handoff.ExternalHandoffParser
import com.projectnuke.fusion.handoff.ExternalTextHandoff
import com.projectnuke.fusion.handoff.ExternalTextHandoffSource
import com.projectnuke.fusion.handoff.ExternalTextHandoffSavedState
import com.projectnuke.fusion.handoff.PendingExternalHandoff
import com.projectnuke.fusion.llm.FusionRuntimeLock
import com.projectnuke.fusion.ui.FusionApp
import com.projectnuke.fusion.ui.ConversationCleanupDebtStore
import com.projectnuke.fusion.data.AppDatabase
import com.projectnuke.fusion.tasks.cleanupLegacyReminderNotifications
import com.projectnuke.fusion.ai.provider.ExternalModelCapabilityRegistry
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.projectnuke.fusion.ui.theme.FusionTheme
import com.projectnuke.fusion.ui.theme.FusionThemeMode
import com.projectnuke.fusion.ui.theme.rememberFusionThemeMode
import com.projectnuke.fusion.util.FusionMemoryManager
import com.projectnuke.fusion.util.FusionThumbnailCache
import com.projectnuke.fusion.util.MemoryTrimAction
import com.projectnuke.fusion.util.resolveMemoryTrimAction

class MainActivity : ComponentActivity() {
    private var unregisterCacheClearer: (() -> Unit)? = null
    // Single-owner Android -> Fusion handoff event. The slot guarantees one
    // Intent is consumed exactly once; the Compose state mirrors the slot so
    // FusionApp recomposes when a new event arrives.
    private val pendingHandoffSlot = PendingExternalHandoff()
    private var pendingHandoff: ExternalHandoff? by mutableStateOf(null)
    // One-shot pinned-shortcut navigation, owned the same way: a second
    // launch supersedes an unconsumed request, and each request navigates
    // at most once (no repeat after recreation, no fresh chat, no send).
    private val pendingShortcutSlot = PendingConversationShortcut()
    private var pendingShortcut: ConversationShortcutNavigation? by mutableStateOf(null)
    // One-shot reminder-tap deep link, owned the same way: only stable IDs
    // travel in the Intent, and each tap navigates at most once.
    private val pendingReminderSlot = PendingReminderNavigation()
    private var pendingReminder: ReminderNavigation? by mutableStateOf(null)
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        override fun onLowMemory() {
            handleMemoryTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        }

        override fun onTrimMemory(level: Int) {
            handleMemoryTrim(level)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Capability resolution is synchronous in Chat and provider settings, so the
        // process catalog must be fully available before Compose starts consuming it.
        ExternalModelCapabilityRegistry.initialize(applicationContext.assets)
        unregisterCacheClearer = FusionMemoryManager.registerLightweightCacheClearer { FusionThumbnailCache.clear() }
        registerComponentCallbacks(memoryCallbacks)

        val incoming = ExternalHandoffParser.parse(intent)
        val restored = restorePendingHandoff(savedInstanceState)
        if (incoming == null && savedInstanceState?.getBoolean(STATE_HANDOFF_TEXT_OVERFLOW) == true) {
            Toast.makeText(
                this,
                "공유한 텍스트가 너무 커서 화면 복원 중 보존되지 않았습니다. 다시 공유해 주세요.",
                Toast.LENGTH_LONG,
            ).show()
        }
        val pending = incoming ?: restored
        if (pending != null) {
            pendingHandoffSlot.submit(pending)
            pendingHandoff = pending
        }

        val incomingShortcut = parseShortcutNavigation(intent)
        val restoredShortcut = restorePendingShortcut(savedInstanceState)
        val shortcut = incomingShortcut ?: restoredShortcut
        if (shortcut != null) {
            pendingShortcutSlot.submit(shortcut)
            pendingShortcut = shortcut
        }

        val incomingReminder = parseReminderNavigation(intent)
        val restoredReminder = restorePendingReminder(savedInstanceState)
        val reminder = incomingReminder ?: restoredReminder
        if (reminder != null) {
            pendingReminderSlot.submit(reminder)
            pendingReminder = reminder
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            val themeMode = rememberFusionThemeMode(this@MainActivity)
            val lightSystemBars = themeMode == FusionThemeMode.LIGHT
            FusionTheme(themeMode = themeMode) {
                val colorScheme = MaterialTheme.colorScheme
                SideEffect {
                    window.statusBarColor = Color.TRANSPARENT
                    window.navigationBarColor = colorScheme.background.toArgb()
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightSystemBars
                        isAppearanceLightNavigationBars = lightSystemBars
                    }
                }
                FusionApp(
                    pendingHandoff = pendingHandoff,
                    onHandoffConsumed = {
                        pendingHandoffSlot.consume()
                        pendingHandoff = pendingHandoffSlot.pending
                    },
                    pendingShortcutNavigation = pendingShortcut,
                    onShortcutConsumed = {
                        pendingShortcutSlot.consume()
                        pendingShortcut = pendingShortcutSlot.pending
                    },
                    pendingReminderNavigation = pendingReminder,
                    onReminderConsumed = {
                        pendingReminderSlot.consume()
                        pendingReminder = pendingReminderSlot.pending
                    },
                )
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            cleanupLegacyReminderNotifications(this@MainActivity)
            val database = AppDatabase.getInstance(this@MainActivity)
            val outcome = ConversationCleanupDebtStore.retry(
                this@MainActivity,
                database.chatDao(),
                database.projectDao(),
            )
            if (!outcome.persistenceOk) {
                Log.w("FusionRecovery", "ConversationCleanup startup recovery not durably persisted (load=${outcome.load::class.simpleName})")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Warm-app handoff: singleTop delivers the new external Intent here.
        // A second intentional share supersedes an unconsumed event; each
        // event is still consumed at most once by identity.
        ExternalHandoffParser.parse(intent)?.let { event ->
            pendingHandoffSlot.submit(event)
            pendingHandoff = event
        }
        // Warm shortcut launch reuses the same singleTop delivery without
        // duplicating the Activity stack.
        parseShortcutNavigation(intent)?.let { navigation ->
            pendingShortcutSlot.submit(navigation)
            pendingShortcut = navigation
        }
        // Warm reminder tap reuses the same singleTop delivery.
        parseReminderNavigation(intent)?.let { navigation ->
            pendingReminderSlot.submit(navigation)
            pendingReminder = navigation
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Preserve an unconsumed handoff across Activity recreation so the
        // event is not lost merely because Compose was not ready yet. Only
        // safe URI string identity plus event metadata is stored; open
        // streams and ContentResolver objects are never retained.
        when (val event = pendingHandoff) {
            is ExternalTextHandoff -> {
                outState.putString(STATE_HANDOFF_KIND, HANDOFF_KIND_TEXT)
                outState.putString(STATE_HANDOFF_ID, event.id)
                outState.putString(STATE_HANDOFF_SOURCE, event.source.name)
                // Keep overflow as typed metadata. User text may legitimately
                // equal the old sentinel literal, so content never signals loss.
                val encoded = ExternalTextHandoffSavedState.encode(event.text)
                outState.putBoolean(STATE_HANDOFF_TEXT_OVERFLOW, encoded.overflow)
                encoded.text?.let { outState.putString(STATE_HANDOFF_TEXT, it) }
            }
            is ExternalDocumentHandoff -> {
                outState.putString(STATE_HANDOFF_KIND, HANDOFF_KIND_DOCUMENT)
                outState.putString(STATE_HANDOFF_ID, event.id)
                // Bounded saved-state envelope: at most MAX_PERSISTED_URIS URI
                // strings of bounded length are ever written, so an external
                // sender cannot inflate the Bundle (TransactionTooLarge) by
                // supplying far more documents than the import admits. The true
                // distinct count is persisted separately so restored reporting
                // stays truthful about documents that were never persisted.
                val bounded = ExternalDocumentHandoff.bounded(event)
                outState.putStringArrayList(
                    STATE_HANDOFF_URIS,
                    ArrayList(bounded.uriStrings),
                )
                outState.putInt(STATE_HANDOFF_URI_TOTAL, bounded.totalDistinctUriCount)
                outState.putString(STATE_HANDOFF_MIME, event.declaredMimeType)
                outState.putString(STATE_HANDOFF_DOC_SOURCE, event.source.name)
            }
            null -> Unit
        }
        // Preserve an unconsumed shortcut request the same way: only the
        // conversation ID plus event identity, never content.
        pendingShortcut?.let { navigation ->
            outState.putLong(STATE_SHORTCUT_CONVERSATION_ID, navigation.conversationId)
            outState.putString(STATE_SHORTCUT_EVENT_ID, navigation.eventId)
        }
        // Preserve an unconsumed reminder tap the same way: only stable IDs
        // plus event identity, never titles or content.
        pendingReminder?.let { navigation ->
            navigation.taskId?.let { outState.putLong(STATE_REMINDER_TASK_ID, it) }
            navigation.cardId?.let { outState.putString(STATE_REMINDER_CARD_ID, it) }
            outState.putString(STATE_REMINDER_EVENT_ID, navigation.eventId)
        }
    }

    private fun parseReminderNavigation(intent: Intent): ReminderNavigation? {
        if (!intent.hasExtra(ReminderNavigationPolicy.EXTRA_TASK_ID) &&
            !intent.hasExtra(ReminderNavigationPolicy.EXTRA_CARD_ID)
        ) return null
        return ReminderNavigationPolicy.parse(
            taskId = readIntentExtraOrNull {
                intent.getLongExtra(ReminderNavigationPolicy.EXTRA_TASK_ID, -1L)
            },
            cardId = readIntentExtraOrNull {
                intent.getStringExtra(ReminderNavigationPolicy.EXTRA_CARD_ID)
            },
        )
    }

    private fun restorePendingReminder(savedInstanceState: Bundle?): ReminderNavigation? {
        if (savedInstanceState == null) return null
        if (!savedInstanceState.containsKey(STATE_REMINDER_TASK_ID) &&
            !savedInstanceState.containsKey(STATE_REMINDER_CARD_ID)
        ) return null
        val taskId = savedInstanceState.getLong(STATE_REMINDER_TASK_ID, -1L).takeIf { it > 0L }
        val cardId = savedInstanceState.getString(STATE_REMINDER_CARD_ID)
            ?.takeIf { it.isNotBlank() && it.length <= 80 }
        val eventId = savedInstanceState.getString(STATE_REMINDER_EVENT_ID) ?: return null
        if (taskId == null && cardId == null) return null
        return ReminderNavigation(taskId = taskId, cardId = cardId, eventId = eventId)
    }

    private fun parseShortcutNavigation(intent: Intent): ConversationShortcutNavigation? {
        if (!intent.hasExtra(ConversationShortcut.EXTRA_CONVERSATION_ID)) return null
        val conversationId = ConversationShortcut.parseConversationId(
            readIntentExtraOrNull {
                intent.getLongExtra(ConversationShortcut.EXTRA_CONVERSATION_ID, -1L)
            },
        ) ?: return null
        return ConversationShortcutNavigation(
            conversationId = conversationId,
            eventId = java.util.UUID.randomUUID().toString(),
        )
    }

    private fun restorePendingShortcut(savedInstanceState: Bundle?): ConversationShortcutNavigation? {
        if (savedInstanceState == null) return null
        if (!savedInstanceState.containsKey(STATE_SHORTCUT_CONVERSATION_ID)) return null
        val conversationId = ConversationShortcut.parseConversationId(
            savedInstanceState.getLong(STATE_SHORTCUT_CONVERSATION_ID, -1L),
        ) ?: return null
        val eventId = savedInstanceState.getString(STATE_SHORTCUT_EVENT_ID) ?: return null
        return ConversationShortcutNavigation(conversationId = conversationId, eventId = eventId)
    }

    private fun restorePendingHandoff(savedInstanceState: Bundle?): ExternalHandoff? {
        if (savedInstanceState == null) return null
        return when (savedInstanceState.getString(STATE_HANDOFF_KIND)) {
            HANDOFF_KIND_DOCUMENT -> restoreDocumentHandoff(savedInstanceState)
            HANDOFF_KIND_TEXT -> restoreTextHandoff(savedInstanceState)
            // Backward compatibility: v1 saved state has no kind key, only
            // the text keys. Treat that shape as a text handoff.
            null -> restoreTextHandoff(savedInstanceState)
            else -> null
        }
    }

    private fun restoreTextHandoff(savedInstanceState: Bundle): ExternalTextHandoff? {
        val id = savedInstanceState.getString(STATE_HANDOFF_ID) ?: return null
        val sourceName = savedInstanceState.getString(STATE_HANDOFF_SOURCE) ?: return null
        val hasOverflowMetadata = savedInstanceState.containsKey(STATE_HANDOFF_TEXT_OVERFLOW)
        val text = ExternalTextHandoffSavedState.restore(
            text = savedInstanceState.getString(STATE_HANDOFF_TEXT),
            overflowMetadataPresent = hasOverflowMetadata,
            overflow = savedInstanceState.getBoolean(STATE_HANDOFF_TEXT_OVERFLOW),
        ) ?: return null
        val source = try {
            ExternalTextHandoffSource.valueOf(sourceName)
        } catch (_: IllegalArgumentException) {
            null
        }
            ?: return null
        return ExternalTextHandoff(id = id, source = source, text = text)
    }

    private fun restoreDocumentHandoff(savedInstanceState: Bundle): ExternalDocumentHandoff? {
        val id = savedInstanceState.getString(STATE_HANDOFF_ID) ?: return null
        // Restoration re-applies the same envelope as persistence and tolerates
        // a malformed or oversized restored list instead of trusting it.
        val restored = ExternalDocumentHandoff.restore(
            id = id,
            uriStrings = savedInstanceState.getStringArrayList(STATE_HANDOFF_URIS),
            declaredMimeType = savedInstanceState.getString(STATE_HANDOFF_MIME),
            source = null,
            totalDistinctUriCount = if (savedInstanceState.containsKey(STATE_HANDOFF_URI_TOTAL)) {
                savedInstanceState.getInt(STATE_HANDOFF_URI_TOTAL)
            } else {
                null
            },
        )
        if (restored.uriStrings.isEmpty()) return null
        val sourceName = savedInstanceState.getString(STATE_HANDOFF_DOC_SOURCE) ?: return null
        val source = try {
            ExternalDocumentHandoffSource.valueOf(sourceName)
        } catch (_: IllegalArgumentException) {
            null
        }
            ?: return null
        return restored.copy(source = source)
    }

    override fun onDestroy() {
        unregisterComponentCallbacks(memoryCallbacks)
        unregisterCacheClearer?.invoke()
        unregisterCacheClearer = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        FusionMemoryManager.logRuntimeSnapshot("FusionMemory", this, "ON_RESUME")
    }

    override fun onPause() {
        FusionMemoryManager.logRuntimeSnapshot("FusionMemory", this, "ON_PAUSE")
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FusionMemoryManager.logRuntimeSnapshot("FusionMemory", this, "WINDOW_FOCUS:$hasFocus")
    }

    override fun onStop() {
        super.onStop()
        // Verifier close is a nonblocking lease fence, so backgrounding always
        // releases the native runtime even if generation is still unwinding.
        com.projectnuke.fusion.verifier.FusionVerifierRuntimeOwner.close()
        if (!FusionRuntimeLock.isChatGenerationRunning && !FusionRuntimeLock.isBenchmarkRunning) {
            Log.i("FusionMemory", "ON_STOP app moved to background; clearing lightweight caches")
            FusionMemoryManager.clearLightweightCaches()
            FusionMemoryManager.requestPressureAwareIdleEngineUnload(
                context = this,
                reason = "activity_on_stop",
            )
            // S1 semantic backbone: release the shared embedding session while
            // backgrounded; the stable proxy recreates it lazily on next rank.
            com.projectnuke.fusion.retrieval.KoEnE5RetrievalOwner.close()
        }
    }

    private fun handleMemoryTrim(level: Int) {
        val action = resolveMemoryTrimAction(level)
        Log.i("FusionMemory", "onTrimMemory level=$level action=$action")
        FusionMemoryManager.logRuntimeSnapshot("FusionMemory", this, "TRIM_LEVEL:$level action=$action")
        FusionMemoryManager.clearLightweightCaches()

        if (action == MemoryTrimAction.RELEASE_IDLE_RUNTIME) {
            com.projectnuke.fusion.verifier.FusionVerifierRuntimeOwner.close()
            FusionMemoryManager.requestPressureAwareIdleEngineUnload(
                context = this,
                reason = "trim_level_$level",
            )
        }

        if (action == MemoryTrimAction.RELEASE_ALL_IDLE_RESOURCES &&
            !FusionRuntimeLock.isChatGenerationRunning &&
            !FusionRuntimeLock.isBenchmarkRunning
        ) {
            Log.i("FusionMemory", "모델 리소스를 정리하는 중입니다.")
            FusionMemoryManager.unloadIdleEngines()
            // S1 semantic backbone joins the same critical-memory release.
            com.projectnuke.fusion.retrieval.KoEnE5RetrievalOwner.close()
            com.projectnuke.fusion.verifier.FusionVerifierRuntimeOwner.close()
        }
    }

    private companion object {
        const val STATE_HANDOFF_KIND = "pending_handoff_kind"
        const val STATE_HANDOFF_ID = "pending_handoff_id"
        const val STATE_HANDOFF_SOURCE = "pending_handoff_source"
        const val STATE_HANDOFF_TEXT = "pending_handoff_text"
        const val STATE_HANDOFF_URIS = "pending_handoff_uris"
        const val STATE_HANDOFF_URI_TOTAL = "pending_handoff_uri_total"
        const val STATE_HANDOFF_MIME = "pending_handoff_mime"
        const val STATE_HANDOFF_DOC_SOURCE = "pending_handoff_doc_source"
        const val HANDOFF_KIND_TEXT = "text"
        const val HANDOFF_KIND_DOCUMENT = "document"
        const val STATE_SHORTCUT_CONVERSATION_ID = "pending_shortcut_conversation_id"
        const val STATE_SHORTCUT_EVENT_ID = "pending_shortcut_event_id"
        const val STATE_REMINDER_TASK_ID = "pending_reminder_task_id"
        const val STATE_REMINDER_CARD_ID = "pending_reminder_card_id"
        const val STATE_REMINDER_EVENT_ID = "pending_reminder_event_id"
        /** Saved-state text ceiling: 50K chars (~50KB ASCII, ~100KB UTF-16). */
        const val STATE_HANDOFF_TEXT_OVERFLOW = "pending_handoff_text_overflow"
    }
}

private inline fun <T> readIntentExtraOrNull(read: () -> T): T? = try {
    read()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
