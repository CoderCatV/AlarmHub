package com.alarmhub.app.permissions

/**
 * The seven items the 权限体检页 checks (PRD FR-5.1 + `fullScreenIntent`, which M4 proved is the
 * lever for "the ring page comes up by itself").
 *
 * [wire] is the string the frozen contract expects (`web/src/bridge/types.ts` `PermissionKey`). It
 * is spelled out here rather than derived from the enum name because the enum name is free to
 * change and the wire name is not: a mismatch would silently make every row render as an unknown
 * item instead of failing loudly.
 */
enum class PermissionKey(val wire: String) {
    EXACT_ALARM("exactAlarm"),
    NOTIFICATIONS("notifications"),
    FULL_SCREEN_INTENT("fullScreenIntent"),
    AUTOSTART("autostart"),
    BATTERY_UNRESTRICTED("batteryUnrestricted"),
    BACKGROUND_POPUP("backgroundPopup"),
    LOCK_SCREEN_DISPLAY("lockScreenDisplay"),
}

/**
 * One row of the 体检页. Field-for-field the contract's `PermissionItem`.
 *
 * [applicable] is the third state the contract can express without lying: `false` means "this device
 * has no such switch at all" (the MIUI-only rows on a stock Android device) and the page renders
 * 「不适用」 instead of a red cross. Everything that exists but is switched off is
 * `applicable = true, granted = false`.
 */
data class PermissionStatus(
    val key: PermissionKey,
    val label: String,
    /** What breaks without it, in the user's words. */
    val description: String,
    val granted: Boolean,
    val applicable: Boolean,
)

/**
 * What a single probe concluded, kept next to the boolean so the debug dump can explain a verdict
 * that looks wrong. `verdict == null` means "this ROM could not be asked", which is a different
 * fact from "the answer is no" and is exactly the distinction that is easy to lose.
 */
data class ProbeResult(val verdict: Boolean?, val detail: String)
