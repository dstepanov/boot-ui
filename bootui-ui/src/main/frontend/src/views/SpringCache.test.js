import {flushPromises, mount} from '@vue/test-utils'
import {ref} from 'vue'
import {afterEach, describe, expect, it, vi} from 'vitest'

import SpringCache from './SpringCache.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function statistics(overrides = {}) {
  return {
    available: true,
    source: 'NATIVE',
    provider: 'Caffeine',
    scope: 'CACHE',
    window: 'APPLICATION_LIFETIME',
    since: null,
    unavailableReason: null,
    requests: 10,
    hits: 8,
    misses: 2,
    hitRatio: 0.8,
    missRatio: 0.2,
    puts: null,
    evictions: 0,
    removals: null,
    loadSuccesses: 0,
    loadFailures: 0,
    size: 2,
    ratioUnavailableReason: null,
    ...overrides
  }
}

function unavailableStatistics(reason) {
  return {
    available: false,
    source: 'NONE',
    provider: 'Caffeine',
    scope: 'CACHE',
    window: 'UNKNOWN',
    since: null,
    unavailableReason: reason,
    requests: null,
    hits: null,
    misses: null,
    hitRatio: null,
    missRatio: null,
    puts: null,
    evictions: null,
    removals: null,
    loadSuccesses: null,
    loadFailures: null,
    size: null,
    ratioUnavailableReason: 'No hit and miss counters are available to derive a ratio from.'
  }
}

function tier(overrides = {}) {
  return {
    id: 'caffeine',
    name: 'Caffeine',
    level: 0,
    implementationType: 'com.github.benmanes.caffeine.cache.BoundedLocalCache',
    locality: 'LOCAL',
    maximumSize: 500,
    expiryPolicy: 'expire after write 5m',
    policyNote: null,
    statistics: statistics({scope: 'TIER'}),
    ...overrides
  }
}

function cache(overrides = {}) {
  return {
    managerName: 'cacheManager',
    name: 'orders',
    nativeType: 'CaffeineCache',
    size: 2,
    metrics: null,
    opaque: false,
    opaqueReason: null,
    tiers: [tier()],
    statistics: statistics(),
    ...overrides
  }
}

function report(overrides = {}) {
  return {
    cacheAvailable: true,
    clearEnabled: true,
    managerCount: 1,
    cacheCount: 1,
    operationCount: 0,
    tierCount: 1,
    truncated: false,
    managers: [
      {
        name: 'cacheManager',
        type: 'CaffeineCacheManager',
        noOp: false,
        composition: 'SIMPLE',
        dynamicCaches: 'UNKNOWN',
        delegateTypes: [],
        caches: [cache()]
      }
    ],
    operations: [],
    warnings: [],
    ...overrides
  }
}

function reportWithCache(cacheOverrides, reportOverrides = {}) {
  const base = report(reportOverrides)
  base.managers[0].caches = [cache(cacheOverrides)]
  return base
}

function mountWithPlatform(platform, body = report()) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(body)))
  return mount(SpringCache, {
    global: {provide: {panels: ref({platform, panels: []})}}
  })
}

describe('SpringCache operations section', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('shows the Spring annotation-operations section on Spring Boot', async () => {
    wrapper = mountWithPlatform('spring-boot')
    await flushPromises()
    const text = wrapper.text()

    expect(text).toContain('Annotation operations')
    expect(text).toContain('@Cacheable')
    expect(text).not.toContain('@CacheResult')
  })

  it('defaults to the Spring section when no platform is provided', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report())))
    wrapper = mount(SpringCache)
    await flushPromises()

    expect(wrapper.text()).toContain('Annotation operations')
  })

  it('describes build-time cached operations on Quarkus', async () => {
    wrapper = mountWithPlatform('quarkus')
    await flushPromises()
    const text = wrapper.text()

    expect(text).toContain('Cached operations')
    expect(text).toContain('@CacheResult')
    expect(text).toContain('build-time')
    expect(text).not.toContain('Annotation operations')
    expect(text).not.toContain('@Cacheable')
  })
})

describe('SpringCache tiers and statistics', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('labels native statistics with their provenance and shows the hit ratio', async () => {
    wrapper = mountWithPlatform('spring-boot')
    await flushPromises()
    const text = wrapper.text()

    expect(text).toContain('Provider statistics · Caffeine')
    expect(text).toContain('hits 8')
    expect(text).toContain('misses 2')
    expect(text).toContain('ratio 80%')
    expect(text).toContain('since the cache was created')
    // A counter the provider does not expose must be omitted, never rendered as zero.
    expect(text).not.toContain('puts ')
  })

  it('keeps tiers collapsed until the disclosure button is used', async () => {
    wrapper = mountWithPlatform('spring-boot')
    await flushPromises()

    const toggle = wrapper.find('.cache-tier-toggle')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.text()).toContain('1 tier')
    // The row stays in the DOM so `aria-controls` always resolves, but it must start hidden.
    const controls = toggle.attributes('aria-controls')
    expect(wrapper.find(`#${controls}`).exists()).toBe(true)
    expect(wrapper.find(`#${controls}`).attributes('style')).toContain('display: none')

    await toggle.trigger('click')

    expect(wrapper.find('.cache-tier-toggle').attributes('aria-expanded')).toBe('true')
    expect(wrapper.find(`#${controls}`).attributes('style') || '').not.toContain('display: none')
    const text = wrapper.text()
    expect(text).toContain('expire after write 5m')
    expect(text).toContain('In this JVM')
    expect(text).toContain('500')
  })

  it('points the disclosure button at the tier row it controls', async () => {
    wrapper = mountWithPlatform('spring-boot')
    await flushPromises()

    const toggle = wrapper.find('.cache-tier-toggle')
    await toggle.trigger('click')

    const controls = wrapper.find('.cache-tier-toggle').attributes('aria-controls')
    expect(controls).toBeTruthy()
    expect(wrapper.find(`#${controls}`).exists()).toBe(true)
  })

  it('explains why a ratio is missing instead of showing zero percent', async () => {
    wrapper = mountWithPlatform(
      'spring-boot',
      reportWithCache({
        statistics: statistics({
          hits: 0,
          misses: 0,
          requests: 0,
          hitRatio: null,
          missRatio: null,
          ratioUnavailableReason: 'No cache requests have been recorded yet.'
        })
      })
    )
    await flushPromises()

    expect(wrapper.text()).toContain('ratio unknown')
    expect(wrapper.text()).not.toContain('ratio 0%')
    const badge = wrapper.findAll('.badge').find((node) => node.text() === 'ratio unknown')
    expect(badge.attributes('title')).toBe('No cache requests have been recorded yet.')
  })

  it('states why statistics are unavailable', async () => {
    wrapper = mountWithPlatform(
      'spring-boot',
      reportWithCache({
        statistics: unavailableStatistics('This Caffeine cache was not built with recordStats().'),
        tiers: [tier({statistics: unavailableStatistics('This Caffeine cache was not built with recordStats().')})]
      })
    )
    await flushPromises()

    expect(wrapper.text()).toContain('This Caffeine cache was not built with recordStats().')
    expect(wrapper.text()).not.toContain('hits 8')
  })

  it('marks a cache whose storage cannot be described as not described', async () => {
    wrapper = mountWithPlatform(
      'spring-boot',
      reportWithCache(
        {
          tiers: [],
          opaque: true,
          opaqueReason: 'BootUI cannot describe the storage of SealedCache through a public API.',
          statistics: unavailableStatistics('BootUI cannot describe the storage of SealedCache.')
        },
        {tierCount: 0}
      )
    )
    await flushPromises()

    expect(wrapper.find('.cache-tier-toggle').exists()).toBe(false)
    expect(wrapper.text()).toContain('Not described')
    const badge = wrapper.findAll('.badge').find((node) => node.text() === 'Not described')
    expect(badge.attributes('title')).toContain('SealedCache')
  })

  it('describes the cache manager composition and dynamic-cache state', async () => {
    const body = report()
    body.managers[0].composition = 'COMPOSITE'
    body.managers[0].dynamicCaches = 'NO'
    wrapper = mountWithPlatform('spring-boot', body)
    await flushPromises()

    expect(wrapper.text()).toContain('Composite')
    expect(wrapper.text()).toContain('Fixed cache set')
  })

  it('shows native and Micrometer counters as separate labelled series', async () => {
    wrapper = mountWithPlatform(
      'spring-boot',
      reportWithCache({
        metrics: {available: true, hits: 3, misses: 1, hitRatio: 0.75, puts: 4, evictions: 0, removals: 0, size: 2}
      })
    )
    await flushPromises()
    const text = wrapper.text()

    expect(text).toContain('Provider statistics · Caffeine')
    expect(text).toContain('Micrometer meters')
    expect(text).toContain('ratio 80%')
    expect(text).toContain('ratio 75%')
  })

  it('refuses to state a Micrometer ratio when nothing has been requested yet', async () => {
    // Micrometer reports a 0.0 hit ratio at zero requests. Repeating that as "0%" next to the native series'
    // honest "ratio unknown" would put two contradictory claims about the same cache side by side.
    const body = reportWithCache({
      statistics: unavailableStatistics('This Caffeine cache was not built with recordStats().'),
      metrics: {available: true, hits: 0, misses: 0, hitRatio: 0.0, puts: 0, evictions: 0, removals: 0, size: 0}
    })
    wrapper = mountWithPlatform('spring-boot', body)
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('Micrometer meters')
    expect(text).not.toContain('ratio 0%')
    expect(text).toContain('ratio unknown')
  })

  it('still states a Micrometer ratio once requests have been recorded', async () => {
    const body = reportWithCache({
      statistics: unavailableStatistics('This Caffeine cache was not built with recordStats().'),
      metrics: {available: true, hits: 3, misses: 1, hitRatio: 0.75, puts: 0, evictions: 0, removals: 0, size: 3}
    })
    wrapper = mountWithPlatform('spring-boot', body)
    await flushPromises()

    expect(wrapper.text()).toContain('ratio 75%')
  })

  it('tells a zero maximum size apart from one the provider never reported', async () => {
    const body = reportWithCache({
      tiers: [
        tier({id: 'no-op', name: 'No-op', maximumSize: 0, policyNote: 'A no-op cache holds no entries.'}),
        tier({id: 'weighted', name: 'Weighted', level: 1, maximumSize: null})
      ]
    })
    wrapper = mountWithPlatform('spring-boot', body)
    await flushPromises()
    await wrapper.find('.cache-tier-toggle').trigger('click')

    const rows = wrapper.findAll('.cache-tier-row tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('A no-op cache holds no entries.')
    expect(rows[0].text()).not.toContain('Not reported')
    expect(rows[1].text()).toContain('Not reported')
  })

  it('surfaces a truncation warning from the report', async () => {
    wrapper = mountWithPlatform(
      'spring-boot',
      report({truncated: true, warnings: ['Cache report was truncated to the first 100 of 240 cache managers.']})
    )
    await flushPromises()

    expect(wrapper.find('.alert-warning').text()).toContain('truncated')
  })
})
