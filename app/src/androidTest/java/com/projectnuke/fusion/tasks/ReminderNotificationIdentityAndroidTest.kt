package com.projectnuke.fusion.tasks

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.content.ContextCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class ReminderNotificationIdentityAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager by lazy { context.getSystemService(NotificationManager::class.java)!! }
    private val testChannel = "p9-reminder-identity-${System.nanoTime()}"
    private val disabledChannel = "p9-reminder-disabled-${System.nanoTime()}"
    private val cardA = ReminderNotificationIdentity.card("00000000-0000-4000-8000-460909ca3012-p9-test")
    private val cardB = ReminderNotificationIdentity.card("00000000-0000-4000-8000-00f66c87acb4-p9-test")
    private val legacyTask = ReminderNotificationIdentity.task(Long.MAX_VALUE)
    private val crossFamilyCardId = "Aa-p9-test"
    private val crossFamilyCard = ReminderNotificationIdentity.card(crossFamilyCardId)
    private val crossFamilyTaskId = run {
        val taskHash = 0x46550000 xor 0x46560000 xor crossFamilyCardId.hashCode()
        (1L shl 32) or Integer.toUnsignedLong(taskHash xor 1)
    }
    private val crossFamilyTask = ReminderNotificationIdentity.task(crossFamilyTaskId)

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(testChannel, "P9 identity test", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun requirePlatformNotifications() {
        assumeTrue("App notifications must be enabled for platform assertions", manager.areNotificationsEnabled())
        if (Build.VERSION.SDK_INT >= 33) {
            assumeTrue(
                "POST_NOTIFICATIONS must be granted for platform assertions",
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        }
    }

    @After
    fun tearDown() {
        manager.activeNotifications
            .filter {
                it.tag == cardA.tag || it.tag == cardB.tag || it.tag == legacyTask.tag ||
                    it.tag == crossFamilyCard.tag || it.tag == crossFamilyTask.tag
            }
            .forEach { manager.cancel(it.tag, it.id) }
        manager.cancel(legacyTask.legacyId)
        cancelOwnedReminder(manager, crossFamilyTask)
        cancelOwnedReminder(manager, crossFamilyCard)
        if (Build.VERSION.SDK_INT >= 26) manager.deleteNotificationChannel(testChannel)
        if (Build.VERSION.SDK_INT >= 26) manager.deleteNotificationChannel(disabledChannel)
    }

    @Test
    fun disabledReminderChannelDoesNotConsumeDurableReminder() = runBlocking {
        assumeTrue("Notification channels require API 26", Build.VERSION.SDK_INT >= 26)
        requirePlatformNotifications()
        manager.createNotificationChannel(
            NotificationChannel(disabledChannel, "P9 disabled channel", NotificationManager.IMPORTANCE_NONE),
        )
        val taskId = Long.MAX_VALUE - 17L
        var consumes = 0

        val delivered = publishReminderBeforeConsume(
            publish = {
                postReminder(
                    context = context,
                    identity = ReminderNotificationIdentity.task(taskId),
                    title = "Disabled channel test",
                    message = "Test",
                    at = System.currentTimeMillis(),
                    channelId = disabledChannel,
                    channelName = "P9 disabled channel",
                    taskId = taskId,
                    cardId = null,
                )
            },
            consume = { consumes++; 1 },
        )

        assertFalse(delivered)
        assertEquals(0, consumes)
        assertFalse(manager.activeNotifications.any { it.tag == ReminderNotificationIdentity.task(taskId).tag })
    }

    @Test
    fun collidingCardsCoexistAndCancelIndependentlyInAndroidNotificationManager() {
        requirePlatformNotifications()
        assertEquals(cardA.id, cardB.id)

        postOwnedReminder(manager, cardA, notification("Card A"))
        postOwnedReminder(manager, cardB, notification("Card B"))
        assertActive(cardA.tag, cardA.id)
        assertActive(cardB.tag, cardB.id)

        cancelOwnedReminder(manager, cardA)
        assertInactive(cardA.tag, cardA.id)
        assertActive(cardB.tag, cardB.id)
    }

    @Test
    fun postingTransitionsExactLegacyIdentityAndCancellationRemovesBothForms() {
        requirePlatformNotifications()
        manager.notify(legacyTask.legacyId, notification("Legacy task"))
        assertActive(null, legacyTask.legacyId)

        postOwnedReminder(manager, legacyTask, notification("Tagged task"))
        assertInactive(null, legacyTask.legacyId)
        assertActive(legacyTask.tag, legacyTask.id)

        cancelOwnedReminder(manager, legacyTask)
        assertInactive(legacyTask.tag, legacyTask.id)
        assertInactive(null, legacyTask.legacyId)
    }

    @Test
    fun taskAndCardWithCollidingLegacyIntegerRemainIndependentInAndroidNotificationManager() {
        requirePlatformNotifications()
        assertEquals(crossFamilyTask.id, crossFamilyCard.id)
        postOwnedReminder(manager, crossFamilyTask, notification("Task owner"))
        postOwnedReminder(manager, crossFamilyCard, notification("Card owner"))
        assertActive(crossFamilyTask.tag, crossFamilyTask.id)
        assertActive(crossFamilyCard.tag, crossFamilyCard.id)

        cancelOwnedReminder(manager, crossFamilyTask)
        assertInactive(crossFamilyTask.tag, crossFamilyTask.id)
        assertActive(crossFamilyCard.tag, crossFamilyCard.id)
    }

    @Test
    fun pendingIntentDataKeepsOwnersDistinctWhenRequestCodesCollide() {
        val scheduler = AndroidProjectTaskReminderScheduler(context)
        val cardIdA = "00000000-0000-4000-8000-460909ca3012-p9-test"
        val cardIdB = "00000000-0000-4000-8000-00f66c87acb4-p9-test"
        assertEquals(cardIdA.hashCode(), cardIdB.hashCode())
        val cardA = scheduler.cardPendingIntent(cardIdA, 100L, 1L)
        val cardB = scheduler.cardPendingIntent(cardIdB, 100L, 1L)
        assertNotEquals(cardA, cardB)

        val taskIdA = 0x0000000100000001L
        val taskIdB = 0x0000000200000002L
        assertEquals(taskIdA.hashCode(), taskIdB.hashCode())
        val taskA = scheduler.pendingIntent(taskIdA, 100L, 1L)
        val taskB = scheduler.pendingIntent(taskIdB, 100L, 1L)
        assertNotEquals(taskA, taskB)

        val sameCardOldGeneration = scheduler.cardPendingIntent("card-X", 100L, 1L)
        val sameCardNewGeneration = scheduler.cardPendingIntent("card-X", 200L, 2L)
        assertEquals(sameCardOldGeneration, sameCardNewGeneration)
    }

    private fun notification(title: String): Notification =
        Notification.Builder(context, testChannel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .build()

    private fun isActive(tag: String?, id: Int): Boolean =
        manager.activeNotifications.any { it.tag == tag && it.id == id }

    /**
     * The platform ranking snapshot behind `activeNotifications` is observed
     * to propagate asynchronously on real devices (an immediate read after
     * post/cancel can still show the previous state), so assertions poll
     * briefly instead of assuming synchronous visibility.
     */
    private fun assertActive(tag: String?, id: Int, timeoutMs: Long = 5_000L) {
        assertTrue("expected active ($tag, $id): ${describe(tag, id)}", awaitState(tag, id, true, timeoutMs))
    }

    private fun assertInactive(tag: String?, id: Int, timeoutMs: Long = 5_000L) {
        assertTrue("expected inactive ($tag, $id): ${describe(tag, id)}", awaitState(tag, id, false, timeoutMs))
    }

    private fun awaitState(tag: String?, id: Int, wantActive: Boolean, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isActive(tag, id) == wantActive) return true
            Thread.sleep(100)
        }
        return isActive(tag, id) == wantActive
    }

    private fun describe(tag: String?, id: Int): String =
        manager.activeNotifications
            .filter { it.id == id }
            .joinToString { "(tag=${it.tag}, id=${it.id})" }
}
