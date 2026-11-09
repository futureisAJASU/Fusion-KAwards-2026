package com.projectnuke.fusion.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Stale-callback regression: a terminal platform callback may settle the
// read-aloud session only if it belongs to the currently owned utterance.
// Older (replaced/stopped/invalidated) utterance identities must be fenced,
// so an old generation can never settle a new one.
class UtteranceOwnershipFenceTest {

    @Test
    fun `late completion for replaced utterance cannot settle the new one`() {
        val fence = UtteranceOwnershipFence()
        val a = fence.begin()
        val b = fence.begin() // B replaces A while A is still active
        // Delayed onDone belonging to A arrives while B is current.
        assertFalse(fence.accept(a)) // must NOT settle B
        // B's own terminal still settles B...
        assertTrue(fence.accept(b))
        // ...exactly once.
        assertFalse(fence.accept(b))
        // And A stays fenced forever.
        assertFalse(fence.accept(a))
    }

    @Test
    fun `late error for replaced utterance is fenced`() {
        val fence = UtteranceOwnershipFence()
        val a = fence.begin()
        val b = fence.begin()
        assertFalse(fence.accept(a)) // stale A error while B is current
        assertTrue(fence.accept(b))
    }

    @Test
    fun `stop invalidates ownership and fences late callbacks`() {
        val fence = UtteranceOwnershipFence()
        val a = fence.begin()
        fence.invalidate() // engine stop()
        assertFalse(fence.accept(a))
        assertFalse(fence.accept(null))
    }

    @Test
    fun `shutdown invalidation fences late callbacks from both generations`() {
        val fence = UtteranceOwnershipFence()
        val a = fence.begin()
        val b = fence.begin()
        fence.invalidate() // engine shutdown()
        assertFalse(fence.accept(a))
        assertFalse(fence.accept(b))
    }

    @Test
    fun `consumed terminal cannot settle twice`() {
        val fence = UtteranceOwnershipFence()
        val a = fence.begin()
        assertTrue(fence.accept(a)) // onDone
        assertFalse(fence.accept(a)) // duplicate onError for same utterance
    }

    @Test
    fun `natural completion settles exactly once then new generation is fenced from old`() {
        val fence = UtteranceOwnershipFence()
        val a = fence.begin()
        assertTrue(fence.accept(a))
        val b = fence.begin()
        assertFalse(fence.accept(a)) // old identity is gone
        assertTrue(fence.accept(b))
    }

    @Test
    fun `foreign or null callback is never accepted and leaves ownership intact`() {
        val fence = UtteranceOwnershipFence()
        val a = fence.begin()
        assertFalse(fence.accept(null))
        assertFalse(fence.accept("some-other-utterance"))
        // Fenced callbacks must not disturb current ownership.
        assertTrue(fence.accept(a))
    }

    @Test
    fun `failed speak path leaves no falsely active ownership token`() {
        val fence = UtteranceOwnershipFence()
        val a = fence.begin()
        fence.invalidate() // engine invalidates when tts.speak fails/throws
        assertFalse(fence.accept(a))
        // A later, successful generation still owns and settles normally.
        val b = fence.begin()
        assertTrue(fence.accept(b))
    }

    @Test
    fun `only the newest generation owns the session`() {
        val fence = UtteranceOwnershipFence()
        val a = fence.begin()
        val b = fence.begin()
        fence.begin() // third generation makes both of these stale
        assertFalse(fence.accept(a))
        assertFalse(fence.accept(b))
    }
}
