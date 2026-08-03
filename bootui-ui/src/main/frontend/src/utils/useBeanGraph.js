import {computed, ref} from 'vue'
import {apiFetch} from '../api.js'
import {describeLoadError} from './loadError.js'

/** Maximum beans to fetch when building the graph index. */
export const MAX_GRAPH_LOAD = 2000
/** Maximum nodes to include in a single neighbourhood render. */
export const MAX_GRAPH_NODES = 60
/** Maximum BFS hop depth from the focused node in either direction. */
export const MAX_GRAPH_DEPTH = 3

/**
 * Builds a forward dependency index (name → BeanSummary) and a reverse index
 * (name → Set of dependent names) from a flat bean list.
 *
 * @param {Array} beans  Array of BeanSummary objects.
 * @returns {{ byName: Map<string,object>, reverseIndex: Map<string,Set<string>> }}
 */
export function buildGraphIndex(beans) {
  const byName = new Map()
  for (const bean of beans) {
    byName.set(bean.name, bean)
  }
  const reverseIndex = new Map()
  for (const bean of beans) {
    for (const dep of bean.dependencies || []) {
      if (!reverseIndex.has(dep)) reverseIndex.set(dep, new Set())
      reverseIndex.get(dep).add(bean.name)
    }
  }
  return {byName, reverseIndex}
}

/**
 * Traverses the dependency neighbourhood of a focus bean using bounded BFS in
 * both directions, guarded against cycles by a visited-set.
 *
 * Node roles:
 *   - 'focus'  — the selected bean (depth 0).
 *   - 'dep'    — direct dependency of focus (focus → node).
 *   - 'rdep'   — direct dependent of focus (node → focus).
 *   - 'both'   — direct dependency AND dependent (mutual/cycle at depth 1).
 *   - 'deep'   — reachable only at depth 2+.
 *
 * @param {string}              focusName     Name of the bean to focus on.
 * @param {Map<string,object>}  byName        Forward dependency index.
 * @param {Map<string,Set>}     reverseIndex  Reverse dependency index.
 * @param {number}              maxNodes      Node limit (default {@link MAX_GRAPH_NODES}).
 * @param {number}              maxDepth      Hop depth limit (default {@link MAX_GRAPH_DEPTH}).
 * @returns {{ nodes: Array, edges: Array, truncated: boolean }}
 */
export function traverseNeighborhood(
  focusName,
  byName,
  reverseIndex,
  maxNodes = MAX_GRAPH_NODES,
  maxDepth = MAX_GRAPH_DEPTH
) {
  if (!byName.has(focusName)) {
    return {nodes: [], edges: [], truncated: false}
  }

  const visited = new Set()
  const nodeDetails = new Map() // name → {name, depth, role}
  const edgeSet = new Set()
  const edges = []
  let truncated = false

  function tryAdd(name, depth, role) {
    if (visited.has(name)) return false
    if (nodeDetails.size >= maxNodes) {
      truncated = true
      return false
    }
    visited.add(name)
    nodeDetails.set(name, {name, depth, role})
    return true
  }

  function addEdge(from, to) {
    const key = `${from}=>${to}`
    if (!edgeSet.has(key)) {
      edgeSet.add(key)
      edges.push({from, to})
    }
  }

  // Focus node
  tryAdd(focusName, 0, 'focus')
  const focusBean = byName.get(focusName)

  // --- Depth-1: direct dependencies (focus → dep) ---
  for (const dep of focusBean?.dependencies || []) {
    tryAdd(dep, 1, 'dep')
    addEdge(focusName, dep)
  }

  // --- Depth-1: direct dependents (rdep → focus) ---
  for (const rdep of reverseIndex.get(focusName) || new Set()) {
    if (!visited.has(rdep)) {
      tryAdd(rdep, 1, 'rdep')
    } else {
      // Already added as a direct dep → mutual / cycle at depth 1
      const nd = nodeDetails.get(rdep)
      if (nd && nd.depth === 1 && nd.role === 'dep') nd.role = 'both'
    }
    addEdge(rdep, focusName)
  }

  // --- BFS for depth 2+ ---
  if (maxDepth > 1) {
    const queue = []
    for (const [name, info] of nodeDetails) {
      if (info.depth === 1) queue.push({name, depth: 1})
    }
    let qi = 0
    while (qi < queue.length) {
      const {name, depth} = queue[qi++]
      if (depth >= maxDepth) continue
      const bean = byName.get(name)
      if (!bean) continue

      for (const dep of bean.dependencies || []) {
        const added = tryAdd(dep, depth + 1, 'deep')
        addEdge(name, dep)
        if (added) queue.push({name: dep, depth: depth + 1})
      }
      for (const rdep of reverseIndex.get(name) || new Set()) {
        const added = tryAdd(rdep, depth + 1, 'deep')
        addEdge(rdep, name)
        if (added) queue.push({name: rdep, depth: depth + 1})
      }
    }
  }

  return {nodes: Array.from(nodeDetails.values()), edges, truncated}
}

/**
 * Composable that manages bean-graph data: load-once, index, and navigation.
 *
 * Call {@link loadAll} when the user activates graph mode; it fetches up to
 * {@link MAX_GRAPH_LOAD} beans from {@code api/beans} and builds the indices.
 * Subsequent calls are no-ops once {@link loaded} is true.
 *
 * @returns {{
 *   allBeans: import('vue').Ref<Array>,
 *   beanNames: import('vue').ComputedRef<string[]>,
 *   byName: import('vue').ComputedRef<Map>,
 *   error: import('vue').Ref,
 *   focusName: import('vue').Ref<string|null>,
 *   graph: import('vue').ComputedRef,
 *   loaded: import('vue').Ref<boolean>,
 *   loading: import('vue').Ref<boolean>,
 *   loadAll: function,
 *   setFocus: function
 * }}
 */
export function useBeanGraph() {
  const allBeans = ref([])
  const loading = ref(false)
  const error = ref(null)
  const focusName = ref(null)
  const loaded = ref(false)

  const byName = computed(() => {
    const m = new Map()
    for (const b of allBeans.value) m.set(b.name, b)
    return m
  })

  const reverseIndex = computed(() => {
    const m = new Map()
    for (const b of allBeans.value) {
      for (const dep of b.dependencies || []) {
        if (!m.has(dep)) m.set(dep, new Set())
        m.get(dep).add(b.name)
      }
    }
    return m
  })

  /** Sorted list of all bean names; used to populate the focus-search datalist. */
  const beanNames = computed(() => allBeans.value.map((b) => b.name).sort((a, b) => a.localeCompare(b)))

  /** Neighbourhood graph for the currently focused bean, or {@code null}. */
  const graph = computed(() => {
    if (!focusName.value || !byName.value.has(focusName.value)) return null
    return traverseNeighborhood(focusName.value, byName.value, reverseIndex.value)
  })

  /** Load all beans (up to {@link MAX_GRAPH_LOAD}) once. Subsequent calls are no-ops. */
  async function loadAll() {
    if (loaded.value) return
    loading.value = true
    error.value = null
    try {
      const params = new URLSearchParams({offset: '0', limit: String(MAX_GRAPH_LOAD)})
      const res = await apiFetch(`api/beans?${params}`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      allBeans.value = data.beans || []
      loaded.value = true
    } catch (e) {
      error.value = describeLoadError(e, 'Could not load beans for graph')
    } finally {
      loading.value = false
    }
  }

  /** Navigate the graph to a different focus bean by name. */
  function setFocus(name) {
    focusName.value = name || null
  }

  return {allBeans, beanNames, byName, error, focusName, graph, loaded, loading, loadAll, setFocus}
}
