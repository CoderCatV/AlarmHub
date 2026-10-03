package com.alarmhub.app.bridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResult
import com.alarmhub.app.AlarmHubApp
import com.alarmhub.app.BuildConfig
import com.alarmhub.app.data.BuiltInGroups
import com.alarmhub.app.domain.model.Alarm
import com.alarmhub.app.domain.model.Group
import com.alarmhub.app.domain.model.PauseScope
import com.alarmhub.app.domain.model.RepeatType
import com.alarmhub.app.domain.schedule.NextRingCalculator
import com.alarmhub.app.domain.schedule.PauseResolver
import com.alarmhub.app.permissions.PermissionInspector
import com.alarmhub.app.permissions.PermissionKey
import com.alarmhub.app.permissions.PermissionSettingsLauncher
import com.alarmhub.app.ring.RingController
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * M6 — the native half of the frozen contract in `web/src/bridge/types.ts`.
 *
 * Three rules this class exists to keep:
 *
 * **1. Every write recomputes the schedule.** A write that moves a ring instant without
 * re-registering it with `AlarmManager` leaves the database and the system's alarm list disagreeing,
 * and the user experiences that as "the alarm I just changed did not ring". Rather than reasoning
 * about which of the twenty methods can move a ring instant, [write] sweeps the whole table
 * afterwards. The table is capped at 200 alarms by the PRD, and a full sweep is the same choice
 * [com.alarmhub.app.alarm.AlarmScheduler.recomputeAll] already documents: it cannot leave a stale
 * registration behind the way an incremental update can.
 *
 * **2. Reads and writes happen off the main thread.** Plugin methods are invoked on the UI thread;
 * Room would throw. Every method runs through [async], which hops to [Dispatchers.IO]. `call.resolve`
 * is safe from any thread: Capacitor posts the reply (`MessageHandler.legacySendResponseMessage`
 * uses `webView.post`, and the modern `WebMessageListener` reply proxy is documented as thread-safe).
 *
 * **3. Arrays are wrapped.** Capacitor cannot resolve a plugin call to a bare JSON array — the reply
 * envelope is built from a `JSObject` (`MessageHandler.sendResponseMessage`, line 117) and the JS side
 * hands `result.data` straight to the promise (`native-bridge.js`, line 965). So the three
 * array-returning methods answer `{groups: …}` / `{alarms: …}` / `{items: …}`, and the thin adapter in
 * `web/src/bridge/index.ts` unwraps them. That adapter is the whole of M6.3's 「接线」, and no page
 * has to know the difference.
 */
@CapacitorPlugin(
    name = "AlarmHub",
    permissions = [
        Permission(
            alias = AlarmHubPlugin.NOTIFICATIONS_ALIAS,
            strings = [Manifest.permission.POST_NOTIFICATIONS],
        ),
    ],
)
class AlarmHubPlugin : Plugin() {

    /**
     * Outlives any single call, so a write started by one call is not cut short by the next.
     *
     * Recreated on demand if it was cancelled ([handleOnDestroy]). A dead scope would make every
     * subsequent call hang with no reply, which on the UI side is indistinguishable from "the button
     * does nothing" — the exact symptom that is hardest to diagnose from a bug report, so the plugin
     * refuses to be able to end up in that state.
     */
    private var scope: CoroutineScope? = null

    private val liveScope: CoroutineScope
        get() = scope?.takeIf { it.isActive }
            ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }

    private val app: AlarmHubApp get() = AlarmHubApp.of(context)

    private val zone: ZoneId get() = ZoneId.systemDefault()

    override fun handleOnDestroy() {
        scope?.cancel()
        scope = null
    }

    // ---- meta -----------------------------------------------------------------------------

    @PluginMethod
    fun ping(call: PluginCall) {
        val appContext = context
        val versionName = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "unknown"

        call.resolve(
            JSObject().apply {
                put("version", versionName)
                put("sdkInt", Build.VERSION.SDK_INT)
                put("buildType", BuildConfig.BUILD_TYPE)
                put("appId", appContext.packageName)
                put("kotlinVersion", KotlinVersion.CURRENT.toString())
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            },
        )
    }

    @PluginMethod
    fun getHolidayDataInfo(call: PluginCall) {
        val currentYear = Instant.ofEpochMilli(app.timeSource.nowMillis()).atZone(zone).year
        call.resolve(
            JSObject().apply {
                put("years", JSONArray(app.holidayYears.sorted()))
                put("source", app.holidaySource)
                // PRD FR-6.3. The *current* year is what matters, and it changes at midnight on
                // New Year's Eve while the process keeps running, so it is not cached.
                put("degraded", !app.holidayCalendar.coversYear(currentYear))
            },
        )
    }

    // ---- groups ---------------------------------------------------------------------------

    @PluginMethod
    fun listGroups(call: PluginCall) = async(call) {
        val groups = JSArray()
        app.repository.groups().forEach { groups.put(it.toJson()) }
        JSObject().put("groups", groups)
    }

    @PluginMethod
    fun saveGroup(call: PluginCall) = async(call) {
        val data = call.data
        val requestedId = data.num("id") ?: 0L
        val base = if (requestedId != 0L) {
            app.repository.group(requestedId) ?: error("分组 $requestedId 不存在")
        } else {
            null
        }
        val group = base?.applyPatch(data) ?: Group(
            id = 0L,
            name = data.str("name") ?: error("新建分组必须给出名称"),
            color = data.int("color") ?: DEFAULT_GROUP_COLOR,
            sortOrder = data.int("sortOrder") ?: app.repository.groups().size,
            isSystem = false,
        )
        val id = write { app.repository.saveGroup(group) }
        app.repository.group(id)?.toJson() ?: error("分组 $id 保存后读不回来")
    }

    @PluginMethod
    fun deleteGroup(call: PluginCall) = async(call) {
        val id = call.data.num("id") ?: error("deleteGroup 需要 id")
        val moveTo = call.data.num("moveAlarmsTo") ?: BuiltInGroups.UNGROUPED_ID
        val moved = write { app.repository.deleteGroup(id, moveTo) }
        JSObject().put("movedCount", moved)
    }

    @PluginMethod
    fun reorderGroups(call: PluginCall) = async(call) {
        val ordered = call.data.longList("orderedIds")
        write { app.repository.reorderGroups(ordered) }
        JSObject()
    }

    // ---- alarms ---------------------------------------------------------------------------

    @PluginMethod
    fun listAlarms(call: PluginCall) = async(call) {
        val alarms = JSArray()
        // Groups are read once rather than per alarm: this is the method the list page calls on
        // every refresh, and `alarmsWithGroups` would repeat the group query for each row.
        val groups = app.repository.groups().associateBy { it.id }
        val now = app.timeSource.nowMillis()
        val calculator = nextRingCalculator()
        app.repository.alarms().forEach { alarm ->
            val group = groups[alarm.groupId]
            alarms.put(alarmViewJson(alarm, group, calculator.statusOf(alarm, group, now, zone)))
        }
        JSObject().put("alarms", alarms)
    }

    @PluginMethod
    fun getAlarm(call: PluginCall) = async(call) {
        alarmView(call.data.num("id") ?: error("getAlarm 需要 id"))
    }

    @PluginMethod
    fun saveAlarm(call: PluginCall) = async(call) {
        val data = call.data
        val requestedId = data.num("id") ?: 0L
        val existing = if (requestedId != 0L) {
            app.repository.alarm(requestedId) ?: error("闹钟 $requestedId 不存在")
        } else {
            null
        }
        val draft = sanityChecked(existing?.applyPatch(data) ?: newAlarm(data, app.repository.settings()))
        val stored = write { app.repository.saveAlarm(backfillOnceDate(draft)) }
        alarmView(stored.id)
    }

    @PluginMethod
    fun deleteAlarm(call: PluginCall) = async(call) {
        write { app.repository.deleteAlarm(call.data.num("id") ?: error("deleteAlarm 需要 id")) }
        JSObject()
    }

    /**
     * The list page's multi-select delete.
     *
     * One [write] for the whole batch, so the schedule is re-registered once rather than once per
     * alarm — and so the batch is atomic from the user's point of view.
     */
    @PluginMethod
    fun deleteAlarms(call: PluginCall) = async(call) {
        val ids = call.data.longList("ids")
        var affected = 0
        write {
            for (id in ids) {
                app.repository.deleteAlarm(id)
                affected++
            }
        }
        JSObject().put("affected", affected)
    }

    @PluginMethod
    fun setAlarmEnabled(call: PluginCall) = async(call) {
        val id = call.data.num("id") ?: error("setAlarmEnabled 需要 id")
        val enabled = call.data.bool("enabled") ?: error("setAlarmEnabled 需要 enabled")
        write { app.repository.setAlarmEnabled(id, enabled) }
        JSObject()
    }

    @PluginMethod
    fun batchUpdateAlarms(call: PluginCall) = async(call) {
        val data = call.data
        val ids = data.longList("ids")
        val patch = data.getJSObject("patch") ?: JSObject()
        var affected = 0
        write {
            for (id in ids) {
                val current = app.repository.alarm(id) ?: continue
                app.repository.saveAlarm(sanityChecked(current.applyPatch(patch)))
                affected++
            }
        }
        JSObject().put("affected", affected)
    }

    // ---- pause / disable ------------------------------------------------------------------

    @PluginMethod
    fun previewPause(call: PluginCall) = async(call) {
        val preview = preview(PauseRequest.from(call.data))
        val skipped = JSArray()
        preview.skippedRingDays.forEach { skipped.put(it.toLong()) }
        JSObject().apply {
            put("resumeAt", preview.resumeAt)
            put("skippedRingDays", skipped)
            putNullable("nextRingAt", preview.nextRingAt)
        }
    }

    /**
     * "When would this ring?" for a draft the user has not saved.
     *
     * Pure, like [previewPause]: it computes and returns, and writes nothing. The editor's
     * relative-time line (「6 小时 35 分钟后响铃」, borrowed from MIUI's clock) is what consumes it.
     *
     * It is here rather than in the WebView because the repeat rules, the holiday calendar and the
     * pause floor are business rules, and TECH-STACK §3 keeps them in `domain/`. [newAlarm] is the
     * same constructor a real save uses, so a preview cannot disagree with what saving would
     * produce — which is the whole point of showing it.
     */
    @PluginMethod
    fun previewNextRing(call: PluginCall) = async(call) {
        // `backfillOnceDate` is not optional here. A brand-new 单次 alarm has no `onceDate` yet — the
        // save path fills it in — and without the same step the preview answered 「不会响铃」 for every
        // new alarm, because a ONCE rule with a null date matches no day. A preview that disagrees
        // with what saving produces is worse than no preview: it is the screen lying about when the
        // alarm will ring. Caught by reading the line on the emulator rather than by reasoning.
        val alarm = sanityChecked(backfillOnceDate(newAlarm(call.data, app.repository.settings())))
        val group = app.repository.group(alarm.groupId)
        val at = nextRingCalculator().nextRing(alarm, group, app.timeSource.nowMillis(), zone)
        JSObject().putNullable("nextRingAt", at)
    }

    @PluginMethod
    fun pause(call: PluginCall) = async(call) {
        val request = PauseRequest.from(call.data)
        val resumeAt = preview(request).resumeAt
        val now = app.timeSource.nowMillis()
        write {
            when (request.scope) {
                PauseScope.GROUP -> app.repository.pauseGroup(request.id, resumeAt, now)
                PauseScope.ALARM -> app.repository.pauseAlarm(request.id, resumeAt)
            }
        }
        JSObject().put("resumeAt", resumeAt)
    }

    @PluginMethod
    fun resume(call: PluginCall) = async(call) {
        val data = call.data
        val scope = parseScope(data.getString("scope"))
        val id = data.num("id") ?: error("resume 需要 id")
        write {
            when (scope) {
                PauseScope.GROUP -> app.repository.resumeGroup(id)
                PauseScope.ALARM -> app.repository.resumeAlarm(id)
            }
        }
        JSObject()
    }

    @PluginMethod
    fun setPermanentDisabled(call: PluginCall) = async(call) {
        val data = call.data
        val scope = parseScope(data.getString("scope"))
        val id = data.num("id") ?: error("setPermanentDisabled 需要 id")
        val disabled = data.bool("disabled") ?: error("setPermanentDisabled 需要 disabled")
        write {
            when (scope) {
                PauseScope.GROUP -> app.repository.setGroupPermanentDisabled(id, disabled)
                PauseScope.ALARM -> app.repository.setAlarmPermanentDisabled(id, disabled)
            }
        }
        JSObject()
    }

    // ---- settings -------------------------------------------------------------------------

    @PluginMethod
    fun getSettings(call: PluginCall) = async(call) {
        app.repository.settings().toJson()
    }

    @PluginMethod
    fun updateSettings(call: PluginCall) = async(call) {
        val patch = call.data
        write { app.repository.updateSettings { it.applyPatch(patch) } }.toJson()
    }

    @PluginMethod
    fun countOnceAlarms(call: PluginCall) = async(call) {
        val (total, alreadyMarked) = app.repository.countOnceAlarms()
        JSObject().put("total", total).put("alreadyMarked", alreadyMarked)
    }

    @PluginMethod
    fun bulkSetDeleteAfterRing(call: PluginCall) = async(call) {
        JSObject().put("affected", write { app.repository.bulkSetDeleteAfterRing() })
    }

    // ---- ringtone -------------------------------------------------------------------------

    /**
     * Opens `ACTION_RINGTONE_PICKER`.
     *
     * A native round trip is the only way to choose a ringtone without `READ_MEDIA_AUDIO`
     * (TECH-STACK §4.6), and it is the one call that genuinely needs a result back from the system
     * UI, so it uses Capacitor's activity-result plumbing rather than the fire-and-forget path the
     * permission jumps use.
     *
     * `EXTRA_RINGTONE_SHOW_SILENT` is deliberately **false**: the ring path treats a chosen URI as
     * "play this", and a `silent:` URI fails to prepare and silently falls through to the system
     * default alarm sound (`AlarmAudioPlayer.start` appends it as the last resort). Offering a silent
     * option that produced the loudest sound on the device would be worse than not offering it.
     */
    @PluginMethod
    fun pickRingtone(call: PluginCall) {
        val current = call.getString("current")
        val defaultUri = defaultAlarmUri()
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择闹钟铃声")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, defaultUri)
            /*
             * `current ?: defaultUri`, not `current`.
             *
             * A null `ringtoneUri` means "follow the app's default ringtone", which is a real,
             * audible choice — but the system picker renders a missing existing-URI as 「None /
             * Currently set」, i.e. it claims the alarm is silent. Handing it the default URI makes
             * the picker show what will actually play, and picking that entry comes back as the
             * default URI, which is mapped to null again on the way out.
             */
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current?.let(Uri::parse) ?: defaultUri)
        }
        startActivityForResult(call, intent, "ringtonePicked")
    }

    @ActivityCallback
    private fun ringtonePicked(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val data = result.data
        val answered = result.resultCode == Activity.RESULT_OK &&
            data != null &&
            data.hasExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)

        // Cancelled (the picker was dismissed) is not the same fact as "chose the default": the
        // contract's `null` URI means "follow the app's default ringtone", not "leave it alone".
        if (!answered) {
            call.resolve(
                JSObject().putNullable("uri", null).putNullable("name", null).put("cancelled", true),
            )
            return
        }

        val picked = readPickedUri(data!!)
        val isDefault = picked != null && picked == defaultAlarmUri()
        call.resolve(
            JSObject().apply {
                putNullable("uri", if (isDefault) null else picked?.toString())
                putNullable("name", if (isDefault) "系统默认铃声" else picked?.let(::ringtoneTitle))
                // The extra exists but carries null: AOSP writes null for 「静音」, which this
                // picker does not offer, so an explicit null here means "no sound chosen".
                put("cancelled", picked == null)
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun readPickedUri(data: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }

    private fun defaultAlarmUri(): Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

    private fun ringtoneTitle(uri: Uri): String =
        runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "自定义铃声"

    // ---- permissions ----------------------------------------------------------------------

    @PluginMethod
    fun getPermissionStatus(call: PluginCall) {
        // No database access, so this one stays on the calling thread instead of hopping.
        val items = JSArray()
        PermissionInspector(context.applicationContext).inspect().forEach { items.put(it.toJson()) }
        call.resolve(JSObject().put("items", items))
    }

    /**
     * PRD FR-5.2 / FR-5.3.
     *
     * Notifications get one extra step: on API 33+ a runtime dialog is a far better answer than
     * dropping the user into Settings, and because the promise only settles once the dialog has been
     * answered, the page's follow-up `refresh()` already sees the new state. Everything else — and
     * notifications once the dialog has been used up — takes the candidate-intent path through
     * [PermissionSettingsLauncher], whose own fallback is the app's system detail page.
     */
    @PluginMethod
    fun openPermissionSetting(call: PluginCall) {
        val wire = call.getString("key")
        val key = PermissionKey.entries.firstOrNull { it.wire == wire }
        if (key == null) {
            call.reject("unknown permission key '$wire'")
            return
        }

        if (key == PermissionKey.NOTIFICATIONS && shouldAskForNotifications()) {
            requestPermissionForAlias(NOTIFICATIONS_ALIAS, call, "notificationsAsked")
            return
        }

        val opened = PermissionSettingsLauncher.open(context, key)
        call.resolve(JSObject().put("opened", opened).put("fallback", !opened))
    }

    private fun shouldAskForNotifications(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            getPermissionState(NOTIFICATIONS_ALIAS) == PermissionState.PROMPT

    @PermissionCallback
    private fun notificationsAsked(call: PluginCall) {
        // The dialog is a system surface that was shown and answered, which is what `opened`
        // reports. Whether the user allowed it is answered by the page's own refresh, from the same
        // probe the row uses — never from the dialog's result, which would be a second source of
        // truth for the same fact.
        call.resolve(JSObject().put("opened", true).put("fallback", false))
    }

    // ---- plumbing -------------------------------------------------------------------------

    /**
     * Runs [block] on the IO dispatcher and resolves the call with what it returns.
     *
     * A thrown exception becomes a rejected promise carrying its message, which the pages surface as
     * an error string. `Throwable` rather than `Exception`, so that a `NoClassDefFoundError` from an
     * API level that is not there still reaches the UI instead of dying on a background thread and
     * leaving the promise pending forever.
     */
    private fun async(call: PluginCall, block: suspend () -> JSObject) {
        liveScope.launch {
            try {
                call.resolve(block())
            } catch (t: Throwable) {
                Log.e(TAG, "${call.methodName} failed", t)
                // `reject(String)` rather than the (message, Exception) overload: `t` is a Throwable
                // on purpose (see above) and Capacitor's overloads only accept an Exception.
                runCatching { call.reject(t.message ?: t::class.java.simpleName) }
            }
        }
    }

    /**
     * Performs a write and then re-registers every alarm.
     *
     * See the class comment: deliberately unconditional. `recomputeAll` cancels and re-creates every
     * registration from stored state, so whichever field the caller changed, the system's alarm list
     * ends up agreeing with the database.
     *
     * It also reconciles the ring: a write can delete or switch off the alarm that is **currently
     * ringing**, and until M7 nothing stopped the sound when it did. Reported from a real device —
     * deleting the ringing alarm left it blaring with no page and no row to touch. Doing it here rather
     * than at each of the twenty mutation sites is the point: "remember to also stop the ring" is the
     * instruction that was forgotten once already.
     */
    private suspend fun <T> write(block: suspend () -> T): T {
        val result = block()
        app.scheduler.recomputeAll()
        runCatching { RingController.cancelIfNoLongerWanted() }
            .onFailure { Log.w(TAG, "could not reconcile the ring after a write", it) }
        return result
    }

    private suspend fun alarmView(id: Long): JSObject {
        val alarm = app.repository.alarm(id) ?: error("闹钟 $id 不存在")
        val group = app.repository.group(alarm.groupId)
        val status = nextRingCalculator()
            .statusOf(alarm, group, app.timeSource.nowMillis(), zone)
        return alarmViewJson(alarm, group, status)
    }

    /**
     * Refuses an impossible time **before** anything is written, with a message the user can act on.
     *
     * `Alarm`'s own `init` also rejects it, but that message is the domain's English invariant text.
     * This one is the boundary's: it is what ends up on the phone screen, so it names the field and
     * the range in the user's language.
     *
     * Added after the 24-hour wheel bug shipped an `hour` of 26 to the native side: the request died
     * deep inside `LocalDate.atTime` with a `DateTimeException` that mentioned neither the alarm nor
     * the wheel, and on the repeating-alarm path it died *after* the row had been inserted.
     */
    private fun sanityChecked(alarm: Alarm): Alarm {
        require(alarm.hour in 0..23) { "小时必须在 0~23 之间，收到 ${alarm.hour}" }
        require(alarm.minute in 0..59) { "分钟必须在 0~59 之间，收到 ${alarm.minute}" }
        return alarm
    }

    /**
     * PRD §5.2's 回填, which M3-STATUS §6 explicitly deferred to this layer.
     *
     * An `ONCE` alarm is stored with a concrete `yyyy-MM-dd`, and `AlarmRule.Once(null)` matches no
     * day at all — so an editor that sent no date would otherwise save an alarm that can never ring.
     * The rule is "the next occurrence of this time": today if that time is still ahead, tomorrow
     * otherwise. That is the same comparison the mock used (`at <= now` rolls forward), so the date
     * the user saw while editing is the date that gets stored.
     */
    private fun backfillOnceDate(alarm: Alarm): Alarm {
        if (alarm.repeatType != RepeatType.ONCE) return alarm
        val now = app.timeSource.nowMillis()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val declared = alarm.onceDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (declared != null && ringInstant(declared, alarm) > now) return alarm
        val date = if (ringInstant(today, alarm) > now) today else today.plusDays(1)
        return alarm.copy(onceDate = date.toString())
    }

    private fun ringInstant(date: LocalDate, alarm: Alarm): Long =
        date.atTime(alarm.hour, alarm.minute).atZone(zone).toInstant().toEpochMilli()

    /**
     * The contract's `PauseRequest`, in either of its two shapes.
     *
     * `until` is taken as given; `days` goes through [PauseResolver] (PRD §5.3). The "first ring
     * after" value in the `until` case is asked for with **on-or-after** semantics, unlike the M1
     * mock, which used strictly-after: the resume instant is itself a ring moment (PRD §5.3 step 3
     * lands it on a midnight, where a 00:00 alarm rings again), and the mock's strict comparison
     * would have hidden exactly that alarm for a day.
     */
    private suspend fun preview(request: PauseRequest): PausePreview {
        val now = app.timeSource.nowMillis()
        if (request.until != null) {
            return PausePreview(
                resumeAt = request.until,
                skippedRingDays = emptyList(),
                nextRingAt = nextRingCalculator()
                    .firstRingOnOrAfter(affectedSchedules(request), request.until, zone),
            )
        }
        val days = request.days ?: app.repository.settings().defaultPauseDays
        val resolution = when (request.scope) {
            PauseScope.GROUP -> PauseResolver(app.holidayCalendar).previewForGroup(
                alarms = app.repository.alarms().filter { it.groupId == request.id },
                group = app.repository.group(request.id),
                days = days,
                now = now,
                zone = zone,
            )

            PauseScope.ALARM -> PauseResolver(app.holidayCalendar).previewForAlarm(
                alarm = app.repository.alarm(request.id) ?: error("闹钟 ${request.id} 不存在"),
                days = days,
                now = now,
                zone = zone,
            )
        }
        return PausePreview(resolution.resumeAt, resolution.skippedRingDays, resolution.nextRingAt)
    }

    /** The schedules a pause would apply to, for the "what rings next after `until`" question. */
    private suspend fun affectedSchedules(request: PauseRequest) = when (request.scope) {
        PauseScope.GROUP ->
            app.repository.alarms()
                .filter { it.groupId == request.id && it.contributesToRingDays }
                .map { it.schedule }

        PauseScope.ALARM -> listOf(
            (app.repository.alarm(request.id) ?: error("闹钟 ${request.id} 不存在")).schedule,
        )
    }

    private fun nextRingCalculator() = NextRingCalculator(app.holidayCalendar)

    private fun parseScope(raw: String?): PauseScope = when (raw?.lowercase()) {
        "group" -> PauseScope.GROUP
        "alarm" -> PauseScope.ALARM
        else -> error("scope 必须是 group 或 alarm，收到 '$raw'")
    }

    /** Local mirror of the contract's `PauseRequest` union. */
    private data class PauseRequest(
        val scope: PauseScope,
        val id: Long,
        val days: Int?,
        val until: Long?,
    ) {
        companion object {
            fun from(data: JSObject): PauseRequest = PauseRequest(
                scope = when (data.getString("scope")?.lowercase()) {
                    "group" -> PauseScope.GROUP
                    "alarm" -> PauseScope.ALARM
                    else -> error("scope 必须是 group 或 alarm")
                },
                id = data.num("id") ?: error("pause 需要 id"),
                days = data.int("days"),
                until = data.num("until"),
            )
        }
    }

    private data class PausePreview(
        val resumeAt: Long,
        val skippedRingDays: List<Long>,
        val nextRingAt: Long?,
    )

    companion object {
        private const val TAG = "AlarmHub/Bridge"

        /** The alias declared in [CapacitorPlugin]; used by the runtime notification request. */
        const val NOTIFICATIONS_ALIAS = "notifications"

        private const val DEFAULT_GROUP_COLOR = 0xff4c9dff.toInt()
    }
}
