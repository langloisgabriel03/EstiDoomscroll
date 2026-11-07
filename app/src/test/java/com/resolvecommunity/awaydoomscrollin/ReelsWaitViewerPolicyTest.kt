package com.resolvecommunity.awaydoomscrollin

import com.resolvecommunity.awaydoomscrollin.InstagramScreen.COMMENTS_OR_DETAIL
import com.resolvecommunity.awaydoomscrollin.InstagramScreen.EXPLORE
import com.resolvecommunity.awaydoomscrollin.InstagramScreen.HOME_FEED
import com.resolvecommunity.awaydoomscrollin.InstagramScreen.REELS
import com.resolvecommunity.awaydoomscrollin.InstagramScreen.SEARCH_RESULTS
import com.resolvecommunity.awaydoomscrollin.InstagramScreen.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelsWaitViewerPolicyTest {

    @Test
    fun `a viewer remembers where it was opened from`() {
        assertEquals(EXPLORE, ReelsWaitViewerPolicy.origin(EXPLORE, COMMENTS_OR_DETAIL, null))
        // A post's video opening as a Reel keeps the Explore origin.
        assertEquals(EXPLORE, ReelsWaitViewerPolicy.origin(COMMENTS_OR_DETAIL, REELS, EXPLORE))
        assertEquals(EXPLORE, ReelsWaitViewerPolicy.origin(COMMENTS_OR_DETAIL, UNKNOWN, EXPLORE))
        assertEquals(HOME_FEED, ReelsWaitViewerPolicy.origin(HOME_FEED, COMMENTS_OR_DETAIL, null))
        assertNull(ReelsWaitViewerPolicy.origin(COMMENTS_OR_DETAIL, EXPLORE, EXPLORE))
    }

    @Test
    fun `only explore and search viewers block scrolling`() {
        assertTrue(ReelsWaitViewerPolicy.blocksScroll(EXPLORE))
        assertTrue(ReelsWaitViewerPolicy.blocksScroll(SEARCH_RESULTS))
        assertFalse(ReelsWaitViewerPolicy.blocksScroll(HOME_FEED))
        assertFalse(ReelsWaitViewerPolicy.blocksScroll(null))
    }

    @Test
    fun `comments over an open post keep that post`() {
        assertTrue(ReelsWaitViewerPolicy.keepsScreenUnderComments(COMMENTS_OR_DETAIL, EXPLORE))
        assertTrue(ReelsWaitViewerPolicy.keepsScreenUnderComments(COMMENTS_OR_DETAIL, REELS))
        assertTrue(ReelsWaitViewerPolicy.keepsScreenUnderComments(HOME_FEED, REELS))
        assertFalse(ReelsWaitViewerPolicy.keepsScreenUnderComments(EXPLORE, REELS))
        assertTrue(ReelsWaitViewerPolicy.isSheetPath(setOf("list", "bottom_sheet_container")))
        assertFalse(ReelsWaitViewerPolicy.isSheetPath(setOf("list")))
    }

    @Test
    fun `scroll budget counts vertical movement only`() {
        val budget = ViewerScrollBudget(limitPx = 1000)
        assertFalse(budget.add(dx = 900, dy = 10, fromIndex = 0))
        assertFalse(budget.add(dx = 0, dy = 400, fromIndex = 0))
        assertFalse(budget.add(dx = 0, dy = -300, fromIndex = 1))
        assertTrue(budget.add(dx = 0, dy = 400, fromIndex = 2))
        budget.reset()
        // A pager reports no delta: one page is enough.
        assertFalse(budget.add(dx = 0, dy = 0, fromIndex = 0))
        assertTrue(budget.add(dx = 0, dy = 0, fromIndex = 1))
    }
}
