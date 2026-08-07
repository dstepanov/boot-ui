import {describe, expect, it, vi} from 'vitest'
import {
  MAX_CONCURRENT_PULSES,
  MAX_PULSES_PER_EDGE,
  SLOW_INTERACTION_MS,
  createFlowQueue,
  describeNewEvidence,
  diffFlowPulses,
  filterServiceMap,
  layoutServiceMap,
  normalizeServiceMap,
  partitionNodes,
  pulseTone
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
  return {id, timestamp: 1000, operation: 'GET', outcome: 'OK', durationMs: 12, ...overrides}
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
})
