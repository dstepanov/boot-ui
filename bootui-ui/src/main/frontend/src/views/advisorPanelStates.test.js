import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Spring from './Spring.vue'

const report = {
  scan: {status: 'COMPLETED', scannedAt: '2024-05-01T10:15:00Z'},
  rulesEvaluated: 12,
  violationsFound: 2,
  componentsAnalyzed: 87,
  disclaimer: 'Heuristic checks only.',
  severityCounts: [{severity: 'HIGH', count: 2}],
  inspected: ['org.example.App'],
  results: [],
  analysisErrors: []
}

function mountSpring() {
  return mount(Spring, {
    global: {
      stubs: {AutoRefreshToggle: true},
      provide: {panels: {value: {platform: 'spring-boot'}}}
    }
  })
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('advisor panel first paint', () => {
  it('shows the shared skeleton until the mount-time report settles', async () => {
    let resolveReport
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise((resolve) => (resolveReport = resolve)))
    )

    const wrapper = mountSpring()
    await flushPromises()

    expect(wrapper.find('.skeleton-wrapper').exists()).toBe(true)
    expect(wrapper.find('.advisor-score-card').exists()).toBe(false)

    resolveReport({ok: true, status: 200, headers: new Headers(), json: () => Promise.resolve(report)})
    await flushPromises()

    expect(wrapper.find('.skeleton-wrapper').exists()).toBe(false)
    expect(wrapper.find('.advisor-score-card').exists()).toBe(true)
  })

  it('clears the first-paint state even when the report request fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))

    const wrapper = mountSpring()
    await flushPromises()

    expect(wrapper.find('.skeleton-wrapper').exists()).toBe(false)
    expect(wrapper.text()).toContain('Unable to load Spring Advisor report')
  })

  it('renders rule identifiers and categories in the monospace stack', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        headers: new Headers(),
        json: () =>
          Promise.resolve({
            ...report,
            results: [
              {
                id: 'SPRING-001',
                name: 'Field injection',
                category: 'BEANS',
                severity: 'HIGH',
                status: 'VIOLATION',
                violationCount: 3,
                description: 'Field injection hides dependencies.',
                recommendation: 'Use constructor injection.',
                sampleViolations: ['org.example.OrderService'],
                dismissed: false
              }
            ]
          })
      })
    )

    const wrapper = mountSpring()
    await flushPromises()

    const ruleId = wrapper.findAll('.font-monospace').filter((node) => node.text() === 'SPRING-001')
    const category = wrapper.findAll('.badge.font-monospace').filter((node) => node.text() === 'BEANS')

    expect(ruleId).toHaveLength(1)
    expect(category).toHaveLength(1)
  })

  it('titles the rule results section with a heading below the panel h2', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ok: true, status: 200, headers: new Headers(), json: () => Promise.resolve(report)})
    )

    const wrapper = mountSpring()
    await flushPromises()

    expect(wrapper.findAll('h2')).toHaveLength(1)
    expect(wrapper.findAll('h3').map((heading) => heading.text())).toContain('Rule results')
  })
})
