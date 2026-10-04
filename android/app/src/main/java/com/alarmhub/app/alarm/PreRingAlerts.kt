package com.alarmhub.app.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.alarmhub.app.AlarmHubApp
import com.alarmhub.app.MainActivity
import com.alarmhub.app.R
import com.alarmhub.app.domain.schedule.NextRingCalculator
import com.alarmhub.app.ring.RingController
import com.alarmhub.app.ring.RingNotifications
import com.alarmhub.app.ring.RingRequest
import java.time.Instant
import java.time.ZoneId
/**
 * PRD FR-7.10 — "09:30 即将响铃": one notification when the next alarm is within the hour.
 *
 * ## The rule, and why it is written this way
 *
 * The user's requirement is 「出现一次即可，用户清理掉不需要二次出现」. So this is **not** a persistent
 * reminder: it is posted once per *occurrence* and never re-posted for the same one.
 *
 * The state that makes that work is a single remembered instant — the **ring time** the notification was
 * posted for — kept in `settings.pre_alert_notified_at`. Every decision is a comparison against it:
 *
 * | situation | next ring vs. remembered | outcome |
 * |---|---|---|
 * | first time the alarm comes within the hour | `next > remembered` | **post**, remember `next` |
 * | the user clears the notification | unchanged | **silent** — nothing changed, so nothing is re-posted |
 * | the alarm is edited to another time | different instant | **post** again, remember the new one |
 * | the alarm is switched off / deleted / paused | another alarm (or none) becomes next | judged on its own merits |
 *
 * Storing *when the notification appeared* instead would get the "cleared it, do not nag" case right and
 * the "I moved the alarm" case wrong, which is the more annoying failure of the two.
 *
 * ## Why it needs a scheduled trigger at all
 *
 * "Inside the hour" passes on its own while the app is not running — the user's whole point is to be told
 * an hour before, which is usually before they next open the app. So the transition is registered with
 * `AlarmManager` ([TriggerKind.ALERT], one hour before the ring) rather than only computed when a screen
 * is up. [notifyIfDue] is then also called after every recompute and at startup, so a device that was
 * off, a changed clock, or a fresh install all get the same answer from the same code.
 */
object PreRingAlerts {

    /** PRD FR-7.10.1: the window, in minutes. */
    const val WINDOW_MINUTES = 60

    private const val TAG = "AlarmHub/PreAlert"

    /**
     * Its own channel, created with `IMPORTANCE_DEFAULT`.
     *
     * Separate from the ring channel on purpose: the ring channel is `IMPORTANCE_HIGH` with a
     * full-screen intent, and reusing it would make an hour-early heads-up behave like the alarm going
     * off. It also has to be created in `Application.onCreate` — Android fixes a channel's importance
     * at creation, so a channel created late can never be raised (the ring channel's KDoc records that
     * same lesson).
     */
    const val CHANNEL_ID = "alarmhub_upcoming"

    /** Distinct from [RingNotifications.NOTIFICATION_ID], so the two can never replace each other. */
    const val NOTIFICATION_ID = 1002

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "闹钟即将响铃", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "下一个闹钟进入一小时内时提醒一次"
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    /**
     * Posts the notification when — and only when — the next alarm has entered the window and this
     * occurrence has not been announced yet.
     *
     * Safe to call as often as convenient: it is a pure read plus one write that happens at most once
     * per occurrence. Callers are the scheduler's recompute (any write, boot, time change) and the
     * one-hour trigger.
     */
    suspend fun notifyIfDue(context: Context) {
        val app = AlarmHubApp.of(context)
        val now = app.timeSource.nowMillis()
        val zone = ZoneId.systemDefault()
        val windowStart = now + WINDOW_MINUTES * 60_000L

        val calculator = NextRingCalculator(app.holidayCalendar)
        val next = app.repository.alarmsWithGroups()
            .mapNotNull { row ->
                calculator.nextRing(row.alarm, row.group, now, zone)
                    ?.let { at -> Triple(row.alarm.id, at, row.alarm.label) }
            }
            .minByOrNull { it.second }

        if (next == null) {
            // Nothing is scheduled at all. Clear the memory so that re-arming the same alarm later is
            // announced again rather than suppressed by a stale instant.
            if (app.repository.preAlertNotifiedAt() != null) {
                Log.i(TAG, "nothing scheduled; clearing the announced instant")
                app.repository.setPreAlertNotifiedAt(null)
            }
            return
        }

        val (alarmId, ringAt, label) = next
        if (ringAt > windowStart) {
            // Still further out than an hour. The ALERT trigger registered by the scheduler will call
            // back when it is not.
            //
            // Take the notification down on the way out: it claims an alarm is imminent, and if the user
            // moved or paused the alarm it is now simply wrong. Leaving it up was the first version's
            // behaviour and it produced a lying notification — found by a test that moved an alarm from
            // 45 minutes out to 3 hours out and still read 「03:46 即将响铃」.
            cancel(context)
            Log.d(TAG, "alarm=$alarmId is ${(ringAt - now) / 60_000} min out; not yet")
            return
        }

        val announced = app.repository.preAlertNotifiedAt()
        if (announced == ringAt) {
            // This exact occurrence has already been announced, and nothing about it has changed since.
            // The user clearing the notification changes nothing here — which is the requirement: one
            // appearance per occurrence, no nagging.
            Log.i(TAG, "alarm=$alarmId at=${iso(ringAt)} already announced; staying silent")
            return
        }

        if (RingController.isRinging) {
            // The alarm it is about is already going off — the ring notification is the one to show
            // (PRD FR-7.10.4: the two must not fight over the shade).
            Log.i(TAG, "a ring is in progress; the pre-alert is pointless")
            return
        }

        post(context, alarmId, ringAt, label, app, app.repository.settings().timeFormat)
        app.repository.setPreAlertNotifiedAt(ringAt)
        Log.i(TAG, "announced alarm=$alarmId at=${iso(ringAt)} (${(ringAt - now) / 60_000} min out)")
    }

    private fun post(
        context: Context,
        alarmId: Long,
        ringAt: Long,
        label: String,
        app: AlarmHubApp,
        timeFormat: com.alarmhub.app.domain.model.TimeFormat,
    ) {
        val at = Instant.ofEpochMilli(ringAt).atZone(ZoneId.systemDefault())
        val clock = RingRequest.formatClock(at.hour, at.minute, timeFormat)
        val name = label.ifBlank { "闹钟" }

        /*
         * Tapping the notification body **turns the alarm off** — there is no separate action button.
         *
         * This is a real-device finding, not a preference. The first version had the obvious design: a
         * 「关闭闹钟」 action via `addAction`, body tap opening the app. On the Xiaomi 14 the user reported
         * "没有「关闭闹钟」按钮", and `dumpsys notification` showed the action *was* registered
         * (`actions={[0] "关闭闹钟" -> PendingIntent{...}}`) — but **HyperOS notifications cannot be
         * expanded by pulling down** (only swiped sideways), and Android only renders action buttons in
         * the expanded state. So the button could never be reached on this device: registered, invisible.
         *
         * Pointing the content intent at the turn-off action instead makes the affordance work in the
         * collapsed row, on every ROM, without depending on a gesture this platform does not have.
         *
         * Trade-off, recorded in PRD FR-7.10.5: tapping no longer opens the app. That is the one thing
         * lost, and it is deliberately traded for an action the user can actually perform.
         */
        val turnOff = PendingIntent.getBroadcast(
            context,
            11,
            PreRingActionReceiver.turnOffIntent(context, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("$clock 即将响铃")
            .setContentText(name)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Dismissible by design: the user asked for one appearance, and "cleared" is an answer.
            .setOngoing(false)
            .setAutoCancel(true)
            .setShowWhen(false)
            .setContentIntent(turnOff)
            .build()

        if (!RingNotifications.canPost(context)) {
            Log.w(TAG, "notifications are not permitted; the alert cannot be shown")
            return
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.e(TAG, "could not post the pre-alert", it) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun iso(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toString()
}
