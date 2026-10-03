package com.alarmhub.app.bridge

import com.alarmhub.app.domain.model.Alarm
import com.alarmhub.app.domain.model.Group
import com.alarmhub.app.domain.model.RepeatType
import com.alarmhub.app.domain.model.Settings
import com.alarmhub.app.domain.model.ThemeMode
import com.alarmhub.app.domain.model.TimeFormat
import com.alarmhub.app.domain.model.VolumeKeyAction
import com.alarmhub.app.domain.schedule.AlarmStatus
import com.alarmhub.app.domain.schedule.NextRingStatus
import com.alarmhub.app.permissions.PermissionStatus
import com.getcapacitor.JSObject
import org.json.JSONObject

/**
 * The one place domain objects become JSON for the WebView (M6).
 *
 * Two traps are handled here rather than at each of the twenty call sites:
 *
 * 1. **`JSONObject.put(key, null)` deletes the key.** The WebView then sees `undefined`, not `null`,
 *    and the front end's `alarm.nextRingAt !== null` guard is *true* for `undefined` — so a paused
 *    alarm would be rendered with a countdown to `NaN`. Every nullable field therefore goes through
 *    [putNullable], which writes `JSONObject.NULL` (a real sentinel that serialises to `null`).
 * 2. **Reading numbers back is asymmetric.** `PluginCall.getLong` returns its *fallback* for an
 *    `Int`-typed extra (verified in Capacitor 8: `PluginCall.java:196`), and a small JS integer
 *    arrives as `Integer`. [num] is the only sanctioned reader; see its comment.
 *
 * Field names are the contract's camelCase names, spelled out one by one on purpose: a typo here is
 * a silently missing field in the UI, and the compiler cannot catch it because the contract lives in
 * TypeScript.
 */

/** The colour a group falls back to when an alarm's group row has gone missing. Matches the mock. */
private const val DEFAULT_GROUP_COLOR = 0xff8b97a8.toInt()

// ---------------------------------------------------------------------------------------
// Reads (JSON -> domain)
// ---------------------------------------------------------------------------------------

/**
 * Reads a JS number as a `Long`, whatever integer width it arrived as.
 *
 * `PluginCall.getLong("id")` returns **null** for `{id: 2}`, because org.json parses a small integer
 * as `Integer` and Capacitor's `getLong` only accepts `Long`. This is the same class of mistake as
 * M4's `--ei`/`--el` trap in `DebugReceiver`, in a different layer, so it gets the same treatment:
 * one helper that accepts every numeric shape, and nobody calls `getLong` directly.
 */
fun JSObject.num(key: String): Long? = when (val value = opt(key)) {
    null, JSONObject.NULL -> null
    is Long -> value
    is Int -> value.toLong()
    is Short -> value.toLong()
    is Byte -> value.toLong()
    is Double -> value.toLong()
    is Float -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}

fun JSObject.int(key: String): Int? = num(key)?.toInt()

/** True only when the key is present and not JSON `null`. */
fun JSObject.present(key: String): Boolean = has(key) && !isNull(key)

fun JSObject.bool(key: String): Boolean? =
    if (present(key)) optBoolean(key) else null

fun JSObject.str(key: String): String? =
    if (present(key)) optString(key) else null

/** A string field the caller may legitimately clear by sending an explicit `null`. */
class NullableString(val present: Boolean, val value: String?)

/**
 * Distinguishes the three states a `string | null` field can be in on the wire: absent ("the form did
 * not mention it"), present and null ("clear it"), present with a value. Collapsing the first two —
 * which is what `optString` does — would make every partial update silently wipe the field.
 */
fun JSObject.nullableString(key: String): NullableString = when {
    !has(key) -> NullableString(false, null)
    isNull(key) -> NullableString(true, null)
    else -> NullableString(true, optString(key))
}

/** `{"ids": [...]}` → `List<Long>`. */
fun JSObject.longList(key: String): List<Long> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        when (val value = array.opt(index)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }
}

// ---------------------------------------------------------------------------------------
// Writes (domain -> JSON)
// ---------------------------------------------------------------------------------------

/** Writes `null` as a real JSON `null` instead of removing the key. See the file comment. */
fun JSObject.putNullable(key: String, value: Any?): JSObject = put(key, value ?: JSONObject.NULL)

fun Group.toJson(): JSObject = JSObject().apply {
    put("id", id)
    put("name", name)
    put("color", color)
    put("sortOrder", sortOrder)
    put("isSystem", isSystem)
    put("permanentDisabled", permanentDisabled)
    putNullable("pauseUntil", pauseUntil)
    putNullable("pausedAt", pausedAt)
    put("alarmCount", alarmCount)
}

fun Alarm.toJson(): JSObject = JSObject().apply {
    put("id", id)
    put("groupId", groupId)
    put("hour", hour)
    put("minute", minute)
    put("label", label)
    put("repeatType", repeatType.name)
    put("repeatDays", repeatDays)
    putNullable("onceDate", onceDate)
    putNullable("ringtoneUri", ringtoneUri)
    putNullable("ringtoneName", ringtoneName)
    put("vibrate", vibrate)
    put("fadeInSeconds", fadeInSeconds)
    put("snoozeEnabled", snoozeEnabled)
    put("snoozeMinutes", snoozeMinutes)
    put("snoozeMaxCount", snoozeMaxCount)
    put("autoStopMinutes", autoStopMinutes)
    put("deleteAfterRing", deleteAfterRing)
    put("enabled", enabled)
    put("permanentDisabled", permanentDisabled)
    putNullable("pauseUntil", pauseUntil)
    put("expired", expired)
}

/**
 * `AlarmView`: the alarm plus everything the list renders that the front end is not allowed to
 * derive (contract rule 2 — `nextRingAt` and `status` are computed natively, always).
 */
fun alarmViewJson(alarm: Alarm, group: Group?, status: NextRingStatus): JSObject = alarm.toJson().apply {
    put("groupName", group?.name ?: "未分组")
    put("groupColor", group?.color ?: DEFAULT_GROUP_COLOR)
    putNullable("nextRingAt", status.nextRingAt)
    put("status", status.status.wire)
}

/** The contract's `AlarmStatus` strings. Kept explicit so an enum rename cannot reach the wire. */
val AlarmStatus.wire: String
    get() = when (this) {
        AlarmStatus.SCHEDULED -> "scheduled"
        AlarmStatus.PAUSED -> "paused"
        AlarmStatus.DISABLED -> "disabled"
        AlarmStatus.EXPIRED -> "expired"
        AlarmStatus.NEVER -> "never"
    }

fun Settings.toJson(): JSObject = JSObject().apply {
    put("defaultDeleteOnceAfterRing", defaultDeleteOnceAfterRing)
    put("defaultPauseDays", defaultPauseDays)
    put("defaultSnoozeMinutes", defaultSnoozeMinutes)
    put("defaultSnoozeMaxCount", defaultSnoozeMaxCount)
    put("snoozeEnabled", snoozeEnabled)
    put("defaultAutoStopMinutes", defaultAutoStopMinutes)
    putNullable("defaultRingtoneUri", defaultRingtoneUri)
    putNullable("defaultRingtoneName", defaultRingtoneName)
    put("defaultFadeInSeconds", defaultFadeInSeconds)
    put("timeFormat", if (timeFormat == TimeFormat.H12) "12" else "24")
    put("theme", theme.wire)
    put("volumeKeyAction", if (volumeKeyAction == VolumeKeyAction.DISMISS) "dismiss" else "snooze")
    put("permissionCheckDone", permissionCheckDone)
}

fun PermissionStatus.toJson(): JSObject = JSObject().apply {
    put("key", key.wire)
    put("label", label)
    put("description", description)
    put("granted", granted)
    put("applicable", applicable)
}

private val ThemeMode.wire: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "system"
        ThemeMode.DARK -> "dark"
        ThemeMode.LIGHT -> "light"
    }

// ---------------------------------------------------------------------------------------
// Patch application (JSON -> domain, field by field, absent key = "leave it alone")
// ---------------------------------------------------------------------------------------

/**
 * Applies a partial patch to an existing alarm.
 *
 * Only keys that are actually present are touched, which is what makes `Partial<Alarm>` behave like
 * a partial update instead of "reset everything the form did not mention". The nullable string
 * fields use [nullableString] so that sending `null` clears them and omitting them keeps them.
 */
fun Alarm.applyPatch(data: JSObject): Alarm {
    var next = this
    data.num("groupId")?.let { next = next.copy(groupId = it) }
    data.int("hour")?.let { next = next.copy(hour = it) }
    data.int("minute")?.let { next = next.copy(minute = it) }
    data.str("label")?.let { next = next.copy(label = it) }
    repeatTypeOrNull(data.str("repeatType"))?.let { next = next.copy(repeatType = it) }
    data.int("repeatDays")?.let { next = next.copy(repeatDays = it) }
    data.nullableString("onceDate").let { if (it.present) next = next.copy(onceDate = it.value) }
    data.nullableString("ringtoneUri").let { if (it.present) next = next.copy(ringtoneUri = it.value) }
    data.nullableString("ringtoneName").let { if (it.present) next = next.copy(ringtoneName = it.value) }
    data.bool("vibrate")?.let { next = next.copy(vibrate = it) }
    data.int("fadeInSeconds")?.let { next = next.copy(fadeInSeconds = it) }
    data.bool("snoozeEnabled")?.let { next = next.copy(snoozeEnabled = it) }
    data.int("snoozeMinutes")?.let { next = next.copy(snoozeMinutes = it) }
    data.int("snoozeMaxCount")?.let { next = next.copy(snoozeMaxCount = it) }
    data.int("autoStopMinutes")?.let { next = next.copy(autoStopMinutes = it) }
    data.bool("deleteAfterRing")?.let { next = next.copy(deleteAfterRing = it) }
    data.bool("enabled")?.let { next = next.copy(enabled = it) }
    data.bool("permanentDisabled")?.let { next = next.copy(permanentDisabled = it) }
    data.num("pauseUntil")?.let { next = next.copy(pauseUntil = it) }
    return next
}

/**
 * PRD FR-5.1's defaults for a freshly created alarm: whatever the editor sent, and for anything it
 * did not send, the value from 设置 rather than a literal invented here.
 */
fun newAlarm(data: JSObject, settings: Settings): Alarm = Alarm(
    id = 0L,
    groupId = data.num("groupId") ?: 1L,
    hour = data.int("hour") ?: 7,
    minute = data.int("minute") ?: 0,
    label = data.str("label") ?: "",
    repeatType = repeatTypeOrNull(data.str("repeatType")) ?: RepeatType.ONCE,
    repeatDays = data.int("repeatDays") ?: 0,
    onceDate = data.str("onceDate"),
    ringtoneUri = if (data.has("ringtoneUri")) data.str("ringtoneUri") else settings.defaultRingtoneUri,
    ringtoneName = if (data.has("ringtoneName")) data.str("ringtoneName") else settings.defaultRingtoneName,
    vibrate = data.bool("vibrate") ?: true,
    fadeInSeconds = data.int("fadeInSeconds") ?: settings.defaultFadeInSeconds,
    snoozeEnabled = data.bool("snoozeEnabled") ?: true,
    snoozeMinutes = data.int("snoozeMinutes") ?: settings.defaultSnoozeMinutes,
    snoozeMaxCount = data.int("snoozeMaxCount") ?: settings.defaultSnoozeMaxCount,
    autoStopMinutes = data.int("autoStopMinutes") ?: settings.defaultAutoStopMinutes,
    deleteAfterRing = data.bool("deleteAfterRing") ?: settings.defaultDeleteOnceAfterRing,
    enabled = data.bool("enabled") ?: true,
)

fun Group.applyPatch(data: JSObject): Group {
    var next = this
    data.str("name")?.let { next = next.copy(name = it) }
    data.int("color")?.let { next = next.copy(color = it) }
    data.int("sortOrder")?.let { next = next.copy(sortOrder = it) }
    data.bool("permanentDisabled")?.let { next = next.copy(permanentDisabled = it) }
    data.num("pauseUntil")?.let { next = next.copy(pauseUntil = it) }
    data.num("pausedAt")?.let { next = next.copy(pausedAt = it) }
    return next
}

/** The 设置 page's partial update. Keys the page did not send keep their stored value. */
fun Settings.applyPatch(data: JSObject): Settings = copy(
    defaultDeleteOnceAfterRing = data.bool("defaultDeleteOnceAfterRing") ?: defaultDeleteOnceAfterRing,
    defaultPauseDays = data.int("defaultPauseDays") ?: defaultPauseDays,
    defaultSnoozeMinutes = data.int("defaultSnoozeMinutes") ?: defaultSnoozeMinutes,
    defaultSnoozeMaxCount = data.int("defaultSnoozeMaxCount") ?: defaultSnoozeMaxCount,
    snoozeEnabled = data.bool("snoozeEnabled") ?: snoozeEnabled,
    defaultAutoStopMinutes = data.int("defaultAutoStopMinutes") ?: defaultAutoStopMinutes,
    defaultRingtoneUri = data.nullableString("defaultRingtoneUri").orElse(defaultRingtoneUri),
    defaultRingtoneName = data.nullableString("defaultRingtoneName").orElse(defaultRingtoneName),
    defaultFadeInSeconds = data.int("defaultFadeInSeconds") ?: defaultFadeInSeconds,
    timeFormat = when (data.str("timeFormat")) {
        "12" -> TimeFormat.H12
        "24" -> TimeFormat.H24
        else -> timeFormat
    },
    theme = when (data.str("theme")) {
        "dark" -> ThemeMode.DARK
        "light" -> ThemeMode.LIGHT
        "system" -> ThemeMode.SYSTEM
        else -> theme
    },
    volumeKeyAction = when (data.str("volumeKeyAction")) {
        "dismiss" -> VolumeKeyAction.DISMISS
        "snooze" -> VolumeKeyAction.SNOOZE
        else -> volumeKeyAction
    },
    permissionCheckDone = data.bool("permissionCheckDone") ?: permissionCheckDone,
)

/** Collapses "the patch left this alone" and "the patch set it to null" into the stored value. */
private fun NullableString.orElse(current: String?): String? = if (present) value else current

private fun repeatTypeOrNull(name: String?): RepeatType? =
    name?.let { value -> RepeatType.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }
