<script setup>
import {computed} from 'vue'
import {MAX_GRAPH_NODES} from '../utils/useBeanGraph.js'

const props = defineProps({
  /** The neighbourhood returned by {@link traverseNeighborhood}. */
  graph: {type: Object, required: true},
  /** Map<name, BeanSummary> used to look up types for tooltip titles. */
  byName: {type: Object, required: true},
  /** Name of the currently focused bean. */
  focusName: {type: String, required: true}
})

const emit = defineEmits(['focus'])

// ── Layout constants ──────────────────────────────────────────────────────────
const NODE_W = 148
const NODE_H = 36
const NODE_RX = 4
const FONT_SIZE = 11
/** Minimum arc spacing (px) between adjacent nodes in the same ring. */
const MIN_ARC_SPACING = 52

/**
 * Compute the ring radius so adjacent nodes in a ring are at least
 * MIN_ARC_SPACING apart; clamp to a minimum radius.
 */
function ringRadius(n, minR) {
  if (n <= 1) return minR
  return Math.max(minR, (MIN_ARC_SPACING * n) / (2 * Math.PI))
}

// ── Dynamic SVG layout ────────────────────────────────────────────────────────
const layout = computed(() => {
  const {nodes, edges} = props.graph

  // Group nodes by depth
  const byDepth = new Map()
  for (const node of nodes) {
    if (!byDepth.has(node.depth)) byDepth.set(node.depth, [])
    byDepth.get(node.depth).push(node)
  }

  // Compute ring radii
  const radii = new Map()
  radii.set(0, 0)
  const depths = [...byDepth.keys()].filter((d) => d > 0).sort((a, b) => a - b)
  let prevR = 0
  for (const d of depths) {
    const count = byDepth.get(d).length
    const minR = prevR + Math.max(NODE_W * 1.1, 140)
    const r = ringRadius(count, minR)
    radii.set(d, r)
    prevR = r
  }

  const maxR = Math.max(0, ...radii.values())
  const svgW = Math.max(520, (maxR + NODE_W * 0.7) * 2)
  const svgH = Math.max(420, (maxR + NODE_H * 1.2) * 2)
  const cx = svgW / 2
  const cy = svgH / 2

  // Position each node
  const pos = new Map()
  for (const [depth, ring] of byDepth) {
    const r = radii.get(depth) ?? depth * 160
    // Sort for deterministic placement: focus first, then by name
    const sorted = [...ring].sort((a, b) => a.name.localeCompare(b.name))
    sorted.forEach((node, i) => {
      if (r === 0) {
        pos.set(node.name, {x: cx, y: cy})
      } else {
        const angle = (2 * Math.PI * i) / sorted.length - Math.PI / 2
        pos.set(node.name, {x: cx + r * Math.cos(angle), y: cy + r * Math.sin(angle)})
      }
    })
  }

  // Compute trimmed edge endpoints (offset from node center toward the target)
  const trimmedEdges = edges
    .filter((e) => pos.has(e.from) && pos.has(e.to))
    .map((e) => {
      const {x: x1, y: y1} = pos.get(e.from)
      const {x: x2, y: y2} = pos.get(e.to)
      // Direction vector
      const dx = x2 - x1
      const dy = y2 - y1
      const dist = Math.sqrt(dx * dx + dy * dy)
      if (dist < 1) return null
      const ux = dx / dist
      const uy = dy / dist
      // Offset from source border and leave gap for arrowhead at target
      const srcOffset = Math.max(NODE_W / 2, Math.abs(ux) * (NODE_W / 2)) + 4
      const dstOffset = Math.max(NODE_H / 2, Math.abs(uy) * (NODE_H / 2)) + 12
      return {
        key: `${e.from}=>${e.to}`,
        x1: x1 + ux * srcOffset,
        y1: y1 + uy * srcOffset,
        x2: x2 - ux * dstOffset,
        y2: y2 - uy * dstOffset
      }
    })
    .filter(Boolean)

  return {svgW, svgH, pos, trimmedEdges, nodes}
})

// ── Node helpers ──────────────────────────────────────────────────────────────

/** Last meaningful segment of a bean name (e.g. 'orderRepository' from FQN). */
function shortName(name) {
  if (!name) return ''
  const base = name.split('#')[0]
  const parts = base.split('.')
  return parts[parts.length - 1] || name
}

/** Full type from the index if available, otherwise the name itself. */
function nodeTitle(name) {
  const bean = props.byName.get(name)
  return bean ? `${name}\n${bean.type || ''}` : name
}

// ── Event handlers ────────────────────────────────────────────────────────────
function refocus(name) {
  if (name !== props.focusName) emit('focus', name)
}

function onKeydown(event, name) {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    refocus(name)
  }
}
</script>

<template>
  <div class="bean-graph-wrap">
    <!-- Truncation notice -->
    <div v-if="graph.truncated" class="alert alert-info py-2 small mb-2" role="status">
      <i class="bi bi-info-circle me-1" aria-hidden="true"></i>
      Graph truncated to {{ MAX_GRAPH_NODES }} nodes. Click any node to re-focus and explore its neighbourhood.
    </div>

    <!-- No neighbours notice for isolated beans -->
    <div
      v-if="graph.nodes.length <= 1 && !graph.truncated"
      class="text-muted small mb-2"
      role="status"
      aria-live="polite"
    >
      <i class="bi bi-diagram-2 me-1" aria-hidden="true"></i>
      <strong>{{ focusName }}</strong> has no recorded dependencies or dependents.
      <span v-if="!byName.get(focusName)?.dependencies?.length">
        Dependency data may not be available on this platform.</span
      >
    </div>

    <div class="bean-graph-scroll" role="region" aria-label="Bean dependency neighbourhood graph">
      <svg
        :width="layout.svgW"
        :height="layout.svgH"
        :viewBox="`0 0 ${layout.svgW} ${layout.svgH}`"
        class="bean-graph-svg"
        role="img"
        :aria-label="`Dependency neighbourhood for ${focusName}: ${graph.nodes.length} nodes, ${graph.edges.length} edges`"
      >
        <!-- Arrowhead marker (dependency direction) -->
        <defs>
          <marker
            id="bg-arrow"
            markerWidth="8"
            markerHeight="6"
            refX="7"
            refY="3"
            orient="auto"
            markerUnits="userSpaceOnUse"
          >
            <polygon class="bg-arrow-poly" points="0 0, 8 3, 0 6" />
          </marker>
        </defs>

        <!-- Edges -->
        <g aria-hidden="true">
          <line
            v-for="edge in layout.trimmedEdges"
            :key="edge.key"
            :x1="edge.x1"
            :y1="edge.y1"
            :x2="edge.x2"
            :y2="edge.y2"
            class="bg-edge"
            marker-end="url(#bg-arrow)"
          />
        </g>

        <!-- Nodes -->
        <g
          v-for="node in layout.nodes"
          :key="node.name"
          :transform="`translate(${(layout.pos.get(node.name)?.x ?? 0) - NODE_W / 2},${(layout.pos.get(node.name)?.y ?? 0) - NODE_H / 2})`"
          :class="['bg-node', `bg-node--${node.role}`]"
          tabindex="0"
          role="button"
          :aria-label="`${node.name}${node.role === 'focus' ? ' (focused)' : ''} — press Enter to focus`"
          :aria-pressed="node.role === 'focus'"
          @click="refocus(node.name)"
          @keydown="onKeydown($event, node.name)"
        >
          <title>{{ nodeTitle(node.name) }}</title>
          <rect :width="NODE_W" :height="NODE_H" :rx="NODE_RX" />
          <text :x="NODE_W / 2" :y="NODE_H / 2" text-anchor="middle" dominant-baseline="central" :font-size="FONT_SIZE">
            {{ shortName(node.name) }}
          </text>
        </g>
      </svg>
    </div>

    <!-- Legend -->
    <div class="bg-legend" aria-label="Graph legend">
      <span class="bg-legend-item">
        <span class="bg-legend-swatch bg-legend-swatch--focus" aria-hidden="true"></span>
        <span>Focus</span>
      </span>
      <span class="bg-legend-item">
        <span class="bg-legend-swatch bg-legend-swatch--dep" aria-hidden="true"></span>
        <span>Dependency</span>
      </span>
      <span class="bg-legend-item">
        <span class="bg-legend-swatch bg-legend-swatch--rdep" aria-hidden="true"></span>
        <span>Dependent</span>
      </span>
      <span class="bg-legend-item">
        <span class="bg-legend-swatch bg-legend-swatch--both" aria-hidden="true"></span>
        <span>Mutual</span>
      </span>
      <span class="bg-legend-item">
        <span class="bg-legend-swatch bg-legend-swatch--deep" aria-hidden="true"></span>
        <span>Deeper hop</span>
      </span>
      <span class="bg-legend-arrow-note" aria-hidden="true"> <i class="bi bi-arrow-right me-1"></i>depends on </span>
    </div>
  </div>
</template>

<style scoped>
/* ── Container ─────────────────────────────────────────────────────────────── */
.bean-graph-wrap {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.bean-graph-scroll {
  overflow: auto;
  background: var(--bootui-surface-alt);
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-md);
  min-height: 200px;
}

/* ── SVG base ───────────────────────────────────────────────────────────────── */
.bean-graph-svg {
  display: block;
}

/* ── Arrowhead ──────────────────────────────────────────────────────────────── */
.bg-arrow-poly {
  fill: var(--bootui-border-alt);
}

/* ── Edges ──────────────────────────────────────────────────────────────────── */
.bg-edge {
  stroke: var(--bootui-border-alt);
  stroke-width: 1.5;
  fill: none;
}

/* ── Nodes shared ───────────────────────────────────────────────────────────── */
.bg-node {
  cursor: pointer;
  outline: none;
}

/* Keyboard focus ring */
.bg-node:focus-visible rect {
  outline: 2px solid var(--bootui-blue);
  outline-offset: 3px;
}

/* Since SVG doesn't support CSS outline on rects directly, use a box-shadow-equivalent */
.bg-node:focus-visible rect {
  stroke: var(--bootui-blue);
  stroke-width: 2.5;
}

.bg-node text {
  font-family: var(--bs-font-monospace, ui-monospace, monospace);
  pointer-events: none;
  user-select: none;
}

/* ── Node role colours ──────────────────────────────────────────────────────── */
/* Focus */
.bg-node--focus rect {
  fill: var(--bootui-green);
  stroke: var(--bootui-green-dark);
  stroke-width: 1.5;
}
.bg-node--focus text {
  fill: #fff;
  font-weight: 600;
}

/* Direct dependency (focus → node) */
.bg-node--dep rect {
  fill: #dbeafe;
  stroke: #3b82f6;
  stroke-width: 1.5;
}
.bg-node--dep text {
  fill: #1e3a5f;
}

/* Direct dependent (node → focus) */
.bg-node--rdep rect {
  fill: #dcfce7;
  stroke: var(--bootui-green);
  stroke-width: 1.5;
}
.bg-node--rdep text {
  fill: #14532d;
}

/* Mutual (dep AND rdep at depth 1) */
.bg-node--both rect {
  fill: #fef9c3;
  stroke: #ca8a04;
  stroke-width: 1.5;
}
.bg-node--both text {
  fill: #713f12;
}

/* Deeper hop */
.bg-node--deep rect {
  fill: var(--bootui-surface);
  stroke: var(--bootui-border-alt);
  stroke-width: 1;
}
.bg-node--deep text {
  fill: var(--bootui-text-muted);
}

/* ── Dark-mode overrides ─────────────────────────────────────────────────────── */
:root[data-bootui-theme='dark'] .bg-node--dep rect {
  fill: #1e3a5f;
  stroke: #60a5fa;
}
:root[data-bootui-theme='dark'] .bg-node--dep text {
  fill: #bfdbfe;
}

:root[data-bootui-theme='dark'] .bg-node--rdep rect {
  fill: #14532d;
  stroke: var(--bootui-green);
}
:root[data-bootui-theme='dark'] .bg-node--rdep text {
  fill: #bbf7d0;
}

:root[data-bootui-theme='dark'] .bg-node--both rect {
  fill: #422006;
  stroke: #d97706;
}
:root[data-bootui-theme='dark'] .bg-node--both text {
  fill: #fde68a;
}

:root[data-bootui-theme='dark'] .bg-node--deep rect {
  fill: var(--bootui-surface-alt);
  stroke: var(--bootui-border-alt);
}
:root[data-bootui-theme='dark'] .bg-node--deep text {
  fill: var(--bootui-text-muted);
}

/* ── Legend ─────────────────────────────────────────────────────────────────── */
.bg-legend {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem;
  font-size: 0.78rem;
  color: var(--bootui-text-muted);
}

.bg-legend-item {
  display: flex;
  align-items: center;
  gap: 0.35rem;
}

.bg-legend-swatch {
  display: inline-block;
  width: 14px;
  height: 14px;
  border-radius: 3px;
  border: 1.5px solid transparent;
}

.bg-legend-swatch--focus {
  background: var(--bootui-green);
  border-color: var(--bootui-green-dark);
}
.bg-legend-swatch--dep {
  background: #dbeafe;
  border-color: #3b82f6;
}
.bg-legend-swatch--rdep {
  background: #dcfce7;
  border-color: var(--bootui-green);
}
.bg-legend-swatch--both {
  background: #fef9c3;
  border-color: #ca8a04;
}
.bg-legend-swatch--deep {
  background: var(--bootui-surface);
  border-color: var(--bootui-border-alt);
}

:root[data-bootui-theme='dark'] .bg-legend-swatch--dep {
  background: #1e3a5f;
  border-color: #60a5fa;
}
:root[data-bootui-theme='dark'] .bg-legend-swatch--rdep {
  background: #14532d;
  border-color: var(--bootui-green);
}
:root[data-bootui-theme='dark'] .bg-legend-swatch--both {
  background: #422006;
  border-color: #d97706;
}

.bg-legend-arrow-note {
  margin-left: auto;
  font-size: 0.75rem;
  opacity: 0.65;
}

/* ── Reduced motion ──────────────────────────────────────────────────────────── */
@media (prefers-reduced-motion: reduce) {
  .bg-node,
  .bg-edge {
    transition: none;
  }
}
</style>
