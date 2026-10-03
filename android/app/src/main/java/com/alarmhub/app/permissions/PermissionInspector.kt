package com.alarmhub.app.permissions

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Reads the seven items of PRD FR-5.1 straight off the platform.
 *
 * Three rules shape everything here:
 *
 * 1. **Never guess in the direction of "fine".** A row only shows a green tick when a platform API
 *    said so, or when the capability is granted at install and cannot be revoked. Vendor-only
 *    switches that this ROM does not expose are reported as needing attention, with the reason
 *    spelled out in [PermissionStatus.description] — see [vendorOpGranted].
 * 2. **A switch that does not exist is not a failing switch.** The MIUI-only rows become
 *    `applicable = false` on a stock device, which the page renders as 「不适用」 rather than a red
 *    cross. That is what DEVELOPMENT-PLAN M5 asks the emulator to show.
 * 3. **No Android-version cliff is allowed to throw.** Every probe is guarded by `SDK_INT` and
 *    wrapped so a missing API reports "已满足" when the capability is in fact unconditional.
 *
 * This class decides *state* only. Changing the state — jumping to a settings page, asking for
 * `POST_NOTIFICATIONS` — lives in [PermissionSettingsLauncher] and the bridge.
 */
class PermissionInspector(private val context: Context) {

    /** Cached once per instance: whether the hidden numeric AppOps route is reachable at all. */
    private var numericUnavailable: String? = null
    private var lastNumericError: String? = null

    /** Every row, in the order PRD FR-5.1 lists them (fullScreenIntent inserted after 通知). */
    fun inspect(): List<PermissionStatus> = PermissionKey.entries.map(::statusOf)

    /** What the contract needs for any single row. */
    fun statusOf(key: PermissionKey): PermissionStatus = when (key) {
        PermissionKey.EXACT_ALARM -> exactAlarm()
        PermissionKey.NOTIFICATIONS -> notifications()
        PermissionKey.FULL_SCREEN_INTENT -> fullScreenIntent()
        PermissionKey.AUTOSTART -> autostart()
        PermissionKey.BATTERY_UNRESTRICTED -> batteryUnrestricted()
        PermissionKey.BACKGROUND_POPUP -> backgroundPopup()
        PermissionKey.LOCK_SCREEN_DISPLAY -> lockScreenDisplay()
    }

    // ---- the four rows with a real platform API ------------------------------------------

    private fun exactAlarm(): PermissionStatus {
        // `canScheduleExactAlarms` exists from API 31. Below that every app may use exact alarms,
        // so the honest answer is "already satisfied", not "not applicable": the capability is
        // there, it simply has no switch.
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager()?.canScheduleExactAlarms() ?: false
        } else {
            true
        }
        return PermissionStatus(
            key = PermissionKey.EXACT_ALARM,
            label = "精确闹钟",
            description = "没有它，闹钟会被系统推迟到几分钟后，甚至和别的通知合并掉。这是「到点就响」的地基。",
            granted = granted,
            applicable = true,
        )
    }

    private fun notifications(): PermissionStatus {
        val granted = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return PermissionStatus(
            key = PermissionKey.NOTIFICATIONS,
            label = "通知",
            description = "没有它，响铃时不会出现通知；解锁状态下也就没有任何入口能回到响铃界面。",
            granted = granted,
            applicable = true,
        )
    }

    private fun fullScreenIntent(): PermissionStatus {
        // API 34 introduced both the check and the switch. Below it USE_FULL_SCREEN_INTENT is
        // granted at install and cannot be taken away, so again: satisfied, not not-applicable.
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            notificationManager()?.canUseFullScreenIntent() ?: false
        } else {
            true
        }
        return PermissionStatus(
            key = PermissionKey.FULL_SCREEN_INTENT,
            label = "全屏通知",
            description = "没有它，锁屏时闹钟只能以一条通知的形式出现，不能直接铺满屏幕。",
            granted = granted,
            applicable = true,
        )
    }

    private fun batteryUnrestricted(): PermissionStatus {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager()?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else {
            true
        }
        return PermissionStatus(
            key = PermissionKey.BATTERY_UNRESTRICTED,
            label = "电池无限制",
            description = "省电策略会冻结后台进程，闹钟到点时进程可能还没醒过来。",
            granted = granted,
            applicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M,
        )
    }

    // ---- the three vendor rows ------------------------------------------------------------

    private fun autostart(): PermissionStatus {
        if (!isMiui()) {
            return PermissionStatus(
                key = PermissionKey.AUTOSTART,
                label = "自启动",
                description = "本机（${deviceLabel()}）没有这个开关，开机后由系统广播直接恢复闹钟，不需要额外授权。",
                granted = true,
                applicable = false,
            )
        }
        val probe = vendorOpGranted(AUTOSTART_OP_CODES, AUTOSTART_OPS)
        return PermissionStatus(
            key = PermissionKey.AUTOSTART,
            label = "自启动",
            description = "小米 / 红米 / HyperOS 必须开启，否则重启后或被清理后闹钟不会恢复。" + probe.note(),
            granted = probe.verdict == true,
            applicable = true,
        )
    }

    private fun backgroundPopup(): PermissionStatus {
        val overlay = ProbeResult(overlayGranted(), "Settings.canDrawOverlays")
        if (!isMiui()) {
            // On stock Android the switch that actually gates "may this app put a window on top of
            // the current one" is the overlay permission. M4 measured that it is the only lever
            // that can make the ring page appear by itself while the device is unlocked
            // (docs/M4-STATUS.md §4.3), so the row is mapped onto it rather than declared 不适用.
            return PermissionStatus(
                key = PermissionKey.BACKGROUND_POPUP,
                label = "后台弹出界面",
                description = "本机（${deviceLabel()}）对应的是「显示在其他应用上层」。没有它，解锁状态下应用无法自己拉起响铃页。",
                granted = overlay.verdict == true,
                applicable = true,
            )
        }
        val vendor = vendorOpGranted(BACKGROUND_POPUP_OP_CODES, BACKGROUND_POPUP_OPS)
        // Either lever can let the page come up, so a granted one is enough to call it satisfied.
        val granted = vendor.verdict == true || overlay.verdict == true
        return PermissionStatus(
            key = PermissionKey.BACKGROUND_POPUP,
            label = "后台弹出界面",
            description = "没有它，应用在后台时无法直接拉起响铃页，只能靠通知。" +
                "点「去设置」会打开本应用的权限页，还要再点一次「其他权限」才能看到这个开关。" +
                vendor.note(),
            granted = granted,
            applicable = true,
        )
    }

    private fun lockScreenDisplay(): PermissionStatus {
        if (!isMiui()) {
            return PermissionStatus(
                key = PermissionKey.LOCK_SCREEN_DISPLAY,
                label = "锁屏显示",
                description = "本机（${deviceLabel()}）没有这个开关，锁屏响铃由响铃页自己的 showWhenLocked 负责。",
                granted = true,
                applicable = false,
            )
        }
        val probe = vendorOpGranted(LOCK_SCREEN_DISPLAY_OP_CODES, LOCK_SCREEN_DISPLAY_OPS)
        return PermissionStatus(
            key = PermissionKey.LOCK_SCREEN_DISPLAY,
            label = "锁屏显示",
            description = "没有它，锁屏时看不到响铃界面。" +
                "点「去设置」会打开本应用的权限页，还要再点一次「其他权限」才能看到这个开关。" +
                probe.note(),
            granted = probe.verdict == true,
            applicable = true,
        )
    }

    // ---- probes --------------------------------------------------------------------------

    /**
     * Reads one of MIUI's private AppOps entries.
     *
     * MIUI implements 自启动 / 后台弹出界面 / 锁屏显示 as AppOps operations rather than permissions.
     * Two ways of naming them exist in the wild, and **which one works is not a matter of taste**:
     *
     * * **HyperOS 3 (measured on a Xiaomi 14, `V816`, Android 16):** the operations are registered
     *   with **no queryable string name at all**. `dumpsys` renders them as `MIUIOP(10008)` and
     *   `unsafeCheckOpNoThrow("MIUIOP(10008)", …)` answers *"Unknown operation string"*. The only
     *   working route is the **numeric** overload, `unsafeCheckOpNoThrow(int op, …)` (public since
     *   API 29). Before this was found, all three rows reported ✕ on the reporting device — including
     *   `自启动`, which was demonstrably **on** in MIUI's own 自启动管理 page. A permanently wrong red
     *   cross on the app's own health page is worse than useless: it teaches the user to ignore it.
     * * **Older MIUI:** string names such as `android:auto_start` are registered and the numeric codes
     *   are not, so both spellings are tried.
     *
     * The op codes were **identified by experiment on the real device**, not guessed: each vendor
     * switch was turned off and on again while `cmd appops get` was polled every two seconds.
     *
     * | code | switch | off → on |
     * |---|---|---|
     * | 10008 | 自启动 | `ignore` → `allow` |
     * | 10021 | 后台弹出界面 | `ignore` → `allow` |
     * | 10020 | 锁屏显示 | `ignore` → `allow` |
     * | 10053 | 链式启动管理 (not a contract row) | follows 自启动 |
     *
     * **`allow` really does mean "the user granted it".** That was checked against four apps sitting
     * in MIUI's 禁止自启动 list (钉钉 / 阿里云 / 通义 / 阿里巴巴): every one of them reads `ignore` for
     * 10008, 10020 and 10021. So the default is deny and a green tick is not a false positive — which
     * matters, because the opposite reading would have made the whole probe worthless.
     */
    private fun vendorOpGranted(opCodes: IntArray, legacyNames: List<String>): ProbeResult {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return ProbeResult(null, "no AppOpsManager")
        val uid = Process.myUid()
        val attempted = ArrayList<String>(opCodes.size + legacyNames.size)

        // 1) By number — the only route that works on HyperOS.
        for (code in opCodes) {
            val mode = numericOpMode(ops, code, uid, context.packageName) ?: continue
            attempted += "op#$code=$mode"
            return verdict(mode, attempted)
        }
        if (numericUnavailable != null) attempted += "op#=unavailable($numericUnavailable)"

        // 2) By name — older MIUI.
        for (name in legacyNames) {
            val mode = try {
                @Suppress("DEPRECATION")
                ops.unsafeCheckOpNoThrow(name, uid, context.packageName)
            } catch (_: IllegalArgumentException) {
                attempted += "$name=?"
                continue
            } catch (_: SecurityException) {
                attempted += "$name=denied"
                continue
            }
            attempted += "$name=$mode"
            return verdict(mode, attempted)
        }

        Log.i(TAG, "no MIUI op candidate resolved: ${attempted.joinToString(", ")}")
        return ProbeResult(null, attempted.joinToString(","))
    }

    /**
     * The numeric op lookup, by reflection.
     *
     * It has to be reflection: HyperOS 3 gives these operations **no string name**, and the SDK only
     * exposes the `String` overloads of `checkOpNoThrow` / `unsafeCheckOpNoThrow` — the `int op`
     * forms exist in the platform but are hidden. Reflection on a hidden member is subject to the
     * non-SDK-interface restrictions, so this may legitimately fail; the result is cached in
     * [numericUnavailable] and the caller falls back to the legacy string names.
     *
     * Several spellings are tried because which ones survive the greylist differs between releases.
     */
    private fun numericOpMode(ops: AppOpsManager, op: Int, uid: Int, pkg: String): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (numericUnavailable != null) return null

        for (name in NUMERIC_OP_METHODS) {
            val value = try {
                AppOpsManager::class.java
                    .getMethod(name, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
                    .invoke(ops, op, uid, pkg) as? Int
            } catch (t: Throwable) {
                lastNumericError = "${t::class.java.simpleName}: ${t.message}"
                continue
            }
            Log.i(TAG, "numeric AppOps route via $name")
            return value
        }
        numericUnavailable = lastNumericError ?: "no method found"
        Log.w(TAG, "numeric AppOps probe unavailable ($numericUnavailable); falling back to string names")
        return null
    }

    /**
     * `MODE_ALLOWED` is the only value that means granted.
     *
     * Measured defaults on HyperOS 3: an app sitting in the 禁止自启动 list reads `ignore`, not
     * `MODE_DEFAULT`, so "everything that is not an explicit allow is off" is the correct reading
     * rather than a conservative guess.
     */
    private fun verdict(mode: Int, attempted: List<String>): ProbeResult =
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> ProbeResult(true, attempted.joinToString(","))
            else -> ProbeResult(false, attempted.joinToString(","))
        }

    private fun overlayGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true

    /** One sentence to append to a vendor row's description when the ROM could not be asked. */
    private fun ProbeResult.note(): String =
        if (verdict == null) "（本机无法自动检测这一项，请自己确认它已经打开）" else ""

    // ---- platform handles ----------------------------------------------------------------

    private fun alarmManager() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private fun notificationManager() =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun powerManager() = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private fun deviceLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    companion object {
        private const val TAG = "AlarmHub/Permissions"

        /**
         * MIUI's private switches, identified **by number** (the HyperOS 3 route) and, behind that,
         * by the string names older MIUI builds register instead. See [vendorOpGranted] for how the
         * codes were established and why `allow` is meaningful.
         */
        private val AUTOSTART_OP_CODES = intArrayOf(10008)
        private val BACKGROUND_POPUP_OP_CODES = intArrayOf(10021)
        private val LOCK_SCREEN_DISPLAY_OP_CODES = intArrayOf(10020)

        /**
         * Hidden `AppOpsManager` entry points that take an op **code** rather than a name, tried in
         * order. `unsafeCheckOpNoThrow` is the modern one; `checkOpNoThrow` is the API-19-era public
         * method that was later hidden and therefore has the best chance of being greylisted.
         */
        private val NUMERIC_OP_METHODS = listOf("unsafeCheckOpNoThrow", "checkOpNoThrow")

        /** Legacy MIUI string spellings, most likely first. */
        private val AUTOSTART_OPS = listOf("android:auto_start", "auto_start", "miui:auto_start")
        private val BACKGROUND_POPUP_OPS = listOf(
            "android:background_start_activity",
            "background_start_activity",
            "miui:background_start_activity",
        )
        private val LOCK_SCREEN_DISPLAY_OPS = listOf(
            "android:lockscreen_display",
            "lockscreen_display",
            "android:show_when_locked",
            "show_when_locked",
        )

        /**
         * Xiaomi / Redmi / POCO, i.e. the ROMs that have the three vendor switches.
         *
         * Deliberately brand-based and not "does com.miui.securitycenter exist": package visibility
         * filtering (API 30+) would make the package query itself answer `false` unless a `<queries>`
         * entry is declared, which would turn a detection rule into a copy of the manifest.
         */
        fun isMiui(): Boolean = MIUI_BRANDS.any { brand ->
            listOf(Build.MANUFACTURER, Build.BRAND).any { it?.lowercase()?.contains(brand) == true }
        }

        private val MIUI_BRANDS = listOf("xiaomi", "redmi", "poco")
    }
}
