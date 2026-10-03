<script setup lang="ts">
/**
 * PRD FR-5.4 — the persistent warning strip on the main screen.
 *
 * It is shown only for the two permissions whose absence makes the alarm untrustworthy
 * (精确闹钟 / 通知); see `CRITICAL_PERMISSIONS` in `state/store.ts`. The point is that it stays up
 * until the permissions are actually granted — so it can never be dismissed into silence.
 */
import { computed } from 'vue'
import type { PermissionItem } from '../bridge/types'

const props = defineProps<{ items: PermissionItem[] }>()
const emit = defineEmits<{ open: [] }>()

const names = computed(() => props.items.map((item) => item.label).join('、'))
</script>

<template>
  <button type="button" class="banner" @click="emit('open')">
    <span class="banner__mark" aria-hidden="true">!</span>
    <span class="banner__text">
      <span class="banner__title">{{ names }} 还没开</span>
      <span class="banner__body">缺了它，到点可能不响，或者响了也看不到界面。</span>
    </span>
    <span class="banner__go">去设置 ›</span>
  </button>
</template>

<style scoped>
.banner {
  inline-size: 100%;
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  text-align: start;
  padding: var(--sp-3) var(--sp-4);
  border-radius: var(--r-lg);
  background: var(--tint-warn);
  border: 1px solid color-mix(in srgb, var(--warn) 45%, transparent);
}

.banner__mark {
  flex: 0 0 auto;
  inline-size: 24px;
  block-size: 24px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
  background: var(--warn);
  color: var(--surface);
}

.banner__text {
  flex: 1 1 auto;
  min-inline-size: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.banner__title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--warn);
}

.banner__body {
  font-size: var(--fs-2xs);
  color: var(--text-dim);
  line-height: 1.5;
}

.banner__go {
  flex: 0 0 auto;
  font-size: var(--fs-xs);
  color: var(--warn);
}
</style>
