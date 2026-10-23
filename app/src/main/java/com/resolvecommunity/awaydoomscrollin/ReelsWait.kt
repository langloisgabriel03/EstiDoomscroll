package com.resolvecommunity.awaydoomscrollin

import android.content.SharedPreferences

/**
 * Reels Wait fork: the single place to tune the wait timer. Instead of a hard
 * block, a blocked Instagram Reel shows a countdown of [WAIT_SECONDS]; after it,
 * or after winning the mini-game shown with it, the user may open Instagram
 * unguarded for [WINDOW_MINUTES]. Game rules live in UnlockChallengeLogic.kt.
 */
object ReelsWaitConfig {
    const val WAIT_SECONDS = 120
    const val WINDOW_MINUTES = 5

    const val WINDOW_MS = WINDOW_MINUTES * 60_000L

    /**
     * Keep the Instagram Home/Explore and YouTube Home/Subscriptions feeds
     * visible; only Reels and Shorts stay blocked. Upstream covers the feeds.
     */
    const val KEEP_FEEDS_OPEN = true

    /**
     * Hook for later friction ideas (escalating waits per unlock, daily caps).
     * Every caller asks here instead of reading [WAIT_SECONDS] directly.
     */
    fun waitSeconds(): Int = WAIT_SECONDS

    /** Countdown label: "2:00", "1:05", "0:09". */
    fun formatCountdown(seconds: Long): String {
        val clamped = seconds.coerceAtLeast(0L)
        return "%d:%02d".format(clamped / 60, clamped % 60)
    }
}

/**
 * Pure unlock-window logic, kept free of Android types so it is unit tested.
 * Times are wall-clock epoch milliseconds so the window survives a service
 * restart.
 */
object ReelsUnlock {
    const val KEY_UNLOCKED_UNTIL = "reels_unlocked_until_ms"

    /**
     * True only inside the window. A stored end time further away than one
     * window (the clock was moved back) is treated as locked, so a clock change
     * can never grant a longer unlock than [windowMs].
     */
    fun isUnlocked(
        nowMs: Long,
        unlockedUntilMs: Long,
        windowMs: Long = ReelsWaitConfig.WINDOW_MS
    ): Boolean = nowMs < unlockedUntilMs && unlockedUntilMs - nowMs <= windowMs

    fun unlockedUntil(nowMs: Long, windowMs: Long = ReelsWaitConfig.WINDOW_MS): Long =
        nowMs + windowMs

    /** Milliseconds left in the window, or 0 when locked. */
    fun remainingMs(
        nowMs: Long,
        unlockedUntilMs: Long,
        windowMs: Long = ReelsWaitConfig.WINDOW_MS
    ): Long = if (isUnlocked(nowMs, unlockedUntilMs, windowMs)) unlockedUntilMs - nowMs else 0L

    fun isUnlocked(prefs: SharedPreferences, nowMs: Long = System.currentTimeMillis()): Boolean =
        isUnlocked(nowMs, prefs.getLong(KEY_UNLOCKED_UNTIL, 0L))

    fun remainingMs(prefs: SharedPreferences, nowMs: Long = System.currentTimeMillis()): Long =
        remainingMs(nowMs, prefs.getLong(KEY_UNLOCKED_UNTIL, 0L))

    /** Persists synchronously so the service never re-reads an older value. */
    fun unlock(prefs: SharedPreferences, nowMs: Long = System.currentTimeMillis()): Long {
        val until = unlockedUntil(nowMs)
        prefs.edit().putLong(KEY_UNLOCKED_UNTIL, until).commit()
        return until
    }
}
