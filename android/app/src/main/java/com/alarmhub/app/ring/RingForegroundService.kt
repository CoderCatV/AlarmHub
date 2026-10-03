package com.alarmhub.app.ring

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.alarmhub.app.ring.media.AlarmAudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * M4.1 — the ring's sound, held by a `mediaPlayback` foreground service (TECH-STACK §4.4).
 *
 * **Why the sound is not in [RingActivity]:** bringing the activity up depends on
 * `USE_FULL_SCREEN_INTENT`, which Android 14+ lets the user revoke. If the sound travelled with the
 * activity, revoking that permission would turn the alarm into "a notification with no sound" —
 * which for an alarm is the worst possible failure. Keeping the sound in a service means the worst
 * case is "audible but needs a tap to see the page".
 *
 * The service is started with `startForegroundService`, so the platform requires a notification
 * within a few seconds; that notification is also the ring's notification (PRD FR-4.3.10).
 */
class RingForegroundService : Service() {

    /** Lets [RingActivity] ask about auto-stop state without duplicating the timer. */
    inner class LocalBinder : Binder() {
        fun service(): RingForegroundService = this@RingForegroundService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var audio: AlarmAudioPlayer? = null
    private var autoStopJob: Job? = null

    /** When the ring started, so the page can show how long it has been going. */
    var startedAtMillis: Long = 0L
        private set

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Kept for completeness: a stop that arrives as an intent still means "the ring is over".
            // `stop()` itself no longer uses this route (see the companion object).
            Log.i(TAG, "stop requested through the service intent")
            RingController.dismiss()
            return START_NOT_STICKY
        }

        val request = RingController.current()
        if (request == null) {
            // The controller has already ended the ring (or the process was recreated with no state).
            // Nothing to play, so do not linger as a foreground service.
            Log.i(TAG, "started with no active ring; stopping")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        startedAtMillis = System.currentTimeMillis()

        /*
         * The notification has to exist before the foreground call returns, and the ring page's own
         * notification doubles as the foreground one. Posted unconditionally: `startForeground` is
         * required whether or not the user granted POST_NOTIFICATIONS, and on 13+ a missing grant
         * means the notification is not shown rather than that the call fails.
         */
        val notification = RingNotifications.build(this, request)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, RingNotifications.NOTIFICATION_ID, notification, type)
        }.onFailure {
            // Most likely a SecurityException from a revoked permission. The alarm must still sound,
            // so this is logged and the ring continues without foreground status.
            Log.e(TAG, "could not enter the foreground; continuing to ring anyway", it)
        }

        startAudio(request)

        if (request.autoStopMinutes > 0) armAutoStop(request) else RingController.setAutoStopArmed(false)

        /*
         * Bring the page up through a PendingIntent, not a direct startActivity: on API 36 a
         * background activity launch from a foreground service is blocked, and `RingActivity.show`
         * documents the platform message that says so. The notification's `fullScreenIntent` covers
         * the locked case; this covers the device already being in use.
         */
        RingActivity.show(this)

        // START_NOT_STICKY: if the process is killed, restarting the service would ring again with no
        // way to know how long it had already been going. The alarm's next occurrence is re-armed by
        // the controller's ending path, and the reboot sweep handles a genuinely lost state.
        return START_NOT_STICKY
    }

    private fun startAudio(request: RingRequest) {
        val player = audio ?: AlarmAudioPlayer(this).also { audio = it }
        player.start(
            sound = request.sound,
            /*
             * Preference order, per PRD FR-4.3.9 and §4.3: the alarm's own ringtone, then the global
             * default, then the system alarm sound. `AlarmAudioPlayer` appends the system sound as a
             * final fallback, so an unplayable URI degrades instead of going silent.
             */
            attempts = listOf(request.sound.ringtoneUri),
        )
        val defaultAlarm = android.media.RingtoneManager
            .getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
            ?.toString()
        RingController.updateAudio(
            playing = player.playingFrom != "nothing",
            usedFallback = player.playingFrom == defaultAlarm,
        )
        Log.i(TAG, "audio started from ${player.playingFrom}; alarm stream volume=${player.alarmStreamVolume()}")
    }

    /** PRD FR-4.3.5: stop by itself after `autoStopMinutes`. */
    private fun armAutoStop(request: RingRequest) {
        RingController.setAutoStopArmed(true)
        Log.i(TAG, "auto-stop armed for ${request.autoStopMinutes} min")
        autoStopJob = scope.launch {
            delay(request.autoStopMinutes * 60_000L)
            Log.i(TAG, "auto-stop elapsed after ${request.autoStopMinutes} min")
            RingController.timeOut()
        }
    }

    override fun onDestroy() {
        autoStopJob?.cancel()
        audio?.stop()
        audio = null
        RingNotifications.cancel(this)
        Log.i(TAG, "ring service destroyed")
        // If the service dies without the controller having ended the ring, the ring must not be left
        // half-alive with the UI still showing it.
        RingController.onServiceGone()
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    companion object {
        private const val TAG = "AlarmHub/RingService"

        /** Start the ring's sound and notification. */
        const val ACTION_START = "com.alarmhub.app.action.RING_START"

        /** Ask the service to stop; the controller decides what that means for the alarm. */
        const val ACTION_STOP = "com.alarmhub.app.action.RING_STOP"

        /** Dismisses the current ring from a notification action. */
        const val ACTION_DISMISS = "com.alarmhub.app.action.RING_DISMISS"

        /** Snoozes the current ring from a notification action. */
        const val ACTION_SNOOZE = "com.alarmhub.app.action.RING_SNOOZE"

        fun start(context: Context) {
            val intent = Intent(context, RingForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Stops the service if it is running, and does nothing if it is not.
         *
         * **Not** `startService` with a stop action: starting a service that is not running would
         * *create* it, and `onStartCommand` would then run for a ring the controller has already
         * ended — which re-entered the ending path and started the service again, once per dismissal.
         * The service stops only itself, through [stopSelf]; anything else that wants to end a ring
         * goes to [RingController].
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, RingForegroundService::class.java))
        }
    }
}
