package com.alarmhub.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.alarmhub.app.AlarmHubApp

/**
 * M3.6 / PRD FR-4.5 — re-register after the events that invalidate a registration.
 *
 * | event | why the registrations are stale |
 * |---|---|
 * | `BOOT_COMPLETED` | every `AlarmManager` registration is dropped when the device powers off |
 * | `TIME_SET` | every stored instant keeps its value but its distance from "now" changed, so a `WEEKLY` alarm's next ring moves |
 * | `TIMEZONE_CHANGED` | the same wall-clock rule now lands on a different instant |
 * | `MY_PACKAGE_REPLACED` | registrations do not survive an app update |
 *
 * Each one is answered the same way — a full sweep — because the sweep is cheap (≤200 rows, PRD NFR
 * 「容量」) and cannot leave a stale registration behind. `AlarmHubApp` also sweeps on cold start, so
 * a missed broadcast is self-correcting the next time the app runs.
 */
class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) {
            Log.d(TAG, "ignoring $action")
            return
        }

        /*
         * The sweep runs on the application's scope rather than a scope created here: a broadcast
         * receiver's process may be torn down as soon as onReceive returns, and `goAsync()` only
         * keeps it alive while the token is held. Handing the work to the application scope means a
         * sweep that outlives the broadcast still completes.
         */
        Log.i(TAG, "$action: recomputing and re-registering every alarm")
        AlarmHubApp.recomputeAll(context)
    }

    companion object {
        private const val TAG = "AlarmHub/SystemEvent"

        private val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
