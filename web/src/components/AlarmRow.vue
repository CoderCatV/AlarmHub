<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import type { AlarmView, TimeFormat } from '../bridge/types'
import { formatClock, formatCountdown, formatRepeat, statusBadge } from '../utils/format'
import ToggleSwitch from './ToggleSwitch.vue'

/**
 * `showSwitch` needs an explicit default: Vue casts an absent Boolean prop to `false`, so a
 * bare `showSwitch?: boolean` would hide the switch on every normal row. This was a real bug
 * — the layout still looked plausible without switches, so only a DOM probe caught it.
 */
const props = withDefaults(
  defineProps<{
    alarm: AlarmView
    now: number
    timeFormat: TimeFormat
    /** Expired rows are shown for information only — no switch to flip. */
    showSwitch?: boolean
    /** True while the list is in long-press multi-select mode. */
    selectMode?: boolean
    /** Whether this row is ticked, only meaningful in select mode. */
    selected?: boolean
  }>(),
  { showSwitch: true, selectMode: false, selected: false }
)

const emit = defineEmits<{
  toggle: [id: number, enabled: boolean]
  open: [id: number]
  /** Long-press: the caller decides whether that starts or extends a selection. */
  longPress: [id: number]
  /** A tap in select mode toggles the tick instead of opening the editor. */
  pick: [id: number]
}>()

// ---------------------------------------------------------------------------------------
// Long-press
// ---------------------------------------------------------------------------------------

/**
 * How long a finger has to stay put before it counts as a long press. 450 ms is the Android
 * `ViewConfiguration.getLongPressTimeout()` default, so it matches what the phone's own lists feel
 * like rather than a number invented here.
 */
const LONG_PRESS_MS = 450
/** Moving further than this cancels the press: the finger was scrolling, not holding. */
const MOVE_TOLERANCE_PX = 12

const pressing = ref(false)
let timer: number | null = null
let originX = 0
let originY = 0
/** Swallows the click that the browser fires after a long press, so it does not also open the editor. */
let swallowClick = false

function clearTimer() {
  if (timer !== null) {
    window.clearTimeout(timer)
    timer = null
  }
}

function onPointerDown(e: PointerEvent) {
  // Secondary buttons (right-click, stylus barrel) are not a long press.
  if (e.button !== 0) return
  clearTimer()
  swallowClick = false
  originX = e.clientX
  originY = e.clientY
  pressing.value = true
  timer = window.setTimeout(() => {
    timer = null
    if (!pressing.value) return
    pressing.value = false
    swallowClick = true
    // A short buzz is the only feedback the WebView can give that the press "took" — the OS haptic
    // for a native long press has already been consumed by the row's own touch listener.
    navigator.vibrate?.(12)
    emit('longPress', props.alarm.id)
  }, LONG_PRESS_MS)
}

function onPointerMove(e: PointerEvent) {
  if (!pressing.value) return
  if (Math.abs(e.clientX - originX) > MOVE_TOLERANCE_PX || Math.abs(e.clientY - originY) > MOVE_TOLERANCE_PX) {
    pressing.value = false
    clearTimer()
  }
}

function onPointerUp() {
  pressing.value = false
  clearTimer()
}

function onMainClick() {
  if (swallowClick) {
    swallowClick = false
    return
  }
  if (props.selectMode) emit('pick', props.alarm.id)
  else emit('open', props.alarm.id)
}

onUnmounted(clearTimer)

const clock = computed(() => formatClock(props.alarm.hour, props.alarm.minute, props.timeFormat))
const repeat = computed(() => formatRepeat(props.alarm, props.now))

/** Only a `scheduled` alarm is dimmed-able; everything else gets the grey treatment. */
const dimmed = computed(() => props.alarm.status !== 'scheduled')

const badge = computed(() => statusBadge(props.alarm.status, props.alarm, props.now, props.timeFormat))

/**
 * The countdown is meaningless while paused or expired, so those rows show the badge
 * instead. The two never compete for the same line.
 */
const countdown = computed(() =>
  props.alarm.status === 'scheduled' && props.alarm.nextRingAt !== null
    ? formatCountdown(props.alarm.nextRingAt, props.now)
    : null
)

const label = computed(() => props.alarm.label.trim() || '闹钟')
</script>

<template>
  <li class="row" :class="{ 'row--dimmed': dimmed, 'row--picked': selectMode && selected }">
    <span v-if="selectMode" class="row__tick" :class="{ 'row__tick--on': selected }" aria-hidden="true">
      <svg viewBox="0 0 24 24" class="row__tick-mark">
        <path d="M5 13l4 4 10-10" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </span>

    <button
      type="button"
      class="row__main"
      @click="onMainClick"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
      @pointerleave="onPointerUp"
      @contextmenu.prevent
    >
      <span class="row__line">
        <span class="row__time">{{ clock }}</span>
        <span v-if="badge" class="row__badge">{{ badge }}</span>
      </span>

      <span class="row__label">{{ label }}</span>

      <span class="row__meta">
        <span>{{ repeat }}</span>
        <template v-if="countdown">
          <span class="row__dot">·</span>
          <span>{{ countdown }}</span>
        </template>
      </span>
    </button>

    <ToggleSwitch
      v-if="showSwitch && !selectMode"
      class="row__switch"
      :model-value="alarm.enabled"
      :label="`${label} ${clock}`"
      @update:model-value="(v) => emit('toggle', alarm.id, v)"
    />
  </li>
</template>

<style scoped>
.row {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  transition: opacity var(--dur-base) var(--ease);
}

.row + .row {
  border-block-start: 1px solid var(--border);
}

.row--dimmed {
  opacity: var(--dim-opacity);
}

/* A ticked row keeps full opacity and gains a tint, so "dimmed because disabled" and
   "dimmed because picked" cannot be confused. */
.row--picked {
  opacity: 1;
  background: var(--tint-accent);
}

/* Long-pressing must not select the text or pop the WebView's own context menu. */
.row__main {
  user-select: none;
  -webkit-user-select: none;
  -webkit-touch-callout: none;
}

/* ---- selection tick ---- */

.row__tick {
  flex: 0 0 auto;
  inline-size: 24px;
  block-size: 24px;
  margin-block-start: 6px;
  border-radius: 50%;
  border: 2px solid var(--border-strong);
  display: grid;
  place-items: center;
  color: transparent;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}

.row__tick--on {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-ink);
}

.row__tick-mark {
  inline-size: 16px;
  block-size: 16px;
}

.row__main {
  flex: 1 1 auto;
  min-inline-size: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  text-align: start;
}

.row__line {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  min-inline-size: 0;
}

.row__time {
  font-size: var(--fs-clock);
  font-weight: var(--fw-medium);
  line-height: var(--lh-tight);
  letter-spacing: -0.5px;
  font-variant-numeric: tabular-nums;
}

.row__badge {
  font-size: var(--fs-2xs);
  color: var(--warn);
  background: var(--tint-warn);
  border-radius: var(--r-pill);
  padding: 1px 7px;
  white-space: nowrap;
}

.row__label {
  font-size: var(--fs-sm);
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row__meta {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-xs);
  color: var(--text-faint);
  overflow: hidden;
  white-space: nowrap;
}

.row__dot {
  opacity: 0.6;
}

.row__switch {
  margin-block-start: 6px;
}
</style>
