<script setup lang="ts">
/**
 * PRD FR-5.5 — the first-run walkthrough, plus FR-5.6's wording.
 *
 * Shown once, on the list page, and only when there is actually something to fix: pointing a user at
 * a 体检页 that is already all green would teach them to dismiss it. "Skipped" and "went to the
 * 体检页" both clear the flag, because the flag means "we have already pointed at it" — the warning
 * strip is a separate mechanism that keeps tracking the real state (FR-5.4).
 */
import type { PermissionItem } from '../bridge/types'

defineProps<{ open: boolean; items: PermissionItem[] }>()
const emit = defineEmits<{ goToCheckup: []; skip: [] }>()
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="wrap">
      <div class="scrim" />

      <div class="sheet" role="dialog" aria-modal="true" aria-label="首次使用检查">
        <span class="grabber" aria-hidden="true" />

        <h2 class="title">先花半分钟做一次体检</h2>
        <p class="lead">
          闹钟能不能准时响，取决于系统给不给下面这几项权限。它们都在你的手机设置里，本应用无法
          自己代你打开。
        </p>

        <ul class="list">
          <li v-for="item in items" :key="item.key" class="item">
            <span class="item__label">{{ item.label }}</span>
            <span class="item__desc">{{ item.description }}</span>
          </li>
        </ul>

        <p class="note">
          还有一件事系统帮不了你：如果你在最近任务里手动「结束运行」了这个应用，或者把它彻底划掉，
          那么<strong>任何</strong>第三方闹钟都无法再被唤醒。请把它在最近任务里加锁。
        </p>

        <div class="actions">
          <button type="button" class="btn" @click="emit('skip')">以后再说</button>
          <button type="button" class="btn btn--primary" @click="emit('goToCheckup')">
            去体检
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.wrap {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  align-items: flex-end;
}

.scrim {
  position: absolute;
  inset: 0;
  background: var(--scrim);
  animation: fade var(--dur-base) var(--ease);
}

.sheet {
  position: relative;
  inline-size: 100%;
  max-block-size: 82vh;
  overflow-y: auto;
  background: var(--surface);
  border-start-start-radius: var(--r-xl);
  border-start-end-radius: var(--r-xl);
  box-shadow: var(--shadow-sheet);
  padding: var(--sp-3) var(--page-x) calc(var(--sp-5) + var(--safe-bottom));
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  animation: rise var(--dur-base) var(--ease);
}

.grabber {
  inline-size: 36px;
  block-size: 4px;
  border-radius: var(--r-pill);
  background: var(--border-strong);
  align-self: center;
}

.title {
  font-size: var(--fs-lg);
  font-weight: var(--fw-semibold);
  text-align: center;
}

.lead,
.note {
  font-size: var(--fs-xs);
  color: var(--text-dim);
  line-height: 1.75;
}

.note {
  border-radius: var(--r-md);
  padding: var(--sp-3);
  background: var(--surface-2);
}

.note strong {
  color: var(--warn);
}

.list {
  display: flex;
  flex-direction: column;
  background: var(--surface-2);
  border-radius: var(--r-md);
  overflow: hidden;
}

.item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--sp-3);
}

.item + .item {
  border-block-start: 1px solid var(--border);
}

.item__label {
  font-size: var(--fs-sm);
}

.item__desc {
  font-size: var(--fs-2xs);
  color: var(--text-faint);
  line-height: 1.5;
}

.actions {
  display: flex;
  gap: var(--sp-3);
}

.btn {
  flex: 1 1 0;
  min-block-size: 50px;
  border-radius: var(--r-md);
  background: var(--surface-2);
  border: 1px solid var(--border);
  font-size: var(--fs-md);
  font-weight: var(--fw-medium);
}

.btn--primary {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-ink);
  font-weight: var(--fw-semibold);
}
</style>
