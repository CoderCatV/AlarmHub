/**
 * The navigation stack, backed by browser history, and the reason it works that way.
 *
 * Seven screens and no deep linking, so a router library would be more configuration than navigation
 * — but "no router" does **not** mean "no history", and that distinction cost a real bug:
 *
 * The first version kept only a current route, and `back()` simply jumped to the list. On a phone that
 * meant the system back gesture (left-edge swipe, or the hardware key) had nothing of ours to go back
 * to, so it fell through to the platform and **closed the app** — from 设置, from the editor, from
 * anywhere. A user who swipes back expects the previous screen.
 *
 * So every level that can be backed out of pushes a history entry, and `popstate` is what actually
 * changes the app. Two consequences worth stating plainly:
 *
 * 1. The gesture, the hardware key and our own ✕ / 取消 buttons all take **the same path**, so they
 *    cannot disagree about what "back" means.
 * 2. `MainActivity` needs no knowledge of screens at all: it asks the WebView whether it can go back.
 *
 * ## What counts as a level
 *
 * Not just routes. Selection mode (长按批量勾选) is a level *inside* the list screen, and the user's
 * own annotation on it was 「左滑也可以起到返回作用」 — a left swipe should leave selection mode, not
 * close the app. So an entry carries the route **and** whether the selection overlay is up, and a pop
 * restores both. Storing the whole state in the history entry rather than mirroring a separate array is
 * what keeps them from drifting; forward navigation then works for free.
 */

import { ref } from 'vue'

export type Route =
  | { name: 'list' }
  | { name: 'edit'; id: number | null }
  | { name: 'groups' }
  | { name: 'settings' }
  | { name: 'permissions' }

/** One level of "where the user is", as stored in a history entry. */
export type NavEntry = {
  route: Route
  /** True while the list is in long-press multi-select. */
  selecting: boolean
}

const ROOT_ENTRY: NavEntry = { route: { name: 'list' }, selecting: false }

export const route = ref<Route>(ROOT_ENTRY.route)

let current: NavEntry = { ...ROOT_ENTRY }

const listeners: ((entry: NavEntry) => void)[] = []

/** Told about every pop, so state that lives outside this file (the selection set) can follow along. */
export function onEntryChange(fn: (entry: NavEntry) => void): void {
  listeners.push(fn)
}

/** The level the app believes it is showing. */
export function currentEntry(): NavEntry {
  return current
}

function pushEntry(entry: NavEntry): void {
  current = entry
  route.value = entry.route
  try {
    window.history.pushState({ dsh: entry }, '')
  } catch {
    // No history (a bare browser context): navigation still works, the system gesture just cannot
    // reach us. Deliberately not fatal.
  }
}

export function go(next: Route): void {
  pushEntry({ route: next, selecting: false })
}

/** Pushes a level *within* the current screen — today, entering selection mode. */
export function pushOverlay(): void {
  pushEntry({ ...current, selecting: true })
}

/**
 * Ask to go back one level.
 *
 * Delegates to `history.back()` whenever there is something of ours to return to, so the app state is
 * changed by `popstate` and never by two code paths that could disagree.
 */
export function back(): void {
  if (!canGoBack()) return
  try {
    window.history.back()
  } catch {
    current = { ...ROOT_ENTRY }
    route.value = current.route
  }
}

/**
 * True while there is a level of ours to return to.
 *
 * Read by `MainActivity` through `window.__dshCanGoBack`; at the root (list, no selection) it is false,
 * which is what lets the gesture close the app there — the behaviour a user expects on a home screen.
 */
export function canGoBack(): boolean {
  return !(current.route.name === 'list' && !current.selecting)
}

export function initNav(): void {
  try {
    if (!(window.history.state as { dsh?: NavEntry } | null)?.dsh) {
      window.history.replaceState({ dsh: current }, '')
    }
    window.addEventListener('popstate', (e) => {
      const entry = (e.state as { dsh?: NavEntry } | null)?.dsh ?? ROOT_ENTRY
      current = entry
      route.value = entry.route
      for (const fn of listeners) fn(entry)
    })
  } catch {
    /* see pushEntry */
  }

  // What the native side needs from this module. Two functions on `window` rather than bridge calls
  // because `MainActivity` has to answer a back gesture, and a bridge round trip is asynchronous.
  //
  // **These read the app's own state, not the WebView's history.** That distinction is the whole
  // reason they exist: `WebView.canGoBack()` and this module disagreed in practice — after the app had
  // been in the background, Chromium can swap the renderer and the WebView's history is not what the
  // page believes it is, so the native side concluded "nothing to go back to" and finished the
  // Activity from a sub-page. Asking the page removes the second source of truth.
  const w = window as unknown as { __dshCanGoBack?: () => boolean; __dshBack?: () => void }
  w.__dshCanGoBack = canGoBack
  w.__dshBack = back
}
