<script setup lang="ts">
import { computed } from 'vue'
import type { TimeFormat } from '../bridge/types'
import type { Section } from '../state/store'
import { argbToHex, argbToRgba } from '../utils/color'
import { formatDayTime, formatResumeIn } from '../utils/format'
import AlarmRow from './AlarmRow.vue'
import ToggleSwitch from './ToggleSwitch.vue'

const props = defineProps<{
  section: Section
  now: number
  timeFormat: TimeFormat
  /** Passed straight through to the rows; see `AlarmRow`. */
  selectMode?: boolean
  selectedIds?: Set<number>
}>()

const emit = defineEmits<{
  toggleAlarm: [id: number, enabled: boolean]
  openAlarm: [id: number]
  setGroupEnabled: [id: number, enabled: boolean]
  pauseGroup: [id: number]
  resumeGroup: [id: number]
  longPressAlarm: [id: number]
  pickAlarm: [id: number]
}>()

const colorHex = computed(() => argbToHex(props.section.group.color))
const colorTint = computed(() => argbToRgba(props.section.group.color, 0.16))

/** A group is "on" only when it is neither temporarily paused nor permanently stopped. */
const groupOn = computed(() => !props.section.paused && !props.section.disabled)

const statusText = computed(() => {
  const { group, paused, disabled } = props.section
  if (paused && group.pauseUntil !== null) {
    return `${formatResumeIn(group.pauseUntil, props.now, props.timeFormat)}`
  }
  if (disabled) return '已停用'
  if (props.section.nextRingAt !== null) {
    return `下次响铃 ${formatDayTime(props.section.nextRingAt, props.now, props.timeFormat)}`
  }
  return '暂无响铃安排'
})

const stateClass = computed(() => ({
  'section--paused': props.section.paused,
  'section--disabled': props.section.disabled,
}))
</script>

<template>
  <section class="section" :class="stateClass" :style="{ '--group': colorHex, '--group-tint': colorTint }">
    <header class="head">
      <span class="head__dot" aria-hidden="true" />
      <span class="head__text">
        <span class="head__name">
          {{ section.group.name }}
          <span class="head__count">{{ section.alarms.length }}</span>
        </span>
        <span class="head__status">{{ statusText }}</span>
      </span>

      <button
        v-if="groupOn || section.paused"
        type="button"
        class="head__pause"
        :aria-label="`暂停 ${section.group.name}`"
        @click="section.paused ? emit('resumeGroup', section.group.id) : emit('pauseGroup', section.group.id)"
      >
        {{ section.paused ? '恢复' : '暂停' }}
      </button>

      <ToggleSwitch
        :model-value="groupOn"
        :label="`${section.group.name} 分组开关`"
        @update:model-value="(v) => emit('setGroupEnabled', section.group.id, v)"
      />
    </header>

    <p v-if="section.paused || section.disabled" class="banner">
      <span>
        {{ section.paused ? `这个分组的闹钟已暂停，${statusText}` : '这个分组已停用，闹钟不会响' }}
      </span>
      <button type="button" class="banner__action" @click="emit('resumeGroup', section.group.id)">
        立即恢复
      </button>
    </p>

    <ul class="list">
      <AlarmRow
        v-for="alarm in section.alarms"
        :key="alarm.id"
        :alarm="alarm"
        :now="now"
        :time-format="timeFormat"
        :select-mode="selectMode"
        :selected="selectedIds?.has(alarm.id) ?? false"
        @toggle="(id, v) => emit('toggleAlarm', id, v)"
        @open="(id) => emit('openAlarm', id)"
        @long-press="(id) => emit('longPressAlarm', id)"
        @pick="(id) => emit('pickAlarm', id)"
      />
    </ul>
  </section>
</template>

<style scoped>
.section {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  overflow: hidden;
}

.section--paused {
  border-color: color-mix(in srgb, var(--warn) 40%, var(--border));
}

.section--disabled {
  opacity: 0.72;
}

/* ---- header ---- */

.head {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  background: var(--surface-2);
}

.head__dot {
  inline-size: 9px;
  block-size: 9px;
  border-radius: 50%;
  background: var(--group);
  box-shadow: 0 0 0 4px var(--group-tint);
  flex: 0 0 auto;
}

.head__text {
  flex: 1 1 auto;
  min-inline-size: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.head__name {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  display: flex;
  align-items: center;
  gap: 6px;
}

.head__count {
  font-size: var(--fs-2xs);
  font-weight: var(--fw-regular);
  color: var(--text-faint);
  background: var(--surface-3);
  border-radius: var(--r-pill);
  padding: 0 6px;
}

.head__status {
  font-size: var(--fs-2xs);
  color: var(--text-faint);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.head__pause {
  flex: 0 0 auto;
  font-size: var(--fs-xs);
  color: var(--accent);
  background: var(--tint-accent);
  border-radius: var(--r-pill);
  padding: 5px 12px;
  min-block-size: 30px;
}

/* ---- paused banner ---- */

.banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-2) var(--sp-4);
  font-size: var(--fs-xs);
  color: var(--warn);
  background: var(--tint-warn);
}

.banner__action {
  flex: 0 0 auto;
  color: var(--warn);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.list {
  display: flex;
  flex-direction: column;
}
</style>
