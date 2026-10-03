package com.alarmhub.app.ring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The two ring actions that must work without the ring page being on screen: the notification's
 * 关闭 button, and 贪睡 from the notification.
 *
 * They go through [RingController] rather than talking to the service, because the decision about
 * what an ending does to the stored alarm belongs in exactly one place.
 */
class RingActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        RingController.attach(context)
        when (intent.action) {
            ACTION_DISMISS -> {
                Log.i(TAG, "dismiss from a notification action")
                RingController.dismiss()
            }

            ACTION_SNOOZE -> {
                Log.i(TAG, "snooze from a notification action")
                RingController.snooze()
            }

            else -> Log.w(TAG, "ignored unknown action ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "AlarmHub/RingAction"

        const val ACTION_DISMISS = "com.alarmhub.app.action.RING_DISMISS"
        const val ACTION_SNOOZE = "com.alarmhub.app.action.RING_SNOOZE"

        fun dismissIntent(context: Context): Intent =
            Intent(context, RingActionReceiver::class.java).setAction(ACTION_DISMISS)

        fun snoozeIntent(context: Context): Intent =
            Intent(context, RingActionReceiver::class.java).setAction(ACTION_SNOOZE)
    }
}
