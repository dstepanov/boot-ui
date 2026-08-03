import {describe, expect, it} from 'vitest'
import {buildGraphIndex, MAX_GRAPH_DEPTH, MAX_GRAPH_NODES, traverseNeighborhood} from '../utils/useBeanGraph.js'

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
    overrides.maxDepth ?? MAX_GRAPH_DEPTH
  )
}

function nodeNames(nodes) {
  return nodes.map((n) => n.name).sort()
}

function edgePairs(edges) {
  return edges.map((e) => `${e.from}=>${e.to}`).sort()
}

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
