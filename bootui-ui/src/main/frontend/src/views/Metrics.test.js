import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'

import Metrics from './Metrics.vue'

function meter(name, type = 'COUNTER') {
  return {name, description: `${name} description`, baseUnit: null, type, availableTags: []}
}

function metricsReport(meters = [], overrides = {}) {
  return {
    metricsAvailable: true,
    total: meters.length,
    meters,
    availableTypes: ['COUNTER', 'GAUGE'],
    page: {
      total: meters.length,
      matched: meters.length,
      offset: 0,
      limit: 200,
      returned: meters.length,
      hasMore: false
    },
    ...overrides
  }
}

function metricDetail(name, overrides = {}) {
  return {
    metricsAvailable: true,
    name,
    description: `${name} description`,
    baseUnit: null,
    type: 'COUNTER',
    measurements: [{statistic: 'count', value: 3}],
    availableTags: [],
    samples: [{tags: [], measurements: [{statistic: 'count', value: 3}]}],
    totalSamples: 1,
    samplePage: {total: 1, matched: 1, offset: 0, limit: 100, returned: 1, hasMore: false},
    samplesTruncated: false,
    ...overrides
  }
}

function stubFetch(handler) {
  const fetchMock = vi.fn((input) => {
    const body = handler(String(input))
    return Promise.resolve(new Response(JSON.stringify(body), {status: 200}))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function mountMetrics(platform) {
  const global = platform ? {provide: {panels: ref({platform})}} : {}
  return mount(Metrics, {global})
}

describe('Metrics', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('points Spring Boot users at Actuator or a MeterRegistry by default', async () => {
    stubFetch(() => metricsReport([], {metricsAvailable: false}))
    const wrapper = mountMetrics()
    await flushPromises()
    expect(wrapper.text()).toContain('Add Actuator or a MeterRegistry')
    expect(wrapper.text()).not.toContain('quarkus-micrometer')
  })

  it('points Quarkus users at a quarkus-micrometer registry', async () => {
    stubFetch(() => metricsReport([], {metricsAvailable: false}))
    const wrapper = mountMetrics('quarkus')
    await flushPromises()
    expect(wrapper.text()).toContain('quarkus-micrometer')
    expect(wrapper.text()).not.toContain('Add Actuator')
  })

  it('sends debounced search and type filters to the server', async () => {
    vi.useFakeTimers()
    const fetchMock = stubFetch((url) =>
      url.includes('/detail') ? metricDetail('http.server.requests') : metricsReport([meter('http.server.requests')])
    )
    const wrapper = mountMetrics()
    await flushPromises()

    await wrapper.get('input[aria-label="Search meters"]').setValue('http')
    await wrapper.get('select[aria-label="Filter meters by type"]').setValue('COUNTER')
    vi.advanceTimersByTime(251)
    await flushPromises()

    const filteredRequest = fetchMock.mock.calls
      .map(([input]) => String(input))
      .filter((url) => url.includes('/metrics?'))
      .at(-1)
    const params = new URL(filteredRequest, 'http://localhost').searchParams
    expect(params.get('q')).toBe('http')
    expect(params.get('type')).toBe('COUNTER')
    expect(params.get('offset')).toBe('0')
    expect(params.get('limit')).toBe('200')
  })

  it('loads additional bounded meter pages', async () => {
    const fetchMock = stubFetch((url) => {
      if (url.includes('/detail')) return metricDetail('alpha')
      const params = new URL(url, 'http://localhost').searchParams
      if (params.get('offset') === '2') {
        return metricsReport([meter('charlie')], {
          total: 3,
          page: {total: 3, matched: 3, offset: 2, limit: 200, returned: 1, hasMore: false}
        })
      }
      return metricsReport([meter('alpha'), meter('bravo')], {
        total: 3,
        page: {total: 3, matched: 3, offset: 0, limit: 200, returned: 2, hasMore: true}
      })
    })
    const wrapper = mountMetrics()
    await flushPromises()

    const loadMore = wrapper.findAll('button').find((button) => button.text() === 'Load next 1')
    await loadMore.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.meter-list .list-group-item')).toHaveLength(3)
    expect(
      fetchMock.mock.calls
        .map(([input]) => String(input))
        .some((url) => new URL(url, 'http://localhost').searchParams.get('offset') === '2')
    ).toBe(true)
    expect(wrapper.text()).toContain('3 shown · 3 matched · 3 total')
  })

  it('pages samples independently and reports the bounded range', async () => {
    const fetchMock = stubFetch((url) => {
      if (!url.includes('/detail')) return metricsReport([meter('http.server.requests')])
      const params = new URL(url, 'http://localhost').searchParams
      if (params.get('offset') === '100') {
        return metricDetail('http.server.requests', {
          samples: [{tags: [{key: 'route', value: 'last'}], measurements: [{statistic: 'count', value: 1}]}],
          totalSamples: 101,
          samplePage: {total: 101, matched: 101, offset: 100, limit: 100, returned: 1, hasMore: false},
          samplesTruncated: true
        })
      }
      return metricDetail('http.server.requests', {
        totalSamples: 101,
        samplePage: {total: 101, matched: 101, offset: 0, limit: 100, returned: 1, hasMore: true},
        samplesTruncated: true
      })
    })
    const wrapper = mountMetrics()
    await flushPromises()

    const next = wrapper.findAll('button').find((button) => button.text() === 'Next')
    await next.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Showing 101–101 of 101')
    expect(
      fetchMock.mock.calls
        .map(([input]) => String(input))
        .some((url) => url.includes('/detail') && new URL(url, 'http://localhost').searchParams.get('offset') === '100')
    ).toBe(true)
  })

  it('returns to the last valid sample page when live cardinality shrinks', async () => {
    let secondPageRequests = 0
    const fetchMock = stubFetch((url) => {
      if (!url.includes('/detail')) return metricsReport([meter('http.server.requests')])
      const params = new URL(url, 'http://localhost').searchParams
      if (params.get('offset') === '100') {
        secondPageRequests++
        if (secondPageRequests === 1) {
          return metricDetail('http.server.requests', {
            totalSamples: 101,
            samplePage: {total: 101, matched: 101, offset: 100, limit: 100, returned: 1, hasMore: false}
          })
        }
        return metricDetail('http.server.requests', {
          samples: [],
          totalSamples: 50,
          samplePage: {total: 50, matched: 50, offset: 50, limit: 100, returned: 0, hasMore: false},
          samplesTruncated: true
        })
      }
      return metricDetail('http.server.requests', {
        totalSamples: 50,
        samplePage: {total: 50, matched: 50, offset: 0, limit: 100, returned: 1, hasMore: false}
      })
    })
    const wrapper = mountMetrics()
    await flushPromises()

    const next = wrapper.findAll('button').find((button) => button.text() === 'Next')
    await next.trigger('click')
    await flushPromises()
    await wrapper.get('button[title="Refresh"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Showing 1–1 of 50')
    expect(wrapper.text()).not.toContain('Showing 51–50')
    expect(
      fetchMock.mock.calls
        .map(([input]) => String(input))
        .filter((url) => url.includes('/detail'))
        .some((url) => new URL(url, 'http://localhost').searchParams.get('offset') === '0')
    ).toBe(true)
  })

  it('renders a clear empty state for server-side filters', async () => {
    vi.useFakeTimers()
    stubFetch(() =>
      metricsReport([], {
        total: 12,
        page: {total: 12, matched: 0, offset: 0, limit: 200, returned: 0, hasMore: false}
      })
    )
    const wrapper = mountMetrics()
    await flushPromises()

    await wrapper.get('input[aria-label="Search meters"]').setValue('missing')
    vi.advanceTimersByTime(251)
    await flushPromises()
    expect(wrapper.text()).toContain('No meters match the current server-side filters')
  })
})
