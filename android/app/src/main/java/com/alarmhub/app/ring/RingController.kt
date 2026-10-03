package com.alarmhub.app.ring

import android.content.Context
import android.util.Log
import com.alarmhub.app.AlarmHubApp
import com.alarmhub.app.data.AlarmRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** What the ring page renders, plus the bits of live state only the service knows. */
data class RingUiState(
    val request: RingRequest,
    /** True while `autoStopMinutes` is counting down. */
    val autoStopArmed: Boolean = false,
    /** Whether the audio actually started, so the page can say so when it did not. */
    val audioPlaying: Boolean = false,
    val usedFallbackSound: Boolean = false,
)

/**
 * The one active ring, and the only place a ring is ever ended.
 *
 * Centralising the ending is the point. M4 has five ways a ring can finish — 关闭, 贪睡, 超时,
 * the alarm being deleted from elsewhere, and the service being stopped — and each of them has to do
 * the right thing to the *stored* alarm. Scattering that logic is how an app ends up deleting a
 * repeating alarm, or marking a one-off expired that the user asked to be deleted.
 *
 * Lifecycle: the [RingForegroundService] owns the audio and the notification; this object owns the
 * decision-making and the state the UI observes, and survives the service being recreated mid-ring.
 */
object RingController {

    private const val TAG = "AlarmHub/Ring"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<RingUiState?>(null)

    /** Non-null exactly while a ring is on screen. */
    val state: StateFlow<RingUiState?> = _state.asStateFlow()

    /** Guards the ending path: a dismiss arriving as the timeout fires must not run twice. */
    private val ending = AtomicBoolean(false)

    private var repository: AlarmRepository? = null
    private var appContext: Context? = null

    /** True while a ring is active; consulted by PRD §5.4's `ringNow` check. */
    val isRinging: Boolean get() = _state.value != null

    fun attach(context: Context) {
        appContext = context.applicationContext
        repository = AlarmHubApp.of(context).repository
    }

    /**
     * Publishes a new ring.
     *
     * Returns false when a ring for this alarm is already active, so a second trigger for the same
     * alarm cannot start it twice (PRD §5.7's redundant front arriving after the main trigger).
     */
    fun begin(request: RingRequest): Boolean {
        val current = _state.value
        if (current != null) {
            if (current.request.alarmId == request.alarmId) {
                Log.i(TAG, "alarm=${request.alarmId} already ringing; ignoring the repeat trigger")
                return false
            }
            /*
             * A different alarm is ringing. In this app only one can ring at a time: two overlapping
             * rings would fight over the audio stream and leave the user with one page and no way to
             * silence the other. The newer alarm wins, and the older one is ended as CANCELLED so
             * nothing is done to its stored row.
             */
            Log.w(TAG, "alarm=${current.request.alarmId} was ringing; replacing it with alarm=${request.alarmId}")
            end(RingEndReason.CANCELLED)
        }
        ending.set(false)
        _state.value = RingUiState(request)
        return true
    }

    /** The service reports what it managed to start, so the page can be honest about it. */
    fun updateAudio(playing: Boolean, usedFallback: Boolean) {
        _state.value = _state.value?.copy(audioPlaying = playing, usedFallbackSound = usedFallback)
    }

    fun setAutoStopArmed(armed: Boolean) {
        _state.value = _state.value?.copy(autoStopArmed = armed)
    }

    fun current(): RingRequest? = _state.value?.request

    /**
     * PRD FR-4.3.3 / FR-4.3.4: 贪睡.
     *
     * Increments the persisted counter, arms a snooze trigger, and ends the current ring. When the
     * allowance is used up this does nothing and returns false — the caller (the ring page) is
     * expected to have hidden or disabled the button, and this is the second line of defence.
     */
    fun snooze(): Boolean {
        val request = current() ?: return false
        if (!request.canSnooze) {
            Log.i(TAG, "alarm=${request.alarmId} has no snoozes left; ignoring 贪睡")
            return false
        }
        val app = appContext?.let { AlarmHubApp.of(it) } ?: return false
        val repo = repository ?: return false

        scope.launch {
            runCatching {
                val used = repo.recordSnooze(request.alarmId)
                val until = app.timeSource.nowMillis() + request.snoozeMinutes * 60_000L
                app.scheduler.scheduleSnooze(request.alarmId, until)
                Log.i(
                    TAG,
                    "alarm=${request.alarmId} snoozed ${request.snoozeMinutes} min (${used}/${request.snoozeMaxCount}) until $until",
                )
                end(RingEndReason.SNOOZED)
            }.onFailure { Log.e(TAG, "snooze failed", it) }
        }
        return true
    }

    /** PRD FR-4.3.3: 关闭. */
    fun dismiss() = end(RingEndReason.DISMISSED)

    /** The alarm vanished or was switched off while ringing. */
    fun cancelFromOutside() = end(RingEndReason.CANCELLED)

    /**
     * Stops the ring when the alarm it belongs to is no longer wanted.
     *
     * **This is the wiring that was missing, and a real user paid for it.** M4 defined
     * [cancelFromOutside] for precisely this case — `docs/M4-STATUS.md` §4.1 lists 「响铃期间闹钟被别处
     * 删掉 / 关掉」 as one of the five ways a ring ends — but nothing ever called it. So deleting the
     * ringing alarm from the list left the sound playing with **no page and no row**: the report was
     * 「一直在响，也找不到关闭按钮」, and the only escape was the 关闭 button inside the notification.
     *
     * Call it after any write that could have removed or switched off an alarm. It is deliberately
     * one function rather than a call at each mutation site, because "remember to also stop the ring
     * here" is exactly the kind of instruction that gets forgotten — that is how this bug happened.
     *
     * Deliberately **not** triggered by a temporary pause or by 贪睡: those govern *future* rings, and
     * cutting off a ring the user is currently hearing is worse than letting it finish. Being deleted,
     * switched off, or having its group switched off indefinitely does end it — in those cases the user
     * has said they do not want this alarm at all.
     *
     * @return true when a ring was actually stopped.
     */
    suspend fun cancelIfNoLongerWanted(): Boolean {
        val request = current() ?: return false
        val repo = repository ?: return false
        val alarm = repo.alarm(request.alarmId)
        val wanted = alarm != null &&
            alarm.enabled &&
            !alarm.permanentDisabled &&
            repo.group(alarm.groupId)?.permanentDisabled != true
        if (wanted) return false
        Log.i(TAG, "alarm=${request.alarmId} is gone or switched off; ending the ring")
        cancelFromOutside()
        return true
    }

    /** PRD FR-4.3.5: the auto-stop timer elapsed. */
    fun timeOut() = end(RingEndReason.TIMED_OUT)

    /**
     * Ends the ring and applies the ending's consequences to the stored alarm.
     *
     * Safe to call from any thread and safe to call twice: only the first call does anything.
     */
    private fun end(reason: RingEndReason) {
        val request = _state.value?.request
        if (request == null) {
            // No ring, but the audio or notification may still be up (a race with a service restart).
            // Stopping is harmless and leaving sound playing is not.
            appContext?.let { RingForegroundService.stop(it) }
            return
        }
        if (!ending.compareAndSet(false, true)) {
            Log.i(TAG, "alarm=${request.alarmId} already ending; ignoring $reason")
            return
        }

        Log.i(TAG, "alarm=${request.alarmId} ring ended: $reason")
        _state.value = null
        appContext?.let { RingForegroundService.stop(it) }

        val app = appContext?.let { AlarmHubApp.of(it) }
        if (app == null || reason == RingEndReason.CANCELLED) {
            // Nothing to do to the row: it is already gone, already off, or the user asked for
            // nothing to happen to it.
            ending.set(false)
            return
        }

        scope.launch {
            runCatching { applyPostRing(app, request, reason) }
                .onFailure { Log.e(TAG, "post-ring handling for alarm=${request.alarmId} failed", it) }
            ending.set(false)
        }
    }

    /**
     * PRD §5.6's lifecycle tail.
     *
     * | ending | one-off with 响铃后删除 | other one-off | repeating |
     * |---|---|---|---|
     * | 关闭 / 超时 | delete the row | mark `expired` | reschedule the next ring |
     * | 贪睡 | keep it (a snooze is not the end of the alarm) | same | same |
     */
    private suspend fun applyPostRing(app: AlarmHubApp, request: RingRequest, reason: RingEndReason) {
        val repository = app.repository
        val scheduler = app.scheduler

        if (reason == RingEndReason.SNOOZED) {
            // The row stays exactly as it is: the alarm has not finished, it is waiting to ring again.
            // Re-arming was already done by `snooze()`, and the main registration is deliberately left
            // alone -- `AlarmScheduler.scheduleSnooze` documents why.
            return
        }

        val stillThere = repository.alarm(request.alarmId) != null
        if (!stillThere) {
            Log.i(TAG, "alarm=${request.alarmId} is gone; nothing to finish")
            return
        }

        if (request.deleteAfterRing) {
            // PRD FR-3.6. The DAO's own SQL re-checks "ONCE and marked for deletion", so a repeating
            // alarm can never be removed by this path even if the flag were somehow set.
            val deleted = repository.deleteIfMarkedForDeletion(request.alarmId)
            if (deleted) {
                scheduler.cancel(request.alarmId)
                Log.i(TAG, "alarm=${request.alarmId} deleted after ringing (PRD FR-3.6)")
                return
            }
        }

        // PRD FR-3.7: a one-off that already rang and was kept becomes 过期, and stops being scheduled.
        val updated = repository.alarm(request.alarmId)
        if (updated != null && updated.repeatType == com.alarmhub.app.domain.model.RepeatType.ONCE) {
            repository.clearSnoozeCount(request.alarmId)
            repository.markExpired(request.alarmId)
            scheduler.cancel(request.alarmId)
            Log.i(TAG, "alarm=${request.alarmId} marked expired (PRD FR-3.7)")
            return
        }

        // A repeating alarm: it has not finished, so arm tomorrow's occurrence and reset the snooze
        // allowance so the next morning gets the full count (PRD FR-4.3.4).
        repository.clearSnoozeCount(request.alarmId)
        scheduler.recompute(request.alarmId)
    }

    /** Called when the foreground service is destroyed for a reason the controller did not cause. */
    internal fun onServiceGone() {
        if (_state.value != null && !ending.get()) {
            Log.w(TAG, "the ring service went away while a ring was active")
            end(RingEndReason.CANCELLED)
        }
    }
}
