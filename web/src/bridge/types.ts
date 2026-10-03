/**
 * The cross-language contract.
 *
 * This file is the single interface between the H5 layer and the native Kotlin layer
 * (docs/DEVELOPMENT-PLAN.md §4). It is frozen at M1 and implemented by:
 *
 *   - `mock.ts`      — this milestone, so the UI can run on fake data
 *   - `AlarmHubPlugin.kt` — at M6, with no change to any page component
 *
 * Discipline that keeps the two implementations from drifting:
 *   1. `previewPause` is a PURE calculation and must not persist anything. The "resume at"
 *      preview in the pause sheet comes from here, so the front end never re-implements the
 *      "skip N ring days" algorithm from PRD §5.3.
 *   2. Every timestamp the UI displays is computed natively and passed in. The front end
 *      formats, it does not derive.
 */

// ---------------------------------------------------------------------------------------
// Enums and unions
// ---------------------------------------------------------------------------------------

/** Mirrors PRD §5.2. */
export type RepeatType = 'ONCE' | 'DAILY' | 'WEEKLY' | 'WORKDAY' | 'HOLIDAY'

/** Whether a pause applies to a whole group or to one alarm. */
export type PauseScope = 'group' | 'alarm'

/** Why an alarm will or will not ring next. Drives how a row is rendered. */
export type AlarmStatus =
  /** Enabled and has a next ring time. */
  | 'scheduled'
  /** Temporarily paused; `pauseUntil` is set. */
  | 'paused'
  /** Switched off by the user (permanently). */
  | 'disabled'
  /** A one-off alarm that already rang and was not deleted (PRD §5.6). */
  | 'expired'
  /** Enabled, but its repeat rule matches no day (e.g. WEEKLY with no weekday set). */
  | 'never'

export type TimeFormat = '12' | '24'
export type ThemeMode = 'system' | 'dark' | 'light'
export type VolumeKeyAction = 'snooze' | 'dismiss'

export type PermissionKey =
  | 'exactAlarm'
  | 'notifications'
  | 'fullScreenIntent'
  | 'autostart'
  | 'batteryUnrestricted'
  | 'backgroundPopup'
  | 'lockScreenDisplay'

// ---------------------------------------------------------------------------------------
// Entities
// ---------------------------------------------------------------------------------------

export interface Group {
  id: number
  name: string
  /** ARGB packed into a number, e.g. 0xff4c9dff. */
  color: number
  sortOrder: number
  /** Built-in groups (未分组 / 工作日 / 节假日) cannot be deleted. */
  isSystem: boolean
  /** Indefinite off; must be undone by hand. Mutually exclusive with `pauseUntil`. */
  permanentDisabled: boolean
  /** Resume moment for a temporary pause, epoch millis. `null` = not paused. */
  pauseUntil: number | null
  /** When the pause was applied, so the UI can say "已暂停 3 小时". */
  pausedAt: number | null
  /** How many alarms belong to this group — so the delete prompt can warn about scope. */
  alarmCount: number
}

export interface Alarm {
  id: number
  groupId: number
  hour: number
  minute: number
  /** Free text; empty is rendered as "闹钟". */
  label: string
  repeatType: RepeatType
  /** 7-bit mask, bit0 = Monday … bit6 = Sunday. Only meaningful for WEEKLY. */
  repeatDays: number
  /** `yyyy-MM-dd`, only for ONCE. Resolved to a concrete date on save. */
  onceDate: string | null
  /** `null` means "use the global default ringtone". */
  ringtoneUri: string | null
  ringtoneName: string | null
  vibrate: boolean
  /** Seconds to ramp from silence to full volume. 0 = no fade. */
  fadeInSeconds: number
  snoozeEnabled: boolean
  snoozeMinutes: number
  snoozeMaxCount: number
  /** Stop ringing after this long. 0 = ring until dismissed. */
  autoStopMinutes: number
  /** Only meaningful for ONCE (PRD FR-3.5). */
  deleteAfterRing: boolean
  enabled: boolean
  permanentDisabled: boolean
  pauseUntil: number | null
  /** Rang already, kept because `deleteAfterRing` was false. */
  expired: boolean
}

/**
 * What the list actually renders. Everything derived is computed natively: the front end
 * formats `nextRingAt`, it never recomputes it (contract rule 2).
 */
export interface AlarmView extends Alarm {
  groupName: string
  groupColor: number
  /** `null` when nothing is scheduled. */
  nextRingAt: number | null
  status: AlarmStatus
}

export interface Settings {
  /** PRD FR-3.1 — the switch this whole feature was requested for. Factory default: true. */
  defaultDeleteOnceAfterRing: boolean
  /** PRD FR-2.3 — how many ring days "pause" skips by default. */
  defaultPauseDays: number
  defaultSnoozeMinutes: number
  defaultSnoozeMaxCount: number
  /**
   * Whether 贪睡 is offered at all (M8).
   *
   * A **global** switch, not a per-alarm default: the editor already has a per-alarm one, and a user
   * who never uses snooze should not have to turn it off alarm by alarm. When false the ring page
   * removes the 贪睡 button and the volume key acts as 关闭.
   */
  snoozeEnabled: boolean
  defaultAutoStopMinutes: number
  defaultRingtoneUri: string | null
  defaultRingtoneName: string | null
  defaultFadeInSeconds: number
  timeFormat: TimeFormat
  theme: ThemeMode
  volumeKeyAction: VolumeKeyAction
  /** Whether the first-run permission walkthrough has been shown. */
  permissionCheckDone: boolean
}

export interface PermissionItem {
  key: PermissionKey
  label: string
  /** What breaks if this is missing, in the user's words. */
  description: string
  granted: boolean
  /**
   * False on devices where the check does not exist (e.g. the MIUI-specific entries on a
   * stock Android device). Rendered as "不适用" instead of a red cross.
   */
  applicable: boolean
}

// ---------------------------------------------------------------------------------------
// Requests and derived results
// ---------------------------------------------------------------------------------------

/** Ask for a pause either as "skip N ring days" or as an explicit resume moment. */
export type PauseRequest =
  | { scope: PauseScope; id: number; days: number }
  | { scope: PauseScope; id: number; until: number }

export interface PausePreview {
  /** The absolute resume moment the request resolves to, epoch millis. */
  resumeAt: number
  /**
   * The ring days that would be skipped, epoch millis at each day's midnight. Used to show
   * the user exactly which mornings they are silencing.
   */
  skippedRingDays: number[]
  /** First ring after the pause ends. `null` if nothing follows. */
  nextRingAt: number | null
}

export interface HolidayDataInfo {
  years: number[]
  source: string
  /** True when the bundled data does not cover the current year and we fell back to Mon–Fri. */
  degraded: boolean
}

export interface RingtonePickResult {
  /** `null` when the user cancelled or reset to the default. */
  uri: string | null
  name: string | null
  cancelled: boolean
}

export interface PingResult {
  version: string
  sdkInt: number
  buildType: string
  appId: string
  kotlinVersion: string
  device: string
}

// ---------------------------------------------------------------------------------------
// The bridge
// ---------------------------------------------------------------------------------------

export interface AlarmHubBridge {
  // ---- meta ----
  /** M0 integration probe; kept as a cheap health check from the settings page. */
  ping(): Promise<PingResult>
  getHolidayDataInfo(): Promise<HolidayDataInfo>

  // ---- groups ----
  listGroups(): Promise<Group[]>
  saveGroup(group: Partial<Group> & { name: string }): Promise<Group>
  /** Deleting a group never deletes its alarms; they move to `moveAlarmsTo`. */
  deleteGroup(o: { id: number; moveAlarmsTo: number }): Promise<{ movedCount: number }>
  reorderGroups(o: { orderedIds: number[] }): Promise<void>

  // ---- alarms ----
  listAlarms(): Promise<AlarmView[]>
  getAlarm(o: { id: number }): Promise<AlarmView>
  saveAlarm(alarm: Partial<Alarm>): Promise<AlarmView>
  deleteAlarm(o: { id: number }): Promise<void>
  /**
   * Deletes several alarms in one round trip.
   *
   * Added after M6, for the list page's long-press multi-select. Looping `deleteAlarm` would have
   * worked, but every write re-registers the whole schedule with `AlarmManager`
   * (docs/DEVELOPMENT-PLAN.md §M6.4), so deleting twenty alarms would have swept the table twenty
   * times. Deleting one alarm at a time is also not atomic: a failure halfway leaves the user with
   * the list in a state they did not ask for and no way to tell which half went through.
   */
  deleteAlarms(o: { ids: number[] }): Promise<{ affected: number }>
  setAlarmEnabled(o: { id: number; enabled: boolean }): Promise<void>
  batchUpdateAlarms(o: {
    ids: number[]
    patch: Partial<Alarm>
  }): Promise<{ affected: number }>

  // ---- pause / disable ----
  /** Pure; persists nothing. Powers the resume-time preview in the pause sheet. */
  previewPause(o: PauseRequest): Promise<PausePreview>
  /**
   * "When would this ring?" for a draft that has not been saved yet.
   *
   * Added in M8, for the editor's relative-time line (「6 小时 35 分钟后响铃」) — borrowed from MIUI's
   * own clock, where it sits under the title. It is a native round trip rather than a calculation on
   * this side because the repeat rules, the holiday calendar and the pause floor are **business
   * rules**, and TECH-STACK §3 keeps those in `domain/`. The WebView has no next-ring logic at all,
   * which is exactly why it could show `nextRingAt` for a saved alarm (native computes it) but had no
   * way to show one for a draft.
   *
   * Pure, like `previewPause`: it computes and returns, and writes nothing.
   */
  previewNextRing(o: Partial<Alarm>): Promise<{ nextRingAt: number | null }>
  pause(o: PauseRequest): Promise<{ resumeAt: number }>
  resume(o: { scope: PauseScope; id: number }): Promise<void>
  setPermanentDisabled(o: {
    scope: PauseScope
    id: number
    disabled: boolean
  }): Promise<void>

  // ---- settings ----
  getSettings(): Promise<Settings>
  updateSettings(patch: Partial<Settings>): Promise<Settings>
  /** Feeds the confirmation prompt on the bulk-convert action (PRD FR-3.4). */
  countOnceAlarms(): Promise<{ total: number; alreadyMarked: number }>
  bulkSetDeleteAfterRing(): Promise<{ affected: number }>

  // ---- ringtone ----
  /**
   * Opens the platform ringtone chooser.
   *
   * Added during M1b: the first draft of this contract had no way to pick a ringtone at all.
   * It has to be a native round trip because `RingtoneManager.ACTION_RINGTONE_PICKER` is the
   * only way to choose one without requesting READ_MEDIA_AUDIO (TECH-STACK §4.6).
   */
  pickRingtone(o: { current: string | null }): Promise<RingtonePickResult>

  // ---- permissions ----
  getPermissionStatus(): Promise<PermissionItem[]>
  openPermissionSetting(o: {
    key: PermissionKey
  }): Promise<{ opened: boolean; fallback: boolean }>
}
