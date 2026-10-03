/**
 * Single reactive store for the whole UI.
 *
 * Rule 3 of the architecture invariants: the native database is the only source of truth, so
 * this holds a *cache* of the last fetch and re-reads after every write. Nothing here derives
 * a schedule — `nextRingAt` / `status` always come from the bridge.
 */

import { computed, reactive } from 'vue'
import { bridge } from '../bridge'
import type {
  Alarm,
  AlarmView,
  Group,
  PauseRequest,
  PermissionItem,
  PermissionKey,
  Settings,
  ThemeMode,
} from '../bridge/types'
import { back, currentEntry, onEntryChange, pushOverlay } from './nav'

interface State {
  phase: 'loading' | 'ready' | 'error'
  error: string | null
  groups: Group[]
  alarms: AlarmView[]
  settings: Settings | null
  /** Ticks so countdowns age without every component owning a timer. */
  now: number
  /** True while a write is in flight, so the UI can disable the control that started it. */
  busy: boolean
}

export const state = reactive<State>({
  phase: 'loading',
  error: null,
  groups: [],
  alarms: [],
  settings: null,
  now: Date.now(),
  busy: false,
})

/** One group's worth of the list. */
export interface Section {
  group: Group
  alarms: AlarmView[]
  paused: boolean
  disabled: boolean
  /** Earliest ring among this group's alarms, ignoring paused/disabled ones. */
  nextRingAt: number | null
}

const sortByTime = (a: AlarmView, b: AlarmView) => a.hour * 60 + a.minute - (b.hour * 60 + b.minute)

export const sections = computed<Section[]>(() =>
  [...state.groups]
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((group) => {
      const alarms = state.alarms
        .filter((a) => a.groupId === group.id && a.status !== 'expired')
        .sort(sortByTime)

      const paused = group.pauseUntil !== null && group.pauseUntil > state.now
      const disabled = group.permanentDisabled

      const nextRingAt = alarms.reduce<number | null>(
        (min, a) => (a.nextRingAt !== null && (min === null || a.nextRingAt < min) ? a.nextRingAt : min),
        null
      )

      return { group, alarms, paused, disabled, nextRingAt }
    })
)

export const expiredAlarms = computed(() =>
  state.alarms.filter((a) => a.status === 'expired').sort(sortByTime)
)

/** Drives the banner at the top of the list. */
export const pausedGroups = computed(() => sections.value.filter((s) => s.paused || s.disabled))

/** The single nearest ring across everything, for the header. */
export const nextRingOverall = computed<number | null>(() =>
  state.alarms.reduce<number | null>(
    (min, a) => (a.nextRingAt !== null && (min === null || a.nextRingAt < min) ? a.nextRingAt : min),
    null
  )
)

// ---------------------------------------------------------------------------------------
// Loading
// ---------------------------------------------------------------------------------------

export async function load(): Promise<void> {
  state.phase = 'loading'
  state.error = null
  try {
    const [groups, alarms, settings] = await Promise.all([
      bridge.listGroups(),
      bridge.listAlarms(),
      bridge.getSettings(),
    ])
    state.groups = groups
    state.alarms = alarms
    state.settings = settings
    state.now = Date.now()
    state.phase = 'ready'
    applyTheme(settings.theme)
  } catch (e) {
    state.error = e instanceof Error ? e.message : String(e)
    state.phase = 'error'
  }
  // Deliberately after the try/catch and never awaited into `state.phase`: a permission probe that
  // fails must not turn the whole app into 「载入失败」. It only feeds the banner and the 体检页.
  await loadPermissionStatus()
  watchOnReturn()
}

/** Re-read after a write. Cheap: the dataset is at most a few hundred rows. */
export async function refreshAlarms(): Promise<void> {
  state.alarms = await bridge.listAlarms()
  state.groups = await bridge.listGroups()
  state.now = Date.now()
}

async function write<T>(fn: () => Promise<T>): Promise<T> {
  state.busy = true
  try {
    const result = await fn()
    await refreshAlarms()
    return result
  } finally {
    state.busy = false
  }
}

// ---------------------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------------------

export const setAlarmEnabled = (id: number, enabled: boolean) =>
  write(() => bridge.setAlarmEnabled({ id, enabled }))

export const deleteAlarm = (id: number) => write(() => bridge.deleteAlarm({ id }))

/** Loads one alarm for the editor. Goes through the bridge so derived fields stay native. */
export function fetchAlarm(id: number): Promise<AlarmView> {
  return bridge.getAlarm({ id })
}

/** Persists the editor's form and repopulates the list cache. */
export function saveAlarm(patch: Partial<Alarm>): Promise<AlarmView> {
  return write(() => bridge.saveAlarm(patch))
}

/**
 * Drops every expired one-off alarm in one pass. PRD FR-3.7 keeps them visible so nothing
 * disappears behind the user's back; this is the manual cleanup for that pile.
 */
export async function clearExpired(): Promise<number> {
  const ids = state.alarms.filter((a) => a.status === 'expired').map((a) => a.id)
  if (ids.length === 0) return 0
  await write(async () => {
    for (const id of ids) await bridge.deleteAlarm({ id })
  })
  return ids.length
}

export const pauseTarget = (req: PauseRequest) => write(() => bridge.pause(req))

export const resumeTarget = (scope: 'group' | 'alarm', id: number) =>
  write(() => bridge.resume({ scope, id }))

export const setPermanentDisabled = (
  scope: 'group' | 'alarm',
  id: number,
  disabled: boolean
) => write(() => bridge.setPermanentDisabled({ scope, id, disabled }))

export const previewPause = (req: PauseRequest) => bridge.previewPause(req)

export async function updateSettings(patch: Partial<Settings>): Promise<void> {
  state.settings = await bridge.updateSettings(patch)
  if (patch.theme) applyTheme(patch.theme)
}

// ---------------------------------------------------------------------------------------
// Groups
// ---------------------------------------------------------------------------------------

export const saveGroup = (group: Partial<Group> & { name: string }) =>
  write(() => bridge.saveGroup(group))

export const deleteGroup = (id: number, moveAlarmsTo: number) =>
  write(() => bridge.deleteGroup({ id, moveAlarmsTo }))

export const reorderGroups = (orderedIds: number[]) =>
  write(() => bridge.reorderGroups({ orderedIds }))

// ---------------------------------------------------------------------------------------
// Bulk one-off cleanup (PRD FR-3.4) — read-only probes, so they skip `write()`
// ---------------------------------------------------------------------------------------

export const countOnceAlarms = () => bridge.countOnceAlarms()
export const bulkSetDeleteAfterRing = () => write(() => bridge.bulkSetDeleteAfterRing())

/**
 * Which years the built-in 节假日 table covers, and whether it still covers the current one (PRD
 * FR-6.2 / FR-6.3).
 *
 * Read-only, so it skips `write()`. It is a readout rather than a control: the data ships inside the
 * APK (`assets/holidays.json`), so the only action available to a user is "install a build with newer
 * data" — which is why the settings page shows it as a value, and only raises its voice when the
 * current year is not covered at all (the degrading case FR-6.3 asks to be surfaced).
 */
export const fetchHolidayDataInfo = () => bridge.getHolidayDataInfo()

// ---------------------------------------------------------------------------------------
// Multi-select — long-press a row to pick several alarms
// ---------------------------------------------------------------------------------------

/**
 * Which rows are currently ticked, and whether the list is in selection mode.
 *
 * Lives here rather than in the list page because three components need it: the page draws the
 * header and the bottom bar, `GroupSection` forwards it, and `AlarmRow` renders the tick — and the
 * rows live under a `v-for` that must not own the state.
 */
export const selection = reactive({
  active: false,
  ids: new Set<number>(),
})

/** Long-press on a row starts a selection with that row already ticked. */
export function enterSelection(id: number): void {
  selection.active = true
  selection.ids.clear()
  selection.ids.add(id)
  // A level the system back gesture must be able to leave. The user's annotation on this screen was
  // 「左滑也可以起到返回作用」, so entering selection mode pushes a history entry — see nav.ts.
  pushOverlay()
}

export function toggleSelection(id: number): void {
  if (selection.ids.has(id)) selection.ids.delete(id)
  else selection.ids.add(id)
  // Note: deliberately does NOT leave selection mode when the last row is unticked. Tapping the last
  // ticked row is a normal thing to do — you picked one row by mistake — and having the whole mode
  // collapse under the finger is disorienting. The mode ends on ✕, on a successful delete, or never.
}

/**
 * Leaves selection mode.
 *
 * Goes through the navigation stack when there is an entry for it, so ✕, the delete button and the
 * system gesture all end up in the same place. Clearing the state directly here would leave a stale
 * history entry behind, and the next back gesture would appear to do nothing.
 */
export function exitSelection(): void {
  if (currentEntry().selecting) back()
  else {
    selection.active = false
    selection.ids.clear()
  }
}

/** Keeps the selection state in step with whatever level the history restored. */
onEntryChange((entry) => {
  if (!entry.selecting && selection.active) {
    selection.active = false
    selection.ids.clear()
  }
})

/** Ticks everything, or clears everything when it is already all ticked. Stays in selection mode. */
export function toggleSelectAll(ids: number[]): void {
  const allTicked = ids.length > 0 && ids.every((id) => selection.ids.has(id))
  selection.ids.clear()
  if (!allTicked) ids.forEach((id) => selection.ids.add(id))
}

/**
 * Deletes everything ticked and leaves selection mode.
 *
 * One bridge call, not one per alarm: each write re-registers the whole schedule, so a loop would
 * sweep the table N times, and a failure halfway would leave half the rows gone with no way to say
 * which half. Errors propagate so the caller can show them — a delete that silently does nothing is
 * the failure mode that wasted the most time in this project.
 */
export async function deleteSelected(): Promise<number> {
  const ids = [...selection.ids]
  if (ids.length === 0) return 0
  const { affected } = await write(() => bridge.deleteAlarms({ ids }))
  exitSelection()
  return affected
}

/**
 * Ticks or unticks several alarms in one bridge call.
 *
 * Same reasoning as `deleteSelected`: `batchUpdateAlarms` exists precisely so a batch is **one** write,
 * and therefore one `recomputeAll()` rather than one per alarm (docs/DEVELOPMENT-PLAN.md §M6.4).
 * Looping `setAlarmEnabled` would have worked, and would have swept the whole schedule table N times —
 * which is exactly the argument that added `deleteAlarms` for multi-select delete.
 *
 * Deliberately does **not** leave selection mode: the switches in the list are the feedback that the
 * batch landed, so the user stays where they can see it. Errors propagate for the same reason as the
 * delete path — a batch that silently does nothing is the failure mode that cost the most time here.
 */
export async function setSelectedEnabled(enabled: boolean): Promise<number> {
  const ids = [...selection.ids]
  if (ids.length === 0) return 0
  const { affected } = await write(() => bridge.batchUpdateAlarms({ ids, patch: { enabled } }))
  return affected
}

/**
 * Asks native when a *draft* would ring, for the editor's relative-time line.
 *
 * The caller owns the ordering guard (see `AlarmEditorPage`'s `previewGen`): during a fast spin the
 * responses arrive out of order, and without a token the line would settle on an earlier value while
 * the wheel shows a later one.
 */
export async function previewNextRing(draft: Partial<Alarm>): Promise<number | null> {
  try {
    const { nextRingAt } = await bridge.previewNextRing(draft)
    return nextRingAt
  } catch {
    // A preview is decoration: if it cannot be computed, show nothing rather than an error.
    return null
  }
}

// ---------------------------------------------------------------------------------------
// Permissions (M5)
// ---------------------------------------------------------------------------------------

/**
 * The last permission probe, shared by the 体检页 and by the banner on the list page.
 *
 * Living in the store rather than in the page is what keeps the two from disagreeing: there is one
 * probe, one verdict, and one place a refresh can come from.
 */
export const permissions = reactive<{
  items: PermissionItem[]
  loaded: boolean
  error: string | null
}>({ items: [], loaded: false, error: null })

/**
 * PRD FR-5.4 — the two permissions whose absence makes the alarm untrustworthy, and therefore the
 * two that earn a persistent banner. The other five are worth showing on the 体检页 but are not
 * worth a permanent red strip on the main screen.
 */
const CRITICAL_PERMISSIONS: PermissionKey[] = ['exactAlarm', 'notifications']

/** Applicable items that are switched off. `applicable: false` rows are excluded on purpose. */
export const missingPermissions = computed(() =>
  permissions.items.filter((item) => item.applicable && !item.granted)
)

/** The subset of {@link missingPermissions} that the main-screen banner is about. */
export const criticalMissingPermissions = computed(() =>
  permissions.items.filter(
    (item) => item.applicable && !item.granted && CRITICAL_PERMISSIONS.includes(item.key)
  )
)

export async function loadPermissionStatus(): Promise<void> {
  try {
    permissions.items = await bridge.getPermissionStatus()
    permissions.error = null
  } catch (e) {
    permissions.error = e instanceof Error ? e.message : String(e)
  } finally {
    permissions.loaded = true
  }
}

/**
 * Re-read on returning to the foreground: the permission probe **and the alarm list**.
 *
 * Two different reasons, one event:
 *
 * 1. **Permissions.** `openPermissionSetting` resolves as soon as the settings page has been
 *    *launched* — it cannot wait for the user to come back, or a jump that silently failed would hang
 *    the button forever. So the refresh has to be triggered by the return instead.
 * 2. **The alarm list.** Rule 3 of the architecture invariants says this store is a *cache* of the
 *    native database, and **native code changes that database while this page is in the background**:
 *    a ring that is dismissed deletes or expires its alarm (PRD §5.6 / FR-3.6), a notification action
 *    snoozes one, and the expiry sweep re-arms others. Without a re-read, a row that no longer exists
 *    stays on screen until the app is restarted — which is exactly what a user reported:
 *    「响完之后那条闹钟还在，没删除，重启之后才消失」. The write was correct; the view was stale.
 *
 * Measured on Android 16 (emulator) before relying on it: `document.visibilityState` goes
 * `visible → hidden → visible` across a background/foreground round trip and `visibilitychange` fires
 * each time, so this is a working hook rather than an assumption.
 *
 * If a WebView build ever stops firing it, nothing breaks: the 体检页 still has its 「刷新」 button,
 * and the list is still correct as of the last read.
 */
let watchStarted = false

function watchOnReturn(): void {
  if (watchStarted || typeof document === 'undefined') return
  watchStarted = true
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible') return
    void loadPermissionStatus()
    // Guarded so a page still in its error state is not silently overwritten by a partial read.
    if (state.phase === 'ready') void refreshAlarms()
  })
}

export const fetchPermissionStatus = () => bridge.getPermissionStatus()

export const openPermissionSetting = (key: PermissionKey) =>
  bridge.openPermissionSetting({ key })

/**
 * PRD FR-5.5 — the first-run walkthrough is shown once. "Skipped" and "done" both set this, because
 * the flag means "we have already pointed at the 体检页"; it deliberately does **not** silence the
 * banner, which keeps tracking the actual permission state (FR-5.4).
 */
export const markPermissionCheckDone = () => updateSettings({ permissionCheckDone: true })

// ---------------------------------------------------------------------------------------
// Theme
// ---------------------------------------------------------------------------------------

const systemPrefersDark = () =>
  typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches

export function applyTheme(mode: ThemeMode): void {
  const resolved = mode === 'system' ? (systemPrefersDark() ? 'dark' : 'light') : mode
  document.documentElement.dataset.theme = resolved
}

// ---------------------------------------------------------------------------------------
// Clock
// ---------------------------------------------------------------------------------------

/**
 * 10s is enough: every countdown is formatted to the minute, so a faster tick would only
 * burn renders.
 */
export function startClock(intervalMs = 10_000): () => void {
  const id = window.setInterval(() => {
    state.now = Date.now()
  }, intervalMs)
  return () => window.clearInterval(id)
}
