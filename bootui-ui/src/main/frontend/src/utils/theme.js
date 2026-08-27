import {safeLocalStorage} from './safeStorage.js'

export const THEME_QUERY = '(prefers-color-scheme: dark)'
export const THEME_STORAGE_KEY = 'bootui.theme'

/* The theme registry is the single source of truth for which shells exist, how
   they are labelled in the picker, and whether Bootstrap's own component scheme
   should run light or dark underneath them.

   `light` and `dark` are the two lightings of the Calm Control Room and the only
   ones `prefers-color-scheme` can ever resolve to. Every other entry is an
   opt-in skin: a deliberate departure that is only ever reached by an explicit
   choice, never inferred from the system. */
export const THEME_REGISTRY = [
  {id: 'light', label: 'Light', icon: 'bi-brightness-high', hint: 'The default daylight console', scheme: 'light'},
  {id: 'dark', label: 'Dark', icon: 'bi-moon-stars', hint: 'The same room, after hours', scheme: 'dark'},
  {id: 'graphite', label: 'Graphite', icon: 'bi-gem', hint: 'Slick, professional, high-focus', scheme: 'dark'},
  {id: 'minimal', label: 'Minimal', icon: 'bi-circle', hint: 'Ink on paper, nothing else', scheme: 'light'},
  {id: 'cyberpunk', label: 'Cyberpunk', icon: 'bi-cpu', hint: 'Neon terminal, night city', scheme: 'dark'},
  {id: 'dsfr', label: 'France', icon: 'bi-bank', hint: "Système de design de l'État", scheme: 'light'},
  {id: 'win95', label: 'Windows 95', icon: 'bi-window-stack', hint: 'A 1995 desktop application', scheme: 'light'}
]

export const THEMES = THEME_REGISTRY.map((theme) => theme.id)

const THEME_BY_ID = new Map(THEME_REGISTRY.map((theme) => [theme.id, theme]))

export function themeDefinition(theme) {
  return THEME_BY_ID.get(theme) ?? THEME_REGISTRY[0]
}

export function normalizeThemePreference(value) {
  return THEME_BY_ID.has(value) ? value : null
}

export function readThemePreference(storage = safeLocalStorage) {
  return normalizeThemePreference(storage.getItem(THEME_STORAGE_KEY))
}

export function resolveTheme(preference, prefersDark) {
  return normalizeThemePreference(preference) ?? (prefersDark ? 'dark' : 'light')
}

/* Bootstrap only knows `light` and `dark`, so each skin declares which of the
   two its components should be built on. A light-luminance skin such as the
   Windows 95 silver shell runs on `light` even though it looks nothing like the
   default theme. */
export function bootstrapThemeFor(theme) {
  return themeDefinition(theme).scheme
}

export function applyTheme(root, theme) {
  const resolvedTheme = resolveTheme(theme, false)
  const bootstrapTheme = bootstrapThemeFor(resolvedTheme)
  root.dataset.bootuiTheme = resolvedTheme
  root.dataset.bsTheme = bootstrapTheme
  root.style.colorScheme = bootstrapTheme
}
