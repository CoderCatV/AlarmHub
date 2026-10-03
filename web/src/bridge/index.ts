/**
 * Bridge entry point: picks the native plugin or the M1 mock.
 *
 * Pages import `bridge` from here and never care which one they got. That is what let M6 swap in the
 * Kotlin implementation without touching a single component.
 */

import { registerPlugin } from '@capacitor/core'
import { mockBridge } from './mock'
import type { AlarmHubBridge } from './types'

export * from './types'

/** True inside the Capacitor native container; false in a plain browser. */
export function isNativePlatform(): boolean {
  const cap = (window as unknown as { Capacitor?: { isNativePlatform?: () => boolean } }).Capacitor
  return typeof cap?.isNativePlatform === 'function' && cap.isNativePlatform()
}

/**
 * M6.5 — browser degradation.
 *
 * The native plugin is now the real implementation, so the rule is inverted from M1: the plugin is
 * used **whenever we are inside the Capacitor container**, and the mock is the fallback for a plain
 * browser (`npm run web:dev`), where `AlarmHub` does not exist and every call would reject.
 *
 * `VITE_BRIDGE=mock` still forces the mock inside the container. That is the escape hatch for
 * reviewing the UI without touching real alarm data — the same role `sessionStorage['alarmhub:scenario']`
 * plays for the mock's own demo data.
 */
const FORCE_MOCK = import.meta.env.VITE_BRIDGE === 'mock'

/** True while the UI is running on fake data. Surfaced as a badge so a review can never mistake
 *  mock content for real content. */
export const usingMock = FORCE_MOCK || !isNativePlatform()

/**
 * A Capacitor plugin method cannot resolve to a bare JSON array.
 *
 * The reply envelope is built from a `JSObject` on the native side
 * (`MessageHandler.sendResponseMessage`) and the JS side hands `result.data` straight to the promise
 * (`native-bridge.js`), so `call.resolve(...)` always lands as an **object**. Three contract methods
 * promise an array, so `AlarmHubPlugin` answers with the list under a named key and this adapter
 * unwraps it.
 *
 * This is the whole of M6.3's 「接线」, and it is deliberately the only place that knows: pages keep
 * seeing the frozen contract, and the native method names stay identical to the contract's.
 */
const LIST_ENVELOPE_KEYS: Record<string, string> = {
  listGroups: 'groups',
  listAlarms: 'alarms',
  getPermissionStatus: 'items',
}

/**
 * A native call that never settles would leave the UI disabled **forever** — the button keeps
 * saying 「保存中」 and nothing can be done about it short of killing the app. That failure mode is
 * invisible from a bug report ("点了没反应"), so the wiring puts a bound on it and turns it into a
 * message the user can read and send back.
 *
 * Two methods are exempt because waiting is their whole job and a human is the one deciding when it
 * ends: `pickRingtone` (the system sound picker) and `openPermissionSetting` (which holds the
 * promise until the runtime-permission dialog has been answered).
 */
const CALL_TIMEOUT_MS = 20_000
const PATIENT_METHODS = new Set(['pickRingtone', 'openPermissionSetting'])

function withTimeout<T>(promise: Promise<T>, method: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(
      () => reject(new Error(`原生调用超时（${CALL_TIMEOUT_MS / 1000} 秒没有回应）：${method}`)),
      CALL_TIMEOUT_MS
    )
    promise.then(
      (value) => {
        clearTimeout(timer)
        resolve(value)
      },
      (error) => {
        clearTimeout(timer)
        reject(error)
      }
    )
  })
}

function withUnwrappedLists(plugin: AlarmHubBridge): AlarmHubBridge {
  return new Proxy(plugin, {
    get(target, property, receiver) {
      const method = typeof property === 'string' ? property : ''
      const value: unknown = Reflect.get(target, property, receiver)
      if (!method || typeof value !== 'function') return value

      const call = value as (...a: unknown[]) => Promise<unknown>
      const envelopeKey = LIST_ENVELOPE_KEYS[method]
      const bounded = PATIENT_METHODS.has(method) ? call : (...a: unknown[]) =>
        withTimeout(call(...a), method)

      if (!envelopeKey) return bounded

      return async (...args: unknown[]): Promise<unknown[]> => {
        const raw = (await bounded(...args)) as Record<string, unknown> | null | undefined
        const list = raw?.[envelopeKey]
        // A missing envelope means the native side changed shape; an empty list keeps the UI usable
        // instead of turning the list page into "载入失败".
        return Array.isArray(list) ? list : []
      }
    },
  })
}

export const bridge: AlarmHubBridge = usingMock
  ? mockBridge
  : withUnwrappedLists(registerPlugin<AlarmHubBridge>('AlarmHub'))

/**
 * Dev-only handle on the mock, so a scripted UI review can drive the app over the DevTools
 * protocol — e.g. delete every alarm to look at the empty state, then reload. It is absent
 * in a native build, where `usingMock` is false.
 */
if (usingMock && typeof window !== 'undefined') {
  ;(window as unknown as { __alarmhubMock?: AlarmHubBridge }).__alarmhubMock = mockBridge
}
