package com.alarmhub.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.alarmhub.app.data.AlarmRepository
import com.alarmhub.app.data.AlarmWithGroup
import com.alarmhub.app.domain.TimeSource
import com.alarmhub.app.domain.calendar.HolidayCalendar
import com.alarmhub.app.domain.schedule.NextRingCalculator
import java.time.Instant
import java.time.ZoneId

/**
 * What a registered [PendingIntent] is supposed to do when it fires.
 *
 * Carried as an [Intent] extra rather than inferred from the action string, so adding a kind never
 * changes an action name that already-registered (and possibly reboot-surviving) intents were
 * created with.
 */
enum class TriggerKind {
    /** The on-time trigger (PRD §5.7's 主触发). */
    MAIN,

    /** Five seconds early, the redundant front (PRD §5.7's 冗余前沿). */
    PRE,

    /** Fires at `pauseUntil` so the next alarm gets re-registered (M3.7). */
    PAUSE,

    /**
     * A snooze's re-ring (PRD FR-4.3.4).
     *
     * Separate from [MAIN] because the two are validated differently: §5.4's trigger-time check is
     * about "is this the occurrence I registered", and a snooze is deliberately *not* the alarm's next
     * regular occurrence. Running it through that check would always reject it.
     */
    SNOOZE,
}

/**
 * Registers and cancels the system alarms (M3.4, TECH-STACK §4.2).
 *
 * Registration shape, per alarm and only when it is actually allowed to ring:
 *
 * | trigger | API | why |
 * |---|---|---|
 * | MAIN | `setAlarmClock` | the highest priority a third-party app gets; exempt from Doze; shows the system alarm icon, which is what an alarm is expected to do |
 * | PRE (5 s early) | `setExactAndAllowWhileIdle` | guards against a single trigger being dropped. Deliberately **not** `setAlarmClock`, or the status-bar icon would advertise a time five seconds early |
 * | PAUSE | `setExactAndAllowWhileIdle` | at `pauseUntil`, so expiry re-registers alarms as an event instead of being noticed on the next app launch |
 *
 * Every pending intent's `requestCode` is derived from `alarm.id` (M3.4) and never from a counter,
 * which is what stops one alarm's cancellation from silently evicting another's registration.
 */
class AlarmScheduler(
    private val context: Context,
    private val repository: AlarmRepository,
    private val calendar: HolidayCalendar,
    private val timeSource: TimeSource = TimeSource.system,
) {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Recomputes and re-registers everything.
     *
     * Called on boot, on a time or timezone change, after an app update, and after any write that
     * could move a ring instant. A full sweep rather than a diff, on purpose: the table is small
     * (PRD NFR「容量」 caps it at 200 alarms), and a full sweep cannot leave a stale registration
     * behind the way an incremental update can.
     */
    suspend fun recomputeAll() {
        val now = timeSource.nowMillis()
        val zone = ZoneId.systemDefault()
        val stored = repository.alarmsWithGroups()

        for (row in stored) cancel(row.alarm.id)

        val calculator = NextRingCalculator(calendar)
        var registered = 0
        for (row in stored) {
            val next = calculator.nextRing(row.alarm, row.group, now, zone) ?: continue
            schedule(row, next, now)
            registered++
        }
        // PRD FR-4.5.2: an alarm missed while the device was off must not be replayed. Nothing is
        // needed for that here — a next-ring computed from `now` can only return a future instant,
        // so a missed occurrence is skipped by construction.
        //
        // Per-alarm detail is logged at debug level only; the pause values are the first thing worth
        // seeing when a registration looks wrong, and dumping one line per alarm per sweep is noise
        // once the alarm count is realistic (PRD NFR 容量 allows 200).
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            for (row in stored) {
                val floor = NextRingCalculator.floorOf(row.alarm.pauseUntil, row.group?.pauseUntil, now)
                Log.d(
                    TAG,
                    "  alarm=${row.alarm.id} group=${row.alarm.groupId} " +
                        "alarmPauseUntil=${row.alarm.pauseUntil?.let(::iso)} " +
                        "groupPauseUntil=${row.group?.pauseUntil?.let(::iso)} " +
                        "groupDisabled=${row.group?.permanentDisabled} floor=${iso(floor)}",
                )
            }
        }
        Log.i(TAG, "recomputeAll: ${stored.size} stored, $registered registered, zone=$zone")
    }

    /**
     * Re-registers a single alarm; used after a write to one row so the whole table is not swept.
     *
     * Cancels first, so shrinking the schedule (a pause, a switch-off, a moved `onceDate`) cannot
     * leave the previous registration alive.
     */
    suspend fun recompute(alarmId: Long) {
        cancel(alarmId)
        val row = repository.alarmsWithGroups().firstOrNull { it.alarm.id == alarmId }
            // Deleted: nothing left to register (PRD §5.4's 被删 branch).
            ?: return
        val now = timeSource.nowMillis()
        val next = NextRingCalculator(calendar).nextRing(row.alarm, row.group, now, ZoneId.systemDefault())
            ?: return
        schedule(row, next, now)
    }

    /**
     * PRD FR-4.3.4: arms a snooze's re-ring at [atMillis].
     *
     * Deliberately **not** `setAlarmClock`. The main registration keeps showing the alarm's next real
     * occurrence in the status bar; moving that to "10 minutes from now" would make the icon lie about
     * when the alarm is set for. `setExactAndAllowWhileIdle` still fires through Doze, which is what
     * a snooze needs.
     *
     * The alarm's other registrations are left alone on purpose: the next regular occurrence must
     * still fire even if this snooze is never consumed (say the user dismisses from the notification
     * before it re-rings).
     */
    suspend fun scheduleSnooze(alarmId: Long, atMillis: Long) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            atMillis,
            pendingIntent(alarmId, TriggerKind.SNOOZE),
        )
        repository.setLastTriggerAt(alarmId, atMillis)
        Log.i(TAG, "snooze armed for alarm=$alarmId at=${iso(atMillis)}")
    }

    /**
     * Cancels every registration this app could have made for [alarmId] and clears its remembered
     * trigger instant.
     *
     * The kinds are enumerated rather than remembered: a `PendingIntent` is identified by
     * (requestCode, Intent), so cancelling all derived codes is the only way to be sure a stale kind
     * — say a PAUSE registered before a manual resume, or a SNOOZE left over from a ring that was
     * dismissed — is not left behind.
     */
    suspend fun cancel(alarmId: Long) {
        for (kind in TriggerKind.entries) {
            val pending = PendingIntent.getBroadcast(
                context,
                requestCodeFor(alarmId, kind),
                intentFor(alarmId, kind),
                FLAGS_NO_CREATE,
            )
            if (pending != null) {
                alarmManager.cancel(pending)
                pending.cancel()
            }
        }
        repository.setLastTriggerAt(alarmId, null)
    }

    private suspend fun schedule(row: AlarmWithGroup, nextRingAt: Long, now: Long) {
        val alarmId = row.alarm.id
        val preAt = nextRingAt - LEAD_MILLIS

        // Only worth registering if still in the future: a next ring computed to be within the next
        // five seconds would otherwise schedule an instant in the past, which AlarmManager fires
        // immediately — i.e. the alarm would ring early.
        if (preAt > now) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                preAt,
                pendingIntent(alarmId, TriggerKind.PRE),
            )
        }

        alarmManager.setAlarmClock(alarmClockInfo(nextRingAt, alarmId), pendingIntent(alarmId, TriggerKind.MAIN))

        // M3.7: a trigger at the end of the later pause, so expiry is an event rather than something
        // noticed on the next launch.
        val pauseUntil = listOfNotNull(row.alarm.pauseUntil, row.group?.pauseUntil).maxOrNull()
        if (pauseUntil != null && pauseUntil > now) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                pauseUntil,
                pendingIntent(alarmId, TriggerKind.PAUSE),
            )
        }

        // Remembered for PRD §5.4's 本次触发时间 comparison when the trigger actually fires.
        repository.setLastTriggerAt(alarmId, nextRingAt)

        Log.i(
            TAG,
            "scheduled alarm=$alarmId at=${iso(nextRingAt)} pre=${iso(preAt)} pauseUntil=${pauseUntil?.let(::iso)}",
        )
    }

    /**
     * `setAlarmClock` drives the status-bar alarm icon as well as the trigger, and the icon shows
     * the trigger time. That is the whole reason PRD §5.7 insists the redundant 5-second-early front
     * uses `setExactAndAllowWhileIdle`: registering it this way would advertise a time five seconds
     * early. The show intent is where the user lands when they tap that icon.
     */
    private fun alarmClockInfo(triggerAt: Long, alarmId: Long): AlarmManager.AlarmClockInfo {
        val showIntent = PendingIntent.getActivity(
            context,
            requestCodeFor(alarmId, TriggerKind.MAIN),
            Intent(context, com.alarmhub.app.MainActivity::class.java),
            FLAGS_UPDATE_CURRENT,
        )
        return AlarmManager.AlarmClockInfo(triggerAt, showIntent)
    }

    private fun pendingIntent(alarmId: Long, kind: TriggerKind): PendingIntent =
        PendingIntent.getBroadcast(context, requestCodeFor(alarmId, kind), intentFor(alarmId, kind), FLAGS_UPDATE_CURRENT)

    private fun intentFor(alarmId: Long, kind: TriggerKind): Intent =
        Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER
            // The alarm id is part of the Intent so that two alarms' triggers are distinct for
            // PendingIntent matching, not only for the receiver; without it they would collide on
            // the action alone and the second registration would replace the first.
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_KIND, kind.name)
        }

    private fun iso(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toString()

    companion object {
        private const val TAG = "AlarmHub/Scheduler"

        const val ACTION_TRIGGER = "com.alarmhub.app.action.ALARM_TRIGGER"
        const val EXTRA_ALARM_ID = "alarmId"
        const val EXTRA_KIND = "kind"

        /** PRD §5.7's 准点前 5 秒. */
        const val LEAD_MILLIS = 5_000L

        /**
         * `FLAG_IMMUTABLE` is mandatory from API 31 for any PendingIntent that is not filled in
         * later; these extras are set at creation time and never changed.
         */
        private const val FLAGS_UPDATE_CURRENT = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private const val FLAGS_NO_CREATE = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE

        /**
         * Request codes live in a band derived from the alarm id: one consecutive code per kind, in
         * the low 24 bits `Intent` allows. 21 bits of id is 2 097 151 alarms, far past PRD's 200.
         *
         * `TriggerKind` has grown twice (PAUSE at M3, SNOOZE at M4) without any code having to be
         * renumbered, because the kind only ever contributes its ordinal.
         */
        private const val ID_SHIFT = 3
        private const val ID_MASK = 0x1FFFFFL

        fun requestCodeFor(alarmId: Long, kind: TriggerKind): Int =
            ((alarmId and ID_MASK).toInt() shl ID_SHIFT) + kind.ordinal
    }
}
