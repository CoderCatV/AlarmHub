package com.alarmhub.app.ring.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.io.IOException
import kotlin.math.roundToInt

/**
 * The audio attributes every alarm sound must use.
 *
 * `USAGE_ALARM` is the whole reason the alarm is audible in silent mode and under Do Not Disturb
 * (PRD FR-4.3.11): `USAGE_ALARM` streams are not muted by the ringer mode and are exempt from DND
 * unless the user explicitly blocks alarms. Using `USAGE_MEDIA` here — the default for most audio
 * code — would make the app silent exactly when it matters most, which is the single most common way
 * a third-party alarm fails.
 */
private val ALARM_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ALARM)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()

/** Everything the ring needs to know about sound and vibration. */
data class AlarmSound(
    val ringtoneUri: String?,
    val vibrate: Boolean,
    /** Seconds to ramp from silence to full volume; 0 = no fade (PRD FR-4.3.8). */
    val fadeInSeconds: Int,
)

/**
 * Plays the alarm sound, ramping up if asked, and drives vibration alongside it.
 *
 * Implemented with [MediaPlayer] rather than the platform's `Ringtone` because the fade has to be
 * driven by hand (PRD FR-4.3.8 / TECH-STACK §4.4 say not to use the system fade API: it is not
 * available on every supported version and gives no control over the curve).
 */
class AlarmAudioPlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var fadeThread: Thread? = null

    @Volatile
    private var released = false

    /** Which source actually got used, for the log and for tests to assert on. */
    var playingFrom: String = "nothing"
        private set

    /**
     * Starts looping the alarm sound and, if requested, vibration.
     *
     * @param attempts the candidate URIs in priority order. The first one that both resolves and
     *   plays wins; the system default alarm sound is appended as the last resort, so there is
     *   always something audible even if the user's chosen URI has since been deleted.
     */
    fun start(sound: AlarmSound, attempts: List<String?>) {
        val candidates = (attempts + RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.toString())
            .filterNotNull()
            .distinct()

        for (candidate in candidates) {
            if (tryStart(candidate, sound.fadeInSeconds)) {
                playingFrom = candidate
                Log.i(TAG, "ringing from $candidate fadeIn=${sound.fadeInSeconds}s")
                if (sound.vibrate) startVibration()
                return
            }
        }
        Log.e(TAG, "no candidate ringtone could be played: $candidates")
    }

    private fun tryStart(uriString: String, fadeInSeconds: Int): Boolean {
        val uri = Uri.parse(uriString)
        val created = MediaPlayer()
        return try {
            created.setAudioAttributes(ALARM_ATTRIBUTES)
            created.setDataSource(context, uri)
            created.isLooping = true
            // Start silent and ramp, or start at full volume when there is no fade.
            created.setVolume(if (fadeInSeconds > 0) 0f else 1f, if (fadeInSeconds > 0) 0f else 1f)
            created.prepare()
            created.start()
            player = created
            if (fadeInSeconds > 0) startFade(fadeInSeconds)
            true
        } catch (e: IOException) {
            Log.w(TAG, "could not play $uriString: ${e.message}")
            runCatching { created.release() }
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "$uriString is not a playable ringtone: ${e.message}")
            runCatching { created.release() }
            false
        } catch (e: SecurityException) {
            // A persisted URI whose grant was lost. Fall through to the next candidate.
            Log.w(TAG, "no permission for $uriString: ${e.message}")
            runCatching { created.release() }
            false
        }
    }

    /**
     * Ramps to full volume over [seconds].
     *
     * A plain thread rather than a coroutine: it has to keep running while the ring is on screen and
     * must stop the instant the ring does, and it touches nothing else, so the extra machinery of a
     * scope would buy nothing here.
     */
    private fun startFade(seconds: Int) {
        val target = seconds * 1000L
        val active = player ?: return
        fadeThread = Thread {
            val stepMillis = 100L
            var elapsed = 0L
            while (!released && elapsed < target) {
                try {
                    Thread.sleep(stepMillis)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                elapsed += stepMillis
                val fraction = (elapsed.toDouble() / target).coerceIn(0.0, 1.0)
                // A squared curve sounds like a smoother ramp than a linear one, which is audible
                // as "still quiet, then suddenly loud" in the last second.
                val volume = (fraction * fraction).toFloat().coerceIn(0f, 1f)
                runCatching { active.setVolume(volume, volume) }
            }
            if (!released) runCatching { active.setVolume(1f, 1f) }
        }.apply {
            isDaemon = true
            name = "alarm-fade"
            start()
        }
    }

    /** PRD FR-4.3.7: vibration is independent of the ringtone. */
    private fun startVibration() {
        val v = resolveVibrator() ?: return
        vibrator = v
        // 500 ms on, 500 ms off, repeating: the on/off shape a phone alarm is expected to have.
        val pattern = longArrayOf(0, 500, 500)
        val effect = VibrationEffect.createWaveform(pattern, 0)
        runCatching { v.vibrate(effect) }
            .onFailure { Log.w(TAG, "vibration refused: ${it.message}") }
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /**
     * Stops everything. Idempotent, because every ring ending path calls it and some of them can
     * race (a dismiss arriving as the timeout fires).
     */
    fun stop() {
        released = true
        fadeThread?.interrupt()
        fadeThread = null
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }.onFailure { Log.w(TAG, "releasing the player failed: ${it.message}") }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        playingFrom = "nothing"
    }

    /** The stream volume the alarm will use, so the ring page can warn when it is at zero. */
    fun alarmStreamVolume(): Int {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return -1
        return audio.getStreamVolume(AudioManager.STREAM_ALARM)
    }

    companion object {
        private const val TAG = "AlarmHub/Audio"

        /** Exposed so the ring page can show the same value the player uses. */
        fun attributes(): AudioAttributes = ALARM_ATTRIBUTES

        /** A rough percentage, purely for display. */
        fun percent(volume: Int, max: Int): Int =
            if (max <= 0) 0 else ((volume.toDouble() / max) * 100).roundToInt()
    }
}
