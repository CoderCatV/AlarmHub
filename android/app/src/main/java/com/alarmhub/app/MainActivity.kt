package com.alarmhub.app

import android.os.Bundle
import android.util.Log
import androidx.activity.addCallback
import com.alarmhub.app.bridge.AlarmHubPlugin
import com.alarmhub.app.ring.RingActivity
import com.alarmhub.app.ring.RingController
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must happen before super.onCreate(): the bridge is built there (BridgeActivity.load),
        // and it only picks up plugins that were registered beforehand.
        registerPlugin(AlarmHubPlugin::class.java)
        super.onCreate(savedInstanceState)
        installBackNavigation()
    }

    /**
     * The system back gesture returns to the previous **level**, not out of the app.
     *
     * Before this, an edge swipe (or the hardware key) closed the app from every page: the WebView had
     * no history of its own, so the platform did what the platform does. A user on 设置 who swipes back
     * expects the list, and closing the app instead reads as a crash. The levels are, in order:
     * selection mode (长按批量勾选) → the previous page → and only then the app itself.
     *
     * **The page is asked, not the WebView.** The obvious implementation uses
     * `webView.canGoBack()` / `goBack()`, and it is wrong here: the WebView's history and the app's own
     * navigation state disagreed in practice — after the app had been backgrounded, Chromium can swap
     * the renderer, and the native answer was "nothing to go back to" while the page was demonstrably
     * showing a sub-page. The result was `finish()` from 设置. `nav.ts` publishes
     * `window.__dshCanGoBack` / `window.__dshBack`, which read the app's state and are therefore the
     * only authority.
     *
     * `evaluateJavascript` is asynchronous, so the decision lands one tick later. That is fine for a
     * back gesture, and it is the price of not having two sources of truth.
     *
     * Registered on androidx's dispatcher rather than by overriding `onBackPressed`: on Android 13+ the
     * gesture can arrive through the platform's back-invoked path, and the deprecated override is not
     * called for it (measured on this device — see `RingActivity`).
     */
    private fun installBackNavigation() {
        onBackPressedDispatcher.addCallback(this) {
            val web = bridge?.webView
            if (web == null) {
                finish()
                return@addCallback
            }
            web.evaluateJavascript(
                "(window.__dshCanGoBack && window.__dshCanGoBack()) ? 'yes' : 'no'",
            ) { answer ->
                if (answer?.trim('"') == "yes") {
                    web.evaluateJavascript("window.__dshBack && window.__dshBack()", null)
                } else {
                    // Nothing of ours is showing a sub-level, so this is the root and the platform's
                    // behaviour — leaving the app — is what the user expects.
                    finish()
                }
            }
        }
    }

    /**
     * Opening the app while an alarm is ringing shows the ring page.
     *
     * **Why this exists — it is the fix for a real "the alarm would not stop" report.** Until this,
     * the app was a dead end during a ring:
     *
     * * `RingActivity` is declared `excludeFromRecents`, so the ring task cannot be reached from the
     *   task switcher;
     * * and the platform **refuses to let the app bring its own page to the front** while the device
     *   is unlocked (measured during M4, docs/M4-STATUS.md §4.3 — a background activity launch from a
     *   foreground service is blocked, and a self-sent PendingIntent does not help either).
     *
     * So if the ring page did not happen to be in front when the alarm fired, the *only* way to
     * silence it was the 关闭 button inside the notification — a place nobody thinks to look, and the
     * user's first instinct (open the app) landed on the list, which offered no way out at all. The
     * alarm then rang until `autoStopMinutes` (10 by default) elapsed.
     *
     * A **foreground** activity starting another activity is not subject to that restriction, so this
     * one call closes the hole: whatever the user does — tapping the notification, or just opening the
     * app — they end up on a page with 关闭 on it.
     *
     * Safe to run on every resume: `RingController.isRinging` is derived from the same state the ring
     * page renders, so when nothing is ringing this is a boolean read. `RingActivity`'s own intent
     * carries `NEW_TASK | CLEAR_TASK`, and because that activity lives in its own task affinity
     * (`.ring`) the clear applies to the ring task, not to this one.
     */
    override fun onResume() {
        super.onResume()
        if (!RingController.isRinging) return
        Log.i(TAG, "a ring is active; bringing the ring page to the front")
        runCatching { startActivity(RingActivity.intent(this)) }
            .onFailure { Log.w(TAG, "could not bring the ring page up: ${it.message}") }
    }

    private companion object {
        private const val TAG = "AlarmHub/MainActivity"
    }
}
