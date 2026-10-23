package com.resolvecommunity.awaydoomscrollin

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Mounts the real wait screen with each game and draws it, as the phone does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReelsWaitOverlayTest {

    class TestService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
        override fun onInterrupt() {}
    }

    private fun drawn(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1080, 2340)
        view.draw(Canvas(Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun views(root: View): List<View> =
        listOf(root) + ((root as? ViewGroup)?.let { group ->
            (0 until group.childCount).flatMap { views(group.getChildAt(it)) }
        } ?: emptyList())

    @Test
    fun `every game mounts, draws and takes input`() {
        UnlockChallenges.all().forEach { game ->
            val service = Robolectric.setupService(TestService::class.java)
            var unlocked = 0
            val overlay = ReelsWaitOverlay(service, onUnlock = { unlocked++ }, onDismiss = {})
            assertTrue(game.title, overlay.show(game))
            val root = assertNotNull(overlay.viewForTest).let { overlay.viewForTest!! }
            drawn(root)

            // Tap and type into every text box, then redraw.
            views(root).filterIsInstance<EditText>().forEach { field ->
                field.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0,
                    android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0))
                field.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0,
                    android.view.MotionEvent.ACTION_UP, 10f, 10f, 0))
                field.setText("12")
                field.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
            }
            // Press every button that is not "Never mind".
            views(root).filterIsInstance<TextView>()
                .filter { it.isClickable && it !is EditText && it.text.toString() != "Never mind" }
                .forEach { it.performClick() }
            drawn(root)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            overlay.hide()
            assertEquals(null, overlay.viewForTest)
        }
    }

    @Test
    fun `typing game unlocks on an exact copy`() {
        val service = Robolectric.setupService(TestService::class.java)
        var unlocked = 0
        val overlay = ReelsWaitOverlay(service, onUnlock = { unlocked++ }, onDismiss = {})
        val game = TypingChallenge()
        assertTrue(overlay.show(game))
        val root = overlay.viewForTest!!
        drawn(root)
        val sentence = views(root).filterIsInstance<TextView>()
            .first { it !is EditText && it.text.toString() in TypingCheck.sentences }
            .text.toString()
        views(root).filterIsInstance<EditText>().single().setText(sentence)
        assertEquals(1, unlocked)
        assertEquals(null, overlay.viewForTest)
    }
}
