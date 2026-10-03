<script setup lang="ts">
/**
 * Wheel time picker — the first-version choice from TECH-STACK §9 (T3): a scrolling wheel
 * rather than a clock face. Two columns in 24-hour mode, three when 12-hour is on.
 *
 * The scrolling is native (CSS scroll-snap); JS only reads the resting index.
 *
 * ## Why this file was rewritten after the first real-device test
 *
 * M1b reviewed this wheel in a browser and M6 drove it over the DevTools protocol — **both by
 * clicking individual items, which is not the gesture it exists for**. The first time a finger
 * flicked it (on the user's phone) it was unusable: a quick 130 px drag moved the hour column
 * **10–17 items**, so anything between 08:00 and 22:00 was effectively unreachable. Measured on the
 * emulator with `adb shell input swipe`: 13 items for a 130 px/150 ms drag, where the finger itself
 * only travelled ~3 items.
 *
 * That is normal browser fling physics, not a bug — and it is exactly why a picker has to constrain
 * it. Three changes do that, all measured:
 *
 * 1. **`scroll-snap-stop: always`** — a gesture may pass at most one snap point, so the wheel
 *    travels roughly as far as the finger. Without it, `scroll-snap-type: mandatory` still lets a
 *    fling sail past a dozen stops and slam into the end of the list.
 * 2. **No `mask-image` on the scrolling element.** A mask on a scroller is a well-known way to push
 *    it off the compositor's fast path and rasterise on every frame; with 84 items in two columns
 *    that is what "不流畅" was. The end fade is now a static overlay gradient on the wrapper — same
 *    look, no per-frame work.
 * 3. **No per-item JS styling.** The old version wrote `opacity` and `transform: scale()` inline for
 *    all 84 items on every scroll frame. Emphasis is now `[aria-selected='true']` in CSS, which the
 *    browser updates itself.
 *
 * `overscroll-behavior: contain` is belt-and-braces: a flick at the end of a column must never chain
 * into the page, because the page scrolling under the finger looks exactly like "the wheel jumped".
 *
 * **Not** done, deliberately: a custom drag/fling implementation with velocity capping. It would let
 * a fast flick still cross many items while landing softly, which is what a stock time picker feels
 * like. It is ~60 lines of pointer plumbing and a new class of bug, so it waits until the cheap fixes
 * have been looked at on the phone (see docs/手机反馈-滚轮与保存.md).
 */

import { computed, onMounted, ref, watch } from 'vue'
import type { TimeFormat } from '../bridge/types'

const props = defineProps<{
  hour: number
  minute: number
  format: TimeFormat
}>()

const emit = defineEmits<{
  'update:hour': [value: number]
  'update:minute': [value: number]
}>()

/** Must match `--wheel-item` in the stylesheet; the scroll maths divides by it. */
const ITEM_H = 48
// The number of visible rows (5) lives in CSS as `--wheel-visible`, because only the layout
// needs it — the JS works purely in item units.

/**
 * How many identical copies of each column are rendered, and the reason the wheel can wrap.
 *
 * The list is finite but the *content* is cyclic (23 is followed by 00), so the column is rendered
 * [COPIES] times and silently re-centred whenever the user scrolls out of the middle copy. The
 * copies are byte-identical, so the jump is invisible.
 *
 * Three is the smallest number that works: the user can travel a whole copy's worth of items in
 * either direction before a re-centre is needed, and `scroll-snap-stop: always` already caps one
 * gesture at a few items (measured: 2–3).
 *
 * **A re-centre cannot change the selected value, and that is a structural property, not a
 * coincidence.** The logical index is `domIndex % count`, and shifting the scroll position by a
 * whole copy adds exactly `count` to `domIndex` — so the modulo is unchanged, no `change` is emitted,
 * and the "scroll handler reacts to its own correction" loop that this pattern usually brings with
 * it cannot happen here. See [settle] for when the correction runs.
 */
const COPIES = 3
/** Where the middle copy starts: the position a re-centre always returns to. */
const MID_COPY = Math.floor(COPIES / 2)

const hourCol = ref<HTMLElement | null>(null)
const minuteCol = ref<HTMLElement | null>(null)

const hourIndices = computed(() =>
  props.format === '24'
    ? Array.from({ length: 24 }, (_, i) => i)
    : Array.from({ length: 12 }, (_, i) => i)
)

const minuteIndices = Array.from({ length: 60 }, (_, i) => i)

const pad2 = (n: number) => String(n).padStart(2, '0')

/** Wheel index → the text shown on it. Declared before the item lists that call it. */
function hourLabelOf(index: number, format: TimeFormat): string {
  if (format === '24') return pad2(index)
  return String(index === 0 ? 12 : index)
}

/**
 * The rendered rows: every logical index, once per copy.
 *
 * `key` must be unique across copies or Vue reuses the wrong nodes and the emphasis lands on the
 * wrong row; `aria-selected` is applied by *logical* index, which marks one row in each copy — and
 * because the copies are a full column apart, exactly one of them is ever on screen.
 */
function buildItems(indices: number[], prefix: string, label: (i: number) => string) {
  const out: { key: string; logical: number; label: string }[] = []
  for (let copy = 0; copy < COPIES; copy++) {
    for (const i of indices) out.push({ key: `${prefix}${copy}-${i}`, logical: i, label: label(i) })
  }
  return out
}

const hourItems = computed(() =>
  buildItems(hourIndices.value, 'h', (i) => hourLabelOf(i, props.format))
)
const minuteItems = computed(() => buildItems(minuteIndices, 'm', (i) => pad2(i)))

const hourIndex = computed(() => (props.format === '24' ? props.hour : props.hour % 12))
const minuteIndex = computed(() => props.minute)
const period = computed<'am' | 'pm'>(() => (props.hour < 12 ? 'am' : 'pm'))

/**
 * Wheel index → hour.
 *
 * **This is where the milestone-1 wheel was broken, and it took a real finger to find it.**
 * The 12-hour branch was applied unconditionally, so in 24-hour mode — the factory default, and
 * what the reporting device used — the hour column produced:
 *
 * | wheel index | hour it produced | what the user saw |
 * |---|---|---|
 * | 11 | 11 | fine |
 * | 12 | **0** | the wheel jumps back to 00, so **12 and beyond were unreachable** |
 * | 13…23 | **25…35** | no item matches any more, so **the whole column renders grey** and saving throws `Invalid value for HourOfDay` |
 *
 * The grey column and the failed save were therefore one bug, not two: arriving at index 13 or
 * beyond put `draft.hour` out of range, which broke selection *and* the native write. Every earlier
 * check picked a time by **clicking an item** (browser review, CDP) and never crossed 11 → 12, so
 * neither half showed up.
 */
function hourFromIndex(index: number, p: 'am' | 'pm'): number {
  // In 24-hour mode the wheel index *is* the hour. There is no am/pm to fold in.
  if (props.format === '24') return index
  const h12 = index === 0 ? 12 : index
  if (p === 'am') return h12 === 12 ? 0 : h12
  return h12 === 12 ? 12 : h12 + 12
}

/**
 * Jumps a column to a resting position.
 *
 * Always instant, never `behavior: 'smooth'`: a smooth programmatic scroll is allowed to halt at the
 * first snap point it meets once `scroll-snap-stop: always` is in play, which would turn "tap 21"
 * into "advance one item". A direct position write is not subject to snapping at all, and every tap
 * here moves at most two items, so there is nothing to animate.
 *
 * The target is always in the **middle copy**, which leaves a whole copy of travel available in both
 * directions before the next re-centre.
 */
function scrollToIndex(el: HTMLElement | null, logical: number, count: number) {
  if (!el) return
  const top = (MID_COPY * count + logical) * ITEM_H
  if (Math.abs(el.scrollTop - top) < 1) return
  el.scrollTop = top
}

/** `suppress` stops the programmatic sync from being read back as a user scroll. */
let suppress = false
let raf = 0
let settleTimer = 0

/**
 * Re-centres a column that has wandered into an outer copy.
 *
 * Runs **only after the scroll settles** rather than during it. Correcting mid-gesture would fight
 * the finger: the browser derives the next position from the touch delta and would simply overwrite
 * the correction, and doing it mid-momentum makes the remaining fling start from a different scroll
 * origin than the one the compositor is animating.
 *
 * Writing `scrollTop` here fires one more `scroll` event, which reads the same logical index and
 * therefore emits nothing — see [COPIES] for why that is guaranteed rather than merely likely.
 */
function settle() {
  const hEl = hourCol.value
  if (hEl) recentre(hEl, hourIndices.value.length)
  const mEl = minuteCol.value
  if (mEl) recentre(mEl, minuteIndices.length)
}

function recentre(el: HTMLElement, count: number) {
  const domIndex = Math.round(el.scrollTop / ITEM_H)
  // Already inside the middle copy: nothing to do. This is the common case, and it is what makes a
  // normal gesture free of any correction at all.
  if (domIndex >= count && domIndex < count * 2) return
  const logical = ((domIndex % count) + count) % count
  // The target keeps the *logical* index and moves the *copy* back to the middle. Because the copies
  // are identical this is invisible, and because `logical` is unchanged no change event follows.
  el.scrollTop = (MID_COPY * count + logical) * ITEM_H
}

/** domIndex → the logical value, for a column of `count` logical entries. */
const logicalOf = (domIndex: number, count: number) => ((domIndex % count) + count) % count

function handleScroll() {
  if (suppress) return
  if (raf) return
  raf = requestAnimationFrame(() => {
    raf = 0
    const hEl = hourCol.value
    const mEl = minuteCol.value
    if (hEl) {
      const domIndex = Math.round(hEl.scrollTop / ITEM_H)
      const next = logicalOf(domIndex, hourIndices.value.length)
      if (next !== hourIndex.value) emit('update:hour', hourFromIndex(next, period.value))
    }
    if (mEl) {
      const domIndex = Math.round(mEl.scrollTop / ITEM_H)
      const next = logicalOf(domIndex, 60)
      if (next !== minuteIndex.value) emit('update:minute', next)
    }
  })

  window.clearTimeout(settleTimer)
  settleTimer = window.setTimeout(settle, 140)
}

function pickHour(index: number) {
  emit('update:hour', hourFromIndex(index, period.value))
  scrollToIndex(hourCol.value, index, hourIndices.value.length)
}

function pickMinute(index: number) {
  emit('update:minute', index)
  scrollToIndex(minuteCol.value, index, 60)
}

function setPeriod(next: 'am' | 'pm') {
  if (next === period.value) return
  emit('update:hour', hourFromIndex(hourIndex.value, next))
}

onMounted(() => {
  suppress = true
  scrollToIndex(hourCol.value, hourIndex.value, hourIndices.value.length)
  scrollToIndex(minuteCol.value, minuteIndex.value, 60)
  requestAnimationFrame(() => {
    suppress = false
  })
})

watch([hourIndex, minuteIndex], ([hi, mi]) => {
  suppress = true
  scrollToIndex(hourCol.value, hi, hourIndices.value.length)
  scrollToIndex(minuteCol.value, mi, 60)
  window.setTimeout(() => {
    suppress = false
  }, 120)
})
</script>

<template>
  <div class="wheel">
    <div class="wheel__band" aria-hidden="true" />

    <!--
      Column labels, borrowed from MIUI's own picker. They earn their place because 24-hour mode shows
      two identical-looking two-digit columns side by side: `23 : 59` does not say which is which the
      way `11 : 59 pm` does.

      Each label is absolutely positioned *above* its column, inside the wheel's top padding, so the
      column itself keeps its exact height. That matters more than it sounds: `.col` is `block-size:
      100%`, and the whole selection model rests on `round(scrollTop / 48)` with exactly five rows
      visible — a label taking a row out of the scroller would break the arithmetic that decides which
      number is selected.
    -->
    <div class="colwrap">
      <span class="colwrap__head" aria-hidden="true">时</span>
      <div ref="hourCol" class="col" role="listbox" aria-label="小时" @scroll.passive="handleScroll">
        <div class="col__pad" aria-hidden="true" />
        <button
          v-for="it in hourItems"
          :key="it.key"
          type="button"
          class="item"
          role="option"
          :aria-selected="it.logical === hourIndex"
          @click="pickHour(it.logical)"
        >
          {{ it.label }}
        </button>
        <div class="col__pad" aria-hidden="true" />
      </div>
    </div>

    <span class="colon" aria-hidden="true">:</span>

    <div class="colwrap">
      <span class="colwrap__head" aria-hidden="true">分</span>
      <div ref="minuteCol" class="col" role="listbox" aria-label="分钟" @scroll.passive="handleScroll">
        <div class="col__pad" aria-hidden="true" />
        <button
          v-for="it in minuteItems"
          :key="it.key"
          type="button"
          class="item"
          role="option"
          :aria-selected="it.logical === minuteIndex"
          @click="pickMinute(it.logical)"
        >
          {{ it.label }}
        </button>
        <div class="col__pad" aria-hidden="true" />
      </div>
    </div>

    <div v-if="format === '12'" class="period" role="radiogroup" aria-label="上午下午">
      <button
        type="button"
        class="period__opt"
        :class="{ 'period__opt--on': period === 'am' }"
        role="radio"
        :aria-checked="period === 'am'"
        @click="setPeriod('am')"
      >
        上午
      </button>
      <button
        type="button"
        class="period__opt"
        :class="{ 'period__opt--on': period === 'pm' }"
        role="radio"
        :aria-checked="period === 'pm'"
        @click="setPeriod('pm')"
      >
        下午
      </button>
    </div>
  </div>
</template>

<style scoped>
.wheel {
  --wheel-item: 48px;
  --wheel-visible: 5;
  /* Reserved for the 时 / 分 labels above the columns: they live in this padding, so the columns
     below keep exactly `item × visible` and the index arithmetic is untouched. */
  --wheel-head-h: 18px;

  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding-block-start: var(--wheel-head-h);
}

/*
  One wrapper per column, so a label can sit above a column without becoming part of the scroller.

  Absolutely positioned children resolve against the **padding box**, which is why the fades and the
  selection band below are offset by `--wheel-head-h`: without that they would be measured from the
  top of the labels rather than the top of the numbers, and the band would miss the middle row.
*/
.colwrap {
  position: relative;
  block-size: calc(var(--wheel-item) * var(--wheel-visible));
}

.colwrap__head {
  position: absolute;
  inset-block-start: calc(var(--wheel-head-h) * -1);
  inset-inline: 0;
  block-size: var(--wheel-head-h);
  line-height: var(--wheel-head-h);
  text-align: center;
  font-size: var(--fs-2xs);
  color: var(--text-faint);
  user-select: none;
}

/* The end fade, as static overlays **on the wrapper** rather than a mask on the scroller.
   A mask belongs on something that does not scroll: putting one on `.col` pushed the scroll off the
   compositor's fast path and was the main reason the wheel felt sticky. */
.wheel::before,
.wheel::after {
  content: '';
  position: absolute;
  inset-inline: 0;
  block-size: calc(var(--wheel-item) * 1.25);
  pointer-events: none;
}

.wheel::before {
  inset-block-start: var(--wheel-head-h);
  background: linear-gradient(to bottom, var(--surface), transparent);
}

.wheel::after {
  inset-block-end: 0;
  background: linear-gradient(to top, var(--surface), transparent);
}

/* The horizontal band marking the selected row. */
.wheel__band {
  position: absolute;
  inset-inline: 12%;
  block-size: var(--wheel-item);
  /* Centre of the *columns*, not of the padding box: the labels above occupy `--wheel-head-h`. */
  inset-block-start: calc(var(--wheel-head-h) + var(--wheel-item) * var(--wheel-visible) / 2);
  transform: translateY(-50%);
  background: var(--surface-2);
  border-radius: var(--r-md);
  pointer-events: none;
}

.col {
  position: relative;
  block-size: 100%;
  inline-size: 84px;
  overflow-y: auto;
  scroll-snap-type: y mandatory;
  scrollbar-width: none;
  /* A flick at the end of a column must not scroll the page underneath: from the user's side that
     is indistinguishable from the wheel jumping on its own. */
  overscroll-behavior: contain;
  /* Only vertical panning belongs to the wheel; leave horizontal gestures to the browser. */
  touch-action: pan-y;
  /* Promote the scroller so the browser keeps it on its own layer while snapping. */
  will-change: scroll-position;
}

.col::-webkit-scrollbar {
  display: none;
}

.col__pad {
  block-size: calc(var(--wheel-item) * 2);
}

.item {
  display: block;
  inline-size: 100%;
  block-size: var(--wheel-item);
  scroll-snap-align: center;
  /* The fix for "滚过头": without this, one flick sails past a dozen snap points. With it, a gesture
     can pass at most one, so the wheel travels roughly as far as the finger did. */
  scroll-snap-stop: always;
  font-size: 30px;
  line-height: var(--wheel-item);
  color: var(--text);
  font-variant-numeric: tabular-nums;
  text-align: center;
}

/* Emphasis without JS: the attribute is already bound, so the browser styles it itself. */
.item[aria-selected='true'] {
  font-weight: var(--fw-semibold);
}

.colon {
  font-size: 30px;
  color: var(--text-dim);
  transform: translateY(-2px);
}

.period {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  margin-inline-start: var(--sp-2);
}

.period__opt {
  min-inline-size: 54px;
  min-block-size: 40px;
  border-radius: var(--r-sm);
  background: var(--surface-2);
  border: 1px solid var(--border);
  color: var(--text-dim);
  font-size: var(--fs-sm);
}

.period__opt--on {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-ink);
  font-weight: var(--fw-semibold);
}
</style>
