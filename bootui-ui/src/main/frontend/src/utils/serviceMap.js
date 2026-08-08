/**
 * Pure helpers behind Live Activity's Live Flow map.
 *
 * Everything here is deliberately framework-free and side-effect-free so the map's interpretation,
 * layout, and motion rules can be unit tested without a DOM: the Vue component only wires these
 * functions to markup.
 *
 * The motion model is the important part. The map never animates on its own; it only animates
 * *newly observed, already completed* evidence. A pulse is emitted when, and only when:
 *
 *   1. an edge existed in the previous snapshot and still exists in the next one (a stable edge), and
 *   2. that edge's newest-first interaction tail contains an interaction id the previous snapshot did
 *      not carry.
 *
 * A first load therefore produces no motion, a brand-new dependency appears without a pulse, and an
 * idle application stays completely still.
 *
 * Causal sequencing builds on that model rather than replacing it: when several fresh pulses share the
 * server-derived, opaque `flowId` (see `sequenceFlowPulses`), they are evidence of one request's actual
 * path through the application. Inbound HTTP arrives first; downstream completions then replay in their
 * retained timestamp order (with cache before JDBC/HTTP only as the deterministic same-millisecond
 * tie-break), so their *start* reads as one causal story instead of simultaneous, unrelated blips. Motion
 * still only ever depicts evidence that already completed; sequencing never changes the evidence, only
 * how its replay is paced.
 */

export const PROTOCOL_HTTP_INBOUND = 'HTTP_INBOUND'
export const PROTOCOL_HTTP = 'HTTP'
export const PROTOCOL_JDBC = 'JDBC'
export const PROTOCOL_KAFKA = 'KAFKA'
export const PROTOCOL_RABBITMQ = 'RABBITMQ'
export const PROTOCOL_CACHE = 'CACHE'

export const PROTOCOL_LABELS = {
  APPLICATION: 'Application',
  [PROTOCOL_HTTP_INBOUND]: 'Incoming HTTP',
  [PROTOCOL_HTTP]: 'HTTP',
  [PROTOCOL_JDBC]: 'JDBC',
  [PROTOCOL_KAFKA]: 'Kafka',
  [PROTOCOL_RABBITMQ]: 'RabbitMQ',
  [PROTOCOL_CACHE]: 'Cache'
}

export const PROTOCOL_ICONS = {
  APPLICATION: 'bi-box-seam',
  [PROTOCOL_HTTP_INBOUND]: 'bi-box-arrow-in-right',
  [PROTOCOL_HTTP]: 'bi-globe2',
  [PROTOCOL_JDBC]: 'bi-database',
  [PROTOCOL_KAFKA]: 'bi-broadcast-pin',
  [PROTOCOL_RABBITMQ]: 'bi-diagram-3',
  [PROTOCOL_CACHE]: 'bi-lightning-charge'
}

/**
 * Outcome copy. Deliberately describes retained evidence, never remote health: BootUI has not
 * contacted any of these systems, it has only re-read what already happened.
 */
export const OUTCOME_LABELS = {
  NO_EVIDENCE: 'No recent evidence',
  OBSERVED_OK: 'Recent activity completed',
  RETAINED_FAILURES: 'Recent failures retained'
}

export const OUTCOME_ICONS = {
  NO_EVIDENCE: 'bi-dash-circle',
  OBSERVED_OK: 'bi-check-circle',
  RETAINED_FAILURES: 'bi-exclamation-triangle'
}

/** Above this, a completed interaction is drawn as a slow (amber) pulse rather than a normal one. */
export const SLOW_INTERACTION_MS = 500

/** Bounds on motion, so a traffic burst can never turn the map into a fireworks display. */
export const MAX_CONCURRENT_PULSES = 6
export const MAX_PULSES_PER_EDGE = 2
export const REDUCED_MOTION_HIGHLIGHT_MS = 1200

/**
 * Per-tone pulse travel durations. Slow is deliberately the longest and most unmistakable (a calm amber
 * comet, never a flash), failed is a shorter, firmer beat, and a normal completion is the briskest of the
 * three - the timing itself is part of what makes "slow" legible without relying on color alone.
 */
export const PULSE_DURATION_OK_MS = 750
export const PULSE_DURATION_SLOW_MS = 1350
export const PULSE_DURATION_FAILED_MS = 1000
/** Back-compat alias equal to the normal-tone duration; existing call sites keep working unchanged. */
export const PULSE_DURATION_MS = PULSE_DURATION_OK_MS

/** The travel duration for one pulse, keyed by the tone `pulseTone` classified it into. */
export function pulseDurationMs(tone) {
  if (tone === 'slow') return PULSE_DURATION_SLOW_MS
  if (tone === 'failed') return PULSE_DURATION_FAILED_MS
  return PULSE_DURATION_OK_MS
}

/**
 * Deterministic protocol precedence for interactions whose retained completion timestamps are identical.
 * Inbound is always first; cache precedes JDBC/HTTP only for a same-millisecond tie. The timestamp remains
 * authoritative so the replay never invents a cache-before-database order that the completed evidence does
 * not support. Kafka/RabbitMQ never carry a flowId, so they do not reach this lookup in practice.
 */
function flowStage(protocol) {
  if (protocol === PROTOCOL_HTTP_INBOUND) return 0
  if (protocol === PROTOCOL_CACHE) return 1
  return 2
}

/** Truthful replay order: inbound first, then observed completion time, then a stable protocol tie-break. */
function compareFlowPulses(a, b) {
  const stageDifference = flowStage(a?.protocol) - flowStage(b?.protocol)
  if (flowStage(a?.protocol) === 0 || flowStage(b?.protocol) === 0) return stageDifference
  const timestampDifference = (a?.interaction?.timestamp ?? 0) - (b?.interaction?.timestamp ?? 0)
  return timestampDifference || stageDifference || String(a?.id ?? '').localeCompare(String(b?.id ?? ''))
}

/** Small, bounded stagger between same-flow downstream pulses so a fan-out reads as distinguishable beats. */
export const FLOW_STAGE_STAGGER_MS = 90
export const MAX_FLOW_STAGGER_STEPS = 3

/** Normalizes a server report into the shape the map renders, tolerating partial payloads. */
export function normalizeServiceMap(report) {
  const nodes = Array.isArray(report?.nodes) ? report.nodes.filter(Boolean) : []
  const edges = Array.isArray(report?.edges) ? report.edges.filter(Boolean) : []
  return {
    available: report?.available === true,
    unavailableReason: report?.unavailableReason ?? null,
    generatedAt: report?.generatedAt ?? null,
    application: report?.application ?? null,
    nodes,
    edges,
    truncation: report?.truncation ?? null,
    sources: Array.isArray(report?.sources) ? report.sources : [],
    warnings: Array.isArray(report?.warnings) ? report.warnings : []
  }
}

/**
 * Applies the protocol and free-text filters. The application node is never filtered away — it is the
 * anchor every edge is drawn from — and an edge survives only while both of its endpoints do.
 */
export function filterServiceMap(map, {protocol = '', text = ''} = {}) {
  const needle = String(text || '')
    .trim()
    .toLowerCase()
  const nodes = map.nodes.filter((node) => {
    if (protocol && node.protocol !== protocol) return false
    if (!needle) return true
    return [node.label, node.detail, node.sourceLabel, PROTOCOL_LABELS[node.protocol]]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(needle))
  })
  const visibleIds = new Set(nodes.map((node) => node.id))
  if (map.application?.id) visibleIds.add(map.application.id)
  const edges = map.edges.filter((edge) => visibleIds.has(edge.fromId) && visibleIds.has(edge.toId))
  return {...map, nodes, edges}
}

/** Splits the visible nodes into the single inbound lane and the outbound dependencies. */
export function partitionNodes(map) {
  const inbound = map.nodes.find((node) => node.kind === 'INBOUND') ?? null
  const dependencies = map.nodes.filter((node) => node.kind === 'DEPENDENCY')
  return {inbound, dependencies}
}

const APP_W = 172
const APP_H = 60
const NODE_W = 196
const NODE_H = 46
const INBOUND_GAP = 150
const MARGIN = 24
const MIN_ARC_SPACING = NODE_H + 20
const FAN_DEGREES = 150

/**
 * Fans the dependencies out to the right of the centered application, mirroring the Beans graph's
 * ring layout so the two graph surfaces feel like one system.
 *
 * The radius grows with the dependency count so adjacent nodes never overlap, which is what keeps the
 * layout readable at the hard cardinality cap without any collision solving.
 */
export function layoutServiceMap(map, {nodeWidth = NODE_W, nodeHeight = NODE_H} = {}) {
  const {inbound, dependencies} = partitionNodes(map)
  const count = dependencies.length
  const fanRadians = (FAN_DEGREES * Math.PI) / 180
  const minRadius = APP_W / 2 + nodeWidth / 2 + 90
  const radius = count <= 1 ? minRadius : Math.max(minRadius, (MIN_ARC_SPACING * count) / fanRadians)

  const halfHeight = count <= 1 ? APP_H : Math.max(APP_H, radius * Math.sin(fanRadians / 2) + nodeHeight)
  const height = Math.max(360, halfHeight * 2 + MARGIN * 2)
  const cy = height / 2
  const leftExtent = inbound ? nodeWidth + INBOUND_GAP : 0
  const cx = MARGIN + leftExtent + APP_W / 2
  const width = cx + radius + nodeWidth / 2 + MARGIN

  const positions = new Map()
  const application = map.application ? {node: map.application, x: cx, y: cy, w: APP_W, h: APP_H} : null
  if (application) positions.set(application.node.id, application)

  let inboundBox = null
  if (inbound) {
    inboundBox = {node: inbound, x: MARGIN + nodeWidth / 2, y: cy, w: nodeWidth, h: nodeHeight}
    positions.set(inbound.id, inboundBox)
  }

  const dependencyBoxes = dependencies.map((node, index) => {
    // A single dependency sits straight ahead; more than one spreads evenly across the fan.
    const ratio = count === 1 ? 0.5 : index / (count - 1)
    const angle = -fanRadians / 2 + ratio * fanRadians
    const box = {
      node,
      x: cx + radius * Math.cos(angle),
      y: cy + radius * Math.sin(angle),
      w: nodeWidth,
      h: nodeHeight
    }
    positions.set(node.id, box)
    return box
  })

  const edges = map.edges
    .map((edge) => {
      const from = positions.get(edge.fromId)
      const to = positions.get(edge.toId)
      if (!from || !to) return null
      const dx = to.x - from.x
      const dy = to.y - from.y
      const distance = Math.sqrt(dx * dx + dy * dy)
      if (distance < 1) return null
      const ux = dx / distance
      const uy = dy / distance
      const start = trim(from, ux, uy, 4)
      const end = trim(to, ux, uy, 12)
      return {
        edge,
        id: edge.id,
        x1: from.x + ux * start,
        y1: from.y + uy * start,
        x2: to.x - ux * end,
        y2: to.y - uy * end
      }
    })
    .filter(Boolean)

  return {
    width: Math.round(width),
    height: Math.round(height),
    application,
    inbound: inboundBox,
    dependencies: dependencyBoxes,
    edges
  }
}

/** Distance from a box centre to its border along the given unit vector, plus a small gap. */
function trim(box, ux, uy, gap) {
  const horizontal = Math.abs(ux) > 0.0001 ? box.w / 2 / Math.abs(ux) : Number.POSITIVE_INFINITY
  const vertical = Math.abs(uy) > 0.0001 ? box.h / 2 / Math.abs(uy) : Number.POSITIVE_INFINITY
  return Math.min(horizontal, vertical) + gap
}

/** Classifies one completed interaction into the tone its pulse is drawn with. */
export function pulseTone(interaction) {
  if (!interaction) return 'ok'
  if (interaction.outcome === 'FAILED') return 'failed'
  if (interaction.durationMs != null && interaction.durationMs >= SLOW_INTERACTION_MS) return 'slow'
  return 'ok'
}

/**
 * Finds the completed interactions that are new since the previous snapshot.
 *
 * Only stable edges are considered, and only the capped interaction tail is compared, so the amount of
 * motion is bounded by the contract itself rather than by how much traffic the application handled. Each
 * pulse carries the edge's `protocol` (used to order a causal sequence) and a per-tone `durationMs` (see
 * `pulseDurationMs`), both consumed by `sequenceFlowPulses` and the queue below.
 */
export function diffFlowPulses(previousEdges, nextEdges, {maxPerEdge = MAX_PULSES_PER_EDGE} = {}) {
  if (!Array.isArray(previousEdges) || !previousEdges.length || !Array.isArray(nextEdges)) return []
  const previousById = new Map(previousEdges.map((edge) => [edge.id, edge]))
  const pulses = []
  for (const edge of nextEdges) {
    const previous = previousById.get(edge.id)
    // A brand-new edge is not animated: its arrival is already the visible change.
    if (!previous) continue
    const seen = new Set((previous.recentInteractions ?? []).map((interaction) => interaction.id))
    const fresh = (edge.recentInteractions ?? []).filter((interaction) => !seen.has(interaction.id))
    for (const interaction of fresh.slice(0, maxPerEdge)) {
      const tone = pulseTone(interaction)
      pulses.push({
        id: `${edge.id}#${interaction.id}`,
        edgeId: edge.id,
        direction: edge.direction,
        fromId: edge.fromId,
        toId: edge.toId,
        protocol: edge.protocol,
        tone,
        durationMs: pulseDurationMs(tone),
        interaction
      })
    }
  }
  return pulses
}

/** Returns the non-application endpoint represented by a directional service-map edge. */
export function externalEndpointId(edge) {
  return edge?.direction === 'INBOUND' ? edge.fromId : edge?.toId
}

/**
 * Sequences a batch of freshly diffed pulses into a causal story, per flow.
 *
 * Pulses that share a server-derived `interaction.flowId` are evidence of one request's actual path
 * through the application. Inbound HTTP reaches the app first. Downstream interactions replay in ascending
 * retained completion-time order, using cache-before-JDBC/HTTP only as a deterministic tie-break when
 * timestamps are equal - the truthful order documented in `docs/SPECIFICATION.md`. Exactly one inbound
 * pulse *retained in this very batch* is required to anchor that sequence: everything downstream of it
 * starts once it has finished arriving (its own `durationMs`), then downstream pulses are staggered by a
 * small, bounded step so several of them do not all start in the same instant.
 *
 * Every other pulse is untouched:
 *
 *   - a pulse with no `flowId` is not part of any flow and is never delayed;
 *   - a flow whose batch carries no inbound pulse (the common case once the inbound leg has already
 *     scrolled out of the retained tail) never delays its downstream pulses either - they fire
 *     immediately rather than waiting for an inbound arrival this batch will never carry;
 *   - a flow with multiple inbound pulses is ambiguous and remains entirely immediate rather than choosing
 *     an arbitrary inbound pulse and inventing causal delays;
 *   - a flow's own inbound pulse always starts immediately, since it is the first stage.
 *
 * Returns the same pulses, each with a `startDelayMs` (`0` unless actually sequenced) that the caller uses
 * to pace admission into the animation queue.
 */
export function sequenceFlowPulses(
  pulses,
  {stageDelayMs = null, staggerMs = FLOW_STAGE_STAGGER_MS, maxStaggerSteps = MAX_FLOW_STAGGER_STEPS} = {}
) {
  if (!Array.isArray(pulses) || !pulses.length) return Array.isArray(pulses) ? pulses : []

  const groups = new Map()
  for (const pulse of pulses) {
    const flowId = pulse?.interaction?.flowId
    if (!flowId) continue
    if (!groups.has(flowId)) groups.set(flowId, [])
    groups.get(flowId).push(pulse)
  }

  const delayById = new Map()
  for (const group of groups.values()) {
    const inboundPulses = group.filter((pulse) => pulse.direction === 'INBOUND')
    if (inboundPulses.length !== 1) continue
    const [inbound] = inboundPulses
    const arrival = stageDelayMs ?? inbound.durationMs ?? PULSE_DURATION_OK_MS
    const downstream = [...group].filter((pulse) => pulse !== inbound).sort(compareFlowPulses)
    downstream.forEach((pulse, index) => {
      delayById.set(pulse.id, arrival + staggerMs * Math.min(index, maxStaggerSteps))
    })
  }

  return pulses.map((pulse) => ({...pulse, startDelayMs: delayById.get(pulse.id) ?? 0}))
}

/** Renders one causal step for `describeFlowSequence`'s complete flow narration. */
function describeFlowStep(pulse, nodesById) {
  const endpointId = externalEndpointId(pulse)
  const label = nodesById?.get?.(endpointId)?.label ?? endpointId
  const operation = pulse.interaction?.operation ?? ''
  const slow = pulseTone(pulse.interaction) === 'slow' ? ' slow' : ''
  const failed = pulse.interaction?.outcome === 'FAILED' ? ' failed' : ''
  const duration = pulse.interaction?.durationMs != null ? ` (${pulse.interaction.durationMs} ms)` : ''
  return `${label} ${operation}${slow}${failed}${duration}`.replace(/\s+/g, ' ').trim()
}

/**
 * Narrates every qualifying flow's complete causal chain, for screen-reader users who cannot see the
 * sequenced motion. A "slow" step is called out by name - never by color alone - matching the same
 * non-color labelling used in the map's node detail view.
 *
 * Only flows with at least two pulses in this batch produce a sentence: a single event alone is not a
 * "flow" worth narrating and is already covered by the generic summary in `describeNewEvidence`.
 */
export function describeFlowSequence(pulses, nodesById) {
  if (!Array.isArray(pulses) || !pulses.length) return []
  const groups = new Map()
  for (const pulse of pulses) {
    const flowId = pulse?.interaction?.flowId
    if (!flowId) continue
    if (!groups.has(flowId)) groups.set(flowId, [])
    groups.get(flowId).push(pulse)
  }
  const sentences = []
  for (const group of groups.values()) {
    if (group.length < 2) continue
    const ordered = [...group].sort(compareFlowPulses)
    sentences.push(`Flow: ${ordered.map((pulse) => describeFlowStep(pulse, nodesById)).join(' → ')}.`)
  }
  return sentences
}

/** A short, human sentence describing new evidence, for the map's polite live region. */
export function describeNewEvidence(pulses, nodesById) {
  if (!pulses.length) return ''
  const byEdge = new Map()
  for (const pulse of pulses) {
    byEdge.set(pulse.edgeId, (byEdge.get(pulse.edgeId) ?? 0) + 1)
  }
  const parts = []
  for (const [edgeId, count] of byEdge) {
    const pulse = pulses.find((candidate) => candidate.edgeId === edgeId)
    const endpointId = externalEndpointId(pulse)
    const label = nodesById?.get?.(endpointId)?.label ?? endpointId
    parts.push(`${count} on ${label}`)
  }
  const failures = pulses.filter((pulse) => pulse.tone === 'failed').length
  const slow = pulses.filter((pulse) => pulse.tone === 'slow').length
  // Non-color callouts: "slow" and "failed" are always named, never implied by amber/red alone.
  const notes = []
  if (failures) notes.push(`${failures} failed`)
  if (slow) notes.push(`${slow} slow`)
  const suffix = notes.length ? `, including ${notes.join(', ')}` : ''
  const generic = `New completed interactions: ${parts.join(', ')}${suffix}.`
  const flowSentences = describeFlowSequence(pulses, nodesById)
  return [...flowSentences, generic].join(' ')
}

/**
 * A bounded animation queue.
 *
 * Bursts are coalesced rather than buffered: anything over {@link MAX_CONCURRENT_PULSES} in flight is
 * dropped instead of queued, so motion can never lag behind reality or keep running after traffic
 * stops. Each accepted pulse is admitted to `active` immediately (so the concurrency cap is reserved and
 * `active()` stays an honest picture of everything in flight, sequenced or not) and releases itself after
 * its own `startDelayMs + durationMs` - the delay itself is a purely visual concern the CSS layer owns
 * (the pulse renders invisible for its `startDelayMs`, matching `sequenceFlowPulses`'s causal pacing)
 * rather than a second bookkeeping phase in here, so the queue's bounds and "no stale backlog" guarantee
 * never depend on whether any given pulse happens to be sequenced.
 */
export function createFlowQueue({
  maxConcurrent = MAX_CONCURRENT_PULSES,
  duration = PULSE_DURATION_OK_MS,
  schedule = (callback, delay) => setTimeout(callback, delay),
  cancel = (handle) => clearTimeout(handle)
} = {}) {
  let active = []
  const timers = new Map()
  const listeners = new Set()

  function notify() {
    for (const listener of listeners) listener(active)
  }

  function release(id) {
    const handle = timers.get(id)
    if (handle !== undefined) {
      cancel(handle)
      timers.delete(id)
    }
    const next = active.filter((pulse) => pulse.id !== id)
    if (next.length !== active.length) {
      active = next
      notify()
    }
  }

  return {
    /** Accepts as many pulses as the concurrency cap allows and drops the rest. */
    enqueue(pulses) {
      if (!Array.isArray(pulses) || !pulses.length) return []
      const known = new Set(active.map((pulse) => pulse.id))
      const room = Math.max(0, maxConcurrent - active.length)
      const accepted = pulses.filter((pulse) => !known.has(pulse.id)).slice(0, room)
      if (!accepted.length) return []
      active = [...active, ...accepted]
      for (const pulse of accepted) {
        const startDelayMs = Math.max(0, pulse.startDelayMs ?? 0)
        const pulseDuration = pulse.durationMs ?? duration
        timers.set(
          pulse.id,
          schedule(() => release(pulse.id), startDelayMs + pulseDuration)
        )
      }
      notify()
      return accepted
    },
    active: () => active,
    release,
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    /**
     * Cancels everything in flight. Subscriptions survive on purpose: this is also called when the OS
     * reduced-motion preference is switched on, and dropping the subscriber there would silently freeze
     * the map's motion for the rest of the component's life.
     */
    clear() {
      for (const handle of timers.values()) cancel(handle)
      timers.clear()
      if (active.length) {
        active = []
        notify()
      }
    }
  }
}
