/**
 * Returns the configured BootUI base path (e.g. {@code /bootui} or {@code /myapp}) by reading the
 * {@code <base href>} element that the backend injects into the SPA shell at runtime. Falls back to
 * the hard-coded default {@code /bootui} when no {@code <base>} element is present (e.g. in unit
 * tests that mount components without a full HTML document).
 *
 * @returns {string} normalized base path with no trailing slash
 */
export function getBootUiBasePath() {
  if (typeof document === 'undefined') {
    return '/bootui'
  }
  const href = document.querySelector('base')?.getAttribute('href') ?? '/bootui/'
  return href.endsWith('/') ? href.slice(0, -1) : href
}

/**
 * Returns the BootUI API base path (e.g. {@code /bootui/api} or {@code /myapp/api}).
 *
 * @returns {string} API base path with no trailing slash
 */
export function getBootUiApiPath() {
  return getBootUiBasePath() + '/api'
}
