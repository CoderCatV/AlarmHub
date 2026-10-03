<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import AlarmEditorPage from './pages/AlarmEditorPage.vue'
import AlarmListPage from './pages/AlarmListPage.vue'
import GroupsPage from './pages/GroupsPage.vue'
import PermissionsPage from './pages/PermissionsPage.vue'
import SettingsPage from './pages/SettingsPage.vue'
import { back, go, initNav, route } from './state/nav'
import { load, startClock, state } from './state/store'

let stopClock: (() => void) | null = null

onMounted(async () => {
  // Before the first render: the initial history entry has to carry the root route, or the very first
  // system back would find nothing of ours and close the app from a sub-page.
  initNav()
  await load()
  stopClock = startClock()
})

onUnmounted(() => stopClock?.())
</script>

<template>
  <div v-if="state.phase === 'loading'" class="boot">载入中…</div>

  <div v-else-if="state.phase === 'error'" class="boot boot--error">
    <p class="boot__title">载入失败</p>
    <pre class="boot__detail">{{ state.error }}</pre>
  </div>

  <AlarmListPage
    v-else-if="route.name === 'list'"
    @open-alarm="(id) => go({ name: 'edit', id })"
    @create="go({ name: 'edit', id: null })"
    @open-settings="go({ name: 'settings' })"
    @open-permissions="go({ name: 'permissions' })"
  />

  <AlarmEditorPage
    v-else-if="route.name === 'edit'"
    :key="route.id ?? 'new'"
    :id="route.id"
    @back="back"
    @saved="back"
  />

  <!--
    Every 返回 uses `back()` — never `go()`. That is not stylistic: `go()` **pushes** an entry, so a
    screen whose back button called `go({name:'settings'})` left a duplicate behind, and 设置's own
    back then popped straight into 分组管理 again — an infinite ping-pong that only the hardware/gesture
    path escaped, because that one always pops. Reported from the phone: 「返回」 on 分组管理 → 设置 →
    「返回」 → 分组管理 → …, while the left-edge swipe was fine.

    The hierarchy still reads correctly: 分组管理 is reached from 设置, so popping from it lands on 设置.
  -->
  <GroupsPage v-else-if="route.name === 'groups'" @back="back" />

  <SettingsPage
    v-else-if="route.name === 'settings'"
    @back="back"
    @open-groups="go({ name: 'groups' })"
    @open-permissions="go({ name: 'permissions' })"
  />

  <PermissionsPage v-else @back="back" />
</template>

<style scoped>
.boot {
  min-block-size: 100vh;
  display: grid;
  place-items: center;
  padding: var(--sp-6);
  color: var(--text-faint);
  font-size: var(--fs-sm);
}

.boot--error {
  align-content: center;
  gap: var(--sp-3);
  color: var(--danger);
}

.boot__title {
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
}

.boot__detail {
  margin: 0;
  font-size: var(--fs-xs);
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text-faint);
}
</style>
