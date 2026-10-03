<script setup lang="ts">
import { computed, ref } from 'vue'
import type { PermissionItem } from '../bridge/types'
import {
  loadPermissionStatus,
  missingPermissions,
  openPermissionSetting,
  permissions,
} from '../state/store'

const emit = defineEmits<{ back: [] }>()

/**
 * The probe itself lives in the store, not here, because the list page's warning strip reads the
 * same verdict (PRD FR-5.4) — and because the store re-probes when the app returns to the
 * foreground, which is the only way a row can update after the user has been away in Settings.
 */
const items = computed(() => permissions.items)
const loading = computed(() => !permissions.loaded)

const busyKey = ref<string | null>(null)
const lastResult = ref<{ key: string; opened: boolean; fallback: boolean } | null>(null)

const missing = computed(() => missingPermissions.value)
const grantedCount = computed(() => items.value.filter((i) => i.applicable && i.granted).length)
const applicableCount = computed(() => items.value.filter((i) => i.applicable).length)

async function open(item: PermissionItem) {
  busyKey.value = item.key
  try {
    const result = await openPermissionSetting(item.key)
    lastResult.value = { key: item.key, ...result }
    // Correct for the notification dialog, which is answered while this page stays foregrounded.
    // A jump to a system settings page resolves as soon as it has been *launched*, so the row is
    // refreshed by the store's foreground listener once the user actually comes back.
    await loadPermissionStatus()
  } finally {
    busyKey.value = null
  }
}

/**
 * `openPermissionSetting` reports whether it managed to reach the vendor screen. MIUI/HyperOS
 * settings pages have no stable public intent, so a failure is expected sometimes — the UI has to
 * say so plainly rather than pretending the jump worked (PRD FR-5.3).
 */
const fallbackNotice = computed(() => {
  const r = lastResult.value
  if (!r || r.opened) return null
  const label = items.value.find((i) => i.key === r.key)?.label ?? '该项'
  return `没能直接打开「${label}」的系统设置页，已跳到本应用的系统详情页。请在里面手动找到对应开关。`
})
</script>

<template>
  <div class="page">
    <header class="bar">
      <button type="button" class="bar__action" @click="emit('back')">返回</button>
      <h1 class="bar__title">权限体检</h1>
      <button type="button" class="bar__action" @click="loadPermissionStatus">刷新</button>
    </header>

    <div class="body">
      <section class="summary" :class="{ 'summary--warn': missing.length > 0 }">
        <p class="summary__head">
          {{ missing.length > 0 ? `${missing.length} 项待处理` : '全部正常' }}
        </p>
        <p class="summary__sub">
          {{ grantedCount }} / {{ applicableCount }} 项已就绪
        </p>
      </section>

      <p class="explain">
        闹钟类应用必须依赖系统把这些权限放开，否则到点不会响。以下几项在小米 / 红米 / HyperOS
        上尤其重要。
      </p>

      <p v-if="loading" class="loading">检测中…</p>

      <ul v-else class="list">
        <li v-for="item in items" :key="item.key" class="item">
          <button
            type="button"
            class="item__main"
            :disabled="!item.applicable || busyKey === item.key"
            @click="open(item)"
          >
            <span class="item__status" :class="{
              'item__status--ok': item.applicable && item.granted,
              'item__status--bad': item.applicable && !item.granted,
              'item__status--na': !item.applicable,
            }">
              {{ !item.applicable ? '—' : item.granted ? '✓' : '✕' }}
            </span>

            <span class="item__text">
              <span class="item__label">{{ item.label }}</span>
              <span class="item__desc">{{ item.description }}</span>
            </span>

            <span v-if="item.applicable" class="item__go">去设置 ›</span>
            <span v-else class="item__na">不适用</span>
          </button>
        </li>
      </ul>

      <p v-if="fallbackNotice" class="fallback">{{ fallbackNotice }}</p>

      <p v-if="permissions.error" class="fallback">权限检测失败：{{ permissions.error }}</p>

      <section class="warn">
        <p class="warn__title">还有一件事系统帮不了你</p>
        <p class="warn__body">
          如果你在最近任务里手动「结束运行」了这个应用，或者把它从后台彻底划掉，那么<strong>任何</strong>第三方闹钟都无法再被唤醒。
          想让它可靠工作，请在最近任务里把它加锁（小米上是在卡片上点锁头图标），别把它清掉。
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
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: calc(var(--safe-top) + var(--sp-2)) var(--page-x) var(--sp-2);
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

.body {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding: 0 var(--page-x);
}

.summary {
  border-radius: var(--r-lg);
  padding: var(--sp-4);
  background: var(--tint-ok);
  border: 1px solid color-mix(in srgb, var(--ok) 45%, transparent);
}

.summary--warn {
  background: var(--tint-warn);
  border-color: color-mix(in srgb, var(--warn) 45%, transparent);
}

.summary__head {
  font-size: var(--fs-lg);
  font-weight: var(--fw-semibold);
  color: var(--ok);
}

.summary--warn .summary__head {
  color: var(--warn);
}

.summary__sub {
  font-size: var(--fs-xs);
  color: var(--text-dim);
  margin-block-start: 2px;
}

.explain {
  font-size: var(--fs-xs);
  color: var(--text-faint);
  line-height: 1.7;
}

.loading {
  padding: var(--sp-5);
  color: var(--text-faint);
  text-align: center;
  font-size: var(--fs-sm);
}

.list {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  overflow: hidden;
}

.item + .item {
  border-block-start: 1px solid var(--border);
}

.item__main {
  inline-size: 100%;
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  text-align: start;
}

.item__main:disabled {
  opacity: 0.6;
}

.item__status {
  flex: 0 0 auto;
  inline-size: 26px;
  block-size: 26px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
}

.item__status--ok {
  background: var(--tint-ok);
  color: var(--ok);
}

.item__status--bad {
  background: var(--tint-danger);
  color: var(--danger);
}

.item__status--na {
  background: var(--surface-3);
  color: var(--text-faint);
}

.item__text {
  flex: 1 1 auto;
  min-inline-size: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.item__label {
  font-size: var(--fs-sm);
}

.item__desc {
  font-size: var(--fs-2xs);
  color: var(--text-faint);
  line-height: 1.5;
}

.item__go {
  flex: 0 0 auto;
  font-size: var(--fs-xs);
  color: var(--accent);
}

.item__na {
  flex: 0 0 auto;
  font-size: var(--fs-2xs);
  color: var(--text-faint);
}

.fallback {
  border-radius: var(--r-md);
  padding: var(--sp-3);
  background: var(--tint-warn);
  color: var(--warn);
  font-size: var(--fs-xs);
  line-height: 1.7;
}

.warn {
  border-radius: var(--r-lg);
  padding: var(--sp-4);
  background: var(--surface);
  border: 1px dashed var(--border-strong);
}

.warn__title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  margin-block-end: var(--sp-2);
}

.warn__body {
  font-size: var(--fs-xs);
  color: var(--text-dim);
  line-height: 1.75;
}

.warn__body strong {
  color: var(--warn);
}
</style>
