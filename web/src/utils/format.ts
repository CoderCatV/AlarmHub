/**
 * Display formatting.
 *
 * Everything here formats a value that the native side already computed. Nothing in this
 * file derives a schedule — see `bridge/types.ts`, contract rule 2.
 */

import type { Alarm, AlarmStatus, RepeatType, TimeFormat } from '../bridge/types'

const WEEKDAY_SHORT = ['一', '二', '三', '四', '五', '六', '日'] as const
const WEEKDAY_LONG = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'] as const

const pad2 = (n: number) => String(n).padStart(2, '0')

/** `07:00`, or `上午 7:00` in 12-hour mode. */
export function formatClock(hour: number, minute: number, format: TimeFormat): string {
  if (format === '24') return `${pad2(hour)}:${pad2(minute)}`
  const suffix = hour < 12 ? '上午' : '下午'
  const h12 = hour % 12 === 0 ? 12 : hour % 12
  return `${suffix} ${h12}:${pad2(minute)}`
}

/** `6月6日` */
export function formatMonthDay(ts: number): string {
  const d = new Date(ts)
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

/** `6月6日 00:00` */
export function formatMonthDayTime(ts: number): string {
  const d = new Date(ts)
  return `${formatMonthDay(ts)} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

/** Midnight-aligned day difference: today = 0, tomorrow = 1. */
function dayDelta(ts: number, now: number): number {
  const a = new Date(ts)
  const b = new Date(now)
  a.setHours(0, 0, 0, 0)
  b.setHours(0, 0, 0, 0)
  return Math.round((a.getTime() - b.getTime()) / 86_400_000)
}

/** `今天 07:00` / `明天 07:00` / `6月6日 07:00` */
export function formatDayTime(ts: number, now: number, format: TimeFormat): string {
  const d = new Date(ts)
  const clock = formatClock(d.getHours(), d.getMinutes(), format)
  switch (dayDelta(ts, now)) {
    case 0:
      return `今天 ${clock}`
    case 1:
      return `明天 ${clock}`
    case 2:
      return `后天 ${clock}`
    default:
      return `${formatMonthDay(ts)} ${clock}`
  }
}

/**
 * `7 小时 12 分后响铃`. Deliberately coarse: the list is not a stopwatch, and a ticking
 * seconds field would force a re-render every second for 100+ rows.
 */
export function formatCountdown(ts: number, now: number): string {
  const ms = ts - now
  if (ms <= 0) return '即将响铃'

  const totalMinutes = Math.floor(ms / 60_000)
  const days = Math.floor(totalMinutes / 1440)
  const hours = Math.floor((totalMinutes % 1440) / 60)
  const minutes = totalMinutes % 60

  if (days >= 1) return hours > 0 ? `${days} 天 ${hours} 小时后响铃` : `${days} 天后响铃`
  if (hours >= 1) return minutes > 0 ? `${hours} 小时 ${minutes} 分后响铃` : `${hours} 小时后响铃`
  if (minutes >= 1) return `${minutes} 分钟后响铃`
  return '不到 1 分钟后响铃'
}

/** The repeat rule, in the words the user chose it with. */
export function formatRepeat(
  alarm: Pick<Alarm, 'repeatType' | 'repeatDays' | 'onceDate'>,
  now: number
): string {
  const type: RepeatType = alarm.repeatType

  if (type === 'ONCE') {
    if (!alarm.onceDate) return '单次'
    const [y, m, d] = alarm.onceDate.split('-').map(Number)
    const ts = new Date(y, m - 1, d).getTime()
    const delta = dayDelta(ts, now)
    if (delta === 0) return '单次 · 今天'
    if (delta === 1) return '单次 · 明天'
    return `单次 · ${m}月${d}日`
  }

  if (type === 'DAILY') return '每天'
  if (type === 'WORKDAY') return '法定工作日'
  if (type === 'HOLIDAY') return '法定节假日'

  // WEEKLY
  const days = WEEKDAY_LONG.filter((_, i) => (alarm.repeatDays & (1 << i)) !== 0)
  if (days.length === 0) return '未选择星期'
  if (days.length === 7) return '每天'
  if (days.length === 5 && alarm.repeatDays === 0b0011111) return '周一至周五'
  if (days.length === 2 && alarm.repeatDays === 0b1100000) return '周末'
  return days.join(' ')
}

/** `已暂停 3 小时` */
export function formatPausedFor(pausedAt: number | null, now: number): string {
  if (pausedAt === null) return '已暂停'
  const minutes = Math.floor((now - pausedAt) / 60_000)
  if (minutes < 60) return `已暂停 ${Math.max(minutes, 1)} 分钟`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `已暂停 ${hours} 小时`
  return `已暂停 ${Math.floor(hours / 24)} 天`
}

/** `2 天后恢复` / `明天 00:00 恢复` */
export function formatResumeIn(pauseUntil: number, now: number, format: TimeFormat): string {
  const d = new Date(pauseUntil)
  // Pauses always resume at midnight, so the clock part is only informative.
  const days = dayDelta(pauseUntil, now)
  if (days <= 1) return `明天 ${formatClock(d.getHours(), d.getMinutes(), format)} 恢复`
  return `${days} 天后恢复`
}

/** The one-line state badge on a list row, or `null` when the row is normal. */
export function statusBadge(
  status: AlarmStatus,
  alarm: Pick<Alarm, 'pauseUntil'>,
  now: number,
  format: TimeFormat
): string | null {
  switch (status) {
    case 'paused':
      return alarm.pauseUntil !== null ? formatResumeIn(alarm.pauseUntil, now, format) : '已暂停'
    case 'disabled':
      return '已停用'
    case 'expired':
      return '已过期'
    case 'never':
      return '重复规则无匹配日'
    default:
      return null
  }
}

/** Weekday chips for the editor: `一 二 三 四 五 六 日`. */
export function weekdayLabels(): readonly string[] {
  return WEEKDAY_SHORT
}
