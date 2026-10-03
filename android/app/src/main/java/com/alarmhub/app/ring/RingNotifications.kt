package com.alarmhub.app.ring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.alarmhub.app.MainActivity
import com.alarmhub.app.R

/**
 * The ringing notification (PRD FR-4.3.10, TECH-STACK §4.4).
 *
 * Two things here are load-bearing rather than cosmetic:
 *
 *  1. **`fullScreenIntent`** is what makes the ring page appear over the lock screen on its own.
 *     Android 14+ lets the user revoke it, so it is treated as best-effort — the *sound* does not
 *     depend on it (that is why the sound lives in a foreground service, not in the activity).
 *  2. **`CATEGORY_ALARM` + `IMPORTANCE_HIGH`** keep the notification out of the "silent" bucket and
 *     above the lock screen, and let a DND-enabled device still show it.
 */
object RingNotifications {

    /** Its own channel so the user can see exactly which notifications the alarm makes. */
    const val CHANNEL_ID = "alarmhub_ring"

    /** Stable id: there is only ever one ring at a time, and re-posting must replace, not stack. */
    const val NOTIFICATION_ID = 1001

    /**
     * Creates the channel. Called from `Application.onCreate` so it exists before any ring, and
     * because channel importance cannot be raised after creation — creating it late would silently
     * give the user a downgraded channel.
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "闹钟响铃", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "闹钟到点时的全屏提醒"
                // The alarm is already making sound through its own audio stream; the channel must not
                // add a second notification sound on top of it.
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            },
        )
    }

    /**
     * True when the app may post notifications.
     *
     * On Android 13+ this needs `POST_NOTIFICATIONS`; without it `startForeground` still works (the
     * notification is simply not shown to the user), which is why a missing grant must not stop the
     * alarm from ringing.
     */
    fun canPost(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** The full-screen ring page, as an intent the notification can launch. */
    private fun ringActivityIntent(context: Context): Intent =
        Intent(context, RingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

    /** 关闭, straight from the notification, without opening the page. */
    private fun dismissIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            RingActionReceiver.dismissIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun build(context: Context, request: RingRequest): Notification {
        val fullScreen = PendingIntent.getActivity(
            context,
            1,
            ringActivityIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /*
         * The snooze line, driven by the **merged** flag on the request rather than by the alarm's own
         * switch alone.
         *
         * This was the bug: the shade kept advertising 「贪睡 10 分钟（还剩 3 次）」 after 贪睡 was switched
         * off globally, because this builder read `request.snoozeEnabled` back when that field only
         * meant "the alarm's own switch". `RingRequest.from` now merges both switches, so nothing here
         * has to know there are two.
         *
         * The three states have to stay apart, and the first version of this fix collapsed two of them
         * into 「已无贪睡次数」 — an exhausted allowance reported on an alarm whose allowance had never
         * been spent. Merging the switches made that easy to get wrong, so the cases are named here:
         *
         *   贪睡 offered            → 「贪睡 10 分钟（还剩 3 次）」
         *   贪睡 used up            → 「已无贪睡次数」
         *   贪睡 switched off       → 「贪睡已关闭」 (accurate, and it explains the missing button)
         *
         * Caught by reading the notification text in all three states on the emulator
         * (`.shots/t-notification-snooze.py`), not by reasoning about it.
         */
        val snoozeHint = when {
            request.canSnooze -> "贪睡 %d 分钟（还剩 %d 次）".format(request.snoozeMinutes, request.snoozesRemaining)
            !request.snoozeEnabled -> "贪睡已关闭"
            request.snoozeConfigured -> "已无贪睡次数"
            else -> null
        }
        val contentText = listOfNotNull(request.groupName, snoozeHint).joinToString(" · ")

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(request.clockText() + "  " + request.displayedLabel)
            .setContentText(contentText)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            // What actually brings the page up over the lock screen.
            .setFullScreenIntent(fullScreen, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", dismissIntent(context))
            .build()
    }

    fun show(context: Context, request: RingRequest) {
        if (!canPost(context)) return
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context, request))
        }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }
}
