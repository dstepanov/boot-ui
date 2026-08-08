import {describe, expect, it, vi} from 'vitest'
import {
  FLOW_STAGE_STAGGER_MS,
  MAX_CONCURRENT_PULSES,
  MAX_FLOW_STAGGER_STEPS,
  MAX_PULSES_PER_EDGE,
  PULSE_DURATION_FAILED_MS,
  PULSE_DURATION_OK_MS,
  PULSE_DURATION_SLOW_MS,
  SLOW_INTERACTION_MS,
  createFlowQueue,
  describeFlowSequence,
  describeNewEvidence,
  diffFlowPulses,
  filterServiceMap,
  layoutServiceMap,
  normalizeServiceMap,
  partitionNodes,
  pulseDurationMs,
  pulseTone,
  sequenceFlowPulses
} from './serviceMap.js'

function node(overrides = {}) {
  return {
    id: 'http:https://api.example.com',
    kind: 'DEPENDENCY',
    protocol: 'HTTP',
    label: 'https://api.example.com',
    detail: 'Outbound HTTP',
    configured: false,
    observed: true,
    interactions: 3,
    failures: 0,
    distinctOperations: 2,
    lastSeen: 1000,
    outcome: 'OBSERVED_OK',
    sourcePanelId: 'rest-client-trace',
    sourceRoute: '/rest-client-trace',
    sourceLabel: 'REST Client',
    note: null,
    ...overrides
  }
}

function edge(overrides = {}) {
  return {
    id: `app->${overrides.toId ?? 'http:https://api.example.com'}`,
    fromId: 'app',
    toId: 'http:https://api.example.com',
    protocol: 'HTTP',
    direction: 'OUTBOUND',
    interactions: 3,
    failures: 0,
    lastSeen: 1000,
    outcome: 'OBSERVED_OK',
    recentInteractions: [],
    ...overrides
  }
}

function interaction(id, overrides = {}) {
  return {id, timestamp: 1000, operation: 'GET', outcome: 'OK', durationMs: 12, flowId: null, ...overrides}
}

/** A pulse as `diffFlowPulses` would emit it, for tests that exercise sequencing/description directly. */
function flowPulse(overrides = {}) {
  return {
    id: overrides.id ?? 'app->http:https://api.example.com#http:1',
    edgeId: overrides.edgeId ?? 'app->http:https://api.example.com',
    direction: overrides.direction ?? 'OUTBOUND',
    fromId: overrides.fromId ?? 'app',
    toId: overrides.toId ?? 'http:https://api.example.com',
    protocol: overrides.protocol ?? 'HTTP',
    tone: overrides.tone ?? 'ok',
    durationMs: overrides.durationMs ?? PULSE_DURATION_OK_MS,
    interaction: overrides.interaction ?? interaction(overrides.interactionId ?? 'http:1', {flowId: overrides.flowId})
  }
}

function report(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    generatedAt: 5,
    application: {id: 'app', kind: 'APPLICATION', protocol: 'APPLICATION', label: 'This application'},
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

describe('normalizeServiceMap', () => {
  it('tolerates a missing or partial payload without throwing', () => {
    const map = normalizeServiceMap(null)

    expect(map.available).toBe(false)
    expect(map.nodes).toEqual([])
    expect(map.edges).toEqual([])
    expect(map.sources).toEqual([])
  })

  it('drops null entries so a malformed node cannot break the layout', () => {
    const map = normalizeServiceMap({available: true, nodes: [node(), null], edges: [null, edge()]})

    expect(map.nodes).toHaveLength(1)
    expect(map.edges).toHaveLength(1)
  })
})

describe('filterServiceMap', () => {
  const map = normalizeServiceMap(
    report({
      nodes: [
        node(),
        node({id: 'jdbc:pool:dataSource', protocol: 'JDBC', label: 'jdbc:postgresql://localhost:5432/shop'}),
        node({id: 'kafka:topic:orders', protocol: 'KAFKA', label: 'orders'})
      ],
      edges: [
        edge(),
        edge({id: 'app->jdbc:pool:dataSource', toId: 'jdbc:pool:dataSource'}),
        edge({id: 'app->kafka:topic:orders', toId: 'kafka:topic:orders'})
      ]
    })
  )

  it('filters by protocol and keeps only edges whose endpoints survive', () => {
    const filtered = filterServiceMap(map, {protocol: 'JDBC'})

    expect(filtered.nodes.map((entry) => entry.id)).toEqual(['jdbc:pool:dataSource'])
    expect(filtered.edges.map((entry) => entry.id)).toEqual(['app->jdbc:pool:dataSource'])
  })

  it('matches free text case-insensitively across label, detail, and protocol name', () => {
    expect(filterServiceMap(map, {text: 'POSTGRES'}).nodes.map((entry) => entry.id)).toEqual(['jdbc:pool:dataSource'])
    expect(filterServiceMap(map, {text: 'kafka'}).nodes.map((entry) => entry.id)).toEqual(['kafka:topic:orders'])
    expect(filterServiceMap(map, {text: '   '}).nodes).toHaveLength(3)
  })

  it('never filters the application away, so edges keep their anchor', () => {
    const filtered = filterServiceMap(map, {protocol: 'HTTP'})

    expect(filtered.application.id).toBe('app')
    expect(filtered.edges).toHaveLength(1)
  })
})

describe('layoutServiceMap', () => {
  it('centres the application, places the inbound lane left of it, and fans dependencies right', () => {
    const map = normalizeServiceMap(
      report({
        nodes: [
          node({id: 'inbound:http', kind: 'INBOUND', protocol: 'HTTP_INBOUND', label: 'Local HTTP clients'}),
          node(),
          node({id: 'jdbc:pool:dataSource', protocol: 'JDBC', label: 'db'})
        ],
        edges: [
          edge({id: 'inbound:http->app', fromId: 'inbound:http', toId: 'app', direction: 'INBOUND'}),
          edge(),
          edge({id: 'app->jdbc:pool:dataSource', toId: 'jdbc:pool:dataSource'})
        ]
      })
    )

    const layout = layoutServiceMap(map)

    expect(layout.application.x).toBeGreaterThan(layout.inbound.x)
    expect(layout.dependencies).toHaveLength(2)
    for (const box of layout.dependencies) {
      expect(box.x).toBeGreaterThan(layout.application.x)
    }
    expect(layout.width).toBeGreaterThan(0)
    expect(layout.height).toBeGreaterThan(0)
  })

  it('grows the fan radius with the dependency count so nodes never overlap', () => {
    function mapWith(count) {
      const nodes = Array.from({length: count}, (unused, index) =>
        node({id: `http:host-${index}`, label: `host-${index}`})
      )
      return normalizeServiceMap(
        report({nodes, edges: nodes.map((entry) => edge({id: `app->${entry.id}`, toId: entry.id}))})
      )
    }

    const few = layoutServiceMap(mapWith(3))
    const many = layoutServiceMap(mapWith(28))

    expect(many.height).toBeGreaterThan(few.height)
    const spacing = many.dependencies
      .map((box, index) =>
        index === 0
          ? Infinity
          : Math.hypot(box.x - many.dependencies[index - 1].x, box.y - many.dependencies[index - 1].y)
      )
      .slice(1)
    expect(Math.min(...spacing)).toBeGreaterThanOrEqual(46)
  })

  it('drops edges whose endpoints are not laid out', () => {
    const map = normalizeServiceMap(report({edges: [edge({id: 'app->missing', toId: 'missing'})]}))

    expect(layoutServiceMap(map).edges).toEqual([])
  })

  it('trims edge endpoints so a line starts and ends outside its node box', () => {
    const map = normalizeServiceMap(report())

    const [line] = layoutServiceMap(map).edges
    const target = layoutServiceMap(map).dependencies[0]

    expect(line.x1).toBeGreaterThan(layoutServiceMap(map).application.x)
    expect(line.x2).toBeLessThan(target.x)
  })
})

describe('partitionNodes', () => {
  it('separates the single inbound lane from the outbound dependencies', () => {
    const map = normalizeServiceMap(
      report({nodes: [node({id: 'inbound:http', kind: 'INBOUND', protocol: 'HTTP_INBOUND'}), node()]})
    )

    const {inbound, dependencies} = partitionNodes(map)

    expect(inbound.id).toBe('inbound:http')
    expect(dependencies.map((entry) => entry.id)).toEqual(['http:https://api.example.com'])
  })
})

describe('pulseTone', () => {
  it('marks failures red, slow interactions amber, and everything else normal', () => {
    expect(pulseTone(interaction('a', {outcome: 'FAILED'}))).toBe('failed')
    expect(pulseTone(interaction('b', {durationMs: SLOW_INTERACTION_MS}))).toBe('slow')
    expect(pulseTone(interaction('c', {durationMs: 5}))).toBe('ok')
    expect(pulseTone(interaction('d', {durationMs: null}))).toBe('ok')
  })
})

describe('pulseDurationMs', () => {
  it('gives slow the longest, most unmistakable duration, within the documented 1200-1500ms band', () => {
    expect(pulseDurationMs('slow')).toBe(PULSE_DURATION_SLOW_MS)
    expect(PULSE_DURATION_SLOW_MS).toBeGreaterThanOrEqual(1200)
    expect(PULSE_DURATION_SLOW_MS).toBeLessThanOrEqual(1500)
  })

  it('gives a normal completion the briskest duration, within the documented 650-850ms band', () => {
    expect(pulseDurationMs('ok')).toBe(PULSE_DURATION_OK_MS)
    expect(PULSE_DURATION_OK_MS).toBeGreaterThanOrEqual(650)
    expect(PULSE_DURATION_OK_MS).toBeLessThanOrEqual(850)
  })

  it('gives a failure a firm, in-between duration, within the documented 900-1100ms band', () => {
    expect(pulseDurationMs('failed')).toBe(PULSE_DURATION_FAILED_MS)
    expect(PULSE_DURATION_FAILED_MS).toBeGreaterThanOrEqual(900)
    expect(PULSE_DURATION_FAILED_MS).toBeLessThanOrEqual(1100)
  })

  it('keeps the three durations distinct so timing itself carries the "slow" meaning', () => {
    const durations = new Set([PULSE_DURATION_OK_MS, PULSE_DURATION_SLOW_MS, PULSE_DURATION_FAILED_MS])
    expect(durations.size).toBe(3)
  })
})

describe('diffFlowPulses', () => {
  const previous = [edge({recentInteractions: [interaction('http:1')]})]

  it('emits nothing on a first load, so the map never animates on arrival', () => {
    expect(diffFlowPulses(null, [edge({recentInteractions: [interaction('http:9')]})])).toEqual([])
    expect(diffFlowPulses([], [edge({recentInteractions: [interaction('http:9')]})])).toEqual([])
  })

  it('emits nothing when the same evidence is served again, so an idle app stays still', () => {
    expect(diffFlowPulses(previous, previous)).toEqual([])
  })

  it('emits a pulse only for interaction ids the previous snapshot did not carry', () => {
    const next = [edge({recentInteractions: [interaction('http:2'), interaction('http:1')]})]

    const pulses = diffFlowPulses(previous, next)

    expect(pulses).toHaveLength(1)
    expect(pulses[0].interaction.id).toBe('http:2')
    expect(pulses[0].edgeId).toBe(previous[0].id)
  })

  it('ignores edges that are not present in both snapshots, so a new dependency arrives without motion', () => {
    const next = [
      edge({id: 'app->kafka:topic:orders', toId: 'kafka:topic:orders', recentInteractions: [interaction('kafka:1')]})
    ]

    expect(diffFlowPulses(previous, next)).toEqual([])
  })

  it('coalesces a burst down to a small per-edge cap', () => {
    const next = [
      edge({
        recentInteractions: [interaction('http:5'), interaction('http:4'), interaction('http:3'), interaction('http:2')]
      })
    ]

    expect(diffFlowPulses(previous, next)).toHaveLength(MAX_PULSES_PER_EDGE)
  })

  it('carries the tone of each new interaction', () => {
    const next = [edge({recentInteractions: [interaction('http:2', {outcome: 'FAILED'}), interaction('http:1')]})]

    expect(diffFlowPulses(previous, next)[0].tone).toBe('failed')
  })
})

describe('sequenceFlowPulses', () => {
  it('never delays a pulse with no flowId, so unrelated snapshot items stay immediate', () => {
    const pulses = [flowPulse({flowId: null}), flowPulse({id: 'other', flowId: undefined})]

    const sequenced = sequenceFlowPulses(pulses)

    expect(sequenced.map((p) => p.startDelayMs)).toEqual([0, 0])
  })

  it('starts the inbound leg immediately and delays cache until it would have arrived at the app', () => {
    const inbound = flowPulse({
      id: 'inbound-pulse',
      edgeId: 'inbound:http->app',
      direction: 'INBOUND',
      protocol: 'HTTP_INBOUND',
      durationMs: 750,
      flowId: 'flow-1',
      interactionId: 'inbound:1'
    })
    const cache = flowPulse({
      id: 'cache-pulse',
      edgeId: 'app->cache:1',
      protocol: 'CACHE',
      flowId: 'flow-1',
      interactionId: 'cache:1'
    })

    const [sequencedInbound, sequencedCache] = sequenceFlowPulses([inbound, cache])

    expect(sequencedInbound.startDelayMs).toBe(0)
    expect(sequencedCache.startDelayMs).toBe(750)
  })

  it('sequences downstream completions by timestamp, with cache before JDBC/HTTP for an equal-time tie', () => {
    const inbound = flowPulse({
      id: 'inbound-pulse',
      edgeId: 'inbound:http->app',
      direction: 'INBOUND',
      protocol: 'HTTP_INBOUND',
      durationMs: 750,
      flowId: 'flow-1',
      interactionId: 'inbound:1'
    })
    const cache = flowPulse({
      id: 'cache-pulse',
      edgeId: 'app->cache:1',
      protocol: 'CACHE',
      flowId: 'flow-1',
      interaction: interaction('cache:1', {timestamp: 2000, flowId: 'flow-1'})
    })
    const sql = flowPulse({
      id: 'sql-pulse',
      edgeId: 'app->jdbc:statements',
      protocol: 'JDBC',
      flowId: 'flow-1',
      interaction: interaction('sql:1', {timestamp: 2000, flowId: 'flow-1'})
    })

    const [, sequencedCache, sequencedSql] = sequenceFlowPulses([inbound, cache, sql], {staggerMs: 90})

    expect(sequencedCache.startDelayMs).toBe(750)
    expect(sequencedSql.startDelayMs).toBe(750 + 90)
    expect(sequencedSql.startDelayMs).toBeGreaterThan(sequencedCache.startDelayMs)
  })

  it('does not invent cache-before-JDBC order when retained completion timestamps say otherwise', () => {
    const inbound = flowPulse({
      id: 'inbound-pulse',
      edgeId: 'inbound:http->app',
      direction: 'INBOUND',
      protocol: 'HTTP_INBOUND',
      durationMs: 750,
      flowId: 'flow-1',
      interaction: interaction('inbound:1', {timestamp: 1000, flowId: 'flow-1'})
    })
    const cache = flowPulse({
      id: 'cache-pulse',
      edgeId: 'app->cache:1',
      protocol: 'CACHE',
      flowId: 'flow-1',
      interaction: interaction('cache:1', {timestamp: 3000, flowId: 'flow-1'})
    })
    const sql = flowPulse({
      id: 'sql-pulse',
      edgeId: 'app->jdbc:statements',
      protocol: 'JDBC',
      flowId: 'flow-1',
      interaction: interaction('sql:1', {timestamp: 2000, flowId: 'flow-1'})
    })

    const sequenced = sequenceFlowPulses([inbound, cache, sql], {staggerMs: 90})

    expect(sequenced.find((pulse) => pulse.id === 'sql-pulse').startDelayMs).toBe(750)
    expect(sequenced.find((pulse) => pulse.id === 'cache-pulse').startDelayMs).toBe(750 + 90)
  })

  it('fires a downstream pulse immediately when this batch carries no retained inbound pulse', () => {
    const cache = flowPulse({id: 'cache-pulse', edgeId: 'app->cache:1', protocol: 'CACHE', flowId: 'flow-1'})
    const sql = flowPulse({id: 'sql-pulse', edgeId: 'app->jdbc:statements', protocol: 'JDBC', flowId: 'flow-1'})

    const sequenced = sequenceFlowPulses([cache, sql])

    expect(sequenced.map((p) => p.startDelayMs)).toEqual([0, 0])
  })

  it('leaves an ambiguous flow immediate when two inbound pulses share its flowId', () => {
    const inboundA = flowPulse({
      id: 'inbound-a',
      edgeId: 'inbound:http->app',
      direction: 'INBOUND',
      protocol: 'HTTP_INBOUND',
      durationMs: 750,
      flowId: 'flow-1',
      interactionId: 'inbound:1'
    })
    const inboundB = flowPulse({
      id: 'inbound-b',
      edgeId: 'inbound:http->app',
      direction: 'INBOUND',
      protocol: 'HTTP_INBOUND',
      durationMs: 1000,
      flowId: 'flow-1',
      interactionId: 'inbound:2'
    })
    const cache = flowPulse({
      id: 'cache-pulse',
      edgeId: 'app->cache:1',
      protocol: 'CACHE',
      flowId: 'flow-1',
      interactionId: 'cache:1'
    })

    const sequenced = sequenceFlowPulses([inboundA, inboundB, cache])

    expect(sequenced.map((pulse) => pulse.startDelayMs)).toEqual([0, 0, 0])
  })

  it('bounds the stagger so a burst of same-flow downstream pulses cannot grow the delay unboundedly', () => {
    const inbound = flowPulse({
      id: 'inbound-pulse',
      edgeId: 'inbound:http->app',
      direction: 'INBOUND',
      protocol: 'HTTP_INBOUND',
      durationMs: 100,
      flowId: 'flow-1',
      interactionId: 'inbound:1'
    })
    const downstream = Array.from({length: 8}, (unused, index) =>
      flowPulse({
        id: `downstream-${index}`,
        edgeId: `app->jdbc:${index}`,
        protocol: 'JDBC',
        flowId: 'flow-1',
        interactionId: `sql:${index}`
      })
    )

    const sequenced = sequenceFlowPulses([inbound, ...downstream], {staggerMs: 90, maxStaggerSteps: 3})
    const delays = sequenced.slice(1).map((p) => p.startDelayMs)

    expect(Math.max(...delays)).toBe(100 + 90 * 3)
  })

  it('never lets one flow influence another sharing the same batch', () => {
    const inboundA = flowPulse({
      id: 'inbound-a',
      edgeId: 'inbound:http->app',
      direction: 'INBOUND',
      protocol: 'HTTP_INBOUND',
      durationMs: 700,
      flowId: 'flow-a',
      interactionId: 'inbound:a'
    })
    const cacheA = flowPulse({id: 'cache-a', edgeId: 'app->cache:1', protocol: 'CACHE', flowId: 'flow-a'})
    const sqlB = flowPulse({id: 'sql-b', edgeId: 'app->jdbc:statements', protocol: 'JDBC', flowId: 'flow-b'})

    const sequenced = sequenceFlowPulses([inboundA, cacheA, sqlB])

    expect(sequenced.find((p) => p.id === 'cache-a').startDelayMs).toBe(700)
    // flow-b has no inbound pulse in this batch at all, so it is never delayed by flow-a's inbound leg.
    expect(sequenced.find((p) => p.id === 'sql-b').startDelayMs).toBe(0)
  })

  it('tolerates an empty or missing pulse list without throwing', () => {
    expect(sequenceFlowPulses([])).toEqual([])
    expect(sequenceFlowPulses(null)).toEqual([])
    expect(sequenceFlowPulses(undefined)).toEqual([])
  })
})

describe('describeFlowSequence', () => {
  it('narrates a complete causal chain in order for a qualifying flow', () => {
    const nodesById = new Map([
      [
        'inbound:http',
        node({id: 'inbound:http', kind: 'INBOUND', protocol: 'HTTP_INBOUND', label: 'Local HTTP clients'})
      ],
      ['cache:1', node({id: 'cache:1', protocol: 'CACHE', label: 'cacheManager / products'})],
      ['app', report().application]
    ])
    const inbound = flowPulse({
      id: 'inbound-pulse',
      edgeId: 'inbound:http->app',
      direction: 'INBOUND',
      fromId: 'inbound:http',
      toId: 'app',
      protocol: 'HTTP_INBOUND',
      flowId: 'flow-1',
      interaction: interaction('inbound:1', {operation: 'GET', durationMs: null, flowId: 'flow-1'})
    })
    const cache = flowPulse({
      id: 'cache-pulse',
      edgeId: 'app->cache:1',
      toId: 'cache:1',
      protocol: 'CACHE',
      flowId: 'flow-1',
      interaction: interaction('cache:1', {operation: 'MISS', durationMs: null, flowId: 'flow-1'})
    })

    const sentences = describeFlowSequence([inbound, cache], nodesById)

    expect(sentences).toHaveLength(1)
    expect(sentences[0]).toBe('Flow: Local HTTP clients GET → cacheManager / products MISS.')
  })

  it('names a slow step by text, never by color alone', () => {
    const nodesById = new Map([['cache:1', node({id: 'cache:1', protocol: 'CACHE', label: 'products'})]])
    const inbound = flowPulse({
      id: 'inbound-pulse',
      edgeId: 'inbound:http->app',
      direction: 'INBOUND',
      protocol: 'HTTP_INBOUND',
      flowId: 'flow-1',
      interaction: interaction('inbound:1', {operation: 'GET', flowId: 'flow-1'})
    })

    const slowCache = flowPulse({
      id: 'cache-pulse',
      edgeId: 'app->cache:1',
      toId: 'cache:1',
      protocol: 'CACHE',
      flowId: 'flow-1',
      interaction: interaction('cache:1', {operation: 'HIT', durationMs: SLOW_INTERACTION_MS, flowId: 'flow-1'})
    })

    const [sentence] = describeFlowSequence([inbound, slowCache], nodesById)

    expect(sentence).toContain('slow')
  })

  it('narrates downstream steps in retained completion order rather than protocol order', () => {
    const nodesById = new Map([
      [
        'inbound:http',
        node({id: 'inbound:http', kind: 'INBOUND', protocol: 'HTTP_INBOUND', label: 'Local HTTP clients'})
      ],
      ['cache:1', node({id: 'cache:1', protocol: 'CACHE', label: 'products'})],
      ['jdbc:1', node({id: 'jdbc:1', protocol: 'JDBC', label: 'orders database'})]
    ])
    const inbound = flowPulse({
      id: 'inbound-pulse',
      edgeId: 'inbound:http->app',
      direction: 'INBOUND',
      protocol: 'HTTP_INBOUND',
      fromId: 'inbound:http',
      flowId: 'flow-1',
      interaction: interaction('inbound:1', {timestamp: 1000, operation: 'GET', flowId: 'flow-1'})
    })
    const cache = flowPulse({
      id: 'cache-pulse',
      edgeId: 'app->cache:1',
      toId: 'cache:1',
      protocol: 'CACHE',
      flowId: 'flow-1',
      interaction: interaction('cache:1', {timestamp: 3000, operation: 'HIT', flowId: 'flow-1'})
    })
    const sql = flowPulse({
      id: 'sql-pulse',
      edgeId: 'app->jdbc:1',
      toId: 'jdbc:1',
      protocol: 'JDBC',
      flowId: 'flow-1',
      interaction: interaction('sql:1', {timestamp: 2000, operation: 'SELECT', flowId: 'flow-1'})
    })

    expect(describeFlowSequence([inbound, cache, sql], nodesById)).toEqual([
      'Flow: Local HTTP clients GET (12 ms) → orders database SELECT (12 ms) → products HIT (12 ms).'
    ])
  })

  it('names a failed step by text too', () => {
    const nodesById = new Map([
      ['http:down', node({id: 'http:down', protocol: 'HTTP', label: 'https://down.example.com'})]
    ])
    const inbound = flowPulse({
      id: 'inbound-pulse',
      edgeId: 'inbound:http->app',
      direction: 'INBOUND',
      protocol: 'HTTP_INBOUND',
      flowId: 'flow-1',
      interaction: interaction('inbound:1', {operation: 'GET', flowId: 'flow-1'})
    })
    const failedCall = flowPulse({
      id: 'http-pulse',
      edgeId: 'app->http:down',
      toId: 'http:down',
      protocol: 'HTTP',
      flowId: 'flow-1',
      interaction: interaction('http:1', {operation: 'GET', outcome: 'FAILED', flowId: 'flow-1'})
    })

    const [sentence] = describeFlowSequence([inbound, failedCall], nodesById)

    expect(sentence).toContain('failed')
  })

  it('says nothing for a lone pulse or one with no flowId, since that is not a flow to narrate', () => {
    expect(describeFlowSequence([flowPulse({flowId: 'solo-flow'})], new Map())).toEqual([])
    expect(describeFlowSequence([flowPulse({flowId: null})], new Map())).toEqual([])
    expect(describeFlowSequence([], new Map())).toEqual([])
  })
})

describe('describeNewEvidence', () => {
  it('summarizes new evidence per dependency, calling out failures', () => {
    const nodesById = new Map([['http:https://api.example.com', node()]])
    const pulses = diffFlowPulses(
      [edge({recentInteractions: [interaction('http:1')]})],
      [edge({recentInteractions: [interaction('http:2', {outcome: 'FAILED'}), interaction('http:1')]})]
    )

    expect(describeNewEvidence(pulses, nodesById)).toBe(
      'New completed interactions: 1 on https://api.example.com, including 1 failed.'
    )
  })

  it('names the external source of inbound evidence instead of the application', () => {
    const inbound = node({
      id: 'inbound:http',
      kind: 'INBOUND',
      protocol: 'HTTP_INBOUND',
      label: 'Local HTTP clients'
    })
    const inboundEdge = edge({
      id: 'inbound:http->app',
      fromId: 'inbound:http',
      toId: 'app',
      direction: 'INBOUND'
    })
    const pulses = diffFlowPulses(
      [{...inboundEdge, recentInteractions: [interaction('inbound:1')]}],
      [{...inboundEdge, recentInteractions: [interaction('inbound:2'), interaction('inbound:1')]}]
    )
    const nodesById = new Map([
      ['inbound:http', inbound],
      ['app', report().application]
    ])

    expect(describeNewEvidence(pulses, nodesById)).toBe('New completed interactions: 1 on Local HTTP clients.')
  })

  it('says nothing when there is nothing new', () => {
    expect(describeNewEvidence([], new Map())).toBe('')
  })

  it('calls out a slow interaction by name, not just by color', () => {
    const nodesById = new Map([['http:https://api.example.com', node()]])
    const pulses = diffFlowPulses(
      [edge({recentInteractions: [interaction('http:1')]})],
      [edge({recentInteractions: [interaction('http:2', {durationMs: SLOW_INTERACTION_MS}), interaction('http:1')]})]
    )

    expect(describeNewEvidence(pulses, nodesById)).toBe(
      'New completed interactions: 1 on https://api.example.com, including 1 slow.'
    )
  })

  it('prepends a complete flow narration ahead of the generic summary when pulses share a flowId', () => {
    const nodesById = new Map([
      [
        'inbound:http',
        node({id: 'inbound:http', kind: 'INBOUND', protocol: 'HTTP_INBOUND', label: 'Local HTTP clients'})
      ],
      ['cache:1', node({id: 'cache:1', protocol: 'CACHE', label: 'products'})],
      ['app', report().application]
    ])
    const inboundEdge = edge({
      id: 'inbound:http->app',
      fromId: 'inbound:http',
      toId: 'app',
      protocol: 'HTTP_INBOUND',
      direction: 'INBOUND'
    })
    const cacheEdge = edge({id: 'app->cache:1', toId: 'cache:1', protocol: 'CACHE'})
    const pulses = diffFlowPulses(
      [
        {...inboundEdge, recentInteractions: []},
        {...cacheEdge, recentInteractions: []}
      ],
      [
        {
          ...inboundEdge,
          recentInteractions: [interaction('inbound:1', {operation: 'GET', durationMs: null, flowId: 'flow-1'})]
        },
        {
          ...cacheEdge,
          recentInteractions: [interaction('cache:1', {operation: 'HIT', durationMs: null, flowId: 'flow-1'})]
        }
      ]
    )

    const message = describeNewEvidence(pulses, nodesById)

    expect(message.startsWith('Flow: Local HTTP clients GET \u2192 products HIT.')).toBe(true)
    expect(message).toContain('New completed interactions:')
  })
})

describe('createFlowQueue', () => {
  function pulse(id) {
    return {id, edgeId: 'app->x', tone: 'ok', interaction: interaction(id)}
  }

  it('accepts pulses up to the concurrency cap and drops the rest instead of queueing them', () => {
    const queue = createFlowQueue({schedule: () => 1, cancel: () => {}})

    const accepted = queue.enqueue(Array.from({length: MAX_CONCURRENT_PULSES + 4}, (unused, i) => pulse(`p${i}`)))

    expect(accepted).toHaveLength(MAX_CONCURRENT_PULSES)
    expect(queue.active()).toHaveLength(MAX_CONCURRENT_PULSES)

    expect(queue.enqueue([pulse('overflow')])).toEqual([])
  })

  it('never admits the same pulse twice', () => {
    const queue = createFlowQueue({schedule: () => 1, cancel: () => {}})

    queue.enqueue([pulse('a')])
    expect(queue.enqueue([pulse('a')])).toEqual([])
    expect(queue.active()).toHaveLength(1)
  })

  it('releases each pulse after its duration so motion stops when traffic stops', () => {
    vi.useFakeTimers()
    try {
      const queue = createFlowQueue({duration: 100})
      queue.enqueue([pulse('a')])
      expect(queue.active()).toHaveLength(1)

      vi.advanceTimersByTime(100)

      expect(queue.active()).toHaveLength(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('notifies subscribers on admission and release', () => {
    vi.useFakeTimers()
    try {
      const queue = createFlowQueue({duration: 50})
      const seen = []
      queue.subscribe((active) => seen.push(active.length))

      queue.enqueue([pulse('a')])
      vi.advanceTimersByTime(50)

      expect(seen).toEqual([1, 0])
    } finally {
      vi.useRealTimers()
    }
  })

  it('clears every pending timer so an unmounted panel leaves nothing running', () => {
    const cancelled = []
    const queue = createFlowQueue({schedule: () => 'handle', cancel: (handle) => cancelled.push(handle)})
    queue.enqueue([pulse('a')])

    queue.clear()

    expect(cancelled).toEqual(['handle'])
    expect(queue.active()).toEqual([])
  })

  it('keeps subscribers after a clear, so turning reduced motion off restores motion', () => {
    const queue = createFlowQueue({schedule: () => 'handle', cancel: () => {}})
    const seen = []
    queue.subscribe((active) => seen.push(active.map((entry) => entry.id)))

    queue.enqueue([pulse('a')])
    queue.clear()
    queue.enqueue([pulse('b')])

    expect(seen).toEqual([['a'], [], ['b']])
  })

  it('reserves capacity for a delayed pulse immediately, so the concurrency cap stays honest and current', () => {
    const queue = createFlowQueue({maxConcurrent: 2, schedule: () => 'handle', cancel: () => {}})

    const accepted = queue.enqueue([{...pulse('a'), startDelayMs: 700}, pulse('b'), pulse('c')])

    expect(accepted).toHaveLength(2)
    expect(queue.active()).toHaveLength(2)
  })

  it('releases a sequenced pulse only after its own startDelayMs plus its duration have both elapsed', () => {
    vi.useFakeTimers()
    try {
      const queue = createFlowQueue({duration: 100})
      queue.enqueue([{...pulse('delayed'), startDelayMs: 700, durationMs: 100}])

      vi.advanceTimersByTime(700)
      expect(queue.active()).toHaveLength(1)

      vi.advanceTimersByTime(99)
      expect(queue.active()).toHaveLength(1)

      vi.advanceTimersByTime(1)
      expect(queue.active()).toHaveLength(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it("honors each pulse's own tone-specific durationMs over the queue default", () => {
    vi.useFakeTimers()
    try {
      const queue = createFlowQueue({duration: 750})
      queue.enqueue([{...pulse('slow'), durationMs: PULSE_DURATION_SLOW_MS}])

      vi.advanceTimersByTime(750)
      expect(queue.active()).toHaveLength(1)

      vi.advanceTimersByTime(PULSE_DURATION_SLOW_MS - 750)
      expect(queue.active()).toHaveLength(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('never lets a zero/undefined startDelayMs change the immediate-admission behavior', () => {
    vi.useFakeTimers()
    try {
      const queue = createFlowQueue({duration: 50})
      queue.enqueue([{...pulse('a'), startDelayMs: 0}])
      queue.enqueue([pulse('b')])

      expect(queue.active()).toHaveLength(2)
      vi.advanceTimersByTime(50)
      expect(queue.active()).toHaveLength(0)
    } finally {
      vi.useRealTimers()
    }
  })
})
