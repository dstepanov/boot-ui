import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import Copilot from './Copilot.vue'
import AutoRefreshToggle from './components/AutoRefreshToggle.vue'

const route = vi.hoisted(() => ({name: 'copilot'}))

vi.mock('vue-router', () => ({
  useRoute: () => route
}))

function dashboard(overrides = {}) {
  return {
    available: true,
    sessionStateDir: '/home/dev/.copilot/session-state',
    sessionCount: 1,
    eventCount: 2,
    turnCount: 1,
    totalInputTokens: 123,
    totalOutputTokens: 45,
    errorCount: 0,
    activeLast24Hours: 1,
    activeLast7Days: 1,
    sessionsWithSchemaDrift: 0,
    lastActivityEpochMillis: Date.now() - 60_000,
    categoryCounts: [],
    modelCounts: [],
    topTools: [],
    otherToolEventCount: 0,
    activityBuckets: [],
    dailyActivityBuckets: [],
    recentSessions: [],
    warnings: [],
    ...overrides
  }
}

function sessionList(overrides = {}) {
  return {
    available: true,
    sessionStateDir: '/home/dev/.copilot/session-state',
    total: 0,
    returned: 0,
    maxSessions: 100,
    sessions: [],
    warnings: [],
    ...overrides
  }
}

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function fetchForPanel(apiBase, dashboardPayload = dashboard(), sessionPayload = sessionList()) {
  return vi.fn((url) => {
    if (url === `${apiBase}/dashboard`) return Promise.resolve(jsonResponse(dashboardPayload))
    if (url === `${apiBase}/sessions`) return Promise.resolve(jsonResponse(sessionPayload))
    return Promise.resolve(jsonResponse({}, false, 404))
  })
}

describe('Copilot', () => {
  let wrapper

  beforeEach(() => {
    vi.useFakeTimers()
    Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
    route.name = 'copilot'
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('uses the shared auto-refresh controls instead of the live status badge', async () => {
    const eventSource = vi.fn()
    vi.stubGlobal('EventSource', eventSource)
    vi.stubGlobal('fetch', fetchForPanel('api/copilot'))

    wrapper = mount(Copilot)
    await flushPromises()

    expect(wrapper.findComponent(AutoRefreshToggle).exists()).toBe(true)
    expect(wrapper.get('button[title="Refresh"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Auto-refresh')
    expect(wrapper.text()).toContain('168')
    expect(wrapper.text()).toContain('123 in · 45 out')
    expect(wrapper.get('#activity-mode-tokens').element.checked).toBe(true)
    expect(wrapper.text()).not.toContain('Live')
    expect(eventSource).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(10_000)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/copilot/dashboard', expect.anything())
    expect(fetch).toHaveBeenCalledWith('api/copilot/sessions', expect.anything())
    expect(fetch).toHaveBeenCalledTimes(4)
  })

  it('loads Claude Code through the same auto-refresh mechanism', async () => {
    route.name = 'claude-code'
    vi.stubGlobal(
      'fetch',
      fetchForPanel(
        'api/claude-code',
        dashboard({sessionStateDir: '/home/dev/.claude/projects'}),
        sessionList({sessionStateDir: '/home/dev/.claude/projects'})
      )
    )

    wrapper = mount(Copilot)
    await flushPromises()

    expect(wrapper.text()).toContain('Claude Code')
    expect(wrapper.findComponent(AutoRefreshToggle).exists()).toBe(true)
    expect(fetch).toHaveBeenCalledWith('api/claude-code/dashboard', expect.anything())
    expect(fetch).toHaveBeenCalledWith('api/claude-code/sessions', expect.anything())
  })

  it('renders activity tooltips for token and event modes', async () => {
    vi.stubGlobal(
      'fetch',
      fetchForPanel(
        'api/copilot',
        dashboard({
          activityBuckets: [
            {
              startEpochMillis: Date.now() - 60_000,
              endEpochMillis: Date.now(),
              eventCount: 4,
              errorCount: 1,
              inputTokens: 25543,
              outputTokens: 2435432
            }
          ]
        })
      )
    )

    wrapper = mount(Copilot)
    await flushPromises()

    expect(wrapper.get('.activity-tooltip').text()).toBe('Input tokens: 25,54k · Output tokens: 2,43m')

    await wrapper.get('#activity-mode-events').setValue(true)

    expect(wrapper.get('.activity-tooltip').text()).toBe('Events: 4 · Failures: 1')
  })

  it('keeps the session selector and nested activity actions independent', async () => {
    const session = {
      id: 'session-one',
      updatedAtEpochMillis: Date.now() - 60_000,
      eventCount: 2,
      errorCount: 1,
      inputTokens: 123,
      outputTokens: 45
    }
    const detail = {
      summary: session,
      counts: {total: 2, byCategory: {SHELL: 2}, errors: 1},
      turns: [],
      recentEvents: [],
      failureEvents: [{id: 'failure', category: 'SHELL', summary: 'SHELL failed', success: false}],
      warnings: []
    }
    const fetchMock = vi.fn((url) => {
      if (url === 'api/copilot/dashboard') {
        return Promise.resolve(jsonResponse(dashboard({recentSessions: [session]})))
      }
      if (url === 'api/copilot/sessions') {
        return Promise.resolve(jsonResponse(sessionList({total: 1, returned: 1, sessions: [session]})))
      }
      if (url === 'api/copilot/sessions/session-one') {
        return Promise.resolve(jsonResponse(detail))
      }
      return Promise.resolve(jsonResponse({}, false, 404))
    })
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(Copilot)
    await flushPromises()

    const selectSession = wrapper.get('button.session-row-target')
    expect(selectSession.attributes('aria-label')).toBe('View session session-one')
    await selectSession.trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="tab"].active').text()).toContain('Activity')

    await wrapper.get('button[aria-label="Show failures for session-one"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="tab"].active').text()).toContain('Failures')

    await selectSession.trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="tab"].active').text()).toContain('Activity')
  })
})
