package com.alarmhub.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.alarmhub.app.AlarmHubApp
import com.alarmhub.app.domain.schedule.RingDecision
import com.alarmhub.app.domain.schedule.RingExitReason
import com.alarmhub.app.domain.schedule.RingTimeValidator
import com.alarmhub.app.ring.RingActivity
import com.alarmhub.app.ring.RingController
import com.alarmhub.app.ring.RingForegroundService
import com.alarmhub.app.ring.RingRequest
import com.alarmhub.app.ring.media.AlarmSound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * M3.5 — PRD §5.4's 响铃时刻二次校验, then reschedule.
 *
 * ```
 * 若 闹钟已不存在（被删）            → 静默退出（不响、不重排）
 * 若 闹钟.enabled = false            → 静默退出
 * 若 闹钟.permanentlyDisabled        → 静默退出
 * 若 闹钟.pauseUntil > now           → 静默退出
 * 若 分组.permanentlyDisabled        → 静默退出
 * 若 分组.pauseUntil > now           → 静默退出
 * 若 计算出的本次响铃时间 != 本次触发时间 → 静默退出（防止旧的重复闹钟被重放）
 * 否则                                → 进入响铃流程
 * 静默退出时：无论哪种原因，都要重新计算并注册该闹钟的下一次
 * ```
 *
 * The rules live in [RingTimeValidator] — written and unit-tested at M2, with no Android
 * dependency. This class supplies only the database read, the 本次触发时间, and the coroutine
 * plumbing.
 *
 * **M3 scope**: a successful validation ends in a log line. The ring itself — foreground service,
 * `MediaPlayer`, full-screen activity — is M4. Splitting it that way is what lets M3's scheduling
 * decisions be verified from logcat on their own, before any sound is involved.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_TRIGGER) return

        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
        if (alarmId < 0L) {
            Log.w(TAG, "trigger without a usable alarm id; ignoring")
            return
        }
        val rawKind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND)
        val kind = rawKind?.let { runCatching { TriggerKind.valueOf(it) }.getOrNull() }
        if (kind == null) {
            Log.w(TAG, "alarm=$alarmId trigger with unknown kind '$rawKind'")
            return
        }

        /*
         * The database read and the reschedule cannot run on the main thread, and a receiver that
         * returns before its work is done may be killed. `goAsync()` hands the system a token that
         * keeps this process alive until finish() is called.
         */
        val pending = goAsync()
        val app = context.applicationContext as AlarmHubApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handle(app, alarmId, kind)
            } catch (t: Throwable) {
                Log.e(TAG, "alarm=$alarmId kind=$kind failed to handle", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(app: AlarmHubApp, alarmId: Long, kind: TriggerKind) {
        val now = app.timeSource.nowMillis()
        val scheduler = app.scheduler

        /*
         * A PAUSE trigger is not a ring trigger and must never be validated as one. It fires at
         * `pauseUntil`, at which instant the alarm is (by construction) still paused or its next
         * ring has already moved past that moment — so running it through §5.4 would answer
         * "the trigger time changed", and the old code took that as permission to enter the ring
         * flow. Its only job is to make the scheduler recompute now that the pause has ended.
         */
        if (kind == TriggerKind.PAUSE) {
            Log.i(TAG, "alarm=$alarmId PAUSE trigger at $now — recomputing now that the pause has ended")
            scheduler.recompute(alarmId)
            return
        }

        /*
         * PRD FR-7.10: the one-hour heads-up. Not a ring trigger, so it must not go through §5.4's
         * validation — at this instant the alarm is, by design, still an hour away. It only lets the
         * shared check decide whether this occurrence has already been announced.
         */
        if (kind == TriggerKind.ALERT) {
            Log.i(TAG, "alarm=$alarmId ALERT trigger at $now — checking the one-hour heads-up")
            PreRingAlerts.notifyIfDue(app)
            return
        }

        val row = app.repository.alarmsWithGroups().firstOrNull { it.alarm.id == alarmId }

        /*
         * A SNOOZE trigger is a re-ring, not a fresh occurrence, so §5.4's trigger-time check does
         * not apply to it: the alarm's next *regular* occurrence is a different instant by design.
         * What still has to hold is that the alarm is still allowed to ring at all — the user may
         * have deleted it, switched it off, or paused the group during the ten minutes they were
         * asleep. So the same validator runs, with the snooze instant as the trigger time.
         */
        if (kind == TriggerKind.SNOOZE) {
            val scheduledAt = row?.let { app.repository.lastTriggerAt(it.alarm.id) }
            val decision = RingTimeValidator.validate(
                alarm = row?.alarm,
                group = row?.group,
                triggerAt = scheduledAt ?: Long.MIN_VALUE,
                now = now,
                zone = ZoneId.systemDefault(),
                calendar = app.holidayCalendar,
                ringNow = RingController.isRinging,
                // A snooze is its own occurrence, so the "is this the occurrence I registered" check
                // is replaced by "is this the snooze I registered" — which `scheduledAt` already is.
                skipTriggerTimeCheck = true,
            )
            when (decision) {
                is RingDecision.Exit -> {
                    Log.i(TAG, "alarm=$alarmId snooze SILENT EXIT reason=${decision.reason}")
                    if (decision.reason != RingExitReason.ALARM_GONE) scheduler.recompute(alarmId)
                }

                is RingDecision.Ring -> startRing(app, alarmId, now, isSnooze = true)
            }
            return
        }

        /*
         * PRD §5.4's 本次触发时间. The receiver runs a few milliseconds to seconds after the trigger,
         * so `now` is not a substitute; the instant the scheduler registered is read back from the
         * row. A null means nothing is registered for this alarm any more — for a stale pending
         * intent that survived a cancel, which is exactly what the last check is for.
         */
        val scheduledAt = row?.let { app.repository.lastTriggerAt(it.alarm.id) }

        val decision = RingTimeValidator.validate(
            alarm = row?.alarm,
            group = row?.group,
            triggerAt = scheduledAt ?: Long.MIN_VALUE,
            now = now,
            zone = ZoneId.systemDefault(),
            calendar = app.holidayCalendar,
            ringNow = RingController.isRinging,
        )

        when (decision) {
            is RingDecision.Exit -> {
                Log.i(TAG, "alarm=$alarmId kind=$kind SILENT EXIT reason=${decision.reason} scheduledAt=$scheduledAt now=$now")
                // PRD §5.4: every silent exit except "the row is gone" ends by re-registering the
                // next occurrence. A deleted alarm has nothing left to register.
                if (decision.reason != RingExitReason.ALARM_GONE) scheduler.recompute(alarmId)
            }

            is RingDecision.Ring -> {
                if (kind == TriggerKind.PRE) {
                    /*
                     * PRD §5.7: the early front is a safety net, not a starter. It must never begin a
                     * ring, or the alarm would go off five seconds early.
                     *
                     * Its purpose is to have the process alive and the alarms re-armed *before* the
                     * exact minute, so the main trigger does not have to cold-start the app at the
                     * instant the user expects sound. Re-registering here is what buys that: the main
                     * registration is left untouched because `recompute` recomputes the same next ring
                     * (it is still in the future), so this is idempotent rather than a reschedule.
                     */
                    Log.i(TAG, "alarm=$alarmId PRE validated for $scheduledAt; main trigger rings at the exact time")
                    return
                }
                startRing(app, alarmId, now, isSnooze = false)
            }
        }
    }

    /**
     * Hands the validated ring to [RingController] and brings up the sound and the page.
     *
     * The audio is started by the service rather than here, because a `BroadcastReceiver` may not
     * hold a foreground service by itself and must return promptly. The page is launched explicitly as
     * well as through the notification's `fullScreenIntent`, because the two cover different cases: the
     * full-screen intent is what works over the lock screen, and the explicit start is what reliably
     * works when the device is already unlocked and in use.
     */
    private suspend fun startRing(app: AlarmHubApp, alarmId: Long, now: Long, isSnooze: Boolean) {
        val row = app.repository.alarmsWithGroups().firstOrNull { it.alarm.id == alarmId }
        if (row == null) {
            Log.w(TAG, "alarm=$alarmId vanished before the ring could start")
            app.scheduler.cancel(alarmId)
            return
        }

        val settings = app.repository.settings()
        val snoozeUsed = if (isSnooze) app.repository.snoozeCount(alarmId) else 0
        val request = RingRequest.from(
            row = row,
            settings = settings,
            snoozeUsed = snoozeUsed,
            isSnooze = isSnooze,
            alarmSound = AlarmSound(
                // The alarm's own ringtone, falling back to the global default (PRD FR-4.3.9).
                ringtoneUri = row.alarm.ringtoneUri ?: settings.defaultRingtoneUri,
                vibrate = row.alarm.vibrate,
                fadeInSeconds = row.alarm.fadeInSeconds,
            ),
        )

        RingController.attach(app)
        if (!RingController.begin(request)) {
            // Another trigger for this alarm is already ringing; do not double-start.
            return
        }

        /*
         * A fresh ring means a fresh snooze allowance, so the counter is cleared *before* the ring is
         * published. A snooze deliberately keeps it, which is how the cap is enforced across repeats.
         */
        if (!isSnooze) app.repository.clearSnoozeCount(alarmId)

        val context = app.applicationContext
        RingForegroundService.start(context)
        // Through a PendingIntent, for the reason documented on RingActivity.show: a direct
        // startActivity from here is blocked as a background activity launch.
        RingActivity.show(context)

        Log.i(
            TAG,
            "ring started for alarm=$alarmId at=$now snooze=$isSnooze used=$snoozeUsed/${request.snoozeMaxCount}",
        )
    }

    companion object {
        private const val TAG = "AlarmHub/Receiver"
    }
}
