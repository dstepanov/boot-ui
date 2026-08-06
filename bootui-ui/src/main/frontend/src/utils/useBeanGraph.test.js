import {describe, expect, it, vi} from 'vitest'
import {
  buildGraphIndex,
  conditionClassFromResource,
  GRAPH_LOAD_PAGE_SIZE,
  mainApplicationBeanName,
  matchingPositiveConditions,
  MAX_GRAPH_DEPTH,
  MAX_GRAPH_NODES,
  traverseNeighborhood,
  useBeanGraph
} from '../utils/useBeanGraph.js'

// ── helpers ───────────────────────────────────────────────────────────────────

function bean(name, deps = [], type = `com.example.${name}`) {
  return {name, type, scope: 'singleton', classification: 'APPLICATION', dependencies: deps}
}

function graphIndex(beans) {
  return buildGraphIndex(beans)
}

function traverse(focusName, beans, overrides = {}) {
  const {byName, reverseIndex} = graphIndex(beans)
  return traverseNeighborhood(
    focusName,
    byName,
    reverseIndex,
    overrides.maxNodes ?? MAX_GRAPH_NODES,
    overrides.maxDepth ?? MAX_GRAPH_DEPTH,
    overrides.includeName
  )
}

function nodeNames(nodes) {
  return nodes.map((n) => n.name).sort()
}

function edgePairs(edges) {
  return edges.map((e) => `${e.from}=>${e.to}`).sort()
}

describe('default graph focus', () => {
  it('selects the conventional application entry-point bean', () => {
    expect(
      mainApplicationBeanName([
        bean('orderService'),
        bean('demoApplication', [], 'com.example.DemoApplication'),
        bean('aCustomEntryPoint', [], 'com.example.OtherApplication')
      ])
    ).toBe('demoApplication')
  })

  it('recognizes an enhanced application type and ignores non-application classifications', () => {
    expect(
      mainApplicationBeanName([
        {...bean('frameworkApplication', [], 'org.example.FrameworkApplication'), classification: 'FRAMEWORK'},
        bean('sampleApplication', [], 'com.example.SampleApplication$$SpringCGLIB$$0')
      ])
    ).toBe('sampleApplication')
  })

  it('leaves the graph unfocused when no conventional application bean is present', () => {
    expect(mainApplicationBeanName([bean('orderService')])).toBeNull()
  })
})

describe('condition evidence helpers', () => {
  it('extracts an exact class name from a Spring classpath resource', () => {
    expect(conditionClassFromResource('class path resource [com/example/orders/OrderAutoConfiguration.class]')).toBe(
      'com.example.orders.OrderAutoConfiguration'
    )
  })

  it('rejects resource formats that cannot establish an exact configuration class', () => {
    expect(conditionClassFromResource('file [/tmp/classes/com/example/OrderConfiguration.class]')).toBeNull()
    expect(conditionClassFromResource('OrderConfiguration.class')).toBeNull()
    expect(conditionClassFromResource(null)).toBeNull()
  })

  it('keeps exact and method-level positive matches without accepting message-only matches', () => {
    const report = {
      positiveMatches: [
        {
          autoConfigurationClass: 'com.example.OrderAutoConfiguration',
          condition: 'ClassCondition',
          message: 'matched',
          outcome: 'MATCH'
        },
        {
          autoConfigurationClass: 'com.example.OrderAutoConfiguration#orderService',
          condition: 'BeanCondition',
          message: 'matched',
          outcome: 'MATCH'
        },
        {
          autoConfigurationClass: 'com.example.OtherAutoConfiguration',
          condition: 'MessageCondition',
          message: 'mentions com.example.OrderAutoConfiguration',
          outcome: 'MATCH'
        }
      ]
    }

    expect(matchingPositiveConditions(report, 'com.example.OrderAutoConfiguration')).toHaveLength(2)
  })

  it('bounds matching condition evidence deterministically', () => {
    const report = {
      positiveMatches: [
        {
          autoConfigurationClass: 'com.example.OrderAutoConfiguration#z',
          condition: 'Z',
          message: 'z',
          outcome: 'MATCH'
        },
        {
          autoConfigurationClass: 'com.example.OrderAutoConfiguration#a',
          condition: 'A',
          message: 'a',
          outcome: 'MATCH'
        }
      ]
    }

    expect(matchingPositiveConditions(report, 'com.example.OrderAutoConfiguration', 1)[0].condition).toBe('A')
  })
})

// ── buildGraphIndex ───────────────────────────────────────────────────────────

describe('buildGraphIndex', () => {
  it('builds a byName map from all beans', () => {
    const {byName} = graphIndex([bean('a'), bean('b')])
    expect(byName.has('a')).toBe(true)
    expect(byName.has('b')).toBe(true)
    expect(byName.size).toBe(2)
  })

  it('builds a reverse index from dependencies', () => {
    // a depends on b → b's reverse entry should include a
    const {reverseIndex} = graphIndex([bean('a', ['b']), bean('b')])
    expect(reverseIndex.get('b')).toContain('a')
  })

  it('handles multiple beans depending on the same target', () => {
    const {reverseIndex} = graphIndex([bean('a', ['c']), bean('b', ['c']), bean('c')])
    expect(reverseIndex.get('c')).toContain('a')
    expect(reverseIndex.get('c')).toContain('b')
  })

  it('handles beans with no dependencies', () => {
    const {byName, reverseIndex} = graphIndex([bean('lonely')])
    expect(byName.has('lonely')).toBe(true)
    expect(reverseIndex.size).toBe(0)
  })

  it('returns empty maps for an empty bean list', () => {
    const {byName, reverseIndex} = graphIndex([])
    expect(byName.size).toBe(0)
    expect(reverseIndex.size).toBe(0)
  })

  it('merges duplicate bean names deterministically without losing dependencies', () => {
    const {byName, definitionsByName, reverseIndex} = graphIndex([
      bean('shared', ['z']),
      bean('shared', ['a'], 'com.example.SecondShared'),
      bean('a'),
      bean('z')
    ])

    expect(byName.get('shared').dependencies).toEqual(['a', 'z'])
    expect(definitionsByName.get('shared')).toHaveLength(2)
    expect(reverseIndex.get('a')).toEqual(new Set(['shared']))
  })
})

// ── traverseNeighborhood — focus node ────────────────────────────────────────

describe('traverseNeighborhood — focus node', () => {
  it('returns only the focus node when it has no deps or dependents', () => {
    const {nodes, edges, truncated} = traverse('a', [bean('a'), bean('b')])
    expect(nodeNames(nodes)).toEqual(['a'])
    expect(edges).toHaveLength(0)
    expect(truncated).toBe(false)
  })

  it('returns empty result for an unknown focus name', () => {
    const {nodes, edges} = traverse('missing', [bean('a')])
    expect(nodes).toHaveLength(0)
    expect(edges).toHaveLength(0)
  })

  it('assigns role "focus" to the focus node', () => {
    const {nodes} = traverse('a', [bean('a', ['b']), bean('b')])
    const focus = nodes.find((n) => n.name === 'a')
    expect(focus.role).toBe('focus')
    expect(focus.depth).toBe(0)
  })
})

// ── traverseNeighborhood — direct dependencies / dependents ──────────────────

describe('traverseNeighborhood — direct neighbours', () => {
  it('includes direct dependencies with role "dep"', () => {
    const beans = [bean('a', ['b', 'c']), bean('b'), bean('c')]
    const {nodes} = traverse('a', beans)
    const depNode = nodes.find((n) => n.name === 'b')
    expect(depNode).toBeDefined()
    expect(depNode.role).toBe('dep')
    expect(depNode.depth).toBe(1)
  })

  it('includes direct dependents with role "rdep"', () => {
    const beans = [bean('a'), bean('b', ['a'])]
    const {nodes} = traverse('a', beans)
    const rdep = nodes.find((n) => n.name === 'b')
    expect(rdep).toBeDefined()
    expect(rdep.role).toBe('rdep')
    expect(rdep.depth).toBe(1)
  })

  it('adds a forward edge from focus to dependency', () => {
    const beans = [bean('a', ['b']), bean('b')]
    const {edges} = traverse('a', beans)
    expect(edgePairs(edges)).toContain('a=>b')
  })

  it('adds a reverse edge from dependent to focus', () => {
    const beans = [bean('a'), bean('b', ['a'])]
    const {edges} = traverse('a', beans)
    expect(edgePairs(edges)).toContain('b=>a')
  })

  it('adds both edges when a dependency reference is missing from the index', () => {
    // 'unknown' is declared as a dependency but not in the bean list (reduced fidelity)
    const {edges} = traverse('a', [bean('a', ['unknown'])])
    expect(edgePairs(edges)).toContain('a=>unknown')
  })

  it('excludes dependencies rejected by the active graph filter', () => {
    const graph = traverse('applicationBean', [bean('applicationBean', ['bootUiBean']), bean('bootUiBean')], {
      includeName: (name) => name !== 'bootUiBean'
    })

    expect(nodeNames(graph.nodes)).toEqual(['applicationBean'])
    expect(graph.edges).toEqual([])
  })
})

// ── traverseNeighborhood — cycles ─────────────────────────────────────────────

describe('traverseNeighborhood — cycle handling', () => {
  it('assigns role "both" when focus is a direct mutual dependency', () => {
    // a depends on b, b depends on a → mutual at depth 1
    const beans = [bean('a', ['b']), bean('b', ['a'])]
    const {nodes} = traverse('a', beans)
    const bNode = nodes.find((n) => n.name === 'b')
    expect(bNode.role).toBe('both')
  })

  it('does not infinite-loop on a direct cycle', () => {
    const beans = [bean('a', ['b']), bean('b', ['a'])]
    const {nodes} = traverse('a', beans)
    // Should terminate and include only a and b
    expect(nodes.length).toBeLessThanOrEqual(3)
  })

  it('does not infinite-loop on a longer cycle', () => {
    // a→b→c→a
    const beans = [bean('a', ['b']), bean('b', ['c']), bean('c', ['a'])]
    const {nodes} = traverse('a', beans)
    expect(nodes.length).toBeLessThanOrEqual(4)
  })

  it('does not duplicate nodes on a diamond dependency', () => {
    // a depends on b and c; both depend on d
    const beans = [bean('a', ['b', 'c']), bean('b', ['d']), bean('c', ['d']), bean('d')]
    const {nodes} = traverse('a', beans, {maxDepth: 3})
    const names = nodeNames(nodes)
    expect(names.filter((n) => n === 'd').length).toBe(1)
  })

  it('does not duplicate edges on a diamond', () => {
    const beans = [bean('a', ['b', 'c']), bean('b', ['d']), bean('c', ['d']), bean('d')]
    const {edges} = traverse('a', beans, {maxDepth: 3})
    const pairs = edgePairs(edges)
    const unique = new Set(pairs)
    expect(unique.size).toBe(pairs.length)
  })
})

// ── traverseNeighborhood — depth and node limits ──────────────────────────────

describe('traverseNeighborhood — depth limit', () => {
  it('does not traverse beyond maxDepth', () => {
    // chain: a→b→c→d (depth 3 from a would reach d)
    const beans = [bean('a', ['b']), bean('b', ['c']), bean('c', ['d']), bean('d')]
    const {nodes} = traverse('a', beans, {maxDepth: 2})
    expect(nodeNames(nodes)).not.toContain('d')
  })

  it('includes depth-2 nodes when maxDepth is 2', () => {
    const beans = [bean('a', ['b']), bean('b', ['c']), bean('c')]
    const {nodes} = traverse('a', beans, {maxDepth: 2})
    expect(nodeNames(nodes)).toContain('c')
  })

  it('assigns depth 2 to second-hop nodes', () => {
    const beans = [bean('a', ['b']), bean('b', ['c']), bean('c')]
    const {nodes} = traverse('a', beans, {maxDepth: 2})
    const cNode = nodes.find((n) => n.name === 'c')
    expect(cNode.depth).toBe(2)
  })
})

describe('traverseNeighborhood — node limit', () => {
  it('sets truncated=true when the limit is hit', () => {
    // Create more beans than the limit
    const beans = [
      bean(
        'a',
        Array.from({length: 10}, (_, i) => `dep${i}`)
      ),
      ...Array.from({length: 10}, (_, i) => bean(`dep${i}`))
    ]
    const {truncated} = traverse('a', beans, {maxNodes: 5})
    expect(truncated).toBe(true)
  })

  it('reports node and depth bounds separately', () => {
    const beans = [bean('a', ['b', 'c']), bean('b', ['d']), bean('c'), bean('d')]
    const nodeLimited = traverse('a', beans, {maxNodes: 2, maxDepth: 3})
    const depthLimited = traverse('a', beans, {maxNodes: 20, maxDepth: 1})

    expect(nodeLimited).toMatchObject({truncated: true, nodeLimited: true})
    expect(depthLimited).toMatchObject({truncated: true, depthLimited: true, nodeLimited: false})
  })

  it('never exceeds maxNodes in the returned list', () => {
    const beans = [
      bean(
        'a',
        Array.from({length: 20}, (_, i) => `dep${i}`)
      ),
      ...Array.from({length: 20}, (_, i) => bean(`dep${i}`))
    ]
    const {nodes} = traverse('a', beans, {maxNodes: 8})
    expect(nodes.length).toBeLessThanOrEqual(8)
  })

  it('uses locale-independent ordering when the node bound is reached', () => {
    const beans = [bean('focus', ['äBean', 'zBean']), bean('äBean'), bean('zBean')]
    const {nodes} = traverse('focus', beans, {maxNodes: 2})

    expect(nodeNames(nodes)).toEqual(['focus', 'zBean'])
  })

  it('sets truncated=false when well below the limit', () => {
    const {truncated} = traverse('a', [bean('a', ['b']), bean('b')])
    expect(truncated).toBe(false)
  })
})

// ── traverseNeighborhood — multi-hop traversal ────────────────────────────────

describe('traverseNeighborhood — multi-hop', () => {
  it('traverses dependencies of dependents (upstream chain)', () => {
    // b depends on a, c depends on b  →  from a's perspective: b(rdep,d1) c(deep,d2)
    const beans = [bean('a'), bean('b', ['a']), bean('c', ['b'])]
    const {nodes} = traverse('a', beans, {maxDepth: 2})
    expect(nodeNames(nodes)).toContain('c')
  })

  it('traverses dependencies of dependencies (downstream chain)', () => {
    // a depends on b, b depends on c
    const beans = [bean('a', ['b']), bean('b', ['c']), bean('c')]
    const {nodes} = traverse('a', beans, {maxDepth: 2})
    expect(nodeNames(nodes)).toContain('c')
  })

  it('assigns role "deep" to second-hop nodes', () => {
    const beans = [bean('a', ['b']), bean('b', ['c']), bean('c')]
    const {nodes} = traverse('a', beans, {maxDepth: 2})
    const cNode = nodes.find((n) => n.name === 'c')
    expect(cNode.role).toBe('deep')
  })

  it('keeps cycle edges between already included nodes', () => {
    const {edges} = traverse('a', [bean('a', ['b']), bean('b', ['c']), bean('c', ['a'])], {maxDepth: 2})
    expect(edgePairs(edges)).toEqual(['a=>b', 'b=>c', 'c=>a'])
  })
})

// ── traverseNeighborhood — reduced fidelity (empty dependencies) ──────────────

describe('traverseNeighborhood — reduced fidelity', () => {
  it('returns only the focus node when all dependency arrays are empty (Quarkus reduced-fidelity)', () => {
    const beans = [
      {
        name: 'serviceA',
        type: 'com.example.ServiceA',
        scope: 'ApplicationScoped',
        classification: 'APPLICATION',
        dependencies: []
      },
      {
        name: 'serviceB',
        type: 'com.example.ServiceB',
        scope: 'ApplicationScoped',
        classification: 'APPLICATION',
        dependencies: []
      }
    ]
    const {nodes, edges} = traverse('serviceA', beans)
    // Only the focus node; no edges since no dependency data
    expect(nodes.length).toBe(1)
    expect(edges.length).toBe(0)
  })

  it('handles missing references gracefully (dependency not in index)', () => {
    const beans = [bean('a', ['ghostBean'])]
    const {nodes, edges, truncated} = traverse('a', beans)
    expect(nodeNames(nodes)).toContain('a')
    expect(edgePairs(edges)).toContain('a=>ghostBean')
    expect(truncated).toBe(false)
  })
})

describe('useBeanGraph loading', () => {
  it('paginates through the backend 1 000-row cap up to the graph inventory bound', async () => {
    const first = Array.from({length: GRAPH_LOAD_PAGE_SIZE}, (_, index) => bean(`bean${index}`))
    const second = [bean('bean1000')]
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          total: 1001,
          beans: first,
          page: {matched: 1001, returned: 1000, hasMore: true}
        })
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          total: 1001,
          beans: second,
          page: {matched: 1001, returned: 1, hasMore: false}
        })
      })
    vi.stubGlobal('fetch', fetchMock)

    const graph = useBeanGraph()
    await graph.loadAll()

    expect(graph.allBeans.value).toHaveLength(1001)
    expect(graph.inventoryTruncated.value).toBe(false)
    expect(fetchMock).toHaveBeenNthCalledWith(2, expect.stringContaining('offset=1000'), expect.anything())
    vi.unstubAllGlobals()
  })

  it('marks the inventory truncated when more than 2 000 beans exist', async () => {
    const page = Array.from({length: GRAPH_LOAD_PAGE_SIZE}, (_, index) => bean(`bean${index}`))
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce({
          ok: true,
          json: async () => ({total: 2500, beans: page, page: {matched: 2500, hasMore: true}})
        })
        .mockResolvedValueOnce({
          ok: true,
          json: async () => ({total: 2500, beans: page, page: {matched: 2500, hasMore: true}})
        })
    )

    const graph = useBeanGraph()
    await graph.loadAll()

    expect(graph.allBeans.value).toHaveLength(2000)
    expect(graph.inventoryTotal.value).toBe(2500)
    expect(graph.inventoryTruncated.value).toBe(true)
    vi.unstubAllGlobals()
  })
})
