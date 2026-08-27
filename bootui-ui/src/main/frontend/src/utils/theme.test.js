import {describe, expect, it} from 'vitest'

import {
  applyTheme,
  bootstrapThemeFor,
  normalizeThemePreference,
  readThemePreference,
  resolveTheme,
  THEME_REGISTRY,
  THEME_STORAGE_KEY,
  themeDefinition,
  THEMES
} from './theme.js'
import {createSafeStorage} from './safeStorage.js'

describe('theme utilities', () => {
  it('normalizes persisted theme values', () => {
    expect(normalizeThemePreference('dark')).toBe('dark')
    expect(normalizeThemePreference('light')).toBe('light')
    expect(normalizeThemePreference('win95')).toBe('win95')
    expect(normalizeThemePreference('system')).toBeNull()
    expect(normalizeThemePreference(null)).toBeNull()
  })

  it('reads only supported theme preferences from storage', () => {
    const storage = new Map([[THEME_STORAGE_KEY, 'dark']])

    expect(readThemePreference({getItem: (key) => storage.get(key)})).toBe('dark')
    expect(readThemePreference({getItem: () => 'unexpected'})).toBeNull()
    expect(
      readThemePreference(
        createSafeStorage(() => ({
          getItem: () => {
            throw new DOMException('storage unavailable', 'SecurityError')
          }
        }))
      )
    ).toBeNull()
  })

  it('resolves explicit preferences before the system preference', () => {
    expect(resolveTheme(null, true)).toBe('dark')
    expect(resolveTheme(null, false)).toBe('light')
    expect(resolveTheme('light', true)).toBe('light')
    expect(resolveTheme('dark', false)).toBe('dark')
  })

  it('never resolves an opt-in skin from the system preference alone', () => {
    expect(THEMES).toEqual(['light', 'dark', 'graphite', 'cyberpunk', 'dsfr', 'minimal', 'win95'])
    for (const theme of THEMES.filter((id) => id !== 'light' && id !== 'dark')) {
      expect(resolveTheme(null, true)).not.toBe(theme)
      expect(resolveTheme(null, false)).not.toBe(theme)
      expect(resolveTheme(theme, true)).toBe(theme)
    }
  })

  it('describes every registered theme for the picker', () => {
    for (const theme of THEME_REGISTRY) {
      expect(theme.label).toBeTruthy()
      expect(theme.hint).toBeTruthy()
      expect(theme.icon).toMatch(/^bi-/)
      expect(['light', 'dark']).toContain(theme.scheme)
    }
    expect(themeDefinition('cyberpunk').label).toBe('Cyberpunk')
    // An unknown id must not blank the picker: it falls back to the default shell.
    expect(themeDefinition('unexpected')).toBe(THEME_REGISTRY[0])
  })

  it('builds each skin on the Bootstrap component scheme it declares', () => {
    expect(bootstrapThemeFor('light')).toBe('light')
    expect(bootstrapThemeFor('dark')).toBe('dark')
    // A light-luminance skin runs on `light` even though it looks nothing like the default.
    expect(bootstrapThemeFor('win95')).toBe('light')
    expect(bootstrapThemeFor('dsfr')).toBe('light')
    expect(bootstrapThemeFor('minimal')).toBe('light')
    expect(bootstrapThemeFor('graphite')).toBe('dark')
    expect(bootstrapThemeFor('cyberpunk')).toBe('dark')
  })

  it('applies the resolved theme to the document root', () => {
    const root = document.createElement('html')

    applyTheme(root, 'dark')
    expect(root.dataset.bootuiTheme).toBe('dark')
    expect(root.dataset.bsTheme).toBe('dark')
    expect(root.style.colorScheme).toBe('dark')

    applyTheme(root, 'win95')
    expect(root.dataset.bootuiTheme).toBe('win95')
    expect(root.dataset.bsTheme).toBe('light')
    expect(root.style.colorScheme).toBe('light')
  })
})
