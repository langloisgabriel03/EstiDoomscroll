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
     * A comment or share sheet over an open post, or a Home or Explore feed
     * video, sits over that video's player. It must not read as a Reel launch.
     */
    fun keepsScreenUnderComments(current: InstagramScreen, classified: InstagramScreen): Boolean =
        (classified == InstagramScreen.REELS &&
            current in setOf(InstagramScreen.HOME_FEED, InstagramScreen.COMMENTS_OR_DETAIL,
                InstagramScreen.EXPLORE, InstagramScreen.SEARCH_RESULTS)) ||
            // A sheet hides the open post, so the grid behind it classifies as
            // Explore. The post may not have been recorded as open (a post
            // opened from Explore or search), so those count as well.
            (classified == InstagramScreen.EXPLORE && current in setOf(
                InstagramScreen.COMMENTS_OR_DETAIL, InstagramScreen.EXPLORE,
                InstagramScreen.SEARCH_RESULTS))

    /** The ids of a scroll's ancestors that mean a comment or share sheet, not the viewer. */
    /** Share sheet controls, which carry no stable ids: exact visible texts. */
    val shareSheetTexts = setOf("send", "copy link", "add to story", "send separately",
        "gönder", "bağlantıyı kopyala", "hikayene ekle")

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

/**
 * Swipes in a Reel viewer's video pager. Opened from anywhere (a Reel in the
 * Home feed, a DM, Explore), the chosen Reel plays, but swiping to another
 * video is the Reels feed. Small layout nudges are not a swipe: it takes a
 * new page, or a vertical move of at least [minDy] pixels.
 */
internal class ReelPagerSwipe(private val minDy: Int, private val idleMs: Long = 5_000L) {
    private val motion = InstagramReelsPagerMotion()
    private var lastAt = Long.MIN_VALUE

    fun observe(now: Long, dx: Int, dy: Int, fromIndex: Int, scrollY: Int): Boolean {
        // A long pause means a new viewer: forget the old page index.
        if (lastAt == Long.MIN_VALUE || now - lastAt > idleMs) motion.reset()
        lastAt = now
        val moved = motion.observe(dx, dy, fromIndex, scrollY)
        if (!moved) return false
        return dy == 0 || kotlin.math.abs(dy) >= minDy
    }

    fun reset() {
        motion.reset()
        lastAt = Long.MIN_VALUE
    }
}
