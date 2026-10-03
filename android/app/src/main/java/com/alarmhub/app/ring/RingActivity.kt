package com.alarmhub.app.ring

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.alarmhub.app.AlarmHubApp
import com.alarmhub.app.R
import com.alarmhub.app.domain.model.VolumeKeyAction
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * M4.4 — the full-screen ring page (PRD FR-4.3.1 / FR-4.3.2 / FR-4.3.3 / FR-4.3.6).
 *
 * The page is a *view* over [RingController]; it does not decide anything. Every button and every
 * key press routes through the controller, which is the only thing that touches the stored alarm.
 *
 * Shown over the lock screen through `showWhenLocked` + `turnScreenOn`, plus an explicit keyguard
 * dismissal so the buttons are actually tappable rather than dimmed behind the lock UI.
 */
class RingActivity : AppCompatActivity() {

    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var label: TextView
    private lateinit var groupName: TextView
    private lateinit var groupDot: View
    private lateinit var status: TextView
    private lateinit var warning: TextView
    private lateinit var snoozeButton: Button
    private lateinit var dismissButton: Button
    private lateinit var volumeHint: TextView

    /** Volume-key behaviour (PRD FR-4.3.6); read once when the page opens. */
    private var volumeKeyAction: VolumeKeyAction = VolumeKeyAction.SNOOZE

    /** The global 贪睡 switch; false removes the button and the volume-key action entirely. */
    private var snoozeEnabled: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must happen before setContentView, and must not be skipped: without these the page comes up
        // behind the lock screen and the user cannot silence the alarm (PRD FR-4.3.2).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_ring)
        bindViews()
        hideSystemBars()
        dismissKeyguardIfNeeded()

        RingController.attach(this)

        val app = AlarmHubApp.of(this)
        lifecycleScope.launch {
            val settings = app.repository.settings()
            /*
             * The global 贪睡 switch (M8, `settings.snoozeEnabled`).
             *
             * A user who does not use snooze should not have to turn it off alarm by alarm, so this is
             * where the switch takes effect — and it has to take effect in **both** places snooze can be
             * reached, or the page would promise something that does not work:
             *
             * * the 贪睡 button is removed rather than disabled, because a greyed-out button next to a
             *   live 关闭 still invites a tap and explains nothing;
             * * the volume key falls back to 关闭 even if the stored preference says 贪睡, since that
             *   preference now refers to a feature that is off. The hint line is set from the same
             *   value, so what it says and what the key does cannot drift apart.
             */
            snoozeEnabled = settings.snoozeEnabled
            volumeKeyAction = if (snoozeEnabled) settings.volumeKeyAction else VolumeKeyAction.DISMISS
            volumeHint.text = when (volumeKeyAction) {
                VolumeKeyAction.SNOOZE -> "音量键：贪睡"
                VolumeKeyAction.DISMISS -> "音量键：关闭"
            }
            if (!snoozeEnabled) {
                snoozeButton.visibility = View.GONE
            }
        }

        snoozeButton.setOnClickListener { onSnooze() }
        dismissButton.setOnClickListener { RingController.dismiss() }
        makeBackKeyInert()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RingController.state.collect { state ->
                    if (state == null) {
                        // The ring ended (from here, from the notification, or by timing out).
                        finishAndRemoveTask()
                    } else {
                        render(state)
                    }
                }
            }
        }
    }

    private fun bindViews() {
        clock = findViewById(R.id.ring_clock)
        date = findViewById(R.id.ring_date)
        label = findViewById(R.id.ring_label)
        groupName = findViewById(R.id.ring_group_name)
        groupDot = findViewById(R.id.ring_group_dot)
        status = findViewById(R.id.ring_status)
        warning = findViewById(R.id.ring_warning)
        snoozeButton = findViewById(R.id.ring_snooze)
        dismissButton = findViewById(R.id.ring_dismiss)
        volumeHint = findViewById(R.id.ring_volume_hint)
    }

    override fun onResume() {
        super.onResume()
        RingController.attach(this)
        // A ring is time-sensitive: if the state has already gone, do not linger.
        if (RingController.state.value == null) {
            finishAndRemoveTask()
            return
        }
        // Re-checked on every resume: the user may have changed the alarm volume or revoked the
        // notification permission from the shade while the alarm was ringing.
        renderWarning()
    }

    override fun onPause() {
        super.onPause()
    }

    /**
     * PRD FR-4.3.6: the volume key can either snooze or dismiss.
     *
     * Both volume-up and volume-down are intercepted — having one of them adjust the alarm volume
     * while the other silences the alarm would be worse than either behaviour on its own.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            when (volumeKeyAction) {
                VolumeKeyAction.SNOOZE -> onSnooze()
                VolumeKeyAction.DISMISS -> RingController.dismiss()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun onSnooze() {
        // Defence in depth for the global switch: the button is removed and the volume key is
        // redirected when 贪睡 is off, so reaching here means something else found a way in — a stale
        // notification action, or a future caller that forgot the rule. Refusing loudly is better than
        // silently snoozing a feature the user turned off.
        if (!snoozeEnabled) {
            Log.i(TAG, "snooze is disabled globally; ignoring")
            return
        }
        // `snooze()` returns false once the allowance is used up; the page then must not pretend it
        // worked, so the button state is refreshed from the (unchanged) ring state.
        if (!RingController.snooze()) {
            Log.i(TAG, "snooze refused; the allowance is used up")
            renderWarning(override = "贪睡次数已用完，请关闭闹钟")
        }
    }

    private fun render(state: RingUiState) {
        val request = state.request
        clock.text = request.clockText()
        date.text = RingRequest.dateText()
        label.text = request.displayedLabel
        groupName.text = request.groupName
        groupDot.background.setTint(request.groupColor)

        status.text = buildString {
            if (request.isSnooze) append("贪睡中\n")
            /*
             * `request.snoozeEnabled` is already **both** switches merged (see `RingRequest.from`), so
             * this line no longer reads the global setting a second time. It used to: the two switches
             * were combined here by hand, which is exactly how the notification — a different consumer
             * of the same data — ended up with the rule missing and announced 「贪睡 10 分钟，还剩 3 次」
             * while the feature was off. Reported from the phone: 「响铃时也显示了贪睡信息，虽然实际贪睡
             * 功能没有生效」. One merged flag, one place to change, no consumer left to forget.
             *
             * The 「未开启贪睡」 branch therefore now also covers "the user turned 贪睡 off globally":
             * same sentence, and it is true in both cases.
             */
            if (request.snoozeEnabled) {
                append("贪睡 %d 分钟，还剩 %d 次".format(request.snoozeMinutes, request.snoozesRemaining))
            } else {
                append("未开启贪睡")
            }
            if (state.autoStopArmed) {
                append("\n%d 分钟后自动停止".format(request.autoStopMinutes))
            }
        }

        snoozeButton.isEnabled = request.canSnooze
        snoozeButton.alpha = if (request.canSnooze) 1f else 0.4f
        snoozeButton.text = if (request.canSnooze) {
            "贪睡 %d 分钟".format(request.snoozeMinutes)
        } else {
            "无贪睡次数"
        }

        renderWarning()
    }

    /**
     * Surfaces the two ways an alarm can be silent or invisible, both of which the user can fix.
     *
     * Neither is a reason to stop ringing: the page keeps the alarm dismissible, and the warning tells
     * the user what to change (M5's 权限体检 is where the detailed guidance lives).
     */
    private fun renderWarning(override: String? = null) {
        if (override != null) {
            warning.text = override
            warning.visibility = View.VISIBLE
            return
        }
        val messages = mutableListOf<String>()

        val audio = getSystemService<AudioManager>()
        if (audio != null && audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
            messages += "闹钟音量为 0，这次可能听不到声音"
        }
        if (!RingNotifications.canPost(this)) {
            messages += "通知权限未开启，锁屏可能不会自动弹出本页"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val manager = getSystemService<android.app.NotificationManager>()
            if (manager != null && !manager.canUseFullScreenIntent()) {
                messages += "「全屏通知」权限未开启，锁屏不会自动弹出本页"
            }
        }

        if (messages.isEmpty()) {
            warning.visibility = View.GONE
        } else {
            warning.text = messages.joinToString("\n")
            warning.visibility = View.VISIBLE
        }
    }

    private fun hideSystemBars() {
        /*
         * The bars are hidden for the page's own look, but the insets are then applied as padding to
         * the content. That combination is deliberate: without the padding the clock sits under the
         * ringing notification's heads-up card (which is `ongoing`, so it stays for the whole ring),
         * and without hiding the bars a swipe near the edge brings them back over the buttons.
         */
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val root = findViewById<View>(R.id.ring_root)
        val basePaddingTop = root.paddingTop
        val basePaddingBottom = root.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                basePaddingTop + bars.top,
                view.paddingRight,
                basePaddingBottom + bars.bottom,
            )
            insets
        }
    }

    /**
     * Makes the page actually interactive over the lock screen.
     *
     * `showWhenLocked` alone shows the page but leaves it behind the keyguard for input, so tapping
     * 贪睡 would first ask for the PIN — exactly what PRD FR-4.3.2 says must not happen.
     */
    private fun dismissKeyguardIfNeeded() {
        val keyguard = getSystemService<KeyguardManager>() ?: return
        if (!keyguard.isKeyguardLocked) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguard.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
    }

    /**
     * Back is inert while the alarm is ringing; the alarm has to be ended with 贪睡 or 关闭.
     *
     * It used to call `moveTaskToBack(true)`, on the assumption that "the notification is always
     * there to come back through". A real-device report proved that assumption wrong: the task is
     * `excludeFromRecents` (so the switcher cannot reach it), the platform refuses to let the app pull
     * the page forward while unlocked (docs/M4-STATUS.md §4.3), and the user ended up with an alarm
     * that rang for minutes with no page and — after they deleted it — no row either.
     *
     * Inert back is also what keeps [com.alarmhub.app.MainActivity]'s "re-open the ring page on resume"
     * safety net from becoming a loop: with `moveTaskToBack`, back would push the page away,
     * MainActivity would resume, and it would immediately push the page back.
     *
     * Registered on androidx's `onBackPressedDispatcher` rather than by overriding `onBackPressed`.
     * Measured on Android 16: the deprecated override was **not** called at all — back went through the
     * platform's back-invoked path, the page was destroyed, and only MainActivity's safety net brought
     * it back (a visible recreate, and the Toast never appeared). The dispatcher is wired into both
     * paths.
     *
     * Leaving is still possible with Home, and coming back to the app then lands on this page again.
     */
    /** 96dp: about a third of the screen, so it cannot be confused with a tap or a small drag. */
    private val swipeThresholdPx: Float
        get() = 96f * resources.displayMetrics.density

    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeTracking = false
    private val swipeFired = AtomicBoolean(false)

    /**
     * Swipe up anywhere to close the alarm — MIUI's lock-screen gesture, adopted here.
     *
     * **Observed, not intercepted.** This reads the touch stream on the way past and always delegates to
     * `super`, so the 贪睡 / 关闭 buttons keep working. Consuming the events instead would have made the
     * buttons dead — which is the usual way this gesture is implemented, and the reason it usually
     * breaks something.
     *
     * The action fires as soon as the threshold is crossed rather than on release: the alarm should stop
     * the moment the gesture is unambiguous, and waiting for the finger to lift adds a visible delay to
     * the one action the user wants to be instant.
     *
     * The horizontal start is recorded but not checked: a one-handed swipe on a lock screen is never
     * perfectly vertical, and the vertical threshold alone is already far above a tap.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = ev.rawX
                swipeStartY = ev.rawY
                swipeTracking = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeTracking && swipeStartY - ev.rawY > swipeThresholdPx && !swipeFired.get()) {
                    swipeFired.set(true)
                    Log.i(TAG, "swipe up: dismissing the alarm")
                    RingController.dismiss()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                swipeTracking = false
                swipeFired.set(false)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun makeBackKeyInert() {
        onBackPressedDispatcher.addCallback(this) {
            Toast.makeText(this@RingActivity, "请选择「贪睡」或「关闭」", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "AlarmHub/RingActivity"

        /**
         * Brings the page up.
         *
         * `NEW_TASK` is required whenever this is called with an application context (the ring
         * service), and `CLEAR_TASK` guarantees a stale instance from a previous ring is not reused
         * with the old alarm's data still rendered.
         */
        fun intent(context: Context): Intent =
            Intent(context, RingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        /** Stable request code for the show-PendingIntent; only one ring exists at a time. */
        private const val SHOW_REQUEST_CODE = 7

        /**
         * Opportunistically brings the page up from our own process.
         *
         * **Measured outcome on the target emulator (Pixel 7 / API 36): this is refused.** Both a
         * direct `startActivity` and this `PendingIntent` route are blocked as background activity
         * launches while the device is unlocked and the app has no visible window:
         *
         * ```
         * # direct startActivity
         * Background activity launch blocked! ... callingUidProcState: FOREGROUND_SERVICE
         * balAllowedByPiCreator: BSP.ALLOW_BAL ; autoOptInReason: notPendingIntent
         *
         * # this PendingIntent
         * Background activity launch blocked! ... isPendingIntent: true
         * balAllowedByPiSender: BSP.ALLOW_BAL ; resultIfPiSenderAllowsBal: BAL_BLOCK
         * realCallerStartMode: MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED
         * ```
         *
         * Being a foreground service is not an exemption on API 36, and the platform decides against
         * letting the app raise its own page. The route that *is* sanctioned — and the one the locked
         * case actually uses — is the system sending the notification's `fullScreenIntent`, because
         * then SystemUI is the sender.
         *
         * So this call is kept as a best-effort for devices/OEM builds that do permit it (a granted
         * `SYSTEM_ALERT_WINDOW` is the usual way an alarm app unlocks it, which is why M5's 权限体检
         * asks for it), and the audible alarm plus the notification's 关闭 action carry the case where
         * it is refused. See docs/M4-STATUS.md.
         */
        fun show(context: Context) {
            val intent = intent(context)
            val pending = PendingIntent.getActivity(
                context,
                SHOW_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching { pending.send() }
                .onFailure { Log.w(TAG, "could not bring the ring page up: ${it.message}") }
        }
    }
}
