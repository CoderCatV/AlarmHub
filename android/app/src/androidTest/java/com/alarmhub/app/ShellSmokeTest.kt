package com.alarmhub.app

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the AlarmHub shell.
 *
 * `gradlew connectedAndroidTest` cannot run under the DSH sandbox (AGP 8.13 routes it
 * through UTP, whose helper JVM needs named pipes). Run it through adb instead:
 *
 * ```
 * adb shell am instrument -w -e class com.alarmhub.app.ShellSmokeTest \
 *     com.alarmhub.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ShellSmokeTest {

    private companion object {
        const val LOAD_TIMEOUT_MS = 20_000L
    }

    /** The native container must actually show a WebView. */
    @Test
    fun webViewIsDisplayed() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            onView(withId(R.id.webview)).check(matches(isDisplayed()))
        } finally {
            scenario.close()
        }
    }

    /** The WebView must end up on the Capacitor local server, not on about:blank. */
    @Test
    fun shellPageLoads() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val url = arrayOfNulls<String>(1)
            val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS

            while (System.currentTimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    activity.findViewById<WebView>(R.id.webview)?.let { url[0] = it.url }
                }
                if (url[0] != null && url[0] != "about:blank") break
                Thread.sleep(400L)
            }

            assertNotNull("WebView 应当已经加载壳页面，而不是停在空白页", url[0])
            val loaded = url[0]!!
            assertTrue(
                "预期加载 Capacitor 本地服务，实际是: $loaded",
                loaded.startsWith("https://localhost") || loaded.startsWith("http://localhost")
            )
        } finally {
            scenario.close()
        }
    }

    /**
     * M0's actual deliverable: the native Kotlin plugin has to be reachable from the bridge.
     * If this fails, `AlarmHub.ping()` in the WebView will fail too.
     */
    @Test
    fun alarmHubPluginIsRegistered() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                assertNotNull(
                    "AlarmHub 插件必须注册到 bridge，否则 H5 调不到原生",
                    activity.bridge.getPlugin("AlarmHub")
                )
            }
        } finally {
            scenario.close()
        }
    }
}
