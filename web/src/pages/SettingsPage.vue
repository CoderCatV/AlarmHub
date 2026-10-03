<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { bridge, isNativePlatform, usingMock } from '../bridge'
import type { HolidayDataInfo, PermissionItem, Settings, TimeFormat, ThemeMode, VolumeKeyAction } from '../bridge/types'
import FormRow from '../components/FormRow.vue'
import ToggleSwitch from '../components/ToggleSwitch.vue'
import {
  bulkSetDeleteAfterRing,
  countOnceAlarms,
  fetchHolidayDataInfo,
  state,
  updateSettings,
} from '../state/store'

const emit = defineEmits<{ back: []; openGroups: []; openPermissions: [] }>()

const PAUSE_DAYS = [1, 2, 3, 7]
const SNOOZE_MINUTES = [5, 10, 15, 20, 30]
const SNOOZE_COUNTS = [
  { value: 1, label: '1 次' },
  { value: 3, label: '3 次' },
  { value: 5, label: '5 次' },
  { value: 0, label: '不限' },
]
const AUTO_STOP = [
  { value: 5, label: '5 分钟' },
  { value: 10, label: '10 分钟' },
  { value: 15, label: '15 分钟' },
  { value: 30, label: '30 分钟' },
  { value: 0, label: '不限' },
]
const FADE = [
  { value: 0, label: '关闭' },
  { value: 5, label: '5 秒' },
  { value: 10, label: '10 秒' },
  { value: 15, label: '15 秒' },
  { value: 30, label: '30 秒' },
]
const TIME_FORMATS: { value: TimeFormat; label: string }[] = [
  { value: '24', label: '24 小时制' },
  { value: '12', label: '12 小时制' },
]
const THEMES: { value: ThemeMode; label: string }[] = [
  { value: 'system', label: '跟随系统' },
  { value: 'dark', label: '深色' },
  { value: 'light', label: '浅色' },
]
const VOLUME_KEYS: { value: VolumeKeyAction; label: string }[] = [
  { value: 'snooze', label: '贪睡' },
  { value: 'dismiss', label: '关闭' },
]

/**
 * What the 音量键 row offers.
 *
 * With 贪睡 switched off globally, offering 「音量键 → 贪睡」 would describe an action that no longer
 * exists — pressing a volume key during a ring would silently do 关闭 while the setting claimed 贪睡.
 * The row keeps its place so the setting is still discoverable, but only lists what is real.
 */
const volumeKeys = computed(() =>
  settings.value?.snoozeEnabled === false ? VOLUME_KEYS.filter((k) => k.value !== 'snooze') : VOLUME_KEYS
)

const settings = computed(() => state.settings)

async function patch(next: Partial<Settings>) {
  await updateSettings(next)
}

/**
 * The global 贪睡 switch.
 *
 * Turning it **off** also forces 音量键 to 关闭. Leaving it on 贪睡 would store a preference for a
 * feature that is now off, and the ring page would silently reinterpret it — a setting that disagrees
 * with what happens is worse than either behaviour. (It is not restored on turning snooze back on:
 * guessing what the user "probably wanted" is how a settings screen starts lying.)
 */
async function setSnoozeEnabled(on: boolean) {
  await patch(on ? { snoozeEnabled: true } : { snoozeEnabled: false, volumeKeyAction: 'dismiss' })
}

// ---------------------------------------------------------------------------------------
// Bulk one-off conversion (PRD FR-3.4)
// ---------------------------------------------------------------------------------------

const onceCounts = ref<{ total: number; alreadyMarked: number } | null>(null)
const confirmingBulk = ref(false)
const bulkResult = ref<number | null>(null)

async function refreshCounts() {
  onceCounts.value = await countOnceAlarms()
}

onMounted(async () => {
  await refreshCounts()
  permissions.value = await bridge.getPermissionStatus()
  try {
    about.value = await bridge.ping()
  } catch {
    about.value = null
  }
  try {
    holidays.value = await fetchHolidayDataInfo()
  } catch {
    // A readout that cannot be read shows nothing rather than an error banner: nothing is broken for
    // the user, and the alarm rules do not depend on this line being on screen.
    holidays.value = null
  }
})

async function onBulkConvert() {
  if (!confirmingBulk.value) {
    confirmingBulk.value = true
    return
  }
  confirmingBulk.value = false
  const { affected } = await bulkSetDeleteAfterRing()
  bulkResult.value = affected
  await refreshCounts()
}

/** How many one-off alarms would actually change — the number the prompt should quote. */
const pendingOnce = computed(() =>
  onceCounts.value ? onceCounts.value.total - onceCounts.value.alreadyMarked : 0
)

// ---------------------------------------------------------------------------------------
// Permission + about summaries
// ---------------------------------------------------------------------------------------

const permissions = ref<PermissionItem[]>([])
const missingCount = computed(
  () => permissions.value.filter((p) => p.applicable && !p.granted).length
)

const about = ref<Awaited<ReturnType<typeof bridge.ping>> | null>(null)

/**
 * Which years the built-in 节假日 table covers (PRD FR-6.2 / FR-6.3).
 *
 * Shown rather than merely stored: the table ships inside the APK and is only ever replaced by a new
 * build, so "which years does this install know about" is a fact the user can only get from the app.
 * `degraded` is the case FR-6.3 asks to surface — the table no longer covers the *current* year, so
 * every 节假日/调休 rule is silently running on the weekday-only fallback.
 */
const holidays = ref<HolidayDataInfo | null>(null)

const holidayYearsText = computed(() => {
  const years = holidays.value?.years ?? []
  if (years.length === 0) return '—'
  return years.length === 1 ? `${years[0]} 年` : `${years[0]}–${years[years.length - 1]} 年`
})
</script>

<template>
  <div class="page">
    <header class="bar">
      <button type="button" class="bar__action" @click="emit('back')">返回</button>
      <h1 class="bar__title">设置</h1>
      <span class="bar__spacer" />
    </header>

    <p v-if="!settings" class="loading">载入中…</p>

    <div v-else class="body">
      <!-- the switch this whole project was requested for -->
      <section class="card">
        <h2 class="card__title">闹钟默认值</h2>

        <FormRow
          label="新建一次性闹钟默认响铃后删除"
          hint="打开后，新建的一次性闹钟会自动勾选「响铃后删除」，响过就自己消失。单个闹钟仍可单独取消。"
        >
          <ToggleSwitch
            :model-value="settings.defaultDeleteOnceAfterRing"
            label="新建一次性闹钟默认响铃后删除"
            @update:model-value="(v) => patch({ defaultDeleteOnceAfterRing: v })"
          />
        </FormRow>

        <FormRow label="默认暂停天数" hint="点「暂停」时默认选中的天数" stacked>
          <div class="chips">
            <button
              v-for="d in PAUSE_DAYS"
              :key="d"
              type="button"
              class="chip"
              :class="{ 'chip--on': settings.defaultPauseDays === d }"
              :aria-pressed="settings.defaultPauseDays === d"
              @click="patch({ defaultPauseDays: d })"
            >
              {{ d }} 天
            </button>
          </div>
        </FormRow>
      </section>

      <section class="card">
        <h2 class="card__title">响铃默认值</h2>

        <!--
          The global 贪睡 switch. When it is off, the two rows below are hidden rather than disabled:
          they only describe how snooze behaves, and greyed-out settings for a feature that is off invite
          the question "why can I see this at all?". Reported as 「给贪睡设置一个开关，我一般用不上」.
        -->
        <FormRow label="贪睡">
          <ToggleSwitch
            :model-value="settings.snoozeEnabled"
            label="贪睡"
            @update:model-value="(v) => setSnoozeEnabled(v)"
          />
        </FormRow>

        <template v-if="settings.snoozeEnabled">
          <FormRow label="贪睡时长" stacked>
            <div class="chips">
              <button
                v-for="m in SNOOZE_MINUTES"
                :key="m"
                type="button"
                class="chip"
                :class="{ 'chip--on': settings.defaultSnoozeMinutes === m }"
                :aria-pressed="settings.defaultSnoozeMinutes === m"
                @click="patch({ defaultSnoozeMinutes: m })"
              >
                {{ m }} 分钟
              </button>
            </div>
          </FormRow>

          <FormRow label="贪睡次数上限" stacked>
            <div class="chips">
              <button
                v-for="c in SNOOZE_COUNTS"
                :key="c.value"
                type="button"
                class="chip"
                :class="{ 'chip--on': settings.defaultSnoozeMaxCount === c.value }"
                :aria-pressed="settings.defaultSnoozeMaxCount === c.value"
                @click="patch({ defaultSnoozeMaxCount: c.value })"
              >
                {{ c.label }}
              </button>
            </div>
          </FormRow>
        </template>

        <!--
          The hint used to read 「超过这个时长自动停止，0 表示一直响到手动关闭」, which mentions `0` — an
          internal encoding the user never sees, because the option is labelled 「不限」. Reported from the
          phone: 「提示信息说有 0，但实际只有 不限」. Say the same thing in the words on the screen.
        -->
        <FormRow label="响铃超时" hint="超过这个时长自动停止；选「不限」则一直响到手动关闭" stacked>
          <div class="chips">
            <button
              v-for="o in AUTO_STOP"
              :key="o.value"
              type="button"
              class="chip"
              :class="{ 'chip--on': settings.defaultAutoStopMinutes === o.value }"
              :aria-pressed="settings.defaultAutoStopMinutes === o.value"
              @click="patch({ defaultAutoStopMinutes: o.value })"
            >
              {{ o.label }}
            </button>
          </div>
        </FormRow>

        <FormRow label="音量渐强" stacked>
          <div class="chips">
            <button
              v-for="o in FADE"
              :key="o.value"
              type="button"
              class="chip"
              :class="{ 'chip--on': settings.defaultFadeInSeconds === o.value }"
              :aria-pressed="settings.defaultFadeInSeconds === o.value"
              @click="patch({ defaultFadeInSeconds: o.value })"
            >
              {{ o.label }}
            </button>
          </div>
        </FormRow>
      </section>

      <section class="card">
        <h2 class="card__title">显示与操作</h2>

        <FormRow label="时间格式" stacked>
          <div class="chips">
            <button
              v-for="o in TIME_FORMATS"
              :key="o.value"
              type="button"
              class="chip"
              :class="{ 'chip--on': settings.timeFormat === o.value }"
              :aria-pressed="settings.timeFormat === o.value"
              @click="patch({ timeFormat: o.value })"
            >
              {{ o.label }}
            </button>
          </div>
        </FormRow>

        <FormRow label="主题" stacked>
          <div class="chips">
            <button
              v-for="o in THEMES"
              :key="o.value"
              type="button"
              class="chip"
              :class="{ 'chip--on': settings.theme === o.value }"
              :aria-pressed="settings.theme === o.value"
              @click="patch({ theme: o.value })"
            >
              {{ o.label }}
            </button>
          </div>
        </FormRow>

        <FormRow label="音量键" hint="响铃时按下音量键执行的动作" stacked>
          <div class="chips">
            <button
              v-for="o in volumeKeys"
              :key="o.value"
              type="button"
              class="chip"
              :class="{ 'chip--on': settings.volumeKeyAction === o.value }"
              :aria-pressed="settings.volumeKeyAction === o.value"
              @click="patch({ volumeKeyAction: o.value })"
            >
              {{ o.label }}
            </button>
          </div>
        </FormRow>
      </section>

      <section class="card">
        <h2 class="card__title">管理</h2>

        <button type="button" class="nav" @click="emit('openGroups')">
          <span class="nav__label">分组管理</span>
          <span class="nav__value">{{ state.groups.length }} 个分组 ›</span>
        </button>

        <button type="button" class="nav" @click="emit('openPermissions')">
          <span class="nav__label">权限体检</span>
          <span class="nav__value" :class="{ 'nav__value--warn': missingCount > 0 }">
            {{ missingCount > 0 ? `${missingCount} 项待处理` : '全部正常' }} ›
          </span>
        </button>

        <FormRow
          label="把现有的一次性闹钟都设为响铃后删除"
          :hint="
            onceCounts
              ? `共 ${onceCounts.total} 个一次性闹钟，其中 ${onceCounts.alreadyMarked} 个已标记${
                  bulkResult !== null ? `；刚才处理了 ${bulkResult} 个` : ''
                }`
              : '统计中…'
          "
        >
          <button
            type="button"
            class="action"
            :class="{ 'action--armed': confirmingBulk }"
            :disabled="pendingOnce === 0 && !confirmingBulk"
            @click="onBulkConvert"
          >
            {{ confirmingBulk ? `确认处理 ${pendingOnce} 个？` : '一键处理' }}
          </button>
        </FormRow>
      </section>

      <section class="card">
        <h2 class="card__title">关于</h2>
        <FormRow label="版本">
          <span class="mono">{{ about?.version ?? '—' }}</span>
        </FormRow>
        <FormRow label="包名">
          <span class="mono">{{ about?.appId ?? '—' }}</span>
        </FormRow>
        <FormRow label="运行环境">
          <!-- Taken from the platform check rather than from `ping`: during M1 the bridge is
               the mock, so `ping().device` would claim "browser" even inside the native
               container. -->
          <span class="mono">{{ isNativePlatform() ? 'Android 原生容器' : '浏览器预览' }}</span>
        </FormRow>
        <FormRow label="数据来源">
          <span class="mono">{{ usingMock ? '演示数据（未接入原生）' : '本机数据库' }}</span>
        </FormRow>
        <!--
          PRD FR-6.2 / FR-6.3: the holiday table is the one piece of business data that goes stale on
          its own, one year at a time, with no user action involved. Saying which years this install
          covers is the only way the user can tell whether 节假日/调休 rules still have data behind them.
          The warning colour is reserved for the case that actually breaks rules — the current year is
          not covered, so everything falls back to weekday-only.
        -->
        <FormRow label="节假日数据" :hint="holidays?.source ?? ''" stacked>
          <span class="mono" :class="{ 'mono--warn': holidays?.degraded }">
            {{ holidayYearsText }}{{ holidays?.degraded ? ' · 本年未覆盖' : '' }}
          </span>
        </FormRow>
        <p class="note">
          闹钟坞不申请联网权限，所有数据只存在这台设备上。没有账号、没有统计、没有广告。
        </p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-block-size: 100vh;
  padding-block-end: calc(var(--sp-6) + var(--safe-bottom));
}

.bar {
  position: sticky;
  inset-block-start: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: calc(var(--safe-top) + var(--sp-2)) var(--page-x) var(--sp-2);
  background: var(--bg);
}

.bar__action {
  min-block-size: var(--tap-min);
  padding: 0 var(--sp-2);
  font-size: var(--fs-sm);
  color: var(--accent);
}

.bar__title {
  flex: 1 1 auto;
  text-align: center;
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
}

.bar__spacer {
  inline-size: 40px;
}

.loading {
  padding: var(--sp-7) var(--page-x);
  color: var(--text-faint);
  text-align: center;
}

.body {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding: 0 var(--page-x);
}

.card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  overflow: hidden;
}

.card__title {
  padding: var(--sp-3) var(--sp-4) var(--sp-1);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--text-faint);
  letter-spacing: 0.4px;
}

.card > .card__title + * {
  border-block-start: 0 !important;
}

/* ---- chips ---- */

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
}

.chip {
  min-block-size: 38px;
  padding: 0 14px;
  border-radius: var(--r-sm);
  background: var(--surface-2);
  border: 1px solid var(--border);
  color: var(--text-dim);
  font-size: var(--fs-xs);
  transition: all var(--dur-fast) var(--ease);
}

.chip--on {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-ink);
  font-weight: var(--fw-semibold);
}

/* ---- nav rows ---- */

.nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  inline-size: 100%;
  min-block-size: 56px;
  padding: var(--sp-3) var(--sp-4);
  border-block-start: 1px solid var(--border);
}

.nav:first-of-type {
  border-block-start: 0;
}

.nav__label {
  font-size: var(--fs-sm);
}

.nav__value {
  font-size: var(--fs-xs);
  color: var(--text-faint);
}

.nav__value--warn {
  color: var(--warn);
}

/* ---- actions ---- */

.action {
  min-block-size: 38px;
  padding: 0 16px;
  border-radius: var(--r-sm);
  background: var(--tint-accent);
  color: var(--accent);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
}

.action--armed {
  background: var(--accent);
  color: var(--accent-ink);
}

.action:disabled {
  opacity: 0.4;
}

.mono {
  font-family: ui-monospace, Consolas, monospace;
  font-size: var(--fs-xs);
  color: var(--text-dim);
  word-break: break-all;
  text-align: end;
}

/* The current year is not in the holiday table, so 节假日/调休 rules have nothing to read. */
.mono--warn {
  color: var(--warn);
}

.note {
  padding: var(--sp-3) var(--sp-4) var(--sp-4);
  font-size: var(--fs-xs);
  color: var(--text-faint);
  line-height: 1.7;
  border-block-start: 1px solid var(--border);
}
</style>
