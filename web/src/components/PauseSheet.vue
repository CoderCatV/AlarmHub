<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { PausePreview, PauseRequest, PauseScope } from '../bridge/types'
import { previewPause } from '../state/store'
import { formatDayTime, formatMonthDay } from '../utils/format'

const props = defineProps<{
  open: boolean
  scope: PauseScope
  id: number
  /** e.g. 「工作日」or 「起床上班」— the sheet reads "暂停「X」" */
  targetName: string
  defaultDays: number
}>()

const emit = defineEmits<{
  close: []
  confirm: [req: PauseRequest]
  disablePermanently: []
}>()

const QUICK = [1, 2, 3, 7] as const

const startOfDay = (ts: number) => {
  const d = new Date(ts)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

const toDateInput = (ts: number) => {
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const mode = ref<'quick' | 'custom'>('quick')
const days = ref(props.defaultDays)
const customDate = ref(toDateInput(startOfDay(Date.now()) + props.defaultDays * 86_400_000))
const preview = ref<PausePreview | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

// Re-seed whenever the sheet is opened for a (possibly different) target.
watch(
  () => [props.open, props.id, props.defaultDays] as const,
  ([open]) => {
    if (!open) return
    mode.value = 'quick'
    days.value = props.defaultDays
    customDate.value = toDateInput(startOfDay(Date.now()) + props.defaultDays * 86_400_000)
  },
  { immediate: true }
)

function buildRequest(): PauseRequest {
  if (mode.value === 'custom') {
    return { scope: props.scope, id: props.id, until: startOfDay(new Date(customDate.value).getTime()) }
  }
  return { scope: props.scope, id: props.id, days: days.value }
}

/** `previewPause` is a pure round trip to the native side — nothing is persisted here. */
watch(
  [() => props.open, mode, days, customDate],
  async () => {
    if (!props.open) return
    loading.value = true
    error.value = null
    try {
      preview.value = await previewPause(buildRequest())
    } catch (e) {
      preview.value = null
      error.value = e instanceof Error ? e.message : String(e)
    } finally {
      loading.value = false
    }
  },
  { immediate: true }
)

const skippedText = computed(() => {
  const p = preview.value
  if (!p || p.skippedRingDays.length === 0) return null
  return p.skippedRingDays.map((ts) => formatMonthDay(ts)).join('、')
})

const resumeText = computed(() =>
  preview.value ? formatDayTime(preview.value.resumeAt, Date.now(), '24') : null
)

const nextRingText = computed(() =>
  preview.value?.nextRingAt ? formatDayTime(preview.value.nextRingAt, Date.now(), '24') : null
)

const canConfirm = computed(() => !loading.value && preview.value !== null)
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="wrap">
      <div class="scrim" @click="emit('close')" />

      <div class="sheet" role="dialog" aria-modal="true" aria-label="暂停闹钟">
        <span class="grabber" aria-hidden="true" />

        <h2 class="title">暂停「{{ targetName }}」</h2>

        <div class="chips" role="radiogroup" aria-label="暂停时长">
          <button
            v-for="d in QUICK"
            :key="d"
            type="button"
            class="chip"
            :class="{ 'chip--on': mode === 'quick' && days === d }"
            role="radio"
            :aria-checked="mode === 'quick' && days === d"
            @click="((mode = 'quick'), (days = d))"
          >
            {{ d }} 天
          </button>
          <button
            type="button"
            class="chip"
            :class="{ 'chip--on': mode === 'custom' }"
            role="radio"
            :aria-checked="mode === 'custom'"
            @click="mode = 'custom'"
          >
            自定义
          </button>
        </div>

        <label v-if="mode === 'custom'" class="custom">
          <span class="custom__label">恢复日期（当天 00:00 起恢复正常）</span>
          <input v-model="customDate" class="custom__input" type="date" />
        </label>

        <!-- The preview is the whole point of this sheet: the user must see exactly which
             mornings they are silencing and when it comes back on by itself. -->
        <dl class="preview">
          <div v-if="skippedText" class="preview__row">
            <dt>将跳过</dt>
            <dd>{{ skippedText }}</dd>
          </div>
          <div class="preview__row preview__row--strong">
            <dt>自动恢复</dt>
            <dd>
              <template v-if="loading">计算中…</template>
              <template v-else-if="error">{{ error }}</template>
              <template v-else>{{ resumeText ?? '—' }}</template>
            </dd>
          </div>
          <div class="preview__row">
            <dt>下次响铃</dt>
            <dd>{{ loading ? '…' : (nextRingText ?? '恢复后暂无安排') }}</dd>
          </div>
        </dl>

        <div class="actions">
          <button type="button" class="btn" @click="emit('close')">取消</button>
          <button
            type="button"
            class="btn btn--primary"
            :disabled="!canConfirm"
            @click="emit('confirm', buildRequest())"
          >
            暂停
          </button>
        </div>

        <button type="button" class="link" @click="emit('disablePermanently')">
          改为永久停用（不会自动恢复）
        </button>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.wrap {
  position: fixed;
  inset: 0;
  z-index: 50;
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
  background: var(--surface);
  border-start-start-radius: var(--r-xl);
  border-start-end-radius: var(--r-xl);
  box-shadow: var(--shadow-sheet);
  padding: var(--sp-3) var(--page-x) calc(var(--sp-5) + var(--safe-bottom));
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
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

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
}

.chip {
  flex: 1 1 auto;
  min-block-size: var(--tap-min);
  padding: 0 var(--sp-4);
  border-radius: var(--r-md);
  background: var(--surface-2);
  border: 1px solid var(--border);
  font-size: var(--fs-sm);
  color: var(--text-dim);
  transition: all var(--dur-fast) var(--ease);
}

.chip--on {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-ink);
  font-weight: var(--fw-semibold);
}

.custom {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.custom__label {
  font-size: var(--fs-xs);
  color: var(--text-faint);
}

.custom__input {
  min-block-size: var(--tap-min);
  padding: 0 var(--sp-3);
  border-radius: var(--r-md);
  background: var(--surface-2);
  border: 1px solid var(--border);
  color-scheme: inherit;
}

.preview {
  display: flex;
  flex-direction: column;
  gap: 1px;
  background: var(--surface-2);
  border-radius: var(--r-md);
  padding: var(--sp-2) var(--sp-3);
}

.preview__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-4);
  padding: 7px 0;
  font-size: var(--fs-sm);
}

.preview__row + .preview__row {
  border-block-start: 1px solid var(--border);
}

.preview__row dt {
  color: var(--text-faint);
  flex: 0 0 auto;
  font-size: var(--fs-xs);
}

.preview__row dd {
  text-align: end;
  color: var(--text-dim);
}

.preview__row--strong dd {
  color: var(--accent);
  font-weight: var(--fw-semibold);
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

.btn:disabled {
  opacity: 0.45;
}

.link {
  font-size: var(--fs-xs);
  color: var(--text-faint);
  text-decoration: underline;
  text-underline-offset: 3px;
  align-self: center;
  padding: var(--sp-1);
}

@keyframes fade {
  from {
    opacity: 0;
  }
}

@keyframes rise {
  from {
    transform: translateY(14px);
    opacity: 0;
  }
}
</style>
