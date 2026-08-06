import {safeLocalStorage} from './safeStorage.js'

const STORAGE_KEY = 'bootui.recentPanels'
const MAX_RECENT_PANELS = 5

export function loadRecentPanels() {
  const parsed = safeLocalStorage.getJson(STORAGE_KEY, [])
  if (!Array.isArray(parsed)) {
    safeLocalStorage.removeItem(STORAGE_KEY)
    return []
  }
  return parsed.filter((name) => typeof name === 'string')
}

export function recordRecentPanel(name) {
  if (!name) return loadRecentPanels()
  const next = [name, ...loadRecentPanels().filter((entry) => entry !== name)].slice(0, MAX_RECENT_PANELS)
  safeLocalStorage.setJson(STORAGE_KEY, next)
  return next
}
