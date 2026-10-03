/**
 * In-memory stand-in for the native layer, so the UI can be built and reviewed on fake data.
 *
 * IMPORTANT — this file is throwaway. It exists to satisfy `AlarmHubBridge` during M1;
 * `AlarmHubPlugin.kt` implements the same contract at M6 and this module stops being used.
 *
 * It does implement the "skip N ring days" calculation (PRD §5.3) rather than stubbing it,
 * because the pause sheet's resume-time preview is one of the things being reviewed. That is
 * a deliberate, temporary duplicate of native logic — page components must never do this.
 * When the real bridge lands, the authoritative implementation is the Kotlin one and this
 * copy is deleted, so there is no permanent drift risk.
 */

import type {
  Alarm,
  AlarmHubBridge,
  AlarmView,
  AlarmStatus,
  Group,
  HolidayDataInfo,
  PausePreview,
  PauseRequest,
  PermissionItem,
  RepeatType,
  Settings,
} from './types'

const DAY = 86_400_000

const startOfDay = (ts: number): number => {
  const d = new Date(ts)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

// ---------------------------------------------------------------------------------------
// Demo state
// ---------------------------------------------------------------------------------------

const now0 = Date.now()

const groups: Group[] = [
  {
    id: 1,
    name: '未分组',
    color: 0xff8b97a8,
    sortOrder: 0,
    isSystem: true,
    permanentDisabled: false,
    pauseUntil: null,
    pausedAt: null,
    alarmCount: 4,
  },
  {
    id: 2,
    name: '工作日',
    color: 0xff4c9dff,
    sortOrder: 1,
    isSystem: true,
    // Demo: paused, resuming tomorrow at midnight — exercises the paused badge and the
    // "resume at" preview.
    permanentDisabled: false,
    pauseUntil: startOfDay(now0) + DAY,
    pausedAt: now0 - 5 * 3_600_000,
    alarmCount: 3,
  },
  {
    id: 3,
    name: '节假日',
    color: 0xff4ecb71,
    sortOrder: 2,
    isSystem: true,
    permanentDisabled: false,
    pauseUntil: null,
    pausedAt: null,
    alarmCount: 2,
  },
  {
    id: 4,
    name: '健身',
    color: 0xffffb547,
    sortOrder: 3,
    isSystem: false,
    permanentDisabled: true,
    pauseUntil: null,
    pausedAt: null,
    alarmCount: 1,
  },
]

let nextAlarmId = 100

function alarm(partial: Partial<Alarm> & Pick<Alarm, 'groupId' | 'hour' | 'minute'>): Alarm {
  return {
    id: nextAlarmId++,
    label: '',
    repeatType: 'DAILY' as RepeatType,
    repeatDays: 0,
    onceDate: null,
    ringtoneUri: null,
    ringtoneName: null,
    vibrate: true,
    fadeInSeconds: 5,
    snoozeEnabled: true,
    snoozeMinutes: 10,
    snoozeMaxCount: 3,
    autoStopMinutes: 10,
    deleteAfterRing: true,
    enabled: true,
    permanentDisabled: false,
    pauseUntil: null,
    expired: false,
    ...partial,
  }
}

const isoDate = (ts: number) => {
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(
    d.getDate()
  ).padStart(2, '0')}`
}

/**
 * Dev-only scenario switch, so a scripted UI review can reach states the demo data cannot
 * show — chiefly the empty list. Set it before loading the page:
 *   sessionStorage.setItem('alarmhub:scenario', 'empty'); location.reload()
 * sessionStorage rather than a global, because a reload rebuilds this module from scratch.
 */
const scenario = (() => {
  try {
    return sessionStorage.getItem('alarmhub:scenario') ?? 'demo'
  } catch {
    return 'demo'
  }
})()

const alarms: Alarm[] = scenario === 'empty' ? [] : [
  // 工作日 — three alarms, the group is currently paused
  alarm({ groupId: 2, hour: 7, minute: 0, label: '起床上班', repeatType: 'WORKDAY' }),
  alarm({ groupId: 2, hour: 7, minute: 40, label: '出门', repeatType: 'WORKDAY' }),
  alarm({ groupId: 2, hour: 13, minute: 20, label: '午休结束', repeatType: 'WORKDAY' }),

  // 节假日
  alarm({ groupId: 3, hour: 9, minute: 30, label: '睡个懒觉', repeatType: 'HOLIDAY' }),
  alarm({
    groupId: 3,
    hour: 10,
    minute: 0,
    label: '打球',
    repeatType: 'WEEKLY',
    repeatDays: 0b0100000, // Saturday
    fadeInSeconds: 15,
    snoozeMaxCount: 5,
  }),

  // 未分组
  alarm({
    groupId: 1,
    hour: 6,
    minute: 30,
    label: '赶飞机',
    repeatType: 'ONCE',
    onceDate: isoDate(now0 + 3 * DAY),
    deleteAfterRing: false,
  }),
  alarm({
    groupId: 1,
    hour: 20,
    minute: 0,
    label: '给客户回电话',
    repeatType: 'ONCE',
    onceDate: isoDate(now0),
    deleteAfterRing: true,
  }),
  alarm({ groupId: 1, hour: 22, minute: 30, label: '吃药', repeatType: 'DAILY' }),
  // permanently switched off — exercises the 'disabled' row state
  alarm({ groupId: 1, hour: 23, minute: 0, label: '提醒睡觉', enabled: false }),

  // 健身 — the whole group is permanently disabled
  alarm({ groupId: 4, hour: 19, minute: 30, label: '健身房', repeatType: 'WEEKLY', repeatDays: 0b0010100 }),

  // already rang, kept because "delete after ring" was off
  alarm({
    groupId: 1,
    hour: 15,
    minute: 0,
    label: '取快递',
    repeatType: 'ONCE',
    onceDate: isoDate(now0 - DAY),
    expired: true,
  }),
  alarm({
    groupId: 1,
    hour: 18,
    minute: 30,
    label: '交电费',
    repeatType: 'ONCE',
    onceDate: isoDate(now0 - 2 * DAY),
    expired: true,
  }),
]

const settings: Settings = {
  defaultDeleteOnceAfterRing: true,
  defaultPauseDays: 1,
  defaultSnoozeMinutes: 10,
  defaultSnoozeMaxCount: 3,
  snoozeEnabled: true,
  defaultAutoStopMinutes: 10,
  defaultRingtoneUri: null,
  defaultRingtoneName: '系统默认铃声',
  defaultFadeInSeconds: 5,
  timeFormat: '24',
  theme: 'system',
  volumeKeyAction: 'snooze',
  permissionCheckDone: false,
}

// ---------------------------------------------------------------------------------------
// Schedule maths (temporary; the real one lives in Kotlin `domain/`)
// ---------------------------------------------------------------------------------------

function dayMatchesRepeat(a: Alarm, dayStart: number): boolean {
  const weekday = (new Date(dayStart).getDay() + 6) % 7 // 0 = Monday … 6 = Sunday

  switch (a.repeatType) {
    case 'ONCE': {
      if (!a.onceDate) return false
      return isoDate(dayStart) === a.onceDate
    }
    case 'DAILY':
      return true
    case 'WEEKLY':
      return (a.repeatDays & (1 << weekday)) !== 0
    // The mock has no holiday table yet, so WORKDAY degrades to Mon–Fri and HOLIDAY to the
    // weekend. The real implementation reads assets/holidays.json (PRD §FR-6).
    case 'WORKDAY':
      return weekday < 5
    case 'HOLIDAY':
      return weekday >= 5
  }
}

/** The alarm is switched on and neither it nor its group is disabled. */
function isLive(a: Alarm, group: Group | undefined): boolean {
  if (!a.enabled || a.permanentDisabled || a.expired) return false
  if (group?.permanentDisabled) return false
  return true
}

function ringAt(a: Alarm, dayStart: number): number {
  return dayStart + a.hour * 3_600_000 + a.minute * 60_000
}

/** First ring strictly after `after`. */
function nextRingAfter(a: Alarm, after: number): number | null {
  let day = startOfDay(after)
  for (let i = 0; i < 400; i++) {
    if (dayMatchesRepeat(a, day)) {
      const at = ringAt(a, day)
      if (at > after) return at
    }
    day += DAY
  }
  return null
}

function statusOf(a: Alarm, g: Group | undefined, now: number): AlarmStatus {
  if (a.expired) return 'expired'
  if (a.permanentDisabled || g?.permanentDisabled) return 'disabled'
  if (a.pauseUntil !== null && a.pauseUntil > now) return 'paused'
  if (a.pauseUntil !== null && a.pauseUntil <= now) a.pauseUntil = null
  if (!a.enabled) return 'disabled'
  if (a.repeatType === 'WEEKLY' && a.repeatDays === 0) return 'never'
  return 'scheduled'
}

/** Mirrors the priority order in PRD §5.5. */
function scheduleFloor(a: Alarm, g: Group | undefined, now: number): number {
  let floor = now
  if (a.pauseUntil !== null) floor = Math.max(floor, a.pauseUntil)
  if (g?.pauseUntil != null) floor = Math.max(floor, g.pauseUntil)
  return floor
}

function toView(a: Alarm, now: number): AlarmView {
  const g = groups.find((x) => x.id === a.groupId)
  const status = statusOf(a, g, now)
  const live = isLive(a, g) && status === 'scheduled'
  return {
    ...a,
    groupName: g?.name ?? '未分组',
    groupColor: g?.color ?? 0xff8b97a8,
    status,
    nextRingAt: live ? nextRingAfter(a, Math.max(now, scheduleFloor(a, g, now))) : null,
  }
}

// ---------------------------------------------------------------------------------------
// Bridge implementation
// ---------------------------------------------------------------------------------------

function previewOf(req: PauseRequest): PausePreview {
  const scope = req.scope
  const affected =
    scope === 'group'
      ? alarms.filter((a) => a.groupId === req.id && !a.expired)
      : alarms.filter((a) => a.id === req.id)

  if ('until' in req) {
    const resumeAt = req.until
    const after = affected
      .map((a) => nextRingAfter(a, resumeAt))
      .filter((t): t is number => t !== null)
      .sort((x, y) => x - y)
    return { resumeAt, skippedRingDays: [], nextRingAt: after[0] ?? null }
  }

  const now = Date.now()
  const skipped: number[] = []
  let day = startOfDay(now)
  let guard = 0

  while (skipped.length < req.days && guard++ < 400) {
    const ringsThatDay = affected.some((a) => {
      if (!dayMatchesRepeat(a, day)) return false
      return ringAt(a, day) > now
    })
    if (ringsThatDay) skipped.push(day)
    day += DAY
  }

  const resumeAt = skipped.length === req.days ? skipped[req.days - 1] + DAY : day
  const after = affected
    .map((a) => nextRingAfter(a, resumeAt))
    .filter((t): t is number => t !== null)
    .sort((x, y) => x - y)

  return { resumeAt, skippedRingDays: skipped, nextRingAt: after[0] ?? null }
}

/**
 * PRD §5.2: a one-off alarm whose moment has already gone by rolls forward to the next
 * occurrence of that time instead of sitting in the list as a dead entry. The native
 * implementation has to apply the same rule on save.
 */
function rollForwardIfPast(a: Alarm): void {
  if (a.repeatType !== 'ONCE' || !a.onceDate) return
  const [y, m, d] = a.onceDate.split('-').map(Number)
  const at = new Date(y, m - 1, d, a.hour, a.minute).getTime()
  if (at <= Date.now()) a.onceDate = isoDate(Date.now() + DAY)
}

const notYet = (what: string) => () =>
  Promise.reject(new Error(`${what} 尚未在 M1 的 mock 中实现`))

export const mockBridge: AlarmHubBridge = {
  async ping() {
    return {
      version: 'mock',
      sdkInt: 0,
      buildType: 'mock',
      appId: 'com.alarmhub.app',
      kotlinVersion: '—',
      device: '浏览器预览（mock 数据）',
    }
  },

  async getHolidayDataInfo(): Promise<HolidayDataInfo> {
    return { years: [2025, 2026], source: '国务院办公厅（mock）', degraded: false }
  },

  async listGroups() {
    return groups.map((g) => ({ ...g, alarmCount: alarms.filter((a) => a.groupId === g.id).length }))
  },

  async saveGroup(group) {
    if (group.id) {
      const existing = groups.find((g) => g.id === group.id)
      if (!existing) throw new Error(`分组 ${group.id} 不存在`)
      Object.assign(existing, group)
      return { ...existing }
    }
    const created: Group = {
      id: Math.max(0, ...groups.map((g) => g.id)) + 1,
      name: group.name,
      color: group.color ?? 0xff4c9dff,
      sortOrder: groups.length,
      isSystem: false,
      permanentDisabled: false,
      pauseUntil: null,
      pausedAt: null,
      alarmCount: 0,
    }
    groups.push(created)
    return { ...created }
  },

  async deleteGroup({ id, moveAlarmsTo }) {
    const index = groups.findIndex((g) => g.id === id)
    if (index < 0) throw new Error(`分组 ${id} 不存在`)
    if (groups[index].isSystem) throw new Error('内置分组不能删除')
    let moved = 0
    for (const a of alarms) {
      if (a.groupId === id) {
        a.groupId = moveAlarmsTo
        moved++
      }
    }
    groups.splice(index, 1)
    return { movedCount: moved }
  },

  async reorderGroups({ orderedIds }) {
    orderedIds.forEach((id, i) => {
      const g = groups.find((x) => x.id === id)
      if (g) g.sortOrder = i
    })
  },

  async listAlarms() {
    const now = Date.now()
    return alarms.map((a) => toView(a, now))
  },

  async getAlarm({ id }) {
    const a = alarms.find((x) => x.id === id)
    if (!a) throw new Error(`闹钟 ${id} 不存在`)
    return toView(a, Date.now())
  },

  async saveAlarm(patch) {
    if (patch.id) {
      const existing = alarms.find((a) => a.id === patch.id)
      if (!existing) throw new Error(`闹钟 ${patch.id} 不存在`)
      Object.assign(existing, patch)
      rollForwardIfPast(existing)
      return toView(existing, Date.now())
    }
    const created = alarm({
      groupId: patch.groupId ?? 1,
      hour: patch.hour ?? 7,
      minute: patch.minute ?? 0,
      ...patch,
    })
    rollForwardIfPast(created)
    alarms.push(created)
    return toView(created, Date.now())
  },

  async deleteAlarm({ id }) {
    const i = alarms.findIndex((a) => a.id === id)
    if (i >= 0) alarms.splice(i, 1)
  },

  async deleteAlarms({ ids }) {
    let affected = 0
    for (const id of ids) {
      const i = alarms.findIndex((a) => a.id === id)
      if (i >= 0) {
        alarms.splice(i, 1)
        affected++
      }
    }
    return { affected }
  },

  async setAlarmEnabled({ id, enabled }) {
    const a = alarms.find((x) => x.id === id)
    if (a) a.enabled = enabled
  },

  async batchUpdateAlarms({ ids, patch }) {
    let affected = 0
    for (const a of alarms) {
      if (ids.includes(a.id)) {
        Object.assign(a, patch)
        affected++
      }
    }
    return { affected }
  },

  async previewNextRing(patch) {
    // The mock has to fake the one rule the preview is about: a draft behaves like a saved alarm that
    // is switched on, belongs to the draft's group, and is not paused.
    const now = Date.now()
    const a = { ...alarms[0], ...patch, enabled: true, permanentDisabled: false, pauseUntil: null } as Alarm
    const g = groups.find((x) => x.id === a.groupId)
    return { nextRingAt: nextRingAfter(a, Math.max(now, scheduleFloor(a, g, now))) }
  },

  async previewPause(req) {
    return previewOf(req)
  },

  async pause(req) {
    const { resumeAt } = previewOf(req)
    if (req.scope === 'group') {
      const g = groups.find((x) => x.id === req.id)
      if (g) {
        g.pauseUntil = resumeAt
        g.pausedAt = Date.now()
        g.permanentDisabled = false
      }
    } else {
      const a = alarms.find((x) => x.id === req.id)
      if (a) {
        a.pauseUntil = resumeAt
        a.permanentDisabled = false
      }
    }
    return { resumeAt }
  },

  async resume({ scope, id }) {
    if (scope === 'group') {
      const g = groups.find((x) => x.id === id)
      if (g) {
        g.pauseUntil = null
        g.pausedAt = null
      }
    } else {
      const a = alarms.find((x) => x.id === id)
      if (a) a.pauseUntil = null
    }
  },

  async setPermanentDisabled({ scope, id, disabled }) {
    if (scope === 'group') {
      const g = groups.find((x) => x.id === id)
      if (g) {
        g.permanentDisabled = disabled
        if (disabled) {
          g.pauseUntil = null
          g.pausedAt = null
        }
      }
    } else {
      const a = alarms.find((x) => x.id === id)
      if (a) {
        a.permanentDisabled = disabled
        if (disabled) a.pauseUntil = null
      }
    }
  },

  async getSettings() {
    return { ...settings }
  },

  async updateSettings(patch) {
    Object.assign(settings, patch)
    return { ...settings }
  },

  async countOnceAlarms() {
    const once = alarms.filter((a) => a.repeatType === 'ONCE')
    return {
      total: once.length,
      alreadyMarked: once.filter((a) => a.deleteAfterRing).length,
    }
  },

  async bulkSetDeleteAfterRing() {
    const once = alarms.filter((a) => a.repeatType === 'ONCE' && !a.deleteAfterRing)
    once.forEach((a) => {
      a.deleteAfterRing = true
    })
    return { affected: once.length }
  },

  async pickRingtone({ current }) {
    // There is no system chooser in a browser, so tapping the row cycles a fixed list. That
    // is enough to exercise the editor's ringtone row. The native implementation opens
    // RingtoneManager.ACTION_RINGTONE_PICKER instead.
    const catalogue = [
      { uri: null, name: '系统默认铃声' },
      { uri: 'content://media/internal/audio/media/101', name: '清晨鸟鸣' },
      { uri: 'content://media/internal/audio/media/102', name: '钟声' },
      { uri: 'content://media/internal/audio/media/103', name: '轻柔钢琴' },
      { uri: 'silent:', name: '静音（仅震动）' },
    ]
    const index = catalogue.findIndex((c) => c.uri === current)
    const next = catalogue[(index + 1) % catalogue.length]
    return { uri: next.uri, name: next.name, cancelled: false }
  },

  async getPermissionStatus(): Promise<PermissionItem[]> {
    return [
      {
        key: 'exactAlarm',
        label: '精确闹钟',
        description: '没有它，闹钟可能延迟几分钟甚至被系统合并',
        granted: true,
        applicable: true,
      },
      {
        key: 'notifications',
        label: '通知',
        description: '没有它，响铃时不会有通知，也无法从通知进入响铃页',
        granted: false,
        applicable: true,
      },
      {
        key: 'fullScreenIntent',
        label: '全屏通知',
        description: '没有它，锁屏时闹钟只能以通知形式出现，不能直接铺满屏幕',
        granted: true,
        applicable: true,
      },
      {
        key: 'autostart',
        label: '自启动',
        description: '小米/红米必须开启，否则重启后或者被清理后闹钟不会恢复',
        granted: false,
        applicable: true,
      },
      {
        key: 'batteryUnrestricted',
        label: '电池无限制',
        description: '省电策略会冻结后台进程，导致闹钟到点不响',
        granted: false,
        applicable: true,
      },
      {
        key: 'backgroundPopup',
        label: '后台弹出界面',
        description: '没有它，App 在后台时无法直接拉起响铃页',
        granted: false,
        applicable: true,
      },
      {
        key: 'lockScreenDisplay',
        label: '锁屏显示',
        description: '没有它，锁屏时看不到响铃界面',
        granted: true,
        applicable: false,
      },
    ]
  },

  async openPermissionSetting() {
    return { opened: false, fallback: true }
  },
}

// Keep a few members referenced so the unused-parameter lint stays quiet if callers change.
void notYet
