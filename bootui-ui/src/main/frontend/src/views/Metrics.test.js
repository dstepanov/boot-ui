import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'

import Metrics from './Metrics.vue'

function provenance(overrides = {}) {
  return {
    groupId: 'jvm',
    groupLabel: 'JVM',
    contributor: 'Micrometer JVM binders',
    familyId: 'jvm.memory',
    familyLabel: 'JVM memory',
    classified: true,
    explanation: 'Heap and non-heap memory usage per memory pool.',
    explanationSource: 'CURATED',
    interpretation: 'Used, committed and maximum bytes are gauges per area and pool id.',
    ...overrides
  }
}

function meter(name, type = 'COUNTER', provenanceOverrides = {}) {
  return {
    name,
    description: `${name} description`,
    baseUnit: null,
    type,
    availableTags: [],
    provenance: provenance(provenanceOverrides)
  }
}

function group(overrides = {}) {
  return {
    id: 'jvm',
    label: 'JVM',
    contributor: 'Micrometer JVM binders',
    summary: 'Memory pools, garbage collection, threads and class loading reported by the JVM itself.',
    interpretation: 'Gauges are point-in-time readings while counters accumulate for the process lifetime.',
    meterCount: 1,
    describedMeterCount: 1,
    families: ['JVM memory'],
    commonTagKeys: ['area', 'id'],
    baseUnits: ['bytes'],
    ...overrides
  }
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
    groups: meters.length ? [group({meterCount: meters.length, describedMeterCount: meters.length})] : [],
    catalogueVersion: '2026.1',
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
    provenance: provenance(),
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

function mountMetricsWithDetail(detail) {
  stubFetch((url) =>
    url.includes('/detail') ? detail : metricsReport([meter(detail.name, 'COUNTER', detail.provenance)])
  )
  return mountMetrics()
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

  it('groups meters by provenance and explains the selected group', async () => {
    vi.useFakeTimers()
    const fetchMock = stubFetch((url) =>
      url.includes('/detail') ? metricDetail('jvm.memory.used') : metricsReport([meter('jvm.memory.used', 'GAUGE')])
    )
    const wrapper = mountMetrics()
    await flushPromises()

    const chip = wrapper.findAll('.provenance-chip').find((button) => button.text().includes('JVM'))
    expect(chip.attributes('aria-pressed')).toBe('false')
    expect(wrapper.text()).toContain('Catalogue 2026.1')

    await chip.trigger('click')
    vi.advanceTimersByTime(251)
    await flushPromises()

    expect(chip.attributes('aria-pressed')).toBe('true')
    expect(wrapper.text()).toContain('Micrometer JVM binders')
    expect(wrapper.text()).toContain('1 of 1 documented by the registry')
    const groupedRequest = fetchMock.mock.calls
      .map(([input]) => String(input))
      .filter((url) => url.includes('/metrics?'))
      .at(-1)
    expect(new URL(groupedRequest, 'http://localhost').searchParams.get('group')).toBe('jvm')
  })

  it('sends provenance and explanation-source filters to the server', async () => {
    vi.useFakeTimers()
    const fetchMock = stubFetch((url) =>
      url.includes('/detail') ? metricDetail('jvm.memory.used') : metricsReport([meter('jvm.memory.used', 'GAUGE')])
    )
    const wrapper = mountMetrics()
    await flushPromises()

    await wrapper.get('select[aria-label="Filter meters by provenance"]').setValue('unclassified')
    await wrapper.get('select[aria-label="Filter meters by explanation source"]').setValue('UNKNOWN')
    vi.advanceTimersByTime(251)
    await flushPromises()

    const params = new URL(
      fetchMock.mock.calls
        .map(([input]) => String(input))
        .filter((url) => url.includes('/metrics?'))
        .at(-1),
      'http://localhost'
    ).searchParams
    expect(params.get('provenance')).toBe('unclassified')
    expect(params.get('explanation')).toBe('UNKNOWN')
  })

  it('distinguishes native descriptions from curated explanations and undocumented meters', async () => {
    const wrapper = mountMetricsWithDetail(
      metricDetail('app.orders.processed', {
        provenance: provenance({
          groupId: 'application',
          groupLabel: 'Application / unclassified',
          contributor: 'Application or unrecognized instrumentation',
          familyId: null,
          familyLabel: null,
          classified: false,
          explanation: null,
          explanationSource: 'UNKNOWN',
          interpretation: null
        })
      })
    )
    await flushPromises()

    const detailProvenance = wrapper.get('.card .provenance-detail')
    expect(detailProvenance.text()).toContain('Not documented')
    expect(detailProvenance.text()).toContain('BootUI does not explain it')
    expect(detailProvenance.text()).toContain('contributed by Application or unrecognized instrumentation')
    expect(detailProvenance.text()).not.toContain('Micrometer JVM binders')
  })

  it('keeps an active group visible when other filters exclude it from the facets', async () => {
    vi.useFakeTimers()
    let listResponses = 0
    stubFetch((url) => {
      if (url.includes('/detail')) return metricDetail('jvm.memory.used')
      listResponses++
      if (listResponses <= 2) return metricsReport([meter('jvm.memory.used', 'GAUGE')])
      return metricsReport([], {
        groups: [group({id: 'application', label: 'Application / unclassified', meterCount: 4})]
      })
    })
    const wrapper = mountMetrics()
    await flushPromises()

    await wrapper
      .findAll('.provenance-chip')
      .find((chip) => chip.text().includes('JVM'))
      .trigger('click')
    vi.advanceTimersByTime(251)
    await flushPromises()

    await wrapper.get('select[aria-label="Filter meters by provenance"]').setValue('unclassified')
    vi.advanceTimersByTime(251)
    await flushPromises()

    const jvmChip = wrapper.findAll('.provenance-chip').find((chip) => chip.text().includes('JVM'))
    expect(jvmChip, 'the active group stays clearable even when the facets drop it').toBeTruthy()
    expect(jvmChip.attributes('aria-pressed')).toBe('true')
  })

  it('attributes catalogue interpretation even when the description is native', async () => {
    const wrapper = mountMetricsWithDetail(
      metricDetail('http.server.requests', {
        provenance: provenance({
          groupId: 'http-server',
          groupLabel: 'HTTP server',
          familyId: 'http.server.requests',
          familyLabel: 'HTTP server requests',
          explanation: 'Duration of HTTP server request handling',
          explanationSource: 'NATIVE',
          interpretation: 'Read the count and total together over the same interval.'
        })
      })
    )
    await flushPromises()

    const detailProvenance = wrapper.get('.card .provenance-detail')
    expect(detailProvenance.text()).toContain('Native description')
    expect(detailProvenance.text()).toContain('How to read this family (BootUI catalogue)')
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
