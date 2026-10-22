package com.resolvecommunity.awaydoomscrollin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelsUnlockTest {
    private val window = 5 * 60_000L
    private val start = 1_700_000_000_000L
    private val until = ReelsUnlock.unlockedUntil(start, window)

    @Test
    fun `default config is a 30 second wait and a 5 minute window`() {
        assertEquals(30, ReelsWaitConfig.WAIT_SECONDS)
        assertEquals(5, ReelsWaitConfig.WINDOW_MINUTES)
        assertEquals(300_000L, ReelsWaitConfig.WINDOW_MS)
        assertEquals(start + 300_000L, ReelsUnlock.unlockedUntil(start))
    }

    @Test
    fun `never unlocked is locked`() {
        assertFalse(ReelsUnlock.isUnlocked(start, 0L, window))
        assertEquals(0L, ReelsUnlock.remainingMs(start, 0L, window))
    }

    @Test
    fun `before the window starts is locked`() {
        // A timestamp from before the unlock was granted: end time is more
        // than one window away, which only happens after a clock change.
        assertFalse(ReelsUnlock.isUnlocked(start - 1L, until, window))
    }

    @Test
    fun `inside the window is unlocked`() {
        assertTrue(ReelsUnlock.isUnlocked(start, until, window))
        assertTrue(ReelsUnlock.isUnlocked(start + window / 2, until, window))
        assertTrue(ReelsUnlock.isUnlocked(until - 1L, until, window))
        assertEquals(1L, ReelsUnlock.remainingMs(until - 1L, until, window))
    }

    @Test
    fun `at and after expiry is locked`() {
        assertFalse(ReelsUnlock.isUnlocked(until, until, window))
        assertFalse(ReelsUnlock.isUnlocked(until + 60_000L, until, window))
        assertEquals(0L, ReelsUnlock.remainingMs(until + 1L, until, window))
    }

    @Test
    fun `clock moved back cannot extend the window`() {
        assertFalse(ReelsUnlock.isUnlocked(start - 24 * 60 * 60_000L, until, window))
    }
}
