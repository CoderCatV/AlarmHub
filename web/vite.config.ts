import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

const here = (p: string) => fileURLToPath(new URL(p, import.meta.url))

export default defineConfig({
  // Pin the root to web/ so the config works no matter which directory vite is invoked from
  // (npm scripts run from the repo root, not from web/).
  root: here('.'),
  plugins: [vue()],
  build: {
    // Capacitor's webDir is `www`, so the build output goes straight there and
    // `npx cap sync android` picks it up with no copying step in between.
    outDir: here('../www'),
    emptyOutDir: true,
    target: 'es2022',
    // The whole bundle is shipped inside the APK; sourcemaps stay on so that
    // chrome://inspect over the DevTools protocol maps back to real source.
    sourcemap: true,
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
  },
})
