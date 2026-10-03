import { createApp } from 'vue'
import App from './App.vue'
import './styles/base.css'

// Set a theme before the first paint so the app never flashes the wrong palette. The store
// re-applies the stored preference once settings arrive.
const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
document.documentElement.dataset.theme = prefersDark ? 'dark' : 'light'

createApp(App).mount('#app')
