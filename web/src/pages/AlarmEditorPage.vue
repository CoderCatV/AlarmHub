<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { bridge } from '../bridge'
import type { Alarm, RepeatType } from '../bridge/types'
import FormRow from '../components/FormRow.vue'
import ToggleSwitch from '../components/ToggleSwitch.vue'
import WheelTimePicker from '../components/WheelTimePicker.vue'
import {
  deleteAlarm,
  fetchAlarm,
  previewNextRing,
  saveAlarm,
  saveGroup,
  state,
} from '../state/store'
import { GROUP_PALETTE, argbToHex, argbToRgba } from '../utils/color'
import { formatCountdown, weekdayLabels } from '../utils/format'

const props = defineProps<{ id: number | null }>()
const emit = defineEmits<{ back: []; saved: [] }>()

const WEEKDAY = weekdayLabels()

const REPEAT_OPTIONS: { value: RepeatType; label: string }[] = [
  { value: 'ONCE', label: '单次' },
  { value: 'DAILY', label: '每天' },
  { value: 'WEEKLY', label: '自定义' },
  { value: 'WORKDAY', label: '法定工作日' },
  { value: 'HOLIDAY', label: '法定节假日' },
]

const FADE_OPTIONS = [
  { value: 0, label: '关闭' },
  { value: 5, label: '5 秒' },
  { value: 10, label: '10 秒' },
  { value: 15, label: '15 秒' },
  { value: 30, label: '30 秒' },
]

const SNOOZE_MINUTES = [5, 10, 15, 20, 30]
/** 0 means "no limit" — the same convention as `Alarm.snoozeMaxCount`. */
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

// ---------------------------------------------------------------------------------------
// Draft
// ---------------------------------------------------------------------------------------

const todayIso = () => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function blankDraft(): Alarm {
  const s = state.settings
  // 未分组 is the default answer for a new alarm, not "whichever group happens to sort first". Reported
  // as 「未分组 永远排第一位且默认选中」: the fallback used to be the first group by sortOrder, so a
  // user who had put 工作日 at the top silently got every new alarm filed under 工作日.
  const bySort = [...state.groups].sort((a, b) => a.sortOrder - b.sortOrder)
  const fallbackGroup = state.groups.find((g) => g.name === '未分组') ?? bySort[0]
  // A new alarm starts at the time you opened it, not at a fixed 07:00. Opening the editor and then
  // spinning the wheel to "now" is pure friction, and 07:00 is a guess that is wrong most of the day.
  // Overridden by the caller when it opened the editor to copy an existing alarm.
  const now = new Date()
  return {
    id: 0,
    groupId: fallbackGroup?.id ?? 1,
    hour: now.getHours(),
    minute: now.getMinutes(),
    label: '',
    repeatType: 'ONCE',
    repeatDays: 0,
    onceDate: todayIso(),
    ringtoneUri: s?.defaultRingtoneUri ?? null,
    ringtoneName: s?.defaultRingtoneName ?? null,
    vibrate: true,
    fadeInSeconds: s?.defaultFadeInSeconds ?? 5,
    // Follows the global switch (设置 → 响铃默认值 → 贪睡). A new alarm offering a 贪睡 switch that the
    // ring page will ignore is the kind of disagreement this project keeps having to fix: the editor
    // said one thing and the ring did another. Reported from the phone as 「新建页面还是默认开启的，
    // 应该跟全局开关一致」.
    snoozeEnabled: s?.snoozeEnabled ?? true,
    snoozeMinutes: s?.defaultSnoozeMinutes ?? 10,
    snoozeMaxCount: s?.defaultSnoozeMaxCount ?? 3,
    autoStopMinutes: s?.defaultAutoStopMinutes ?? 10,
    // The whole point of the total switch (PRD FR-3.1): a new one-off alarm arrives with
    // "delete after ring" already on.
    deleteAfterRing: s?.defaultDeleteOnceAfterRing ?? true,
    enabled: true,
    permanentDisabled: false,
    pauseUntil: null,
    expired: false,
  }
}

const draft = ref<Alarm>(blankDraft())
const loading = ref(props.id !== null)
const saving = ref(false)
const confirmingDelete = ref(false)

/**
 * Why the last save/delete was refused, straight from the native side.
 *
 * Shown in the bar area rather than logged: a write that silently does nothing is the one failure
 * mode a user cannot tell apart from "my tap missed".
 */
const failure = ref<string | null>(null)

onMounted(async () => {
  if (props.id === null) return
  try {
    const a = await fetchAlarm(props.id)
    // Copy field by field rather than spreading: `AlarmView` carries derived members
    // (groupName / nextRingAt / status) that must not travel back into a save.
    draft.value = {
      id: a.id,
      groupId: a.groupId,
      hour: a.hour,
      minute: a.minute,
      label: a.label,
      repeatType: a.repeatType,
      repeatDays: a.repeatDays,
      onceDate: a.onceDate,
      ringtoneUri: a.ringtoneUri,
      ringtoneName: a.ringtoneName,
      vibrate: a.vibrate,
      fadeInSeconds: a.fadeInSeconds,
      snoozeEnabled: a.snoozeEnabled,
      snoozeMinutes: a.snoozeMinutes,
      snoozeMaxCount: a.snoozeMaxCount,
      autoStopMinutes: a.autoStopMinutes,
      deleteAfterRing: a.deleteAfterRing,
      enabled: a.enabled,
      permanentDisabled: a.permanentDisabled,
      pauseUntil: a.pauseUntil,
      expired: a.expired,
    }
  } finally {
    loading.value = false
  }
})

const isNew = computed(() => props.id === null)

/** The global 贪睡 switch (设置 → 响铃默认值). Off means this page must not offer snooze at all. */
const globalSnoozeEnabled = computed(() => state.settings?.snoozeEnabled ?? true)
const isOnce = computed(() => draft.value.repeatType === 'ONCE')

// ---------------------------------------------------------------------------------------
// Repeat rule
// ---------------------------------------------------------------------------------------

function setRepeat(next: RepeatType) {
  draft.value.repeatType = next
  if (next === 'WEEKLY' && draft.value.repeatDays === 0) {
    // Seed with the working week; a WEEKLY rule with no day selected can never fire.
    draft.value.repeatDays = 0b0011111
  }
}

function toggleWeekday(bit: number) {
  draft.value.repeatDays ^= 1 << bit
}

const weekdaysSelected = computed(() => {
  const mask = draft.value.repeatDays
  return WEEKDAY.map((label, i) => ({ label, bit: i, on: (mask & (1 << i)) !== 0 }))
})

/** PRD §5.1: a WEEKLY rule with no day matches can never ring, so block the save. */
const weeklyInvalid = computed(
  () => draft.value.repeatType === 'WEEKLY' && draft.value.repeatDays === 0
)

const onceTimePassed = computed(() => {
  if (!isOnce.value || !draft.value.onceDate) return false
  const [y, m, d] = draft.value.onceDate.split('-').map(Number)
  const at = new Date(y, m - 1, d, draft.value.hour, draft.value.minute).getTime()
  return at <= Date.now()
})

// ---------------------------------------------------------------------------------------
// Other fields
// ---------------------------------------------------------------------------------------

async function chooseRingtone() {
  const result = await bridge.pickRingtone({ current: draft.value.ringtoneUri })
  if (result.cancelled) return
  draft.value.ringtoneUri = result.uri
  draft.value.ringtoneName = result.name
}

/**
 * The group chips: 未分组 first, then the rest in 分组管理 order.
 *
 * The order is deliberately **not** the plain management order. That order exists for the list page,
 * where the groups are the page's structure and the user arranged them to match how they think about
 * their day. Here the chips are a picker and by far the most common answer is "no group at all", so the
 * one option chosen nearly every time belongs under the thumb.
 *
 * Only 未分组 is pinned. An earlier version pinned every `isSystem` group, which quietly put 工作日 and
 * 节假日 up there too — more than was asked for, and it pushed real user groups (吃药) to the end.
 */
const UNGROUPED = '未分组'
const groupChips = computed(() => {
  const sorted = [...state.groups].sort((a, b) => a.sortOrder - b.sortOrder)
  return sorted
    .map((g) => ({ ...g, pin: g.name === UNGROUPED ? 0 : 1 }))
    .sort((a, b) => a.pin - b.pin || a.sortOrder - b.sortOrder)
    .map((g) => ({ ...g, hex: argbToHex(g.color), tint: argbToRgba(g.color, 0.18) }))
})

// ---------------------------------------------------------------------------------------
// "6 小时 35 分钟后响铃" — borrowed from MIUI's own clock
// ---------------------------------------------------------------------------------------

/**
 * The next-ring instant for the *draft*, or null while it is unknown.
 *
 * Computed natively (see `previewNextRing`): the repeat rules, the holiday calendar and the pause
 * floor are business rules, and TECH-STACK §3 keeps those out of the WebView.
 *
 * `previewGen` is the ordering guard. Moving the wheel fires a request per change, and the responses
 * can arrive out of order; without it the line settles on an older answer than the wheel shows, which
 * is worse than showing nothing — it is a lie about when the alarm will ring.
 */
const nextRingAt = ref<number | null>(null)
const nextRingKnown = ref(false)
let previewGen = 0

async function refreshNextRing() {
  const gen = ++previewGen
  const at = await previewNextRing({ ...draft.value })
  if (gen !== previewGen) return
  nextRingAt.value = at
  nextRingKnown.value = true
}

const nextRingText = computed(() => {
  if (!nextRingKnown.value) return ''
  if (nextRingAt.value === null) return '不会响铃'
  return formatCountdown(nextRingAt.value, Date.now())
})

/** One preview per settled change: a spin would otherwise fire one per row it passes. */
let previewTimer = 0
watch(
  () => [
    draft.value.hour,
    draft.value.minute,
    draft.value.repeatType,
    draft.value.repeatDays,
    draft.value.onceDate,
    draft.value.groupId,
    draft.value.enabled,
  ],
  () => {
    window.clearTimeout(previewTimer)
    previewTimer = window.setTimeout(() => void refreshNextRing(), 120)
  },
  { immediate: true }
)

// ---------------------------------------------------------------------------------------
// Creating a group from here
// ---------------------------------------------------------------------------------------

/**
 * The group a user wants while setting an alarm — 「吃药」, 「健身」 — is thought of *here*, not in
 * 设置 → 分组管理. Until this existed the only way was to leave the editor, create the group
 * elsewhere, come back and pick it, so the group list quietly stopped growing.
 *
 * No colour picker: the name is what the user came to type, so the next unused palette entry is
 * assigned and can be changed later in 设置 → 分组管理.
 */
const newGroup = reactive({ open: false, name: '', busy: false, error: null as string | null })
const newGroupInput = ref<HTMLInputElement | null>(null)

function startNewGroup() {
  newGroup.open = true
  newGroup.name = ''
  newGroup.error = null
  // Focus once the input exists, or the user has to tap a second time to start typing.
  void nextTick(() => newGroupInput.value?.focus())
}

async function createGroup() {
  const name = newGroup.name.trim()
  if (!name || newGroup.busy) return

  // Typing the name of a group that already exists means "use that one", not "make a duplicate".
  const existing = state.groups.find((g) => g.name === name)
  if (existing) {
    draft.value.groupId = existing.id
    newGroup.open = false
    return
  }

  newGroup.busy = true
  newGroup.error = null
  try {
    const created = await saveGroup({
      name,
      color: GROUP_PALETTE[state.groups.length % GROUP_PALETTE.length].argb,
    })
    draft.value.groupId = created.id
    newGroup.open = false
  } catch (e) {
    // Same rule as saving an alarm: never fail silently.
    newGroup.error = e instanceof Error ? e.message : String(e)
  } finally {
    newGroup.busy = false
  }
}

const canSave = computed(() => !weeklyInvalid.value && !saving.value)

// ---------------------------------------------------------------------------------------
// Save / delete
// ---------------------------------------------------------------------------------------

async function onSave() {
  if (!canSave.value) return
  saving.value = true
  failure.value = null
  try {
    const patch: Partial<Alarm> = { ...draft.value }
    // An absent id is what tells the bridge to insert rather than update.
    if (isNew.value) delete patch.id
    await saveAlarm(patch)
    emit('saved')
  } catch (e) {
    /*
     * Until this existed, a failed save did *nothing at all* on screen: the rejection escaped the
     * click handler as an unhandled promise, the button went back to 「保存」, and the write simply
     * never happened. The first real-device report was exactly that — 「基本无法点击保存，只成功了一次」 —
     * and there was no way to tell "the tap missed" from "the write was refused", on either side.
     * A failure the user cannot see is a bug in its own right.
     */
    failure.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}

async function onDelete() {
  if (!confirmingDelete.value) {
    confirmingDelete.value = true
    return
  }
  failure.value = null
  try {
    await deleteAlarm(draft.value.id)
    emit('saved')
  } catch (e) {
    failure.value = e instanceof Error ? e.message : String(e)
  } finally {
    confirmingDelete.value = false
  }
}
</script>

<template>
  <div class="page">
    <header class="bar">
      <button type="button" class="bar__action" @click="emit('back')">取消</button>
      <span class="bar__text">
        <h1 class="bar__title">{{ isNew ? '新建闹钟' : '编辑闹钟' }}</h1>
        <!--
          Borrowed from MIUI's own clock, which puts 「6小时35分钟后响铃」 under the title. It answers
          the mistake this screen makes easiest to make: picking the right time and the wrong day.
          Computed natively (see `previewNextRing`) — the repeat rules are not the WebView's business.
        -->
        <span v-if="nextRingText" class="bar__sub">{{ nextRingText }}</span>
      </span>
      <button
        type="button"
        class="bar__action bar__action--primary"
        :disabled="!canSave"
        @click="onSave"
      >
        {{ saving ? '保存中' : '保存' }}
      </button>
    </header>

    <!-- A refusal has to be visible. See `failure` in the script: this is what turned the
         「点保存没反应」 report into something diagnosable. -->
    <p v-if="failure" class="failure" role="alert">
      <strong>没能保存：</strong>{{ failure }}
      <button type="button" class="failure__again" @click="onSave">重试</button>
    </p>

    <p v-if="loading" class="loading">载入中…</p>

    <div v-else class="body">
      <!-- time -->
      <section class="card card--plain">
        <WheelTimePicker
          v-model:hour="draft.hour"
          v-model:minute="draft.minute"
          :format="state.settings?.timeFormat ?? '24'"
        />
      </section>

      <!-- schedule -->
      <section class="card">
        <FormRow label="重复" stacked>
          <div class="chips">
            <button
              v-for="opt in REPEAT_OPTIONS"
              :key="opt.value"
              type="button"
              class="chip"
              :class="{ 'chip--on': draft.repeatType === opt.value }"
              :aria-pressed="draft.repeatType === opt.value"
              @click="setRepeat(opt.value)"
            >
              {{ opt.label }}
            </button>
          </div>
        </FormRow>

        <FormRow
          v-if="draft.repeatType === 'WEEKLY'"
          label="重复日"
          :hint="weeklyInvalid ? '至少选择一天，否则这个闹钟永远不会响' : undefined"
          stacked
        >
          <div class="chips chips--week">
            <button
              v-for="w in weekdaysSelected"
              :key="w.bit"
              type="button"
              class="chip chip--round"
              :class="{ 'chip--on': w.on, 'chip--danger': weeklyInvalid && !w.on }"
              :aria-pressed="w.on"
              @click="toggleWeekday(w.bit)"
            >
              {{ w.label }}
            </button>
          </div>
        </FormRow>

        <FormRow
          v-if="isOnce"
          label="日期"
          :hint="onceTimePassed ? '这个时间点已经过去了，保存后会顺延到下一个该时刻' : undefined"
        >
          <input v-model="draft.onceDate" class="date" type="date" />
        </FormRow>

        <FormRow label="备注">
          <input
            v-model="draft.label"
            class="text"
            type="text"
            maxlength="20"
            placeholder="闹钟"
          />
        </FormRow>

        <FormRow label="分组" stacked>
          <div class="chips">
            <button
              v-for="g in groupChips"
              :key="g.id"
              type="button"
              class="chip chip--group"
              :class="{ 'chip--on': draft.groupId === g.id }"
              :style="{ '--group': g.hex, '--group-tint': g.tint }"
              :aria-pressed="draft.groupId === g.id"
              @click="draft.groupId = g.id"
            >
              <span class="chip__dot" aria-hidden="true" />
              {{ g.name }}
            </button>

            <button
              v-if="!newGroup.open"
              type="button"
              class="chip chip--add"
              @click="startNewGroup"
            >
              ＋ 新建
            </button>
          </div>

          <div v-if="newGroup.open" class="newgroup">
            <input
              ref="newGroupInput"
              v-model="newGroup.name"
              class="newgroup__input"
              type="text"
              maxlength="12"
              placeholder="分组名称，如 吃药"
              enterkeyhint="done"
              @keyup.enter="createGroup"
            />
            <button
              type="button"
              class="newgroup__ok"
              :disabled="!newGroup.name.trim() || newGroup.busy"
              @click="createGroup"
            >
              创建
            </button>
            <button type="button" class="newgroup__cancel" @click="newGroup.open = false">
              取消
            </button>
          </div>
          <p v-if="newGroup.error" class="newgroup__error">创建失败：{{ newGroup.error }}</p>
        </FormRow>
      </section>

      <!-- sound -->
      <section class="card">
        <FormRow label="铃声">
          <button type="button" class="value" @click="chooseRingtone">
            {{ draft.ringtoneName || '系统默认铃声' }}
            <span class="value__caret" aria-hidden="true">›</span>
          </button>
        </FormRow>

        <FormRow label="震动">
          <ToggleSwitch v-model="draft.vibrate" label="震动" />
        </FormRow>

        <FormRow label="音量渐强" hint="从静音逐渐升到目标音量" stacked>
          <div class="chips">
            <button
              v-for="opt in FADE_OPTIONS"
              :key="opt.value"
              type="button"
              class="chip"
              :class="{ 'chip--on': draft.fadeInSeconds === opt.value }"
              :aria-pressed="draft.fadeInSeconds === opt.value"
              @click="draft.fadeInSeconds = opt.value"
            >
              {{ opt.label }}
            </button>
          </div>
        </FormRow>
      </section>

      <!-- ringing behaviour -->
      <section class="card">
        <!--
          Hidden entirely while 贪睡 is off globally. Leaving it visible would offer a switch that
          cannot take effect — the ring page removes 贪睡 regardless — so the only honest thing is to
          not show it. `draft.snoozeEnabled` is still sent on save; it is simply not in play.
        -->
        <template v-if="globalSnoozeEnabled">
          <FormRow label="贪睡" hint="响铃时可以先按贪睡，稍后再响">
            <ToggleSwitch v-model="draft.snoozeEnabled" label="贪睡" />
          </FormRow>
        </template>

        <template v-if="globalSnoozeEnabled && draft.snoozeEnabled">
          <FormRow label="贪睡时长" stacked>
            <div class="chips">
              <button
                v-for="m in SNOOZE_MINUTES"
                :key="m"
                type="button"
                class="chip"
                :class="{ 'chip--on': draft.snoozeMinutes === m }"
                :aria-pressed="draft.snoozeMinutes === m"
                @click="draft.snoozeMinutes = m"
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
                :class="{ 'chip--on': draft.snoozeMaxCount === c.value }"
                :aria-pressed="draft.snoozeMaxCount === c.value"
                @click="draft.snoozeMaxCount = c.value"
              >
                {{ c.label }}
              </button>
            </div>
          </FormRow>
        </template>

        <FormRow label="响铃超时" hint="超过这个时长自动停止；选「不限」则一直响到手动关闭" stacked>
          <div class="chips">
            <button
              v-for="opt in AUTO_STOP"
              :key="opt.value"
              type="button"
              class="chip"
              :class="{ 'chip--on': draft.autoStopMinutes === opt.value }"
              :aria-pressed="draft.autoStopMinutes === opt.value"
              @click="draft.autoStopMinutes = opt.value"
            >
              {{ opt.label }}
            </button>
          </div>
        </FormRow>
      </section>

      <!-- one-off specific -->
      <section v-if="isOnce" class="card">
        <FormRow
          label="响铃后删除"
          :hint="`一次性闹钟响过之后自动消失。新闹钟的默认值跟随设置里的总开关（当前${
            state.settings?.defaultDeleteOnceAfterRing ? '已开启' : '已关闭'
          }）`"
        >
          <ToggleSwitch v-model="draft.deleteAfterRing" label="响铃后删除" />
        </FormRow>
      </section>

      <section v-if="!isNew" class="card">
        <FormRow label="删除闹钟" hint="删除后无法恢复">
          <button
            type="button"
            class="danger"
            :class="{ 'danger--armed': confirmingDelete }"
            @click="onDelete"
          >
            {{ confirmingDelete ? '确认删除？' : '删除' }}
          </button>
        </FormRow>
      </section>

      <!--
        A second save button, full width and well away from the screen edges.

        Added after the first real-device report of 「基本无法点击保存」. The bar's button sits in the
        top-right corner, which on an edge-to-edge phone is inside the system's right-edge back-gesture
        strip *and* right under the status-bar pull-down region — the two places a tap is least
        reliable. This one is under the thumb and nowhere near either. The top one stays, because it is
        what the M1b design review approved.
      -->
      <button type="button" class="save" :disabled="!canSave" @click="onSave">
        {{ saving ? '保存中…' : '保存' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-block-size: 100vh;
  padding-block-end: calc(var(--sp-6) + var(--safe-bottom));
}

/* ---- top bar ---- */

.bar {
  position: sticky;
  inset-block-start: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: calc(var(--safe-top) + var(--sp-2)) var(--page-x) var(--sp-2);
  background: var(--bg);
  border-block-end: 1px solid var(--border);
}

.bar__action {
  /* 44 was Apple's old minimum; 48 is the current one, and these two live in the corners where
     every pixel of target matters. */
  min-block-size: 48px;
  min-inline-size: 56px;
  padding: 0 var(--sp-3);
  font-size: var(--fs-sm);
  color: var(--accent);
}

.bar__action--primary {
  font-weight: var(--fw-semibold);
}

.bar__action:disabled {
  color: var(--text-faint);
  opacity: 0.6;
}

/* ---- save failure ---- */

.failure {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin: var(--sp-2) var(--page-x) 0;
  padding: var(--sp-3);
  border-radius: var(--r-md);
  background: var(--tint-danger);
  border: 1px solid color-mix(in srgb, var(--danger) 45%, transparent);
  color: var(--danger);
  font-size: var(--fs-xs);
  line-height: 1.6;
}

.failure__again {
  flex: 0 0 auto;
  margin-inline-start: auto;
  min-block-size: 36px;
  padding: 0 var(--sp-3);
  border-radius: var(--r-sm);
  border: 1px solid currentColor;
  color: inherit;
  font-size: var(--fs-xs);
}

/* ---- the thumb-reachable save, at the bottom of the form ---- */

.save {
  inline-size: 100%;
  min-block-size: 52px;
  margin-block-start: var(--sp-2);
  border-radius: var(--r-md);
  background: var(--accent);
  border: 1px solid var(--accent);
  color: var(--accent-ink);
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
}

.save:disabled {
  background: var(--surface-2);
  border-color: var(--border);
  color: var(--text-faint);
}

.bar__title {
  text-align: center;
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
}

/* Holds the title and the relative-time line so the header stays one row tall. */
.bar__text {
  flex: 1 1 auto;
  min-inline-size: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
}

.bar__sub {
  font-size: var(--fs-2xs);
  color: var(--text-faint);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* ---- creating a group from the editor ---- */

.chip--add {
  border-style: dashed;
  color: var(--accent);
}

.newgroup {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-block-start: var(--sp-2);
}

.newgroup__input {
  flex: 1 1 auto;
  min-inline-size: 0;
  min-block-size: 44px;
  padding: 0 var(--sp-3);
  border-radius: var(--r-md);
  border: 1px solid var(--border-strong);
  background: var(--surface);
  color: var(--text);
  font-size: var(--fs-sm);
}

.newgroup__ok,
.newgroup__cancel {
  flex: 0 0 auto;
  min-block-size: 44px;
  padding: 0 var(--sp-3);
  border-radius: var(--r-md);
  font-size: var(--fs-sm);
}

.newgroup__ok {
  background: var(--accent);
  color: var(--accent-ink);
  font-weight: var(--fw-medium);
}

.newgroup__ok:disabled {
  opacity: var(--dim-opacity);
}

.newgroup__cancel {
  color: var(--text-faint);
}

.newgroup__error {
  margin-block-start: var(--sp-1);
  font-size: var(--fs-xs);
  color: var(--danger);
}

.loading {
  padding: var(--sp-7) var(--page-x);
  color: var(--text-faint);
  text-align: center;
}

/* ---- layout ---- */

.body {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--page-x) 0;
}

.card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  overflow: hidden;
}

.card--plain {
  display: grid;
  place-items: center;
  padding: var(--sp-3) 0;
}

/* ---- chips ---- */

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
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

.chip--danger {
  border-color: var(--danger);
  color: var(--danger);
}

.chips--week .chip--round {
  inline-size: 42px;
  padding: 0;
  justify-content: center;
}

.chip--group {
  border-color: color-mix(in srgb, var(--group) 45%, var(--border));
}

.chip--group.chip--on {
  background: var(--group);
  border-color: var(--group);
  color: #06121f;
}

.chip__dot {
  inline-size: 8px;
  block-size: 8px;
  border-radius: 50%;
  background: var(--group);
}

.chip--on .chip__dot {
  background: #06121f;
}

/* ---- value button ---- */

.value {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-block-size: 38px;
  padding: 0 12px;
  border-radius: var(--r-sm);
  background: var(--surface-2);
  border: 1px solid var(--border);
  font-size: var(--fs-sm);
  max-inline-size: 58vw;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.value__caret {
  color: var(--text-faint);
}

/* ---- inputs ---- */

.text,
.date {
  min-block-size: 40px;
  padding: 0 12px;
  border-radius: var(--r-sm);
  background: var(--surface-2);
  border: 1px solid var(--border);
  color-scheme: inherit;
  font-size: var(--fs-sm);
}

/* A free-text label reads better left-aligned; the date keeps the platform's own alignment. */
.text {
  inline-size: 52vw;
  text-align: start;
}

.date {
  text-align: end;
}

/* ---- destructive ---- */

.danger {
  min-block-size: 38px;
  padding: 0 16px;
  border-radius: var(--r-sm);
  background: var(--tint-danger);
  color: var(--danger);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
}

.danger--armed {
  background: var(--danger);
  color: #fff;
}
</style>
