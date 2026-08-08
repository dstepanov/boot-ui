import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import LiveFlowMode from './LiveFlowMode.vue'
import {MAX_CONCURRENT_PULSES, PULSE_DURATION_OK_MS} from '../utils/serviceMap.js'

function node(overrides = {}) {
  return {
    id: 'http:https://api.example.com',
    kind: 'DEPENDENCY',
    protocol: 'HTTP',
    label: 'https://api.example.com',
    detail: 'Outbound HTTP',
    configured: false,
    observed: true,
    interactions: 4,
    failures: 0,
    distinctOperations: 2,
    lastSeen: 1700000000000,
    outcome: 'OBSERVED_OK',
    sourcePanelId: 'rest-client-trace',
    sourceRoute: '/rest-client-trace',
    sourceLabel: 'REST Client',
    note: 'Grouped by origin only. Request paths and query values are never mapped.',
    ...overrides
  }
}

function edge(overrides = {}) {
  return {
    id: 'app->http:https://api.example.com',
    fromId: 'app',
    toId: 'http:https://api.example.com',
    protocol: 'HTTP',
    direction: 'OUTBOUND',
    interactions: 4,
    failures: 0,
    lastSeen: 1700000000000,
    outcome: 'OBSERVED_OK',
    recentInteractions: [{id: 'http:4', timestamp: 1700000000000, operation: 'GET', outcome: 'OK', durationMs: 18}],
    ...overrides
  }
}

function serviceMap(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    generatedAt: 1700000000000,
    application: {
      id: 'app',
      kind: 'APPLICATION',
      protocol: 'APPLICATION',
      label: 'This application',
      configured: true,
      observed: true,
      interactions: 0,
      failures: 0,
      outcome: 'OBSERVED_OK'
    },
    nodes: [node()],
    edges: [edge()],
    truncation: {
      truncated: false,
      dependencyLimit: 28,
      dependenciesShown: 1,
      dependenciesOmitted: 0,
      interactionLimit: 6
    },
    sources: ['REST Client'],
    warnings: [],
    ...overrides
  }
}

function stubFetch(...responses) {
  const queue = [...responses]
  return vi.fn(() => {
    const body = queue.length > 1 ? queue.shift() : queue[0]
    return Promise.resolve({ok: true, status: 200, json: () => Promise.resolve(body)})
  })
}

function deferred() {
  let resolve
  const promise = new Promise((done) => {
    resolve = done
  })
  return {promise, resolve}
}

/** Returns a handle that can flip the OS reduced-motion preference mid-session, like a real browser. */
function stubMatchMedia(matches) {
  const listeners = new Set()
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches,
      addEventListener: (unusedType, listener) => listeners.add(listener),
      removeEventListener: (unusedType, listener) => listeners.delete(listener)
    }))
  )
  return {
    change(next) {
      for (const listener of listeners) listener({matches: next})
    }
  }
}

function mountFlow(options = {}) {
  return mount(LiveFlowMode, {
    global: {stubs: {RouterLink: {template: '<a><slot /></a>'}}},
    ...options
  })
}

describe('LiveFlowMode', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('reads the service map once on mount and performs no other request', async () => {
    const fetchStub = stubFetch(serviceMap())
    vi.stubGlobal('fetch', fetchStub)
    stubMatchMedia(false)

    wrapper = mountFlow()
    await flushPromises()

    expect(fetchStub).toHaveBeenCalledTimes(1)
    expect(fetchStub.mock.calls[0][0]).toContain('api/activity/service-map')
  })

  it('coalesces refresh ticks during a slow fetch into one follow-up request', async () => {
    const first = deferred()
    const second = deferred()
    const fetchStub = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    vi.stubGlobal('fetch', fetchStub)
    stubMatchMedia(false)

    wrapper = mountFlow({props: {refreshTick: 0}})
    await wrapper.setProps({refreshTick: 1})
    await wrapper.setProps({refreshTick: 2})
    await wrapper.setProps({refreshTick: 3})

    expect(fetchStub).toHaveBeenCalledTimes(1)

    first.resolve({
      ok: true,
      status: 200,
      json: () => Promise.resolve(serviceMap({nodes: [node({label: 'first.example.com'})]}))
    })
    await flushPromises()

    expect(fetchStub).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('first.example.com')

    second.resolve({
      ok: true,
      status: 200,
      json: () => Promise.resolve(serviceMap({nodes: [node({label: 'latest.example.com'})]}))
    })
    await flushPromises()

    expect(fetchStub).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('latest.example.com')
    expect(wrapper.text()).not.toContain('first.example.com')
  })

  it('renders the centred application, the dependency, and a relationship edge', async () => {
    vi.stubGlobal('fetch', stubFetch(serviceMap()))
    stubMatchMedia(false)

    wrapper = mountFlow()
    await flushPromises()

    expect(wrapper.text()).toContain('This application')
    expect(wrapper.find('.flow-node--http').attributes('aria-label')).toContain('https://api.example.com')
    expect(wrapper.findAll('.flow-edge')).toHaveLength(1)
  })

  it('distinguishes a configured dependency with no evidence from an observed one', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(
        serviceMap({
          nodes: [
            node({
              id: 'jdbc:pool:dataSource',
              protocol: 'JDBC',
              label: 'jdbc:postgresql://localhost:5432/shop',
              configured: true,
              observed: false,
              interactions: 0,
              lastSeen: null,
              outcome: 'NO_EVIDENCE'
            })
          ],
          edges: [
            edge({
              id: 'app->jdbc:pool:dataSource',
              toId: 'jdbc:pool:dataSource',
              protocol: 'JDBC',
              interactions: 0,
              lastSeen: null,
              outcome: 'NO_EVIDENCE',
              recentInteractions: []
            })
          ]
        })
      )
    )
    stubMatchMedia(false)

    wrapper = mountFlow()
    await flushPromises()

    const dependency = wrapper.find('.flow-node--jdbc')
    expect(dependency.classes()).toContain('flow-node--unobserved')
    expect(dependency.attributes('aria-label')).toContain('configured, no recent evidence')
    expect(wrapper.find('.flow-edge--no_evidence').exists()).toBe(true)
  })

  it('selects a node by keyboard and shows its evidence detail with a source deep link', async () => {
    vi.stubGlobal('fetch', stubFetch(serviceMap()))
    stubMatchMedia(false)

    wrapper = mountFlow()
    await flushPromises()

    await wrapper.find('.flow-node--http').trigger('keydown.enter')

    expect(wrapper.find('.flow-detail').text()).toContain('Retained interactions')
    expect(wrapper.find('.flow-detail').text()).toContain('Request paths and query values are never mapped')
    expect(wrapper.find('.flow-detail__link').text()).toContain('REST Client')
    expect(wrapper.find('.flow-node--http').classes()).toContain('flow-node--selected')
  })

  it('always exposes the map as a hidden textual list for assistive technology', async () => {
    vi.stubGlobal('fetch', stubFetch(serviceMap()))
    stubMatchMedia(false)

    wrapper = mountFlow()
    await flushPromises()

    const textual = wrapper.find('ul[aria-label="Service map relationships as text"]')
    expect(textual.exists()).toBe(true)
    expect(textual.text()).toContain('https://api.example.com')
    expect(textual.text()).toContain('This application calls')
  })

  it('names the external source of an inbound relationship in the hidden textual list', async () => {
    const inbound = node({
      id: 'inbound:http',
      kind: 'INBOUND',
      protocol: 'HTTP_INBOUND',
      label: 'Local HTTP clients'
    })
    vi.stubGlobal(
      'fetch',
      stubFetch(
        serviceMap({
          nodes: [inbound],
          edges: [
            edge({
              id: 'inbound:http->app',
              fromId: 'inbound:http',
              toId: 'app',
              direction: 'INBOUND',
              protocol: 'HTTP_INBOUND'
            })
          ]
        })
      )
    )
    stubMatchMedia(false)

    wrapper = mountFlow()
    await flushPromises()

    const textual = wrapper.find('ul[aria-label="Service map relationships as text"]')
    expect(textual.text()).toContain('Incoming into this application Local HTTP clients')
    expect(textual.text()).not.toContain('Incoming into this application This application')
  })

  it('filters by protocol and reports when nothing matches', async () => {
    vi.stubGlobal('fetch', stubFetch(serviceMap()))
    stubMatchMedia(false)

    wrapper = mountFlow()
    await flushPromises()

    await wrapper.find('#flow-protocol').setValue('KAFKA')

    expect(wrapper.findAll('.flow-node--http')).toHaveLength(0)
    expect(wrapper.text()).toContain('No mapped dependency matches these filters')
  })

  it('renders a cache dependency, filters to it via the protocol dropdown, and shows its evidence', async () => {
    const cacheNode = node({
      id: 'cache:1',
      protocol: 'CACHE',
      label: 'cacheManager / products',
      detail: 'Cache access',
      sourcePanelId: 'cache',
      sourceRoute: '/cache',
      sourceLabel: 'Cache',
      note: 'Observed cache access only. Keys and values are never mapped, only the coarse operation.'
    })
    const cacheEdge = edge({
      id: 'app->cache:1',
      toId: 'cache:1',
      protocol: 'CACHE',
      recentInteractions: [{id: 'cache:1', timestamp: 1700000000000, operation: 'HIT', outcome: 'OK', durationMs: null}]
    })
    vi.stubGlobal('fetch', stubFetch(serviceMap({nodes: [node(), cacheNode], edges: [edge(), cacheEdge]})))
    stubMatchMedia(false)

    wrapper = mountFlow()
    await flushPromises()

    expect(wrapper.find('.flow-node--cache').exists()).toBe(true)

    await wrapper.find('#flow-protocol').setValue('CACHE')
    expect(wrapper.findAll('.flow-node--http')).toHaveLength(0)
    expect(wrapper.findAll('.flow-node--cache')).toHaveLength(1)

    await wrapper.find('.flow-node--cache').trigger('click')
    expect(wrapper.find('.flow-detail').text()).toContain('cacheManager / products')
    expect(wrapper.find('.flow-detail').text()).toContain('Keys and values are never mapped')
    expect(wrapper.find('.flow-detail__link').text()).toContain('Cache')
  })

  it('never animates on a first load', async () => {
    vi.stubGlobal('fetch', stubFetch(serviceMap()))
    stubMatchMedia(false)

    wrapper = mountFlow()
    await flushPromises()

    expect(wrapper.findAll('.flow-pulse')).toHaveLength(0)
  })

  it('animates only genuinely new completed interactions after a refresh', async () => {
    const next = serviceMap({
      edges: [
        edge({
          recentInteractions: [
            {id: 'http:5', timestamp: 1700000001000, operation: 'GET', outcome: 'FAILED', durationMs: 900},
            {id: 'http:4', timestamp: 1700000000000, operation: 'GET', outcome: 'OK', durationMs: 18}
          ]
        })
      ]
    })
    vi.stubGlobal('fetch', stubFetch(serviceMap(), next))
    stubMatchMedia(false)

    wrapper = mountFlow({props: {refreshTick: 0}})
    await flushPromises()
    await wrapper.setProps({refreshTick: 1})
    await flushPromises()

    const pulses = wrapper.findAll('.flow-pulse')
    expect(pulses).toHaveLength(1)
    expect(pulses[0].classes()).toContain('flow-pulse--failed')
    expect(wrapper.find('[aria-live="polite"]').text()).toContain('New completed interactions')
  })

  it('replaces motion with a brief static edge highlight when reduced motion is preferred', async () => {
    vi.useFakeTimers()
    const next = serviceMap({
      edges: [
        edge({
          recentInteractions: [
            {id: 'http:5', timestamp: 1700000001000, operation: 'GET', outcome: 'OK', durationMs: 12}
          ]
        })
      ]
    })
    vi.stubGlobal('fetch', stubFetch(serviceMap(), next))
    stubMatchMedia(true)

    wrapper = mountFlow({props: {refreshTick: 0}})
    await vi.runAllTimersAsync()
    await wrapper.setProps({refreshTick: 1})
    await vi.runAllTimersAsync()
    await wrapper.vm.$nextTick()

    expect(wrapper.findAll('.flow-pulse')).toHaveLength(0)
    expect(wrapper.find('[aria-live="polite"]').text()).toContain('New completed interactions')
  })

  it('restores motion after the reduced-motion preference is turned on and back off', async () => {
    const withNewInteraction = (id, timestamp) =>
      serviceMap({
        edges: [edge({recentInteractions: [{id, timestamp, operation: 'GET', outcome: 'OK', durationMs: 12}]})]
      })
    vi.stubGlobal(
      'fetch',
      stubFetch(serviceMap(), withNewInteraction('http:5', 1700000001000), withNewInteraction('http:6', 1700000002000))
    )
    const motion = stubMatchMedia(false)

    wrapper = mountFlow({props: {refreshTick: 0}})
    await flushPromises()

    // Reduced motion on: the refresh is announced but nothing animates.
    motion.change(true)
    await wrapper.setProps({refreshTick: 1})
    await flushPromises()
    expect(wrapper.findAll('.flow-pulse')).toHaveLength(0)

    // Reduced motion off again: motion must come back rather than stay silently dead.
    motion.change(false)
    await wrapper.setProps({refreshTick: 2})
    await flushPromises()
    expect(wrapper.findAll('.flow-pulse')).toHaveLength(1)
  })

  /** Two edges - a retained inbound lane and a cache dependency - sharing the flow's evidence trail. */
  function sequencedFlowMap(recentInboundInteractions, recentCacheInteractions) {
    const inboundNode = node({
      id: 'inbound:http',
      kind: 'INBOUND',
      protocol: 'HTTP_INBOUND',
      label: 'Local HTTP clients'
    })
    const cacheNode = node({id: 'cache:1', protocol: 'CACHE', label: 'cacheManager / products'})
    const inboundEdge = edge({
      id: 'inbound:http->app',
      fromId: 'inbound:http',
      toId: 'app',
      protocol: 'HTTP_INBOUND',
      direction: 'INBOUND',
      recentInteractions: recentInboundInteractions
    })
    const cacheEdge = edge({
      id: 'app->cache:1',
      toId: 'cache:1',
      protocol: 'CACHE',
      recentInteractions: recentCacheInteractions
    })
    return serviceMap({nodes: [inboundNode, cacheNode], edges: [inboundEdge, cacheEdge]})
  }

  it('sequences a shared flow so the downstream cache pulse starts only after the inbound pulse would arrive', async () => {
    const initial = sequencedFlowMap([], [])
    const next = sequencedFlowMap(
      [{id: 'inbound:1', timestamp: 1700000001000, operation: 'GET', outcome: 'OK', durationMs: 12, flowId: 'flow-1'}],
      [{id: 'cache:1', timestamp: 1700000001000, operation: 'HIT', outcome: 'OK', durationMs: null, flowId: 'flow-1'}]
    )
    vi.stubGlobal('fetch', stubFetch(initial, next))
    stubMatchMedia(false)

    wrapper = mountFlow({props: {refreshTick: 0}})
    await flushPromises()
    await wrapper.setProps({refreshTick: 1})
    await flushPromises()

    const pulses = wrapper.findAll('.flow-pulse')
    expect(pulses).toHaveLength(2)
    // Inbound is the flow's first stage and always starts immediately; cache only starts once the
    // inbound pulse would have finished arriving at the application - its own travel duration later.
    // A delayed pulse's CSS keyframe keeps it fully transparent for its `--flow-delay`, so it never
    // flashes into view before its causal predecessor has actually arrived.
    expect(pulses[0].attributes('style')).toContain('--flow-delay: 0ms')
    expect(pulses[1].attributes('style')).toContain(`--flow-delay: ${PULSE_DURATION_OK_MS}ms`)
  })

  it('never sequences (never delays) a downstream pulse when its batch carries no inbound pulse', async () => {
    const initial = sequencedFlowMap(
      [{id: 'inbound:1', timestamp: 1700000000000, operation: 'GET', outcome: 'OK', durationMs: 12, flowId: 'flow-1'}],
      []
    )
    const next = sequencedFlowMap(
      // Same inbound interaction id already seen: nothing new on that edge this tick.
      [{id: 'inbound:1', timestamp: 1700000000000, operation: 'GET', outcome: 'OK', durationMs: 12, flowId: 'flow-1'}],
      [{id: 'cache:1', timestamp: 1700000001000, operation: 'HIT', outcome: 'OK', durationMs: null, flowId: 'flow-1'}]
    )
    vi.stubGlobal('fetch', stubFetch(initial, next))
    stubMatchMedia(false)

    wrapper = mountFlow({props: {refreshTick: 0}})
    await flushPromises()
    await wrapper.setProps({refreshTick: 1})
    await flushPromises()

    const pulses = wrapper.findAll('.flow-pulse')
    expect(pulses).toHaveLength(1)
    expect(pulses[0].attributes('style')).toContain('--flow-delay: 0ms')
  })

  it('narrates the complete causal flow in the polite live region even under reduced motion', async () => {
    const initial = sequencedFlowMap([], [])
    const next = sequencedFlowMap(
      [
        {id: 'inbound:1', timestamp: 1700000001000, operation: 'GET', outcome: 'OK', durationMs: null, flowId: 'flow-1'}
      ],
      [{id: 'cache:1', timestamp: 1700000001000, operation: 'HIT', outcome: 'OK', durationMs: null, flowId: 'flow-1'}]
    )
    vi.stubGlobal('fetch', stubFetch(initial, next))
    stubMatchMedia(true)

    wrapper = mountFlow({props: {refreshTick: 0}})
    await flushPromises()
    await wrapper.setProps({refreshTick: 1})
    await flushPromises()

    // Reduced motion never sequences/delays - the map only replaces travel with a static edge highlight -
    // but the announcement still tells the complete causal story in one sentence.
    expect(wrapper.findAll('.flow-pulse')).toHaveLength(0)
    const message = wrapper.find('[aria-live="polite"]').text()
    expect(message).toContain('Flow: Local HTTP clients GET')
    expect(message).toContain('cacheManager / products HIT')
  })

  it('keeps the animation queue bounded even when a fan-out burst shares one causally-sequenced flow', async () => {
    // Each downstream dependency is its own edge (the per-edge fresh-pulse cap would otherwise mask the
    // queue's own concurrency bound), all correlated to the same inbound leg by one shared flowId.
    const downstreamCount = MAX_CONCURRENT_PULSES + 4
    const inboundNode = node({
      id: 'inbound:http',
      kind: 'INBOUND',
      protocol: 'HTTP_INBOUND',
      label: 'Local HTTP clients'
    })
    const inboundEdge = (recentInteractions) =>
      edge({
        id: 'inbound:http->app',
        fromId: 'inbound:http',
        toId: 'app',
        protocol: 'HTTP_INBOUND',
        direction: 'INBOUND',
        recentInteractions
      })
    const downstreamNodes = Array.from({length: downstreamCount}, (unused, index) =>
      node({id: `jdbc:${index}`, protocol: 'JDBC', label: `pool-${index}`})
    )
    const downstreamEdges = (fresh) =>
      Array.from({length: downstreamCount}, (unused, index) =>
        edge({
          id: `app->jdbc:${index}`,
          toId: `jdbc:${index}`,
          protocol: 'JDBC',
          recentInteractions: fresh
            ? [
                {
                  id: `sql:${index}`,
                  timestamp: 1700000001000 + index,
                  operation: 'SELECT',
                  outcome: 'OK',
                  durationMs: 4,
                  flowId: 'flow-1'
                }
              ]
            : []
        })
      )
    const initial = serviceMap({
      nodes: [inboundNode, ...downstreamNodes],
      edges: [inboundEdge([]), ...downstreamEdges(false)]
    })
    const next = serviceMap({
      nodes: [inboundNode, ...downstreamNodes],
      edges: [
        inboundEdge([
          {id: 'inbound:1', timestamp: 1700000001000, operation: 'GET', outcome: 'OK', durationMs: 12, flowId: 'flow-1'}
        ]),
        ...downstreamEdges(true)
      ]
    })
    vi.stubGlobal('fetch', stubFetch(initial, next))
    stubMatchMedia(false)

    wrapper = mountFlow({props: {refreshTick: 0}})
    await flushPromises()
    await wrapper.setProps({refreshTick: 1})
    await flushPromises()

    expect(wrapper.findAll('.flow-pulse').length).toBeLessThanOrEqual(MAX_CONCURRENT_PULSES)
  })

  it('reports truncation visibly instead of silently dropping dependencies', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(
        serviceMap({
          truncation: {
            truncated: true,
            dependencyLimit: 28,
            dependenciesShown: 28,
            dependenciesOmitted: 4,
            interactionLimit: 6
          },
          warnings: ['4 less recently used dependencies are not shown because the map is capped at 28 dependencies.']
        })
      )
    )
    stubMatchMedia(false)

    wrapper = mountFlow()
    await flushPromises()

    expect(wrapper.text()).toContain('Showing 28 of 32 dependencies')
    expect(wrapper.text()).toContain('not shown because the map is capped')
  })

  it('states honestly that opening the map contacts nothing', async () => {
    vi.stubGlobal('fetch', stubFetch(serviceMap()))
    stubMatchMedia(false)

    wrapper = mountFlow()
    await flushPromises()

    expect(wrapper.text()).toContain('contacts nothing and probes nothing')
    expect(wrapper.text()).toContain('not a health check of the remote system')
  })

  it('keeps a node reachable by keyboard even when the selected one is filtered away', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch(
        serviceMap({
          nodes: [node(), node({id: 'kafka:topic:orders', protocol: 'KAFKA', label: 'orders'})],
          edges: [edge(), edge({id: 'app->kafka:topic:orders', toId: 'kafka:topic:orders', protocol: 'KAFKA'})]
        })
      )
    )
    stubMatchMedia(false)

    wrapper = mountFlow()
    await flushPromises()

    await wrapper.find('.flow-node--http').trigger('click')
    await wrapper.find('#flow-protocol').setValue('KAFKA')

    const focusable = wrapper.findAll('.flow-node[role="button"]').filter((n) => n.attributes('tabindex') === '0')
    expect(focusable).toHaveLength(1)
    expect(focusable[0].classes()).toContain('flow-node--kafka')
  })

  it('renders the unavailable reason rather than an empty map when no source contributes', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch({
        available: false,
        unavailableReason: 'No service map source is available.',
        nodes: [],
        edges: [],
        sources: [],
        warnings: []
      })
    )
    stubMatchMedia(false)

    wrapper = mountFlow()
    await flushPromises()

    expect(wrapper.text()).toContain('No service map source is available.')
    expect(wrapper.find('.flow-svg').exists()).toBe(false)
  })
})
