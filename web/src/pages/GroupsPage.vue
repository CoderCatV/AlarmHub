<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Group } from '../bridge/types'
import { deleteGroup, reorderGroups, saveGroup, state } from '../state/store'
import { GROUP_PALETTE, argbToHex, argbToRgba } from '../utils/color'

const emit = defineEmits<{ back: [] }>()

/** Must match the row height in the stylesheet; the drag maths divides by it. */
const ROW_H = 68

/** A local mirror of the order, so a drag can rearrange rows without a round trip per pixel. */
const order = ref<Group[]>([])

// ---------------------------------------------------------------------------------------
// Reordering — pointer drag plus a keyboard equivalent, so it is not drag-only
// ---------------------------------------------------------------------------------------

/**
 * Declared before the watcher below on purpose: that watcher runs immediately during setup,
 * and reading a `const` from its temporal dead zone throws "Cannot access 'x' before
 * initialization" — which blanked this whole screen the first time it was opened.
 */
const dragIndex = ref<number | null>(null)
let startY = 0
let startIndex = 0

watch(
  () => state.groups,
  (groups) => {
    // Never clobber an in-flight drag with a refresh.
    if (dragIndex.value !== null) return
    order.value = [...groups].sort((a, b) => a.sortOrder - b.sortOrder)
  },
  { immediate: true }
)

function onHandleDown(e: PointerEvent, index: number) {
  // Capture keeps the drag alive when the finger leaves the handle's bounds. It throws if the
  // pointer id is not an active pointer (which is also the case for synthetic events), so a
  // failure here must not abort the drag.
  try {
    ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  } catch {
    /* capture is an optimisation, not a requirement */
  }
  startY = e.clientY
  startIndex = index
  dragIndex.value = index
}

function onHandleMove(e: PointerEvent) {
  if (dragIndex.value === null) return
  const target = Math.max(
    0,
    Math.min(order.value.length - 1, startIndex + Math.round((e.clientY - startY) / ROW_H))
  )
  if (target === dragIndex.value) return
  const next = [...order.value]
  const [moved] = next.splice(dragIndex.value, 1)
  next.splice(target, 0, moved)
  order.value = next
  dragIndex.value = target
}

async function onHandleUp() {
  if (dragIndex.value === null) return
  dragIndex.value = null
  await reorderGroups(order.value.map((g) => g.id))
}

async function nudge(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= order.value.length) return
  const next = [...order.value]
  const [moved] = next.splice(index, 1)
  next.splice(target, 0, moved)
  order.value = next
  await reorderGroups(next.map((g) => g.id))
}

// ---------------------------------------------------------------------------------------
// Create / edit sheet
// ---------------------------------------------------------------------------------------

const sheet = ref<{ open: boolean; id: number | null; name: string; color: number }>({
  open: false,
  id: null,
  name: '',
  color: GROUP_PALETTE[0].argb,
})

const confirmingDelete = ref(false)
const busy = ref(false)

const editingGroup = computed(() => order.value.find((g) => g.id === sheet.value.id) ?? null)
const canDelete = computed(() => editingGroup.value !== null && !editingGroup.value.isSystem)

/** Where a deleted group's alarms go: the built-in catch-all if it exists, else the first other. */
const fallbackGroupId = computed(() => {
  const others = order.value.filter((g) => g.id !== sheet.value.id)
  return (others.find((g) => g.name === '未分组') ?? others[0])?.id ?? 1
})

function openNew() {
  confirmingDelete.value = false
  sheet.value = {
    open: true,
    id: null,
    name: '',
    color: GROUP_PALETTE[order.value.length % GROUP_PALETTE.length].argb,
  }
}

function openEdit(group: Group) {
  confirmingDelete.value = false
  sheet.value = { open: true, id: group.id, name: group.name, color: group.color }
}

async function onSave() {
  const name = sheet.value.name.trim()
  if (!name || busy.value) return
  busy.value = true
  try {
    await saveGroup({
      id: sheet.value.id ?? undefined,
      name,
      color: sheet.value.color,
    })
    sheet.value.open = false
  } finally {
    busy.value = false
  }
}

async function onDelete() {
  const group = editingGroup.value
  if (!group || !canDelete.value) return
  if (!confirmingDelete.value) {
    confirmingDelete.value = true
    return
  }
  busy.value = true
  try {
    await deleteGroup(group.id, fallbackGroupId.value)
    sheet.value.open = false
  } finally {
    busy.value = false
  }
}

const rows = computed(() =>
  order.value.map((g) => ({
    ...g,
    hex: argbToHex(g.color),
    tint: argbToRgba(g.color, 0.16),
  }))
)
</script>

<template>
  <div class="page">
    <header class="bar">
      <button type="button" class="bar__action" @click="emit('back')">返回</button>
      <h1 class="bar__title">分组管理</h1>
      <span class="bar__spacer" />
    </header>

    <p class="tip">
      拖动右侧手柄排序；也可以用键盘聚焦手柄后按 ↑ ↓ 移动。分组顺序决定主界面的显示顺序。
    </p>

    <ul class="list">
      <li
        v-for="(g, i) in rows"
        :key="g.id"
        class="item"
        :class="{ 'item--dragging': dragIndex === i }"
        :style="{ '--group': g.hex, '--group-tint': g.tint }"
      >
        <button type="button" class="item__main" @click="openEdit(g)">
          <span class="item__dot" aria-hidden="true" />
          <span class="item__text">
            <span class="item__name">
              {{ g.name }}
              <span v-if="g.isSystem" class="item__badge">内置</span>
            </span>
            <span class="item__meta">{{ g.alarmCount }} 个闹钟</span>
          </span>
        </button>

        <span
          class="handle"
          role="button"
          tabindex="0"
          :aria-label="`拖动或按方向键调整「${g.name}」的顺序`"
          @pointerdown="(e) => onHandleDown(e, i)"
          @pointermove="onHandleMove"
          @pointerup="onHandleUp"
          @pointercancel="onHandleUp"
          @keydown.up.prevent="nudge(i, -1)"
          @keydown.down.prevent="nudge(i, 1)"
        >
          <span class="handle__bar" aria-hidden="true" />
          <span class="handle__bar" aria-hidden="true" />
          <span class="handle__bar" aria-hidden="true" />
        </span>
      </li>
    </ul>

    <div class="add">
      <button type="button" class="add__btn" @click="openNew">新建分组</button>
    </div>

    <!-- editor sheet -->
    <Teleport to="body">
      <div v-if="sheet.open" class="wrap">
        <div class="scrim" @click="sheet.open = false" />
        <div class="sheet" role="dialog" aria-modal="true" aria-label="分组设置">
          <span class="grabber" aria-hidden="true" />

          <h2 class="sheet__title">{{ sheet.id === null ? '新建分组' : '编辑分组' }}</h2>

          <label class="field">
            <span class="field__label">名称</span>
            <input
              v-model="sheet.name"
              class="field__input"
              type="text"
              maxlength="12"
              placeholder="例如：工作日"
            />
          </label>

          <div class="field">
            <span class="field__label">标识色</span>
            <div class="swatches">
              <button
                v-for="c in GROUP_PALETTE"
                :key="c.argb"
                type="button"
                class="swatch"
                :class="{ 'swatch--on': sheet.color === c.argb }"
                :style="{ background: argbToHex(c.argb) }"
                :aria-label="c.name"
                :aria-pressed="sheet.color === c.argb"
                @click="sheet.color = c.argb"
              />
            </div>
          </div>

          <!--
            The armed label is kept **short on purpose**. It used to be a sentence —
            「确认删除？组内 3 个闹钟会移到「未分组」」— which wrapped onto a second line, so the button
            grew between the two taps and pushed 保存/取消 down under the user's finger. The detail still
            matters, so it moved to its own line above, where growing costs nothing. Reported from the
            phone as 「非内置分组删不掉」, and the delete itself turned out to work: the interaction is
            what was hostile.
          -->
          <p v-if="canDelete && confirmingDelete" class="hint hint--danger">
            再点一次按钮即可删除；组内 {{ editingGroup?.alarmCount ?? 0 }} 个闹钟会移到「{{
              order.find((g) => g.id === fallbackGroupId)?.name ?? '未分组'
            }}」
          </p>

          <button
            v-if="canDelete"
            type="button"
            class="danger"
            :class="{ 'danger--armed': confirmingDelete }"
            @click="onDelete"
          >
            {{ confirmingDelete ? '确认删除' : '删除分组' }}
          </button>
          <p v-else-if="sheet.id !== null" class="hint">内置分组不能删除，可以改名和改色。</p>

          <div class="actions">
            <button type="button" class="btn" @click="sheet.open = false">取消</button>
            <button
              type="button"
              class="btn btn--primary"
              :disabled="!sheet.name.trim() || busy"
              @click="onSave"
            >
              保存
            </button>
          </div>
        </div>
      </div>
    </Teleport>
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

.bar__spacer {
  inline-size: 40px;
}

.tip {
  margin: 0 var(--page-x) var(--sp-3);
  font-size: var(--fs-xs);
  color: var(--text-faint);
  line-height: 1.7;
}

.list {
  margin: 0 var(--page-x);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  overflow: hidden;
}

.item {
  block-size: 68px;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding-inline-end: var(--sp-2);
  background: var(--surface);
  touch-action: none;
}

.item + .item {
  border-block-start: 1px solid var(--border);
}

.item--dragging {
  background: var(--surface-2);
  box-shadow: 0 6px 18px #00000047;
}

.item__main {
  flex: 1 1 auto;
  min-inline-size: 0;
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding-inline-start: var(--sp-4);
  block-size: 100%;
  text-align: start;
}

.item__dot {
  inline-size: 12px;
  block-size: 12px;
  border-radius: 50%;
  background: var(--group);
  box-shadow: 0 0 0 5px var(--group-tint);
  flex: 0 0 auto;
}

.item__text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-inline-size: 0;
}

.item__name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
}

.item__badge {
  font-size: var(--fs-2xs);
  font-weight: var(--fw-regular);
  color: var(--text-faint);
  background: var(--surface-3);
  border-radius: var(--r-pill);
  padding: 0 6px;
}

.item__meta {
  font-size: var(--fs-xs);
  color: var(--text-faint);
}

.handle {
  flex: 0 0 auto;
  inline-size: 44px;
  block-size: 44px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border-radius: var(--r-sm);
  cursor: grab;
}

.handle:active {
  cursor: grabbing;
}

.handle__bar {
  inline-size: 18px;
  block-size: 2px;
  border-radius: 2px;
  background: var(--text-faint);
}

.add {
  padding: var(--sp-4) var(--page-x) 0;
}

.add__btn {
  inline-size: 100%;
  min-block-size: 50px;
  border-radius: var(--r-md);
  background: var(--surface);
  border: 1px dashed var(--border-strong);
  color: var(--accent);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
}

/* ---- sheet ---- */

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
}

.grabber {
  inline-size: 36px;
  block-size: 4px;
  border-radius: var(--r-pill);
  background: var(--border-strong);
  align-self: center;
}

.sheet__title {
  font-size: var(--fs-lg);
  font-weight: var(--fw-semibold);
  text-align: center;
}

.field {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}

.field__label {
  font-size: var(--fs-xs);
  color: var(--text-faint);
}

.field__input {
  min-block-size: 48px;
  padding: 0 var(--sp-3);
  border-radius: var(--r-md);
  background: var(--surface-2);
  border: 1px solid var(--border);
}

.swatches {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-3);
}

.swatch {
  inline-size: 38px;
  block-size: 38px;
  border-radius: 50%;
  border: 3px solid transparent;
  transition: transform var(--dur-fast) var(--ease);
}

.swatch--on {
  border-color: var(--text);
  transform: scale(1.08);
}

.danger {
  min-block-size: 50px;
  border-radius: var(--r-md);
  background: var(--tint-danger);
  color: var(--danger);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  padding: 0 var(--sp-3);
}

.danger--armed {
  background: var(--danger);
  color: #fff;
}

.hint {
  font-size: var(--fs-xs);
  color: var(--text-faint);
  text-align: center;
}

/* The instruction that replaces the old long button label; red because the next tap destroys
   something, and it is the line that has to be read before tapping again. */
.hint--danger {
  color: var(--danger);
  margin-block-end: var(--sp-1);
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
</style>
