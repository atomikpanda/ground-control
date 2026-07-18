package com.atomikpanda.groundcontrol.notify

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * Cancels the needs-you notification for a thread the user is now viewing (#378). Abstracted behind
 * an interface so the ViewModel's "cancel on open" decision is unit-testable with a fake, while the
 * real [NotificationManagerCompat] call stays thin, Android-only glue.
 */
interface NeedsYouCanceller {
    fun cancel(connId: String, threadId: String)
}

/** No-op default so tests / previews construct a ViewModel without an Android Context. */
object NoopNeedsYouCanceller : NeedsYouCanceller {
    override fun cancel(connId: String, threadId: String) {}
}

/**
 * Real canceller: dismisses the posted notification by the SAME [needsYouNotificationId] derivation
 * [AndroidNotifier] posted it under, so opening a thread proactively clears its shade entry even
 * when the user reached it in-app rather than by tapping the notification.
 */
class AndroidNeedsYouCanceller(private val context: Context) : NeedsYouCanceller {
    override fun cancel(connId: String, threadId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(needsYouNotificationId(connId, threadId))
        }
    }
}
