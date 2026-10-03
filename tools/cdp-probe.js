#!/usr/bin/env node
/**
 * Drives the app's WebView over the Chrome DevTools Protocol.
 *
 * The WebView is the real target: `adb shell input tap` needs coordinates guessed from a
 * screenshot, and `uiautomator dump` cannot see inside a WebView at all. CDP can query and
 * click DOM nodes directly, which makes UI verification deterministic.
 *
 * Setup (once per app launch — the socket is per-process):
 *   $pid = (adb shell pidof com.alarmhub.app).Trim()
 *   adb forward tcp:9222 localabstract:webview_devtools_remote_$pid
 *
 * Usage:
 *   node tools/cdp-probe.js                      # list targets and basic page info
 *   node tools/cdp-probe.js --eval "<expr>"      # evaluate an expression, print the value
 *   node tools/cdp-probe.js --click "<selector>" # click the first match
 *   node tools/cdp-probe.js --click-nth 2 "<sel>"# click the Nth match (0-based)
 *   node tools/cdp-probe.js --touch x y [ms] [dx dy]
 *                                                # inject a real touch: hold `ms` (long press if
 *                                                # large) and optionally drag by dx,dy
 *
 * `--touch` exists because the shell cannot inject touches on HyperOS: MIUI does not grant
 * `INJECT_EVENTS`, so `adb shell input tap` dies with a SecurityException there. Dispatching the
 * touch through the WebView's DevTools socket goes in below the system input pipeline and needs no
 * permission — it is what makes gesture testing possible on the real phone.
 *
 * Any console error/warning and uncaught exception produced while attached is printed at the
 * end, so a silent Vue warning does not slip past a screenshot review.
 *
 * Node 22+ ships a global WebSocket, so there are no dependencies.
 */

const http = require('node:http')

const PORT = process.env.CDP_PORT || 9222
const ENDPOINT = `http://127.0.0.1:${PORT}`

const get = (url) =>
  new Promise((resolve, reject) => {
    http
      .get(url, (res) => {
        let body = ''
        res.on('data', (c) => (body += c))
        res.on('end', () => resolve(body))
      })
      .on('error', reject)
  })

/** Console noise we care about; info/debug/log are ignored on purpose. */
function isInteresting(level, text) {
  if (level === 'error' || level === 'warning' || level === 'warn') return true
  // Vite and Vue both surface problems through console.log too.
  return /\[Vue warn\]|Failed to|Uncaught|TypeError|ReferenceError|is not a function/.test(text)
}

async function main() {
  const args = process.argv.slice(2)

  const targets = JSON.parse(await get(`${ENDPOINT}/json`))
  const page = targets.find((t) => t.type === 'page')

  if (!page) {
    console.log('DevTools targets:')
    for (const t of targets) console.log(`  [${t.type}] ${t.title || '(no title)'}  ${t.url}`)
    throw new Error('no page target — is the app running and is the port forwarded?')
  }

  const ws = new WebSocket(page.webSocketDebuggerUrl)
  const pending = new Map()
  const noise = []
  let nextId = 0

  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve)
    ws.addEventListener('error', () => reject(new Error('websocket failed to open')))
  })

  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data)

    if (msg.id && pending.has(msg.id)) {
      pending.get(msg.id)(msg)
      pending.delete(msg.id)
      return
    }

    if (msg.method === 'Runtime.consoleAPICalled') {
      const text = (msg.params.args || [])
        .map((a) => a.value ?? a.description ?? a.type)
        .join(' ')
      if (isInteresting(msg.params.type, text)) noise.push(`${msg.params.type}: ${text}`)
    }
    if (msg.method === 'Runtime.exceptionThrown') {
      const d = msg.params.exceptionDetails
      noise.push(`exception: ${d.exception?.description || d.text}`)
    }
    if (msg.method === 'Log.entryAdded' && msg.params.entry.level === 'error') {
      noise.push(`log: ${msg.params.entry.text}`)
    }
  })

  const send = (method, params = {}) =>
    new Promise((resolve) => {
      const id = ++nextId
      pending.set(id, resolve)
      ws.send(JSON.stringify({ id, method, params }))
    })

  await send('Runtime.enable')
  await send('Log.enable')

  const evaluate = async (expression) => {
    const r = await send('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true,
    })
    if (r.result?.exceptionDetails) {
      return { __exception: r.result.exceptionDetails.exception?.description || 'exception' }
    }
    return r.result?.result?.value
  }

  const show = (value) => {
    if (typeof value === 'string') console.log(value)
    else console.log(JSON.stringify(value, null, 2))
  }

  /*
   * Turns a *silently truncated* result into a loud failure.
   *
   * `Runtime.evaluate` hands back whatever shape the expression produced. When the expression is an
   * object or an array, the harness used to print only the first character of the printed JSON (`{` or
   * `[`) — see the `--eval` branch below — and `ui-drive.py`'s parser then fell back to "the first line
   * of the output", so the caller received `'{'` as if it were a value. Three separate debugging rounds
   * were spent on that: it read as a page syntax error, then as a plugin patch that did not apply, then
   * as an unimplemented UI branch. It was the tool every time.
   *
   * Nothing legitimate evaluates to a bare `{` or `[` as a *value*, so this is safe to reject.
   */
  const rejectTruncated = (value) => {
    if (value === '{' || value === '[') {
      throw new Error(
        `the expression returned a truncated object/array: ${JSON.stringify(value)}. This is the CDP ` +
          'probe losing multi-line output, not a value from the page. Wrap the expression in ' +
          'JSON.stringify(...) — see docs/STATUS.md §1.1 #24.',
      )
    }
    return value
  }

  if (args.length === 0) {
    console.log('target :', page.url)
    console.log('title  :', await evaluate('document.title'))
    console.log('origin :', await evaluate('location.origin'))
    console.log('native :', await evaluate('!!(window.Capacitor && Capacitor.isNativePlatform())'))
    console.log('sections:', await evaluate("document.querySelectorAll('section').length"))
    console.log('rows    :', await evaluate("document.querySelectorAll('li.row').length"))
  } else if (args[0] === '--touch') {
    // Synthesise a real touch sequence inside the page.
    //
    // This is the only way to test a *gesture* on a HyperOS device: `adb shell input tap/swipe`
    // needs `INJECT_EVENTS`, which MIUI does not grant to the shell, so every touch injection there
    // fails with a SecurityException. `Input.dispatchTouchEvent` goes in through the WebView's own
    // DevTools socket instead, below the system's input pipeline, and Chrome treats it as a genuine
    // user gesture — enough to fire `pointerdown`, produce momentum scrolling, and satisfy the
    // user-activation checks that `navigator.vibrate` requires.
    //
    // --touch <x> <y> [holdMs] [dx] [dy]
    //   holdMs > 0 presses and holds (a long press); dx/dy add a drag before the release.
    const x = Number(args[1])
    const y = Number(args[2])
    const hold = Number(args[3] ?? 60)
    const dx = Number(args[4] ?? 0)
    const dy = Number(args[5] ?? 0)
    if (!Number.isFinite(x) || !Number.isFinite(y)) {
      throw new Error('--touch needs x and y (CSS pixels, page coordinates)')
    }
    const point = (px, py) => [{ x: px, y: py, radiusX: 8, radiusY: 8, force: 1, id: 0 }]
    const touch = (type, px, py) =>
      send('Input.dispatchTouchEvent', { type, touchPoints: type === 'touchEnd' ? [] : point(px, py) })

    await touch('touchStart', x, y)
    // Hold in small steps: a single long sleep can be coalesced away, and Chrome's long-press
    // heuristic wants to see the finger stay put rather than one instantaneous event.
    const steps = Math.max(1, Math.round(hold / 100))
    for (let i = 0; i < steps; i++) {
      await new Promise((r) => setTimeout(r, hold / steps))
      await touch('touchMove', x + (dx * (i + 1)) / steps, y + (dy * (i + 1)) / steps)
    }
    await touch('touchEnd', x + dx, y + dy)
    show({ touched: [x, y], holdMs: hold, drag: [dx, dy] })
  } else if (args[0] === '--click' || args[0] === '--click-nth') {
    const nth = args[0] === '--click-nth' ? Number(args[1]) : 0
    const selector = args[0] === '--click-nth' ? args[2] : args[1]
    if (!selector) throw new Error('--click needs a selector')

    const result = await evaluate(`(() => {
      const nodes = document.querySelectorAll(${JSON.stringify(selector)})
      if (nodes.length === 0) return { found: 0, clicked: false }
      const node = nodes[${nth}]
      if (!node) return { found: nodes.length, clicked: false, reason: 'index out of range' }
      node.click()
      return { found: nodes.length, clicked: true, text: (node.textContent || '').trim().slice(0, 40) }
    })()`)
    show(result)
  } else {
    // `--eval <expr>`: everything after the flag is the expression, joined back together. The shell
    // splits an expression like `({a:1,b:'x'})` into several argv entries (or, when it does not split,
    // the probe's own argument filter can drop the parentheses), and both cases used to reach
    // `Runtime.evaluate` malformed — which came back as a one-character `{` and was read as a value.
    const expression = args[0] === '--eval' ? args.slice(1).join(' ') : args.join(' ')
    show(rejectTruncated(await evaluate(expression)))
  }

  // Give the app a moment to react and to flush any console output caused by the action.
  await new Promise((r) => setTimeout(r, 700))

  if (noise.length) {
    console.log('\n--- page console problems ---')
    for (const line of noise) console.log('  ' + line)
    ws.close()
    process.exitCode = 2
  } else {
    console.log('\nno console errors or warnings')
  }

  ws.close()
}

main().catch((e) => {
  console.error('FAIL:', e.message)
  process.exit(1)
})
