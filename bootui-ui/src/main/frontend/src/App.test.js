import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {createMemoryHistory, createRouter} from 'vue-router'

import {routes} from './routes.js'

const TestPanel = {template: '<section />'}
const namedRoutes = routes.filter((route) => route.name && route.meta?.title)
const NARROW_QUERY = '(max-width: 991.98px)'

function shellRoutes() {
  return routes.map((route) => (route.redirect || !route.name ? route : {...route, component: TestPanel}))
}

function jsonResponse(body) {
  return {
    ok: true,
    json: () => Promise.resolve(body)
  }
}

function mockShellFetch(platform = 'spring-boot') {
  vi.stubGlobal(
    'fetch',
    vi.fn((url) => {
      const requestUrl = String(url)
      if (requestUrl === 'api/overview') {
        return Promise.resolve(
          jsonResponse({
            applicationName: 'bootui-sample',
            frameworkName: 'Spring Boot',
            frameworkVersion: '4.0.6',
            javaVersion: '17',
            activeProfiles: ['dev'],
            activation: {enabled: true}
          })
        )
      }

      if (requestUrl === 'api/panels') {
        return Promise.resolve(
          jsonResponse({
            platform,
            panels: namedRoutes.map((route) => ({
              id: route.name,
              title: route.meta.title,
              available: true,
              enabled: true
            }))
          })
        )
      }

      return Promise.reject(new Error(`Unexpected fetch URL: ${requestUrl}`))
    })
  )
}

function stubLocalStorage(initialValues = {}) {
  const storage = new Map(Object.entries(initialValues))
  const localStorageStub = {
    clear: () => storage.clear(),
    getItem: (key) => storage.get(key) ?? null,
    removeItem: (key) => storage.delete(key),
    setItem: (key, value) => storage.set(key, String(value))
  }

  Object.defineProperty(window, 'localStorage', {
    configurable: true,
    value: localStorageStub
  })
  if (globalThis !== window) {
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: localStorageStub
    })
  }
  return {localStorageStub, storage}
}

function stubLocalStorageGetter(getter) {
  Object.defineProperty(window, 'localStorage', {
    configurable: true,
    get: getter
  })
  if (globalThis !== window) {
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      get: getter
    })
  }
}

function restoreLocalStorage() {
  Reflect.deleteProperty(window, 'localStorage')
  if (globalThis !== window) {
    Reflect.deleteProperty(globalThis, 'localStorage')
  }
}

function stubMatchMedia(narrow = false) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query) => ({
      matches: query === NARROW_QUERY ? narrow : false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn()
    }))
  )
}

async function mountApp(initialPath = '/overview', options = {}) {
  if (typeof window.matchMedia !== 'function') stubMatchMedia()
  vi.resetModules()
  const {default: App} = await import('./App.vue')
  const router = createRouter({
    history: createMemoryHistory(),
    routes: shellRoutes()
  })

  await router.push(initialPath)
  await router.isReady()

  const wrapper = mount(App, {
    attachTo: document.body,
    global: {
      plugins: [router],
      stubs: {
        CommandPalette: options.stubCommandPalette === false ? false : true,
        RouterView: options.stubRouterView === false ? false : {template: '<div />'}
      }
    }
  })
  await flushPromises()

  return {router, wrapper}
}

function groupToggle(wrapper, title) {
  const toggle = wrapper.findAll('.bootui-nav-group__toggle').find((button) => button.text().includes(title))
  if (!toggle) {
    throw new Error(`Could not find ${title} navigation group toggle`)
  }
  return toggle
}

describe('App sidebar navigation', () => {
  beforeEach(() => {
    stubLocalStorage()
    mockShellFetch()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    restoreLocalStorage()
    document.body.innerHTML = ''
  })

  it('moves the active group away from Security after navigating to another group', async () => {
    const {router, wrapper} = await mountApp('/spring-security')

    expect(groupToggle(wrapper, 'Security').classes()).toContain('active')

    await router.push('/scheduled')
    await flushPromises()

    expect(groupToggle(wrapper, 'Security').classes()).not.toContain('active')
    expect(groupToggle(wrapper, 'Services').classes()).toContain('active')
  })

  it('releases pointer focus from group toggles after mouse or touch activation', async () => {
    const {wrapper} = await mountApp()
    const securityToggle = groupToggle(wrapper, 'Security')
    securityToggle.element.focus()

    securityToggle.element.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true, detail: 1}))
    await flushPromises()

    expect(securityToggle.attributes('aria-expanded')).toBe('true')
    expect(document.activeElement).not.toBe(securityToggle.element)
  })

  it('keeps keyboard focus on group toggles after keyboard activation', async () => {
    const {wrapper} = await mountApp()
    const securityToggle = groupToggle(wrapper, 'Security')
    securityToggle.element.focus()

    securityToggle.element.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true, detail: 0}))
    await flushPromises()

    expect(securityToggle.attributes('aria-expanded')).toBe('true')
    expect(document.activeElement).toBe(securityToggle.element)
  })

  it('hides and disables only the closed mobile drawer', async () => {
    stubMatchMedia(true)
    const {wrapper} = await mountApp()
    const drawer = wrapper.find('#bootui-mobile-navigation')
    const toggle = wrapper.find('.nav-hamburger')

    expect(drawer.attributes('aria-hidden')).toBe('true')
    expect(drawer.attributes()).toHaveProperty('inert')
    expect(drawer.attributes('role')).toBe('dialog')
    expect(toggle.attributes('aria-controls')).toBe('bootui-mobile-navigation')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(toggle.attributes('aria-label')).toBe('Open navigation menu')
    expect(wrapper.find('.bootui-workspace').attributes()).not.toHaveProperty('inert')
  })

  it('leaves the desktop sidebar exposed without mobile modal restrictions', async () => {
    const {wrapper} = await mountApp()
    const sidebar = wrapper.find('#bootui-mobile-navigation')

    expect(sidebar.attributes('aria-hidden')).toBeUndefined()
    expect(sidebar.attributes('aria-modal')).toBeUndefined()
    expect(sidebar.attributes('role')).toBeUndefined()
    expect(sidebar.attributes()).not.toHaveProperty('inert')
    expect(wrapper.find('.bootui-workspace').attributes()).not.toHaveProperty('inert')
  })

  it('contains mobile drawer focus and restores it to the toggle on Escape', async () => {
    stubMatchMedia(true)
    const {wrapper} = await mountApp()
    const drawer = wrapper.find('#bootui-mobile-navigation')
    const toggle = wrapper.find('.nav-hamburger')

    toggle.element.focus()
    await toggle.trigger('click')
    await flushPromises()

    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(toggle.attributes('aria-label')).toBe('Close navigation menu')
    expect(drawer.attributes('aria-hidden')).toBeUndefined()
    expect(drawer.attributes('aria-modal')).toBe('true')
    expect(drawer.attributes()).not.toHaveProperty('inert')
    expect(wrapper.find('.bootui-workspace').attributes()).toHaveProperty('inert')
    expect(document.activeElement).toBe(wrapper.find('.sidebar-toggle').element)

    const first = wrapper.find('.brand-card')
    const last = wrapper.find('.contribute-card')
    first.element.focus()
    await drawer.trigger('keydown', {key: 'Tab', shiftKey: true})
    expect(document.activeElement).toBe(last.element)

    last.element.focus()
    await drawer.trigger('keydown', {key: 'Tab'})
    expect(document.activeElement).toBe(first.element)

    await drawer.trigger('keydown', {key: 'Escape'})
    await flushPromises()

    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(drawer.attributes('aria-hidden')).toBe('true')
    expect(document.activeElement).toBe(toggle.element)
  })

  it('moves focus out of the drawer after route and backdrop closure', async () => {
    stubMatchMedia(true)
    const {router, wrapper} = await mountApp()
    const toggle = wrapper.find('.nav-hamburger')

    toggle.element.focus()
    await toggle.trigger('click')
    await flushPromises()
    await wrapper.find('.bootui-nav-backdrop').trigger('click')
    await flushPromises()
    expect(document.activeElement).toBe(toggle.element)

    await toggle.trigger('click')
    await flushPromises()
    const architectureLink = wrapper.find('a[href="/architecture"]')
    architectureLink.element.focus()
    await architectureLink.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('architecture')
    expect(wrapper.find('#bootui-mobile-navigation').attributes('aria-hidden')).toBe('true')
    expect(document.activeElement).toBe(wrapper.find('main').element)
    expect(wrapper.find('#bootui-mobile-navigation').element.contains(document.activeElement)).toBe(false)
  })

  it('keeps the command palette closed while the mobile navigation modal is open', async () => {
    stubMatchMedia(true)
    const {wrapper} = await mountApp('/overview', {stubCommandPalette: false})
    const toggle = wrapper.find('.nav-hamburger')

    await toggle.trigger('click')
    await flushPromises()
    window.dispatchEvent(new KeyboardEvent('keydown', {key: 'k', ctrlKey: true}))
    await flushPromises()

    expect(wrapper.find('[aria-label="Command palette"]').exists()).toBe(false)
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('#bootui-mobile-navigation').attributes('aria-modal')).toBe('true')
  })
})

describe('App command palette', () => {
  beforeEach(() => {
    stubLocalStorage()
    mockShellFetch()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    restoreLocalStorage()
    document.body.innerHTML = ''
  })

  it('awaits rendering, focuses the search input, makes the background inert, and restores the trigger', async () => {
    const {wrapper} = await mountApp('/overview', {stubCommandPalette: false})
    const trigger = wrapper.find('.cp-trigger')
    trigger.element.focus()

    await trigger.trigger('click')
    await flushPromises()

    const input = wrapper.find('.cp-input')
    expect(document.activeElement).toBe(input.element)
    expect(wrapper.find('.bootui-workspace').attributes()).toHaveProperty('inert')
    expect(wrapper.find('#bootui-mobile-navigation').attributes()).toHaveProperty('inert')

    await input.trigger('keydown', {key: 'Escape'})
    await flushPromises()

    expect(wrapper.find('[aria-label="Command palette"]').exists()).toBe(false)
    expect(document.activeElement).toBe(trigger.element)

    await trigger.trigger('click')
    await flushPromises()
    await wrapper.find('.cp-backdrop').trigger('click')
    await flushPromises()

    expect(wrapper.find('[aria-label="Command palette"]').exists()).toBe(false)
    expect(document.activeElement).toBe(trigger.element)
  })

  it('restores the invoking control after opening with the keyboard shortcut', async () => {
    const {wrapper} = await mountApp('/overview', {stubCommandPalette: false})
    const themeToggle = wrapper.find('.theme-toggle')
    themeToggle.element.focus()

    window.dispatchEvent(new KeyboardEvent('keydown', {key: 'k', ctrlKey: true}))
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.find('.cp-input').element)

    await wrapper.find('.cp-input').trigger('keydown', {key: 'Escape'})
    await flushPromises()
    expect(document.activeElement).toBe(themeToggle.element)
  })

  it('moves focus to main content after command-palette navigation', async () => {
    const {router, wrapper} = await mountApp('/overview', {stubCommandPalette: false})
    await wrapper.find('.cp-trigger').trigger('click')
    await flushPromises()
    const input = wrapper.find('.cp-input')
    await input.setValue('Architecture')
    await input.trigger('keydown', {key: 'Enter'})
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('architecture')
    expect(wrapper.find('[aria-label="Command palette"]').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.find('main').element)
  })
})

describe('App route recovery and document title', () => {
  beforeEach(() => {
    stubLocalStorage()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    restoreLocalStorage()
    document.body.innerHTML = ''
    document.title = ''
  })

  it('updates the title from the active platform route and existing overview application name', async () => {
    mockShellFetch('quarkus')
    const {router} = await mountApp('/spring')

    expect(document.title).toBe('Quarkus · bootui-sample · BootUI')

    await router.push('/beans')
    await flushPromises()
    expect(document.title).toBe('Beans · bootui-sample · BootUI')
  })

  it('renders the catch-all route and recovers through overview or command search', async () => {
    mockShellFetch()
    const {router, wrapper} = await mountApp('/missing-panel', {
      stubCommandPalette: false,
      stubRouterView: false
    })

    expect(wrapper.get('#not-found-title').text()).toBe('Page not found')
    expect(document.title).toBe('Not Found · bootui-sample · BootUI')

    await wrapper.get('.not-found-actions a').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('overview')

    await router.push('/still-missing')
    await flushPromises()
    await wrapper.get('.not-found-actions button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[aria-label="Command palette"]').exists()).toBe(true)
    expect(document.activeElement).toBe(wrapper.get('.cp-input').element)
  })
})

describe('App optional browser storage', () => {
  beforeEach(() => {
    mockShellFetch()
    stubMatchMedia()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    restoreLocalStorage()
    document.body.innerHTML = ''
    delete document.documentElement.dataset.bootuiTheme
    delete document.documentElement.dataset.bsTheme
    document.documentElement.style.removeProperty('color-scheme')
  })

  it('mounts and keeps theme, sidebar, and recent panels usable when the storage getter is denied', async () => {
    stubLocalStorageGetter(() => {
      throw new DOMException('Storage denied', 'SecurityError')
    })

    const {wrapper} = await mountApp('/overview', {stubCommandPalette: false})
    expect(wrapper.find('.bootui-shell').exists()).toBe(true)

    await wrapper.find('.theme-toggle').trigger('click')
    expect(document.documentElement.dataset.bsTheme).toBe('dark')

    await wrapper.find('.sidebar-toggle').trigger('click')
    expect(wrapper.find('aside.bootui-sidebar').classes()).toContain('bootui-sidebar--collapsed')

    await wrapper.find('.cp-trigger').trigger('click')
    await flushPromises()
    expect(wrapper.find('.cp-section-label').text()).toBe('Recent')
    expect(wrapper.find('.cp-item-title').text()).toBe('Overview')
  })

  it('keeps current-page controls working when storage methods start throwing', async () => {
    const target = {
      getItem() {
        throw new DOMException('Read denied', 'SecurityError')
      },
      setItem() {
        throw new DOMException('Quota denied', 'QuotaExceededError')
      },
      removeItem() {
        throw new DOMException('Remove denied', 'SecurityError')
      }
    }
    stubLocalStorageGetter(() => target)

    const {wrapper} = await mountApp()
    await wrapper.find('.theme-toggle').trigger('click')
    await wrapper.find('.sidebar-toggle').trigger('click')

    expect(document.documentElement.dataset.bsTheme).toBe('dark')
    expect(wrapper.find('aside.bootui-sidebar').classes()).toContain('bootui-sidebar--collapsed')
  })

  it('ignores malformed persisted values and synchronizes theme storage events without reading storage', async () => {
    const values = new Map([
      ['bootui.sidebar.collapsed', 'sometimes'],
      ['bootui.theme', 'purple'],
      ['bootui.expandedGroups', '{bad']
    ])
    stubLocalStorageGetter(() => ({
      getItem: (key) => values.get(key) ?? null,
      setItem: (key, value) => values.set(key, value),
      removeItem() {
        throw new DOMException('Cleanup denied', 'SecurityError')
      }
    }))

    const {wrapper} = await mountApp()
    expect(wrapper.find('aside.bootui-sidebar').classes()).not.toContain('bootui-sidebar--collapsed')
    expect(document.documentElement.dataset.bsTheme).toBe('light')
    expect(groupToggle(wrapper, 'Advisors').attributes('aria-expanded')).toBe('true')

    stubLocalStorageGetter(() => {
      throw new DOMException('Storage denied', 'SecurityError')
    })
    window.dispatchEvent(new StorageEvent('storage', {key: 'bootui.theme', newValue: 'dark'}))
    await flushPromises()
    expect(document.documentElement.dataset.bsTheme).toBe('dark')

    window.dispatchEvent(new StorageEvent('storage', {key: 'bootui.theme', newValue: 'invalid'}))
    await flushPromises()
    expect(document.documentElement.dataset.bsTheme).toBe('light')
  })

  it('persists again after a write-denied period without losing current-page state', async () => {
    const values = new Map()
    let denyWrites = true
    stubLocalStorageGetter(() => ({
      getItem: (key) => values.get(key) ?? null,
      setItem(key, value) {
        if (denyWrites) throw new DOMException('Quota denied', 'QuotaExceededError')
        values.set(key, value)
      },
      removeItem: (key) => values.delete(key)
    }))

    const {wrapper} = await mountApp()
    await wrapper.find('.sidebar-toggle').trigger('click')
    expect(wrapper.find('aside.bootui-sidebar').classes()).toContain('bootui-sidebar--collapsed')
    expect(values.has('bootui.sidebar.collapsed')).toBe(false)

    denyWrites = false
    await wrapper.find('.sidebar-toggle').trigger('click')
    expect(wrapper.find('aside.bootui-sidebar').classes()).not.toContain('bootui-sidebar--collapsed')
    expect(values.get('bootui.sidebar.collapsed')).toBe('false')
  })
})

describe('App remote authentication', () => {
  beforeEach(() => {
    stubLocalStorage()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    restoreLocalStorage()
    document.body.innerHTML = ''
  })

  it('unlocks the API with the token from the startup log', async () => {
    let authenticated = false
    vi.stubGlobal(
      'fetch',
      vi.fn((url, options = {}) => {
        const requestUrl = String(url)
        if (requestUrl === 'api/auth/session') {
          expect(options.method).toBe('POST')
          expect(new Headers(options.headers).get('Authorization')?.split(' ')).toEqual(['Bearer', 'startup-token'])
          authenticated = true
          return Promise.resolve({ok: true, status: 204})
        }
        if (!authenticated) {
          return Promise.resolve({ok: false, status: 401})
        }
        if (requestUrl === 'api/overview') {
          return Promise.resolve(
            jsonResponse({
              applicationName: 'bootui-sample',
              javaVersion: '17',
              activeProfiles: ['dev'],
              activation: {enabled: true}
            })
          )
        }
        if (requestUrl === 'api/panels') {
          return Promise.resolve(jsonResponse({platform: 'spring-boot', panels: []}))
        }
        return Promise.reject(new Error(`Unexpected fetch URL: ${requestUrl}`))
      })
    )

    const {wrapper} = await mountApp()
    expect(wrapper.find('#authentication-title').text()).toBe('Unlock BootUI')

    await wrapper.find('#bootui-authentication-token').setValue('startup-token')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('#authentication-title').exists()).toBe(false)
    expect(wrapper.text()).toContain('bootui-sample')
  })
})

describe('App shell footer', () => {
  beforeEach(() => {
    stubLocalStorage()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    restoreLocalStorage()
    document.body.innerHTML = ''
  })

  it('labels the footer for Spring Boot when the manifest platform is spring-boot', async () => {
    mockShellFetch('spring-boot')
    const {wrapper} = await mountApp()

    expect(wrapper.find('.bootui-footer a').text()).toBe('BootUI - The missing developer UI for Spring Boot!')
  })

  it('labels the footer for Quarkus when the manifest platform is quarkus', async () => {
    mockShellFetch('quarkus')
    const {wrapper} = await mountApp()

    expect(wrapper.find('.bootui-footer a').text()).toBe('BootUI - The missing developer UI for Quarkus!')
  })
})
