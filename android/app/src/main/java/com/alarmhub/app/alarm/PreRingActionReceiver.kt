package com.alarmhub.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.alarmhub.app.AlarmHubApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The 「关闭闹钟」 action on PRD FR-7.10's "coming up" notification.
 *
 * It switches the alarm off rather than dismissing a notification: the notification is already
 * dismissible by swiping, so a button that did the same thing would be decoration. Turning the alarm off
 * is the action the user actually wants when they see "09:30 即将响铃" and decide they do not need it.
 *
 * The write goes through the repository and then a full recompute, so the alarm's registrations — the
 * one-hour alert, the 5-second front and the main `setAlarmClock` — all go away together. Doing that
 * here and not in the notification builder keeps "the schedule follows the database" in one place.
 */
class PreRingActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TURN_OFF) {
            Log.w(TAG, "ignored unknown action ${intent.action}")
            return
        }
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (alarmId < 0L) {
            Log.w(TAG, "turn-off without a usable alarm id; ignoring")
            return
        }

        val pending = goAsync()
        val app = AlarmHubApp.of(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val stored = app.repository.alarm(alarmId)
                if (stored == null) {
                    Log.i(TAG, "alarm=$alarmId is already gone")
                } else {
                    app.repository.setAlarmEnabled(alarmId, false)
                    // The alert has served its purpose; leaving it up would contradict the switch the
                    // user just turned off.
                    PreRingAlerts.cancel(context)
                    app.scheduler.recomputeAll()
                    Log.i(TAG, "alarm=$alarmId turned off from the pre-alert notification")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "could not turn alarm=$alarmId off", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AlarmHub/PreAlertAction"

        const val ACTION_TURN_OFF = "com.alarmhub.app.action.PRE_ALERT_TURN_OFF"
        const val EXTRA_ALARM_ID = "alarmId"

        fun turnOffIntent(context: Context, alarmId: Long): Intent =
            Intent(context, PreRingActionReceiver::class.java).apply {
                action = ACTION_TURN_OFF
                putExtra(EXTRA_ALARM_ID, alarmId)
            }
    }
}
