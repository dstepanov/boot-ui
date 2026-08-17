import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import HibernateStatistics from './HibernateStatistics.vue'
import PanelHeader from './components/PanelHeader.vue'

function jsonResponse(body) {
  return Promise.resolve(new Response(JSON.stringify(body), {status: 200}))
}

function disabledStatistics(overrides = {}) {
  return {
    available: false,
    enableAvailable: true,
    unavailableReason: 'hibernate.generate_statistics is disabled.',
    statistics: null,
    ...overrides
  }
}

function enabledStatistics(overrides = {}) {
  return {
    available: true,
    enableAvailable: false,
    unavailableReason: null,
    statistics: {
      sessionOpenCount: 12,
      sessionCloseCount: 11,
      flushCount: 20,
      connectCount: 12,
      transactionCount: 8,
      successfulTransactionCount: 8,
      entityLoadCount: 40,
      entityFetchCount: 5,
      entityInsertCount: 3,
      entityUpdateCount: 2,
      entityDeleteCount: 1,
      collectionLoadCount: 6,
      collectionFetchCount: 1,
      collectionRecreateCount: 0,
      collectionUpdateCount: 2,
      collectionRemoveCount: 0,
      queryExecutionCount: 30,
      queryExecutionMaxTime: 15,
      queryExecutionMaxTimeQueryString: 'select e from Example e',
      queryCacheEnabled: true,
      queryCacheHitCount: 10,
      queryCacheMissCount: 2,
      queryCachePutCount: 2,
      secondLevelCacheEnabled: true,
      secondLevelCacheHitCount: 7,
      secondLevelCacheMissCount: 1,
      secondLevelCachePutCount: 1,
      secondLevelCacheRegions: [{regionName: 'com.example.Widget', hitCount: 7, missCount: 1, putCount: 1}]
    },
    ...overrides
  }
}

describe('HibernateStatistics panel', () => {
  let wrapper

  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('does not call the API when the manifest reports the panel unavailable', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(HibernateStatistics, {
      props: {
        panel: {
          id: 'hibernate-statistics',
          enabled: true,
          available: false,
          unavailableReason: 'No EntityManagerFactory bean is available'
        }
      }
    })
    await flushPromises()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Session statistics are unavailable')
    expect(wrapper.text()).toContain('No EntityManagerFactory bean is available')
    expect(wrapper.findComponent(PanelHeader).props('refreshable')).toBe(false)
  })

  it('shows a clear message when session statistics are disabled', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => jsonResponse(disabledStatistics()))
    )
    wrapper = mount(HibernateStatistics)
    await flushPromises()

    expect(wrapper.text()).toContain('Session statistics are unavailable')
    expect(wrapper.text()).toContain('hibernate.generate_statistics is disabled.')
    expect(wrapper.text()).toContain('hibernate.generate_statistics=true')
    expect(wrapper.text()).toContain('quarkus.hibernate-orm.statistics=true')
    expect(wrapper.get('#enable-hibernate-statistics').text()).toContain('Enable for this runtime')
  })

  it('enables statistics for the current runtime only after an explicit click', async () => {
    document.cookie = 'XSRF-TOKEN=test-token; path=/'
    const fetchMock = vi.fn((url, init) => {
      if (url === 'api/hibernate-statistics/enable') {
        return jsonResponse(enabledStatistics())
      }
      return jsonResponse(disabledStatistics())
    })
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(HibernateStatistics)
    await flushPromises()

    await wrapper.get('#enable-hibernate-statistics').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('api/hibernate-statistics/enable', expect.objectContaining({method: 'POST'}))
    expect(wrapper.text()).toContain('Hibernate statistics enabled for this runtime')
    expect(wrapper.text()).toContain('Session lifecycle')
  })

  it('disables runtime activation when the panel is read-only', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => jsonResponse(disabledStatistics()))
    )
    wrapper = mount(HibernateStatistics, {
      props: {
        panel: {
          id: 'hibernate-statistics',
          enabled: true,
          available: true,
          readOnly: true,
          readOnlyReason: 'Panel is read-only.'
        }
      }
    })
    await flushPromises()

    expect(wrapper.get('#enable-hibernate-statistics').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Runtime activation is disabled')
    expect(wrapper.text()).toContain('Panel is read-only.')
  })

  it('shows grouped session, entity, collection, query, and second-level cache statistics when available', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => jsonResponse(enabledStatistics()))
    )
    wrapper = mount(HibernateStatistics)
    await flushPromises()

    expect(wrapper.text()).not.toContain('Session statistics are unavailable')
    expect(wrapper.text()).toContain('Runtime overview')
    expect(wrapper.text()).toContain('Session lifecycle')
    expect(wrapper.text()).toContain('Entity activity')
    expect(wrapper.text()).toContain('Collection activity')
    expect(wrapper.text()).toContain('Query activity')
    expect(wrapper.text()).toContain('Second-level cache')
    expect(wrapper.text()).toContain('8 / 8')
    expect(wrapper.text()).toContain('com.example.Widget')
  })

  it('hides query cache and second-level cache counters when they are not in use', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        jsonResponse(
          enabledStatistics({
            statistics: {
              ...enabledStatistics().statistics,
              queryCacheEnabled: false,
              secondLevelCacheEnabled: false,
              secondLevelCacheRegions: []
            }
          })
        )
      )
    )
    wrapper = mount(HibernateStatistics)
    await flushPromises()

    expect(wrapper.text()).toContain('Query cache not in use')
    expect(wrapper.text()).toContain('No cache region has recorded activity')
  })

  it('fetches from the dedicated hibernate-statistics endpoint', async () => {
    const fetchMock = vi.fn(() => jsonResponse(enabledStatistics()))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(HibernateStatistics)
    await flushPromises()

    const requestedUrl = fetchMock.mock.calls[0][0]
    const href = typeof requestedUrl === 'string' ? requestedUrl : requestedUrl.url
    expect(href).toContain('api/hibernate-statistics')
  })
})
