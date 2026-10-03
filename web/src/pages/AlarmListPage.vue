<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { usingMock } from '../bridge'
import type { PauseRequest, PauseScope } from '../bridge/types'
import AlarmRow from '../components/AlarmRow.vue'
import GroupSection from '../components/GroupSection.vue'
import PauseSheet from '../components/PauseSheet.vue'
import PermissionBanner from '../components/PermissionBanner.vue'
import PermissionGuide from '../components/PermissionGuide.vue'
import {
  clearExpired,
  criticalMissingPermissions,
  deleteSelected,
  enterSelection,
  exitSelection,
  expiredAlarms,
  markPermissionCheckDone,
  missingPermissions,
  pauseTarget,
  pausedGroups,
  permissions,
  resumeTarget,
  sections,
  selection,
  setAlarmEnabled,
  setPermanentDisabled,
  setSelectedEnabled,
  state,
  toggleSelectAll,
  toggleSelection,
} from '../state/store'
import { formatClock, formatCountdown, formatDayTime } from '../utils/format'

const emit = defineEmits<{
  openAlarm: [id: number]
  create: []
  openSettings: []
  openPermissions: []
  openGroups: []
}>()

/**
 * The hero's overflow menu (FR-7.11).
 *
 * The settings entry used to sit top-left as a labelled pill. It moved to a ⋮ menu top-right because
 * 分组管理 — the feature this whole app exists for — should be *nearer* than 设置, not two taps deeper
 * inside it. That is the same arrangement MIUI's own clock uses (作息管理 / 设置).
 *
 * The menu is an **overlay**: it must not change the height of anything above the list. Same reasoning
 * as the selection bar below — a long press is still in progress while the layout settles, and a row
 * that moves under the finger gets ticked instead of the one the user pressed.
 */
const menuOpen = ref(false)

function closeMenu() {
  menuOpen.value = false
}

function pickFromMenu(what: 'groups' | 'permissions' | 'settings') {
  menuOpen.value = false
  if (what === 'groups') emit('openGroups')
  else if (what === 'permissions') emit('openPermissions')
  else emit('openSettings')
}

const WEEKDAY = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

const timeFormat = computed(() => state.settings?.timeFormat ?? '24')

const nowClock = computed(() => {
  const d = new Date(state.now)
  return formatClock(d.getHours(), d.getMinutes(), timeFormat.value)
})

const nowDate = computed(() => {
  const d = new Date(state.now)
  return `${d.getMonth() + 1}月${d.getDate()}日 ${WEEKDAY[d.getDay()]}`
})

const nextRingText = computed(() => {
  const ts = state.alarms.reduce<number | null>(
    (min, a) => (a.nextRingAt !== null && (min === null || a.nextRingAt < min) ? a.nextRingAt : min),
    null
  )
  if (ts === null) return '暂无响铃安排'
  return `${formatDayTime(ts, state.now, timeFormat.value)} · ${formatCountdown(ts, state.now)}`
})

// ---------------------------------------------------------------------------------------
// Pause sheet
// ---------------------------------------------------------------------------------------

const sheet = ref<{ open: boolean; scope: PauseScope; id: number; name: string }>({
  open: false,
  scope: 'group',
  id: 0,
  name: '',
})

function openPauseForGroup(id: number) {
  const target = sections.value.find((s) => s.group.id === id)
  if (!target) return
  sheet.value = { open: true, scope: 'group', id, name: target.group.name }
}

async function onConfirmPause(req: PauseRequest) {
  sheet.value.open = false
  await pauseTarget(req)
}

async function onDisableForever() {
  const { scope, id } = sheet.value
  sheet.value.open = false
  await setPermanentDisabled(scope, id, true)
}

/**
 * Turning a group back on has to clear both flags: the sheet offers permanent stop, and a
 * group can be left in either state.
 */
async function onResumeGroup(id: number) {
  await setPermanentDisabled('group', id, false)
  await resumeTarget('group', id)
}

async function onSetGroupEnabled(id: number, enabled: boolean) {
  if (enabled) await onResumeGroup(id)
  else await setPermanentDisabled('group', id, true)
}

// ---------------------------------------------------------------------------------------
// Expired cleanup — two-step button instead of a blocking native confirm dialog
// ---------------------------------------------------------------------------------------

const confirmingClear = ref(false)

async function onClearExpired() {
  if (!confirmingClear.value) {
    confirmingClear.value = true
    return
  }
  confirmingClear.value = false
  await clearExpired()
}

// ---------------------------------------------------------------------------------------
// Permissions — PRD FR-5.4's banner and FR-5.5's first-run walkthrough
// ---------------------------------------------------------------------------------------

const guideOpen = ref(false)

/**
 * Shown once, and only when there is something to fix. Both buttons clear the flag: the flag means
 * "the walkthrough has been offered", not "the permissions are granted" — the banner keeps tracking
 * the real state, which is what FR-5.4 asks for.
 */
watch(
  () => [permissions.loaded, state.settings?.permissionCheckDone, missingPermissions.value.length],
  ([loaded, done, missing]) => {
    if (loaded && done === false && (missing as number) > 0) guideOpen.value = true
  },
  { immediate: true }
)

async function onGuideGoToCheckup() {
  guideOpen.value = false
  await markPermissionCheckDone()
  emit('openPermissions')
}

async function onGuideSkip() {
  guideOpen.value = false
  await markPermissionCheckDone()
}

// ---------------------------------------------------------------------------------------
// Long-press multi-select
// ---------------------------------------------------------------------------------------

/** Every deletable row: the ones inside groups plus the 已过期 ones. */
const allAlarmIds = computed(() => [
  ...sections.value.flatMap((s) => s.alarms.map((a) => a.id)),
  ...expiredAlarms.value.map((a) => a.id),
])

const allTicked = computed(
  () => allAlarmIds.value.length > 0 && allAlarmIds.value.every((id) => selection.ids.has(id))
)

/**
 * Two-step delete, matching the 清理已过期 and 删除分组 buttons: this project does not use native
 * `confirm()` anywhere, because it is unstyled, blocking, and easy to dismiss by accident.
 */
const confirmingDelete = ref(false)
const deleteError = ref<string | null>(null)

function onCancelSelection() {
  confirmingDelete.value = false
  deleteError.value = null
  exitSelection()
}

async function onDeleteSelected() {
  if (!confirmingDelete.value) {
    confirmingDelete.value = true
    deleteError.value = null
    return
  }
  confirmingDelete.value = false
  try {
    await deleteSelected()
  } catch (e) {
    // A delete that fails silently is the failure mode that cost the most time in this project;
    // say so instead of leaving the rows on screen with no explanation.
    deleteError.value = e instanceof Error ? e.message : String(e)
  }
}

/** Leaving selection mode must never leave the confirm state armed for the next time. */
watch(
  () => selection.active,
  (active) => {
    if (active) {
      // FR-7.11.5: entering selection mode replaces the hero with the selection bar, so a menu left
      // open would float over a state it no longer belongs to.
      closeMenu()
    } else {
      confirmingDelete.value = false
      deleteError.value = null
      batchError.value = null
    }
  }
)

// ---------------------------------------------------------------------------------------
// Batch enable / disable — the same multi-select, for the other thing you do to several rows
// ---------------------------------------------------------------------------------------

/**
 * How many of the ticked rows are currently on.
 *
 * The pair of buttons is a plain 启用 / 停用, not a per-row toggle: with several rows ticked there is no
 * single "current value" to toggle, and a button that flips every row blindly would turn half the batch
 * the wrong way. The count is what tells the user which button is the useful one.
 */
const tickedEnabledCount = computed(
  () => state.alarms.filter((a) => selection.ids.has(a.id) && a.enabled).length
)

const batchError = ref<string | null>(null)

async function onSetSelected(enabled: boolean): Promise<void> {
  batchError.value = null
  try {
    await setSelectedEnabled(enabled)
  } catch (e) {
    batchError.value = e instanceof Error ? e.message : String(e)
  }
}
</script>

<template>
  <div class="page" :class="{ 'page--selecting': selection.active }">
    <!--
      The selection bar is an **overlay on the hero, not a replacement for it**.

      Doing it the obvious way (`v-if`/`v-else` between this bar and the hero) shifts the whole list
      up by the hero's height the instant selection mode starts — and that is not cosmetic. A
      long press is still in progress at that moment: the list moves under the finger, and the `click`
      the browser then delivers at release lands on whichever row has slid into that spot, ticking a
      row the user never touched. Found by a test that long-pressed one row and counted two ticked.

      Keeping the hero in the layout and covering it means nothing above the list ever changes size,
      so the row under the finger stays the row under the finger. The same reasoning is why the
      permission banner and the paused notice below are no longer hidden in this mode.
    -->
    <header class="hero">
      <!--
        FR-7.11: the overflow menu is top-right (was a 「设置」 pill top-left). The scrim is a full-page
        transparent layer *behind* the menu, so a tap anywhere else closes it without a document-level
        listener that would fight the row's long-press handling.
      -->
      <button
        v-if="menuOpen"
        type="button"
        class="menu__scrim"
        aria-label="关闭菜单"
        @click="closeMenu"
      />
      <div class="menu">
        <button
          type="button"
          class="menu__button"
          :aria-expanded="menuOpen"
          aria-label="更多"
          @click="menuOpen = !menuOpen"
        >
          <span aria-hidden="true">⋮</span>
        </button>
        <div v-if="menuOpen" class="menu__sheet" role="menu">
          <!-- 分组管理 first: it is the core feature (MIUI's clock puts 作息管理 in this spot). -->
          <button type="button" class="menu__item" role="menuitem" @click="pickFromMenu('groups')">
            分组管理
          </button>
          <button type="button" class="menu__item" role="menuitem" @click="pickFromMenu('permissions')">
            权限体检
            <span v-if="criticalMissingPermissions.length" class="menu__badge">待处理</span>
          </button>
          <button type="button" class="menu__item" role="menuitem" @click="pickFromMenu('settings')">
            设置
          </button>
        </div>
      </div>

      <span v-if="usingMock" class="hero__mock">演示数据</span>
      <p class="hero__clock">{{ nowClock }}</p>
      <p class="hero__date">{{ nowDate }}</p>
      <p class="hero__next">下次响铃 {{ nextRingText }}</p>

      <div v-if="selection.active" class="selbar">
        <button type="button" class="selbar__action" aria-label="取消选择" @click="onCancelSelection">✕</button>
        <span class="selbar__title">已选择 {{ selection.ids.size }} 项</span>
        <button type="button" class="selbar__action" @click="toggleSelectAll(allAlarmIds)">
          {{ allTicked ? '取消全选' : '全选' }}
        </button>
      </div>
    </header>

    <!-- PRD FR-5.4: persistent while a critical permission is missing, and never dismissible. -->
    <div v-if="criticalMissingPermissions.length" class="perm">
      <PermissionBanner :items="criticalMissingPermissions" @open="emit('openPermissions')" />
    </div>

    <p v-if="state.alarms.length && pausedGroups.length" class="global">
      {{ pausedGroups.length }} 个分组处于暂停或停用状态
    </p>

    <!--
      The groups render even when there are no alarms at all. Before this, deleting the last alarm
      replaced the whole list with the empty-state card, so the user's groups vanished and the only way
      to reach one was to create an alarm first — reported from the phone as 「最后一个闹钟删除后，希望
      能够显示已有分组，即使是空的」. The store already builds a section per group including the empty
      ones; the gate here was the only thing hiding them.
    -->
    <div v-if="sections.length" class="sections">
      <GroupSection
        v-for="s in sections"
        :key="s.group.id"
        :section="s"
        :now="state.now"
        :time-format="timeFormat"
        :select-mode="selection.active"
        :selected-ids="selection.ids"
        @toggle-alarm="(id, v) => setAlarmEnabled(id, v)"
        @open-alarm="(id) => emit('openAlarm', id)"
        @set-group-enabled="onSetGroupEnabled"
        @pause-group="openPauseForGroup"
        @resume-group="onResumeGroup"
        @long-press-alarm="enterSelection"
        @pick-alarm="toggleSelection"
      />
    </div>

    <section v-if="state.alarms.length && expiredAlarms.length" class="expired">
      <header class="expired__head">
        <span>已过期 · {{ expiredAlarms.length }}</span>
        <button
          v-if="!selection.active"
          type="button"
          class="expired__clear"
          @click="onClearExpired"
        >
          {{ confirmingClear ? '确认清理？' : '清理' }}
        </button>
      </header>
      <ul class="expired__list">
        <AlarmRow
          v-for="a in expiredAlarms"
          :key="a.id"
          :alarm="a"
          :now="state.now"
          :time-format="timeFormat"
          :show-switch="false"
          :select-mode="selection.active"
          :selected="selection.ids.has(a.id)"
          @open="(id) => emit('openAlarm', id)"
          @long-press="enterSelection"
          @pick="toggleSelection"
        />
      </ul>
    </section>

    <!-- First-run guidance. Teams of one still need to be told where the killer feature is. -->
    <div v-if="!state.alarms.length" class="empty">
      <p class="empty__title">还没有闹钟</p>
      <p class="empty__body">
        点右下角的 + 新建一个。想让一整组闹钟同时放假，就在分组标题上点「暂停」——
        请假时停一次，到点它会自己恢复。
      </p>
    </div>

    <button
      v-if="!selection.active"
      type="button"
      class="fab"
      aria-label="新建闹钟"
      @click="emit('create')"
    >
      <span aria-hidden="true">+</span>
    </button>

    <!--
      The bottom action bar, borrowed from MIUI's own multi-select (screenshot in
      docs/M1-参考-小米闹钟.md): a single destructive action pinned at the bottom, next to the
      delete glyph, instead of a menu the user has to discover.

      Added above 删除: 启用 / 停用 for the whole ticked batch. It is here rather than behind a "…" menu
      because turning a group of alarms off for a holiday is the *non*-destructive thing people do to
      several rows at once, and because the bridge already has a batch write for it —
      `batchUpdateAlarms` — whose whole reason for existing is that one batch must be one
      `recomputeAll()` rather than one per row.
    -->
    <div v-if="selection.active" class="selactions">
      <p v-if="batchError" class="selactions__error">批量操作失败：{{ batchError }}</p>
      <div class="selactions__row">
        <button
          type="button"
          class="selactions__batch"
          :disabled="selection.ids.size === 0"
          @click="onSetSelected(true)"
        >
          启用
        </button>
        <button
          type="button"
          class="selactions__batch"
          :disabled="selection.ids.size === 0"
          @click="onSetSelected(false)"
        >
          停用
        </button>
        <span class="selactions__count">
          {{ selection.ids.size }} 项中 {{ tickedEnabledCount }} 项已开启
        </span>
      </div>
      <p v-if="deleteError" class="selactions__error">删除失败：{{ deleteError }}</p>
      <button
        type="button"
        class="selactions__delete"
        :class="{ 'selactions__delete--armed': confirmingDelete }"
        :disabled="selection.ids.size === 0"
        @click="onDeleteSelected"
      >
        <svg viewBox="0 0 24 24" class="selactions__icon" aria-hidden="true">
          <path
            d="M6 7h12M9 7V5h6v2M8 7l1 13h6l1-13"
            fill="none"
            stroke="currentColor"
            stroke-width="1.7"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
        {{ confirmingDelete ? `确认删除 ${selection.ids.size} 项？` : '删除' }}
      </button>
    </div>

    <PauseSheet
      :open="sheet.open"
      :scope="sheet.scope"
      :id="sheet.id"
      :target-name="sheet.name"
      :default-days="state.settings?.defaultPauseDays ?? 1"
      @close="sheet.open = false"
      @confirm="onConfirmPause"
      @disable-permanently="onDisableForever"
    />

    <PermissionGuide
      :open="guideOpen"
      :items="missingPermissions"
      @go-to-checkup="onGuideGoToCheckup"
      @skip="onGuideSkip"
    />
  </div>
</template>

<style scoped>
.page {
  min-block-size: 100vh;
  padding-block-end: calc(96px + var(--safe-bottom));
}

/* ---- hero ---- */

.hero {
  position: relative;
  padding: calc(var(--safe-top) + var(--sp-7)) var(--page-x) var(--sp-5);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

/* ---- overflow menu (FR-7.11) ----
   Absolutely positioned, so opening it never changes the hero's height (that would move the list and
   break a long press in progress — see the selection-bar note in the template). */

.menu {
  position: absolute;
  inset-block-start: calc(var(--safe-top) + var(--sp-2));
  inset-inline-end: var(--page-x);
  z-index: 30;
}

.menu__button {
  display: grid;
  place-items: center;
  inline-size: 40px;
  block-size: 40px;
  border-radius: var(--r-pill);
  color: var(--text-dim);
  font-size: 22px;
  line-height: 1;
}

.menu__button[aria-expanded='true'] {
  background: var(--surface-2);
  color: var(--text);
}

/* A full-page transparent layer that catches the "tap elsewhere" case. It sits under the sheet
   (z-index below) but over everything else. */
.menu__scrim {
  position: fixed;
  inset: 0;
  z-index: 25;
}

.menu__sheet {
  position: absolute;
  inset-block-start: calc(100% + var(--sp-1));
  inset-inline-end: 0;
  z-index: 30;
  min-inline-size: 168px;
  padding: var(--sp-1) 0;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-sheet);
}

.menu__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  inline-size: 100%;
  min-block-size: 44px;
  padding: 0 var(--sp-4);
  text-align: start;
  font-size: var(--fs-sm);
  color: var(--text);
}

.menu__item:active {
  background: var(--surface-2);
}

.menu__badge {
  font-size: var(--fs-2xs);
  color: var(--warn);
  background: var(--tint-warn);
  border-radius: var(--r-pill);
  padding: 1px 7px;
  white-space: nowrap;
}

.hero__mock {
  position: absolute;
  inset-block-start: calc(var(--safe-top) + var(--sp-3));
  inset-inline-end: var(--page-x);
  font-size: var(--fs-2xs);
  color: var(--warn);
  background: var(--tint-warn);
  border-radius: var(--r-pill);
  padding: 2px 9px;
}

.hero__clock {
  font-size: 52px;
  font-weight: var(--fw-medium);
  line-height: var(--lh-tight);
  letter-spacing: -1.5px;
  font-variant-numeric: tabular-nums;
}

.hero__date {
  font-size: var(--fs-sm);
  color: var(--text-dim);
}

.hero__next {
  margin-block-start: var(--sp-2);
  font-size: var(--fs-xs);
  color: var(--text-faint);
}

/* ---- permission warning strip (PRD FR-5.4) ---- */

.perm {
  padding: 0 var(--page-x) var(--sp-3);
}

/* ---- selection mode ---- */

.selbar {
  /* Absolute, not sticky: see the template comment — it must cover the hero without changing the
     height of anything above the list, or a long press ticks the wrong row. */
  position: absolute;
  inset: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: 0 var(--page-x);
  background: var(--bg);
}

.selbar__action {
  flex: 0 0 auto;
  min-block-size: 44px;
  min-inline-size: 56px;
  padding: 0 var(--sp-2);
  font-size: var(--fs-sm);
  color: var(--accent);
}

.selbar__title {
  flex: 1 1 auto;
  text-align: center;
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
}

.selactions {
  position: fixed;
  inset-block-end: 0;
  inset-inline: 0;
  z-index: 20;
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  padding: var(--sp-3) var(--page-x) calc(var(--sp-4) + var(--safe-bottom));
  background: var(--surface);
  border-block-start: 1px solid var(--border);
  box-shadow: var(--shadow-sheet);
}

.selactions__error {
  font-size: var(--fs-xs);
  color: var(--danger);
}

/* 启用 / 停用 + the count. Sits above the destructive 删除 so the destructive action keeps the
   bottom edge and stays the one that needs a confirming second tap. */
.selactions__row {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.selactions__batch {
  flex: 0 0 auto;
  min-block-size: 40px;
  padding: 0 18px;
  border-radius: var(--r-md);
  border: 1px solid var(--border);
  background: var(--surface-2);
  color: var(--text-dim);
  font-size: var(--fs-sm);
}

.selactions__batch:disabled {
  opacity: 0.4;
}

.selactions__count {
  flex: 1 1 auto;
  text-align: end;
  font-size: var(--fs-xs);
  color: var(--text-faint);
}

.selactions__delete {
  inline-size: 100%;
  min-block-size: 52px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--sp-2);
  border-radius: var(--r-md);
  border: 1px solid var(--border);
  background: var(--surface-2);
  color: var(--danger);
  font-size: var(--fs-md);
  font-weight: var(--fw-medium);
}

/* Armed = the second, confirming tap. Filled rather than outlined so "this will delete now" is
   visible at a glance. */
.selactions__delete--armed {
  background: var(--danger);
  border-color: var(--danger);
  color: var(--surface);
  font-weight: var(--fw-semibold);
}

.selactions__delete:disabled {
  color: var(--text-faint);
}

.selactions__icon {
  inline-size: 20px;
  block-size: 20px;
}

/*
  The action bar is `position: fixed`, so it floats over the list — and the first version of this
  swallowed taps on the **last row**, which is the row a user is most likely to want (it is the one
  they just scrolled to). Reserve room for it instead: the list keeps scrolling under it.

  The reservation has to cover the bar's real height or the bug comes straight back, and the bar grew
  when 启用 / 停用 was added above 删除 — 112px was measured for one row of buttons, so it is 168px for
  two. Measure this from the DOM (`getBoundingClientRect().height`) whenever the bar gains a row,
  rather than adjusting it by eye: the wrong number here is invisible until someone cannot tap the
  last row.
*/
.page--selecting {
  padding-block-end: 168px;
}

/* ---- global pause banner ---- */

.global {
  margin: 0 var(--page-x) var(--sp-3);
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--r-md);
  background: var(--tint-warn);
  color: var(--warn);
  font-size: var(--fs-xs);
}

/* ---- sections ---- */

.sections {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding: 0 var(--page-x);
}

/* ---- expired ---- */

.expired {
  margin-block-start: var(--sp-5);
  padding: 0 var(--page-x);
}

.expired__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--sp-1) var(--sp-2);
  font-size: var(--fs-xs);
  color: var(--text-faint);
}

.expired__clear {
  font-size: var(--fs-xs);
  color: var(--accent);
  min-block-size: 32px;
}

.expired__list {
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  overflow: hidden;
  opacity: 0.7;
}

/* ---- empty state ---- */

.empty {
  margin: var(--sp-7) var(--page-x) 0;
  padding: var(--sp-6) var(--sp-4);
  border: 1px dashed var(--border-strong);
  border-radius: var(--r-lg);
  text-align: center;
}

.empty__title {
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  margin-block-end: var(--sp-2);
}

.empty__body {
  font-size: var(--fs-sm);
  color: var(--text-faint);
  line-height: 1.75;
}

/* ---- floating action button ---- */

.fab {
  position: fixed;
  inset-inline-end: var(--page-x);
  inset-block-end: calc(var(--sp-5) + var(--safe-bottom));
  inline-size: 58px;
  block-size: 58px;
  border-radius: 50%;
  background: var(--accent);
  color: var(--accent-ink);
  font-size: 32px;
  font-weight: var(--fw-regular);
  line-height: 1;
  display: grid;
  place-items: center;
  box-shadow: 0 8px 24px #00000059;
}

.fab:active {
  transform: scale(0.95);
}
</style>
