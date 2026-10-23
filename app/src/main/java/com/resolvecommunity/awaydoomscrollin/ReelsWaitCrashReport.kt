package com.resolvecommunity.awaydoomscrollin

import android.content.Context

/**
 * Reels Wait fork: remembers an app crash and shows it as a notification the
 * next time the service starts, since the phone has no easy way to read logs.
 * Stays on the device; nothing is sent anywhere.
 */
object ReelsWaitCrashReport {
    private const val KEY_LAST_CRASH = "reels_wait_last_crash"
    private const val NOTIFICATION_ID = 1010
    private const val MAX_FRAMES = 6

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    /** Shows and clears a crash saved by the previous process, if any. */
    fun showPending(context: Context) {
        val prefs = context.getSharedPreferences(ProtectionPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val report = prefs.getString(KEY_LAST_CRASH, null) ?: return
        prefs.edit().remove(KEY_LAST_CRASH).apply()
        NotificationHelper.showShieldStatusNotification(
            context, "Reels Wait crashed (please send this)", report, NOTIFICATION_ID)
    }

    /** "Type: message" plus the first frames, innermost cause first. */
    fun describe(error: Throwable): String {
        var root = error
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val frames = root.stackTrace.take(MAX_FRAMES).joinToString("\n") {
            "at ${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        }
        return "${root.javaClass.simpleName}: ${root.message}\n$frames"
    }

    private class Handler(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                context.getSharedPreferences(ProtectionPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LAST_CRASH, describe(error)).commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
