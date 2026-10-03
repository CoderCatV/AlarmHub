package com.alarmhub.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.alarmhub.app.AlarmHubApp
import com.alarmhub.app.alarm.AlarmScheduler
import com.alarmhub.app.alarm.TriggerKind
import com.alarmhub.app.data.AlarmRepository
import com.alarmhub.app.data.BuiltInGroups
import com.alarmhub.app.data.db.GroupEntity
import com.alarmhub.app.data.toDomain
import com.alarmhub.app.domain.model.Alarm
import com.alarmhub.app.domain.model.RepeatType
import com.alarmhub.app.domain.schedule.PauseResolver
import com.alarmhub.app.permissions.PermissionInspector
import com.alarmhub.app.permissions.PermissionKey
import com.alarmhub.app.permissions.PermissionSettingsLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * M3 test-injection entry point — **delete at M6**.
 *
 * M3's acceptance is about whether the *system* wakes the app at the right minute and stays quiet
 * when it must not. That cannot be shown through the H5 mock (which never touches AlarmManager), and
 * the real bridge does not exist until M6. So this receiver exposes the three things the test needs:
 *
 * ```
 * # add a test group + an alarm that rings `at` minutes from now
 * adb shell am broadcast -a com.alarmhub.app.debug.COMMAND \
 *     -n com.alarmhub.app/.debug.DebugReceiver --es cmd inject --ei at 1
 *
 * # what is stored, and what the scheduler registered
 * adb shell am broadcast -a com.alarmhub.app.debug.COMMAND \
 *     -n com.alarmhub.app/.debug.DebugReceiver --es cmd list
 *
 * # group state: pause / resume / disable / enable
 * adb shell am broadcast -a com.alarmhub.app.debug.COMMAND \
 *     -n com.alarmhub.app/.debug.DebugReceiver --es cmd pause --el group 2 --el days 1
 *
 * # read back the scheduler's registrations and the system's own view
 * adb shell am broadcast ... --es cmd registered
 * ```
 *
 * Guarded by the signature-level `com.alarmhub.app.permission.DEBUG`, so only an app signed with
 * this project's key can drive it. It also refuses to do anything unless this is a debug build.
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMMAND) return
        if (!com.alarmhub.app.BuildConfig.DEBUG) {
            Log.w(TAG, "ignored on a non-debug build")
            return
        }

        val command = intent.getStringExtra("cmd") ?: "list"
        val pending = goAsync()
        val app = AlarmHubApp.of(context)
        CoroutineScope(Dispatchers.IO).launch {
            val output = try {
                execute(app, command, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "cmd=$command failed", t)
                "ERROR ${t::class.java.simpleName}: ${t.message}"
            }
            // Human-readable, multi-line: goes to logcat for anyone reading along.
            for (line in output.lines()) Log.i(TAG, line)

            /*
             * Machine-readable, and deliberately NOT read back through logcat.
             *
             * The M3 acceptance script first parsed the broadcast's own `data=` result, then a logcat
             * marker. Both are unreliable for an automated reader: adb returns CRLF that hides between
             * fields, logcat hard-wraps long messages onto a continuation line, PowerShell loses
             * everything after the first line of a multi-line result, and a polling reader can match
             * the *previous* command's marker. Each of those produced a phantom test failure — the
             * product was behaving correctly every time.
             *
             * A file with one record per line removes the whole class of problem: the app writes it
             * atomically and the harness reads it verbatim.
             */
            runCatching { writeResultFile(context, command, output) }
                .onFailure { Log.e(TAG, "could not write the result file", it) }

            pending.setResultCode(0)
            pending.setResultData(output)
            pending.finish()
        }
    }

    /**
     * Writes the last command's result to `files/debug-result.txt`, read from adb with
     * `adb shell run-as com.alarmhub.app cat files/debug-result.txt`.
     *
     * `filesDir` is app-private, so `run-as` (available for debug builds only) is required to read
     * it — a second reason this cannot leak on a release build, on top of the `BuildConfig.DEBUG`
     * check above.
     */
    private fun writeResultFile(context: Context, command: String, output: String) {
        val body = buildString {
            append("cmd=").append(command).append('\n')
            for (line in output.lines()) {
                if (line.isNotBlank()) append(line.trim()).append('\n')
            }
        }
        // Write-then-rename, so a reader never sees a half-written file.
        val file = java.io.File(context.filesDir, RESULT_FILE)
        val temp = java.io.File(context.filesDir, "$RESULT_FILE.tmp")
        temp.writeText(body)
        if (!temp.renameTo(file)) {
            file.writeText(body)
            temp.delete()
        }
    }

    private suspend fun execute(app: AlarmHubApp, command: String, intent: Intent): String = when (command) {
        "inject" -> inject(app, intent)
        "injectOnce" -> inject(app, intent, forceOnce = true)
        "list" -> list(app)
        "registered" -> registered(app)
        "pause" -> pause(app, intent)
        "pauseUntil" -> pauseUntil(app, intent)
        "resume" -> resume(app)
        "disable" -> disable(app, intent)
        "enable" -> enable(app)
        "wipe" -> wipe(app)
        "trigger" -> trigger(app, intent)
        "ringstate" -> ringState(app)
        "dismiss" -> dismissRing(app)
        "snooze" -> snoozeRing(app)
        "ringnow" -> ringNow(app, intent)
        "permissions" -> permissions(app)
        "permissionjump" -> permissionJump(app, intent)
        else -> "ERROR unknown cmd '$command'"
    }

    /**
     * M5 diagnostic: what the 体检页 would show, plus the two facts a real-device check needs to
     * interpret a surprising row — the device identity, and whether this ROM is one of the MIUI
     * family whose private AppOps the vendor rows depend on.
     *
     * The verdicts come from the same `PermissionInspector` the bridge uses, so this cannot drift
     * from what the page shows; it only adds the device line the page has no room for.
     */
    private fun permissions(app: AlarmHubApp): String {
        val inspector = PermissionInspector(app)
        val rows = inspector.inspect()
        return buildString {
            appendLine(
                "device manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND} model=${Build.MODEL} " +
                    "sdk=${Build.VERSION.SDK_INT} miui=${PermissionInspector.isMiui()}",
            )
            for (row in rows) {
                appendLine(
                    "key=${row.key.wire} granted=${row.granted} applicable=${row.applicable} label=${row.label}",
                )
            }
            append(
                "applicableGranted=${rows.count { it.applicable && it.granted }}/" +
                    "${rows.count { it.applicable }}",
            )
        }
    }

    /**
     * M5 diagnostic: runs the real candidate-intent sequence for one row and reports which
     * candidates this device can even resolve.
     *
     * This is the only way to check 「跳转命中」 without a human tapping — and on a real Xiaomi it is
     * what turns "MIUI changed the page name" from a guess into a list of components that did or did
     * not resolve. It is also the honest way to verify the FR-5.3 fallback: when nothing resolves,
     * `opened=false fallback=true` says the app detail page was used.
     */
    private fun permissionJump(app: AlarmHubApp, intent: Intent): String {
        val wire = intent.getStringExtra("key")
            ?: return "ERROR 'permissionjump' needs --es key <permissionKey>"
        val key = PermissionKey.entries.firstOrNull { it.wire == wire }
            ?: return "ERROR unknown permission key '$wire'"

        val candidates = PermissionSettingsLauncher.candidates(app, key)
        val reachable = candidates.map { candidate -> candidate to resolves(app, candidate) }
        // `--ei breakAll 1` pretends every candidate failed, which is the only way to reach FR-5.3's
        // fallback on a device where the real candidates all exist.
        val attempted = if (intent.longExtra("breakAll", 0L) != 0L) emptyList() else candidates
        val opened = PermissionSettingsLauncher.openFirstOf(app, attempted)

        return buildString {
            appendLine("key=$wire opened=$opened fallback=${!opened} candidates=${attempted.size}")
            for ((candidate, resolved) in reachable) {
                appendLine("resolves=$resolved ${describe(candidate)}")
            }
            append("fallbackTarget=${describe(PermissionSettingsLauncher.appDetails(app))}")
        }
    }

    private fun resolves(app: AlarmHubApp, candidate: Intent): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.packageManager.resolveActivity(
                candidate,
                android.content.pm.PackageManager.ResolveInfoFlags.of(0),
            ) != null
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.resolveActivity(candidate, 0) != null
        }

    private fun describe(candidate: Intent): String =
        candidate.component?.flattenToShortString() ?: candidate.action ?: "?"

    /**
     * Everything M4's acceptance needs to know about the ring.
     *
     * Reports ring *state* rather than trying to observe sound: whether audio is audible is what the
     * screenshots and the audio log are for, and a receiver cannot hear anything.
     */
    private suspend fun ringState(app: AlarmHubApp): String {
        val zone = ZoneId.systemDefault()
        val state = com.alarmhub.app.ring.RingController.state.value
        val now = app.timeSource.nowMillis()
        if (state == null) {
            return "ringing=false now=${iso(now, zone)}"
        }
        val request = state.request
        return buildString {
            appendLine("ringing=true alarmId=${request.alarmId} isSnooze=${request.isSnooze}")
            appendLine("clock=${request.clockText()} label=${request.displayedLabel} group=${request.groupName}")
            appendLine("snoozeEnabled=${request.snoozeEnabled} snoozeUsed=${request.snoozeUsed} snoozeMax=${request.snoozeMaxCount} canSnooze=${request.canSnooze}")
            appendLine("autoStopMinutes=${request.autoStopMinutes} autoStopArmed=${state.autoStopArmed}")
            appendLine("audioPlaying=${state.audioPlaying} usedFallbackSound=${state.usedFallbackSound}")
            appendLine("deleteAfterRing=${request.deleteAfterRing} fadeInSeconds=${request.sound.fadeInSeconds} vibrate=${request.sound.vibrate}")
            append("alarmStreamVolume=${com.alarmhub.app.ring.media.AlarmAudioPlayer(app).alarmStreamVolume()}")
        }
    }

    /** Ends the ring the way the 关闭 button does, so the post-ring flow can be asserted. */
    private suspend fun dismissRing(app: AlarmHubApp): String {
        val request = com.alarmhub.app.ring.RingController.current()
            ?: return "ERROR nothing is ringing"
        com.alarmhub.app.ring.RingController.dismiss()
        // The ending applies to the database asynchronously; give it a moment before reporting.
        kotlinx.coroutines.delay(600)
        val surviving = app.repository.alarm(request.alarmId)
        return buildString {
            appendLine("dismissed alarmId=${request.alarmId}")
            appendLine("stillExists=${surviving != null} expired=${surviving?.expired}")
            append("ringing=${com.alarmhub.app.ring.RingController.state.value != null}")
        }
    }

    private suspend fun snoozeRing(app: AlarmHubApp): String {
        val request = com.alarmhub.app.ring.RingController.current()
            ?: return "ERROR nothing is ringing"
        val accepted = com.alarmhub.app.ring.RingController.snooze()
        kotlinx.coroutines.delay(600)
        val zone = ZoneId.systemDefault()
        val stored = app.repository.alarm(request.alarmId)
        return buildString {
            appendLine("snoozeAccepted=$accepted alarmId=${request.alarmId}")
            appendLine("snoozeCount=${stored?.snoozeCount} max=${stored?.snoozeMaxCount} expired=${stored?.expired}")
            append("snoozeAt=${app.repository.lastTriggerAt(request.alarmId)?.let { iso(it, zone) }}")
        }
    }

    /**
     * Rings an alarm immediately, skipping its schedule.
     *
     * The one thing M4 needs that M3 did not: a ring has to be observable now, not at the next
     * minute boundary. This goes through the same validation and the same controller as a real
     * trigger, so what it exercises is the production path minus the wait.
     */
    private suspend fun ringNow(app: AlarmHubApp, intent: Intent): String {
        val alarmId = intent.longExtra("alarm", 0L)
        if (alarmId == 0L) return "ERROR 'ringnow' needs --el alarm <id>"
        val isSnooze = intent.getStringExtra("as") == "snooze"
        val row = app.repository.alarmsWithGroups().firstOrNull { it.alarm.id == alarmId }
            ?: return "ERROR alarm $alarmId does not exist"

        val settings = app.repository.settings()
        val used = if (isSnooze) app.repository.snoozeCount(alarmId) else 0
        val request = com.alarmhub.app.ring.RingRequest.from(
            row = row,
            settings = settings,
            snoozeUsed = used,
            isSnooze = isSnooze,
            alarmSound = com.alarmhub.app.ring.media.AlarmSound(
                ringtoneUri = row.alarm.ringtoneUri ?: settings.defaultRingtoneUri,
                vibrate = row.alarm.vibrate,
                fadeInSeconds = row.alarm.fadeInSeconds,
            ),
        )

        com.alarmhub.app.ring.RingController.attach(app)
        val begun = com.alarmhub.app.ring.RingController.begin(request)
        if (begun && !isSnooze) app.repository.clearSnoozeCount(alarmId)
        if (begun) {
            com.alarmhub.app.ring.RingForegroundService.start(app)
            runCatching { app.startActivity(com.alarmhub.app.ring.RingActivity.intent(app)) }
        }

        return buildString {
            appendLine("ringStarted=$begun alarmId=$alarmId isSnooze=$isSnooze")
            appendLine("autoStopMinutes=${request.autoStopMinutes} snoozeMax=${request.snoozeMaxCount}")
            append("deleteAfterRing=${request.deleteAfterRing}")
        }
    }

    /**
     * Replays the main trigger a scheduler worker would have registered *before* the current state
     * was applied.
     *
     * This is the only way to exercise PRD §5.4's secondary validation from outside: once a group is
     * paused, a correct scheduler immediately re-registers the alarm for a later day, so no trigger
     * fires during the pause at all and there is nothing to validate. In the field the trigger that
     * needs suppressing is exactly this one — registered earlier, still alive in AlarmManager
     * (cancellation races, or a pending intent that survived a reboot), and now stale.
     */
    private suspend fun trigger(app: AlarmHubApp, intent: Intent): String {
        val alarmId = intent.longExtra("alarm", 0L)
        if (alarmId == 0L) return "ERROR 'trigger' needs --el alarm <id>"

        // The instant can be given explicitly, because the scenario that needs this most — "a group
        // was just disabled" — has by definition already cleared the stored registration. Falling
        // back to the stored value covers the case where it is still there.
        val scheduledAt = intent.longExtra("at", app.repository.lastTriggerAt(alarmId) ?: 0L)
        if (scheduledAt == 0L) {
            return "ERROR alarm $alarmId has no trigger instant to replay (pass --el at <epochMillis>)"
        }

        val zone = ZoneId.systemDefault()
        val replay = Intent(app, com.alarmhub.app.alarm.AlarmReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_TRIGGER
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_KIND, TriggerKind.MAIN.name)
        }
        app.sendBroadcast(replay)

        return "replayed MAIN trigger for alarm=$alarmId scheduledAt=${iso(scheduledAt, zone)} ($scheduledAt)"
    }

    /**
     * Replaces any previous test group with a fresh one holding a single alarm.
     *
     * [forceOnce] makes the alarm a one-off regardless of the `type` extra, which is what M4's
     * delete-after-ringing and expired scenarios need: those rules only apply to `ONCE` alarms
     * (PRD FR-3.5).
     */
    private suspend fun inject(app: AlarmHubApp, intent: Intent, forceOnce: Boolean = false): String {
        val repository = app.repository
        val atMinutes = intent.longExtra("at", 1L)
        val kind = if (forceOnce) "ONCE" else (intent.getStringExtra("type") ?: "DAILY")
        val repeatType = RepeatType.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) }
            ?: return "ERROR unknown type '$kind'"

        removeTestGroup(repository)

        val now = app.timeSource.nowMillis()
        val zone = ZoneId.systemDefault()
        val ringAt = now + atMinutes * 60_000L
        val local = Instant.ofEpochMilli(ringAt).atZone(zone)

        val groupId = repository.saveGroup(
            GroupEntity(
                name = TEST_GROUP_NAME,
                color = 0xff00c2a8.toInt(),
                sortOrder = 90,
                isSystem = false,
            ).toDomain(),
        )

        val saved = repository.saveAlarm(
            Alarm(
                id = 0L,
                groupId = groupId,
                hour = local.hour,
                minute = local.minute,
                label = "M3 测试 ($kind)",
                repeatType = repeatType,
                repeatDays = if (repeatType == RepeatType.WEEKLY) 0b111_1111 else 0,
                onceDate = if (repeatType == RepeatType.ONCE) local.toLocalDate().toString() else null,
                /*
                 * Test knobs. `autoStop` exists because the acceptance has to watch a ring stop by
                 * itself, and the production default is 10 minutes; `snoozeMinutes` exists so a snooze
                 * can be observed within one test run instead of ten minutes later. Both only affect
                 * alarms this debug command creates.
                 */
                autoStopMinutes = intent.longExtra("autoStop", 10L).toInt(),
                snoozeMinutes = intent.longExtra("snoozeMinutes", 10L).toInt(),
                snoozeMaxCount = intent.longExtra("snoozeMax", 3L).toInt(),
                snoozeEnabled = intent.longExtra("snoozeEnabled", 1L) != 0L,
                deleteAfterRing = intent.longExtra("deleteAfterRing", 0L) != 0L,
                fadeInSeconds = intent.longExtra("fadeIn", 5L).toInt(),
                vibrate = intent.longExtra("vibrate", 1L) != 0L,
            ),
        )

        app.scheduler.recomputeAll()

        val registeredAt = repository.lastTriggerAt(saved.id)
        return buildString {
            appendLine("injected alarmId=${saved.id} groupId=$groupId type=$repeatType")
            appendLine("now=${iso(now, zone)} ($now)")
            appendLine("ringAt=${iso(ringAt, zone)}")
            appendLine("registeredAtMillis=${registeredAt ?: 0L}")
            append("lastTriggerAt=${registeredAt?.let { iso(it, zone) }}")
        }
    }

    private suspend fun pause(app: AlarmHubApp, intent: Intent): String {
        val groupId = intent.longExtra("group", BuiltInGroups.UNGROUPED_ID)
        val days = intent.longExtra("days", 1L).toInt()
        val now = app.timeSource.nowMillis()
        val zone = ZoneId.systemDefault()

        val alarms = app.repository.alarms().filter { it.groupId == groupId }
        val resolver = PauseResolver(app.holidayCalendar)
        val result = resolver.previewForGroup(alarms, app.repository.group(groupId), days, now, zone)

        app.repository.pauseGroup(groupId, result.resumeAt, now)
        app.scheduler.recomputeAll()

        return buildString {
            appendLine("paused group=$groupId days=$days now=${iso(now, zone)}")
            appendLine("skippedRingDays=${result.skippedRingDays.map { iso(it, zone) }}")
            append("resumeAt=${iso(result.resumeAt, zone)}")
        }
    }

    /**
     * Sets the resume instant directly instead of converting 「跳过 N 天」.
     *
     * The conversion can only ever land on a midnight (PRD §5.3 step 3), so a test that wants the
     * pause to expire *within the next minute or two* — without waiting for midnight or moving the
     * device clock — has to state the instant. Everything downstream (the PAUSE trigger, the
     * secondary validation, the recompute) is the same code path.
     */
    private suspend fun pauseUntil(app: AlarmHubApp, intent: Intent): String {
        val groupId = intent.longExtra("group", BuiltInGroups.WORKDAY_ID)
        val afterSeconds = intent.longExtra("afterSeconds", 120L)
        val now = app.timeSource.nowMillis()
        val zone = ZoneId.systemDefault()
        val resumeAt = now + afterSeconds * 1000L

        app.repository.pauseGroup(groupId, resumeAt, now)
        app.scheduler.recomputeAll()

        return buildString {
            appendLine("paused group=$groupId until=${iso(resumeAt, zone)} (now=${iso(now, zone)}, +${afterSeconds}s)")
            append("resumeAt=${iso(resumeAt, zone)}")
        }
    }

    private suspend fun resume(app: AlarmHubApp): String {
        val groupId = BuiltInGroups.WORKDAY_ID
        app.repository.resumeGroup(groupId)
        app.repository.setGroupPermanentDisabled(groupId, false)
        app.scheduler.recomputeAll()
        return "resumed group=$groupId"
    }

    private suspend fun disable(app: AlarmHubApp, intent: Intent): String {
        val groupId = intent.longExtra("group", BuiltInGroups.WORKDAY_ID)
        app.repository.setGroupPermanentDisabled(groupId, true)
        app.scheduler.recomputeAll()
        return "permanently disabled group=$groupId"
    }

    private suspend fun enable(app: AlarmHubApp): String {
        val groupId = BuiltInGroups.WORKDAY_ID
        app.repository.setGroupPermanentDisabled(groupId, false)
        app.scheduler.recomputeAll()
        return "enabled group=$groupId"
    }

    private suspend fun wipe(app: AlarmHubApp): String {
        removeTestGroup(app.repository)
        // Also clear any pause or permanent disable the previous command left behind. Without this,
        // one scenario's state leaks into the next (a group disabled here is still disabled there),
        // which is exactly the kind of cross-test coupling that produces phantom failures.
        for (group in app.repository.groups()) {
            app.repository.resumeGroup(group.id)
            app.repository.setGroupPermanentDisabled(group.id, false)
        }
        app.scheduler.recomputeAll()
        return "removed the test group; cleared pause/disable state on every group"
    }

    private suspend fun list(app: AlarmHubApp): String {
        val zone = ZoneId.systemDefault()
        val rows = app.repository.alarmsWithGroups()
        return buildString {
            appendLine("now=${iso(app.timeSource.nowMillis(), zone)} zone=$zone")
            for (group in app.repository.groups()) {
                appendLine(
                    "group id=${group.id} '${group.name}' pauseUntil=${group.pauseUntil?.let { iso(it, zone) }} " +
                        "permanentDisabled=${group.permanentDisabled}",
                )
            }
            if (rows.isEmpty()) appendLine("(no alarms)")
            for (row in rows) {
                val a = row.alarm
                appendLine(
                    "alarm id=${a.id} group=${a.groupId} ${"%02d:%02d".format(a.hour, a.minute)} ${a.repeatType} " +
                        "enabled=${a.enabled} pauseUntil=${a.pauseUntil?.let { iso(it, zone) }} expired=${a.expired}",
                )
            }
            append("alarms=${rows.size}")
        }
    }

    /**
     * Reads back what the scheduler persisted: the instant each alarm's main trigger is registered
     * for. Compared against `dumpsys alarm` this is what shows the registration is real and not just
     * a value the app wrote down.
     */
    private suspend fun registered(app: AlarmHubApp): String {
        val zone = ZoneId.systemDefault()
        val rows = app.repository.alarmsWithGroups()
        return buildString {
            appendLine("now=${iso(app.timeSource.nowMillis(), zone)}")
            if (rows.isEmpty()) appendLine("(no alarms)")
            for (row in rows) {
                val at = app.repository.lastTriggerAt(row.alarm.id)
                appendLine(
                    "alarm id=${row.alarm.id} registeredAt=${at?.let { iso(it, zone) }} registeredAtMillis=${at ?: 0L} " +
                        "reqMain=${AlarmScheduler.requestCodeFor(row.alarm.id, TriggerKind.MAIN)} " +
                        "reqPre=${AlarmScheduler.requestCodeFor(row.alarm.id, TriggerKind.PRE)} " +
                        "reqPause=${AlarmScheduler.requestCodeFor(row.alarm.id, TriggerKind.PAUSE)}",
                )
            }
            append("alarms=${rows.size}")
        }
    }

    private suspend fun removeTestGroup(repository: AlarmRepository) {
        val test = repository.groups().firstOrNull { it.name == TEST_GROUP_NAME } ?: return
        // The alarms first, then the group: the foreign key is RESTRICT, so a group with alarms in it
        // cannot be deleted (PRD FR-1.4 relies on that).
        for (alarm in repository.alarms().filter { it.groupId == test.id }) {
            repository.deleteAlarm(alarm.id)
        }
        repository.deleteGroup(test.id, BuiltInGroups.UNGROUPED_ID)
    }

    /**
     * Reads a numeric extra regardless of the integer type adb chose for it.
     *
     * adb's `--ei k v` stores an **Int** and `--el k v` stores a **Long**. `getLongExtra` does not
     * coerce: asked for an Int extra it returns the *fallback*, silently. That is exactly how this
     * helper first went wrong — `--ei snoozeMax 2` was reported as the default 3 while `--ei
     * snoozeMinutes 1` looked like it had worked (it had not; the resulting timestamp merely happened
     * to land where a one-minute snooze would have).
     *
     * [fallback] is also used when the value is 0 and a non-zero default exists, because `--el group 0`
     * is what adb produces when a shell variable expanded to empty, and 0 is never a valid id.
     */
    private fun Intent.longExtra(key: String, fallback: Long): Long {
        if (!hasExtra(key)) return fallback
        val raw: Long? = when (val value = extras?.get(key)) {
            is Long -> value
            is Int -> value.toLong()
            is Short -> value.toLong()
            is Byte -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
        val resolved = raw ?: fallback
        return if (resolved == 0L && fallback != 0L) fallback else resolved
    }

    private fun iso(epochMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toString()

    companion object {
        private const val TAG = "AlarmHub/Debug"
        const val ACTION_COMMAND = "com.alarmhub.app.debug.COMMAND"

        /** Where the last command's result is left for the acceptance script to read. */
        const val RESULT_FILE = "debug-result.txt"

        private const val TEST_GROUP_NAME = "M3 TEST"
    }
}
