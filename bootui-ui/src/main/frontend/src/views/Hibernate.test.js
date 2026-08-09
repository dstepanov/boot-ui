import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Hibernate from './Hibernate.vue'

function ruleResult(id, name, severity, status, violationCount = 0) {
  return {
    id,
    name,
    category: 'Fetching',
    severity,
    description: `${name} description.`,
    status,
    violationCount,
    sampleViolations: violationCount > 0 ? [`${id} detail`] : [],
    recommendation: `${name} recommendation.`,
    learnMoreUrl: 'https://example.com/hibernate-check'
  }
}

function advisorReport(results, violationsFound = results.filter((result) => result.status === 'VIOLATION').length) {
  return {
    localOnly: true,
    disclaimer: 'Hibernate disclaimer.',
    entityPackages: ['com.example'],
    entitiesAnalyzed: 3,
    rulesEvaluated: 9,
    violationsFound,
    severityCounts: [
      {severity: 'HIGH', count: severityCount(results, 'HIGH')},
      {severity: 'MEDIUM', count: severityCount(results, 'MEDIUM')},
      {severity: 'LOW', count: severityCount(results, 'LOW')},
      {severity: 'INFO', count: severityCount(results, 'INFO')}
    ],
    scan: {
      analyzer: 'BootUI Hibernate Advisor',
      status: 'SCANNED',
      message: 'Hibernate Advisor completed.',
      scannedAt: 1_700_000_000_000,
      rulesEvaluated: 9,
      entitiesAnalyzed: 3,
      violationsFound
    },
    results
  }
}

function severityCount(results, severity) {
  return results.filter((result) => result.status === 'VIOLATION' && result.severity === severity).length
}

async function mountWithReport(report, statistics = defaultStatistics()) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url) => {
      const href = typeof url === 'string' ? url : url.url
      if (href.includes('/hibernate/statistics')) {
        return Promise.resolve(new Response(JSON.stringify(statistics), {status: 200}))
      }
      return Promise.resolve(new Response(JSON.stringify(report), {status: 200}))
    })
  )

  const wrapper = mount(Hibernate)
  await flushPromises()
  return wrapper
}

function defaultStatistics(overrides = {}) {
  return {
    available: false,
    unavailableReason: 'hibernate.generate_statistics is disabled.',
    statistics: null,
    ...overrides
  }
}

function availableStatistics(overrides = {}) {
  return {
    available: true,
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

describe('Hibernate', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows the Vlad Mihalcea best-practices note under the title', async () => {
    const wrapper = await mountWithReport(advisorReport([]))
    const link = wrapper.get('a[href="https://vladmihalcea.com"]')

    expect(wrapper.text()).toContain(
      'Many of those rules are best practices from Vlad Mihalcea, who reviewed the code himself - join him at'
    )
    expect(link.text()).toBe('https://vladmihalcea.com')
    expect(link.attributes('target')).toBe('_blank')
  })

  it('shows only advisor findings sorted by importance', async () => {
    const wrapper = await mountWithReport(
      advisorReport([
        ruleResult('HIB-FETCH-002', 'Informational fetch finding', 'INFO', 'VIOLATION', 2),
        ruleResult('HIB-MAP-004', 'Passing mapping rule', 'MEDIUM', 'PASS'),
        ruleResult('HIB-ID-001', 'Medium severity finding', 'MEDIUM', 'VIOLATION', 1),
        ruleResult('HIB-FETCH-001', 'High severity finding', 'HIGH', 'VIOLATION', 1)
      ])
    )

    expect(wrapper.text()).toContain('Scan complete')
    expect(wrapper.text()).toContain('3 violating rules, sorted by importance')
    expect(wrapper.text()).toContain('What happened:')
    expect(wrapper.text()).toContain('2 findings found for this rule.')
    expect(wrapper.text()).toContain('Learn more')
    expect(wrapper.text()).not.toContain('Passing mapping rule')
    expect(wrapper.findAll('.list-group-item h3').map((title) => title.text())).toEqual([
      'High severity finding',
      'Medium severity finding',
      'Informational fetch finding'
    ])
  })

  it('shows an empty findings state when every evaluated rule passes', async () => {
    const wrapper = await mountWithReport(
      advisorReport([ruleResult('HIB-MAP-004', 'Passing mapping rule', 'MEDIUM', 'PASS')], 0)
    )

    expect(wrapper.text()).toContain('No Hibernate Advisor findings')
    expect(wrapper.text()).not.toContain('Passing mapping rule')
  })

  it('shows a clear message when session statistics are disabled', async () => {
    const wrapper = await mountWithReport(advisorReport([]))

    await wrapper.findAll('button[role="tab"]')[1].trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Session statistics are unavailable')
    expect(wrapper.text()).toContain('hibernate.generate_statistics is disabled.')
    expect(wrapper.text()).toContain('hibernate.generate_statistics=true')
    expect(wrapper.text()).toContain('quarkus.hibernate-orm.statistics=true')
  })

  it('shows grouped session, entity, collection, query, and second-level cache statistics when available', async () => {
    const wrapper = await mountWithReport(advisorReport([]), availableStatistics())

    await wrapper.findAll('button[role="tab"]')[1].trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('Session statistics are unavailable')
    expect(wrapper.text()).toContain('Sessions & transactions')
    expect(wrapper.text()).toContain('Entities')
    expect(wrapper.text()).toContain('Collections')
    expect(wrapper.text()).toContain('Queries')
    expect(wrapper.text()).toContain('Second-level cache')
    expect(wrapper.text()).toContain('com.example.Widget')
  })

  it('hides query cache and second-level cache counters when they are not in use', async () => {
    const wrapper = await mountWithReport(
      advisorReport([]),
      availableStatistics({
        statistics: {
          ...availableStatistics().statistics,
          queryCacheEnabled: false,
          secondLevelCacheEnabled: false,
          secondLevelCacheRegions: []
        }
      })
    )

    await wrapper.findAll('button[role="tab"]')[1].trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Query cache is not in use')
    expect(wrapper.text()).toContain('No second-level cache region has recorded activity')
  })
})
