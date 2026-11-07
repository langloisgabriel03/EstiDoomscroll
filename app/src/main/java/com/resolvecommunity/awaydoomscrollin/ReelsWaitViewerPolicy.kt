package com.resolvecommunity.awaydoomscrollin

import kotlin.math.abs

/**
 * Reels Wait fork: posts and Reels opened from Explore or search. One chosen
 * post (and its comments) may be read, but Explore opens it in a feed of more
 * suggested posts and Reels, so scrolling on to the next one is blocked like
 * the Reels tab. Posts opened from the Home feed stay free to scroll.
 */
internal object ReelsWaitViewerPolicy {
    private val viewers = setOf(InstagramScreen.REELS, InstagramScreen.COMMENTS_OR_DETAIL)

    /** The surface a viewer was opened from; null outside a viewer. */
    fun origin(previous: InstagramScreen, screen: InstagramScreen,
        current: InstagramScreen?): InstagramScreen? = when {
        screen == InstagramScreen.UNKNOWN -> current
        screen !in viewers -> null
        // Post -> its Reel or back keeps where the viewer came from.
        previous in viewers || previous == InstagramScreen.UNKNOWN -> current
        else -> previous
    }

    fun blocksScroll(origin: InstagramScreen?): Boolean =
        origin == InstagramScreen.EXPLORE || origin == InstagramScreen.SEARCH_RESULTS

    /**
     * A comment sheet over an open post or Home feed video sits over that
     * video's player. It must not read as a Reel launch or as Explore behind.
     */
    fun keepsScreenUnderComments(current: InstagramScreen, classified: InstagramScreen): Boolean =
        (current == InstagramScreen.HOME_FEED || current == InstagramScreen.COMMENTS_OR_DETAIL) &&
            (classified == InstagramScreen.REELS || classified == InstagramScreen.EXPLORE)

    /** The ids of a scroll's ancestors that mean a comment or share sheet, not the viewer. */
    fun isSheetPath(ids: Set<String>): Boolean = ids.any {
        it in InstagramReelsIdentity.safeSheetIds ||
            it == "bottom_sheet_container" || it == "layout_container_bottom_sheet"
    }
}

/**
 * Adds up vertical scrolling in one viewer. A little is reading the chosen
 * post (its caption, the start of comments); [limitPx] is moving on to the
 * next one. Sideways swipes (photo carousels) never count.
 */
internal class ViewerScrollBudget(private val limitPx: Int) {
    private var total = 0
    private var lastIndex = -1

    fun reset() {
        total = 0
        lastIndex = -1
    }

    /** True once the viewer has been scrolled past the limit. */
    fun add(dx: Int, dy: Int, fromIndex: Int): Boolean {
        val indexChanged = lastIndex >= 0 && fromIndex >= 0 && fromIndex != lastIndex
        if (fromIndex >= 0) lastIndex = fromIndex
        if (abs(dx) > abs(dy)) return false
        total += when {
            dy != 0 -> abs(dy)
            // No delta reported (a pager): a new page is a whole step.
            indexChanged -> limitPx
            else -> 0
        }
        return total >= limitPx
    }
}
