<script setup>
import {apiFetch, getJson} from '../api.js'
import {computed, onMounted, ref} from 'vue'
import {useRoute} from 'vue-router'
import {formatBytes, formatClockTime, formatNumber} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useConfirm} from '../utils/useConfirm.js'
import {useEventStreamRefresh} from '../utils/useEventStreamRefresh.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason, manifestAvailable, manifestUnavailableReason} = usePanelState(props)
const {confirm} = useConfirm()
const report = ref(null)
const error = ref(null)
const {message: banner, flash, clear: clearBanner} = useFlashMessage()
const filter = ref('')
const directionFilter = ref('')
const busy = ref(null)
const lastFetched = ref(null)
const tab = ref('endpoints')

async function fetchReport() {
  error.value = null
  try {
    report.value = await getJson('api/websockets')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load WebSocket endpoints')
  }
}

const {autoRefresh, loading, initialLoading, load, retryConnection, connectionState} = useEventStreamRefresh(
  'api/websockets/stream',
  fetchReport,
  {enabled: manifestAvailable, initialLoading: false}
)

const route = useRoute()
onMounted(() => {
  const prefill = route?.query?.q
  if (typeof prefill === 'string' && prefill) {
    filter.value = prefill
  }
})

const stats = computed(() => report.value?.stats ?? null)
const endpoints = computed(() => report.value?.endpoints ?? [])
const sessions = computed(() => report.value?.sessions ?? [])
const subscriptions = computed(() => report.value?.subscriptions ?? [])
const activity = computed(() => report.value?.activity ?? [])

const available = computed(() => manifestAvailable.value && report.value?.available !== false)
const unavailableReason = computed(() => {
  if (!manifestAvailable.value) return manifestUnavailableReason.value
  return report.value?.unavailableReason || 'WebSocket support is not available.'
})

function matches(value, needle) {
  return (value || '').toLowerCase().includes(needle)
}

const filteredEndpoints = computed(() => {
  const value = filter.value.trim().toLowerCase()
  if (!value) return endpoints.value
  return endpoints.value.filter(
    (endpoint) => matches(endpoint.path, value) || matches(endpoint.handlerClass, value) || matches(endpoint.id, value)
  )
})

const filteredSessions = computed(() => {
  const value = filter.value.trim().toLowerCase()
  if (!value) return sessions.value
  return sessions.value.filter(
    (session) => matches(session.path, value) || matches(session.id, value) || matches(session.endpointId, value)
  )
})

const filteredSubscriptions = computed(() => {
  const value = filter.value.trim().toLowerCase()
  if (!value) return subscriptions.value
  return subscriptions.value.filter(
    (subscription) => matches(subscription.destination, value) || matches(subscription.sessionId, value)
  )
})

const filteredActivity = computed(() => {
  const direction = directionFilter.value
  const value = filter.value.trim().toLowerCase()
  return activity.value.filter((entry) => {
    if (direction && entry.direction !== direction) return false
    if (!value) return true
    return (
      matches(entry.destination, value) ||
      matches(entry.sessionId, value) ||
      matches(entry.endpointId, value) ||
      matches(entry.frameType, value)
    )
  })
})

const tabs = computed(() => [
  {id: 'endpoints', label: 'Endpoints', count: endpoints.value.length},
  {id: 'sessions', label: 'Sessions', count: sessions.value.length},
  {id: 'subscriptions', label: 'Subscriptions', count: subscriptions.value.length},
  {id: 'activity', label: 'Activity', count: activity.value.length}
])

const subtitle = computed(() => {
  if (!available.value || !report.value) return null
  const parts = []
  if (report.value.framework) parts.push(report.value.framework)
  const current = stats.value
  if (current) {
    parts.push(`${formatNumber(current.endpoints)} endpoint${current.endpoints === 1 ? '' : 's'}`)
    parts.push(`${formatNumber(current.openSessions)} open`)
  }
  if (report.value.frameCaptureSupported) {
    parts.push(report.value.capturing ? 'capturing' : 'capture paused')
  } else {
    parts.push('metadata only')
  }
  return parts.join(' · ')
})

const filtering = computed(() => filter.value.trim().length > 0)

const activityEmptyMessage = computed(() => {
  if (!report.value?.frameCaptureSupported) {
    return 'This stack exposes no frame capture seam, so no frame metadata is recorded here.'
  }
  if (!activity.value.length) {
    return 'No frame captured yet. Frames appear here as soon as the application exchanges messages.'
  }
  return 'No captured WebSocket frame matches your filter.'
})

const sessionsEmptyMessage = computed(() => {
  if (!report.value?.sessionTrackingSupported) {
    return 'This stack exposes no seam for observing live sessions, so connections are not listed here.'
  }
  if (!sessions.value.length) {
    return 'No session open yet. Connections appear here as soon as a client connects.'
  }
  return 'No WebSocket session matches your filter.'
})

const subscriptionsEmptyMessage = computed(() => {
  if (!report.value?.sessionTrackingSupported) {
    return 'This stack exposes no seam for observing subscriptions, so none are listed here.'
  }
  if (!subscriptions.value.length) {
    return 'No subscription yet. STOMP subscriptions appear here as clients subscribe.'
  }
  return 'No subscription matches your filter.'
})

const endpointsEmptyMessage = computed(() =>
  filtering.value ? 'No WebSocket endpoint matches your filter.' : 'No WebSocket endpoint is declared.'
)

const captureNoticeSuffix = computed(() =>
  report.value?.sessionTrackingSupported
    ? 'Endpoints and live connections below are still reported.'
    : 'Endpoints below are still reported; this stack exposes no seam for listing live sessions.'
)

// Clearing also drops closed-session history, which on a stack without frame capture is the only state
// there is to clear -- so the button must not be disabled just because the activity list is empty.
const clearable = computed(() => activity.value.length > 0 || (stats.value?.closedSessions ?? 0) > 0)

function formatTimestamp(timestamp) {
  if (!timestamp) return '—'
  return formatClockTime(timestamp)
}

function formatOptionalBytes(value) {
  if (value == null) return '—'
  return formatBytes(value)
}

function directionIcon(direction) {
  return direction === 'OUTBOUND' ? 'bi-arrow-up-right text-primary' : 'bi-arrow-down-left text-success'
}

function directionLabel(direction) {
  return direction === 'OUTBOUND' ? 'Outbound' : 'Inbound'
}

function callbackSummary(endpoint) {
  const callbacks = endpoint.callbacks ?? []
  if (!callbacks.length) return '—'
  return callbacks
    .map((callback) => (callback.destination ? `${callback.type} ${callback.destination}` : callback.type))
    .join(', ')
}

async function applyAction(action, options) {
  if (readOnly.value) {
    flash(readOnlyReason.value, 'warning')
    return
  }
  if (options.confirm && !(await confirm(options.confirm))) return
  busy.value = action
  clearBanner()
  try {
    const response = await apiFetch(options.url, options.init)
    const result = await response.json().catch(() => ({}))
    if (!response.ok) {
      flash(result.message || result.error || `HTTP ${response.status}`, 'warning')
      return
    }
    report.value = result
    lastFetched.value = Date.now()
    flash(options.success(), 'success')
  } catch (e) {
    flash(formatLoadError(e, options.failure), 'danger')
  } finally {
    busy.value = null
  }
}

function toggleCapture() {
  const next = !report.value?.capturing
  applyAction('capture', {
    url: 'api/websockets/capture',
    init: {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({enabled: next})},
    success: () => (next ? 'Frame capture resumed.' : 'Frame capture paused; existing activity is kept.'),
    failure: 'Could not change frame capture state'
  })
}

function clearActivity() {
  applyAction('clear', {
    url: 'api/websockets',
    init: {method: 'DELETE'},
    confirm: {
      title: 'Clear captured WebSocket activity?',
      message: 'Clear every captured frame and per-session counter from the in-memory buffer.',
      confirmLabel: 'Clear all',
      danger: true,
      irreversible: true
    },
    success: () => 'Cleared captured WebSocket activity.',
    failure: 'Could not clear captured WebSocket activity'
  })
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-broadcast-pin"
      title="WebSockets"
      :subtitle="subtitle"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      :refreshable="manifestAvailable"
      :auto-refreshable="manifestAvailable"
      v-model:auto-refresh="autoRefresh"
      :auto-refresh-state="connectionState"
      @refresh="load"
      @retry-auto-refresh="retryConnection"
    >
      <template #actions>
        <SpinnerButton
          v-if="report && report.frameCaptureSupported"
          :loading="busy === 'capture'"
          :disabled="!available || readOnly || busy"
          class="ms-2"
          :class="report.capturing ? 'btn btn-sm btn-outline-warning' : 'btn btn-sm btn-outline-success'"
          :icon="report.capturing ? 'bi-pause-fill' : 'bi-record-fill'"
          :label="report.capturing ? 'Pause' : 'Resume'"
          @click="toggleCapture"
        />
        <SpinnerButton
          :loading="busy === 'clear'"
          :disabled="!available || readOnly || busy || !clearable"
          class="btn btn-sm btn-outline-danger ms-2"
          icon="bi-trash"
          label="Clear"
          @click="clearActivity"
        />
      </template>
    </PanelHeader>

    <FlashBanner :message="banner" @dismiss="clearBanner" />

    <PanelSkeleton v-if="initialLoading && manifestAvailable" />

    <template v-else-if="!manifestAvailable || report">
      <div v-if="!available" class="alert alert-warning">
        <strong>WebSockets are unavailable.</strong>
        <span class="d-block small">{{ unavailableReason }}</span>
      </div>

      <template v-else>
        <div v-for="warning in report.warnings" :key="warning" class="alert alert-warning small py-2">
          {{ warning }}
        </div>

        <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason"
          >Frame capture and clearing are read-only.</ReadOnlyNotice
        >

        <div v-if="!report.frameCaptureSupported" class="alert alert-secondary small py-2">
          <i class="bi bi-info-circle me-1" aria-hidden="true"></i>
          {{ report.frameCaptureUnavailableReason || 'Frame capture is not supported on this stack.' }}
          {{ captureNoticeSuffix }}
        </div>
        <div v-else-if="!report.capturing" class="alert alert-secondary small py-2">
          Frame capture is currently paused; frames captured before it was paused remain below.
        </div>

        <p class="text-muted small">
          BootUI records frame metadata only — direction, type, destination, and payload size. Message payloads are
          never read or stored. Frame totals count everything seen since startup, so they keep their value after the
          retained buffer is cleared.
        </p>

        <div v-if="stats" class="row g-3 mb-3">
          <div class="col-6 col-md-3">
            <div class="border rounded p-2 h-100">
              <div class="text-muted small">Open sessions</div>
              <div class="fs-5 fw-semibold">{{ formatNumber(stats.openSessions) }}</div>
            </div>
          </div>
          <div class="col-6 col-md-3">
            <div class="border rounded p-2 h-100">
              <div class="text-muted small">Subscriptions</div>
              <div class="fs-5 fw-semibold">{{ formatNumber(stats.subscriptions) }}</div>
            </div>
          </div>
          <div class="col-6 col-md-3">
            <div class="border rounded p-2 h-100">
              <div class="text-muted small">Inbound frames <span class="text-body-secondary">(total)</span></div>
              <div class="fs-5 fw-semibold">{{ formatNumber(stats.inboundFrames) }}</div>
              <div class="text-muted small">{{ formatBytes(stats.inboundBytes) }}</div>
            </div>
          </div>
          <div class="col-6 col-md-3">
            <div class="border rounded p-2 h-100">
              <div class="text-muted small">Outbound frames <span class="text-body-secondary">(total)</span></div>
              <div class="fs-5 fw-semibold">{{ formatNumber(stats.outboundFrames) }}</div>
              <div class="text-muted small">{{ formatBytes(stats.outboundBytes) }}</div>
            </div>
          </div>
        </div>

        <dl v-if="report.brokerPrefixes.length || report.applicationDestinationPrefixes.length" class="row small mb-3">
          <template v-if="report.applicationDestinationPrefixes.length">
            <dt class="col-sm-3">Application prefixes</dt>
            <dd class="col-sm-9 font-monospace">{{ report.applicationDestinationPrefixes.join(', ') }}</dd>
          </template>
          <template v-if="report.brokerPrefixes.length">
            <dt class="col-sm-3">Broker prefixes</dt>
            <dd class="col-sm-9 font-monospace">{{ report.brokerPrefixes.join(', ') }}</dd>
          </template>
          <template v-if="report.userDestinationPrefix">
            <dt class="col-sm-3">User prefix</dt>
            <dd class="col-sm-9 font-monospace">{{ report.userDestinationPrefix }}</dd>
          </template>
        </dl>

        <div class="mb-3 d-flex gap-2 flex-wrap">
          <input
            v-model="filter"
            class="form-control form-control-sm websockets-filter-input"
            aria-label="Filter WebSocket endpoints, sessions and activity"
            placeholder="Filter by path, destination, handler, or session…"
          />
          <select
            v-if="tab === 'activity'"
            v-model="directionFilter"
            class="form-select form-select-sm websockets-direction-select"
            aria-label="Filter WebSocket activity by direction"
          >
            <option value="">All directions</option>
            <option value="INBOUND">Inbound</option>
            <option value="OUTBOUND">Outbound</option>
          </select>
        </div>

        <ul class="nav nav-tabs mb-3">
          <li v-for="entry in tabs" :key="entry.id" class="nav-item">
            <button
              type="button"
              class="nav-link"
              :class="{active: tab === entry.id}"
              :aria-current="tab === entry.id ? 'page' : undefined"
              @click="tab = entry.id"
            >
              {{ entry.label }}
              <span class="badge text-bg-secondary ms-1">{{ formatNumber(entry.count) }}</span>
            </button>
          </li>
        </ul>

        <div v-if="tab === 'endpoints'" class="table-responsive">
          <div v-if="report.endpointsTruncated" class="alert alert-secondary small py-2">
            Only the first {{ formatNumber(report.maxEndpoints) }} endpoints are shown.
          </div>
          <table class="table table-sm table-hover align-middle">
            <thead>
              <tr>
                <th scope="col">Path</th>
                <th scope="col">Kind</th>
                <th scope="col">Handler</th>
                <th scope="col">Open</th>
                <th scope="col">Capture</th>
                <th scope="col">Callbacks / destinations</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="endpoint in filteredEndpoints" :key="endpoint.id">
                <td class="font-monospace fw-semibold text-truncate websockets-path-cell">
                  {{ endpoint.path || '—' }}
                </td>
                <td>
                  <span class="badge text-bg-secondary">{{ endpoint.kind }}</span>
                  <span v-if="endpoint.sockJs" class="badge text-bg-info ms-1">SockJS</span>
                </td>
                <td class="text-truncate websockets-handler-cell small font-monospace">
                  {{ endpoint.handlerClass || '—' }}
                </td>
                <td>{{ formatNumber(endpoint.openSessions) }}</td>
                <td>
                  <span v-if="endpoint.captureInstalled" class="badge text-bg-success">installed</span>
                  <span v-else class="badge text-bg-secondary" title="No frame capture seam for this endpoint"
                    >metadata</span
                  >
                </td>
                <td class="small text-truncate websockets-callbacks-cell">{{ callbackSummary(endpoint) }}</td>
              </tr>
              <tr v-if="!filteredEndpoints.length">
                <td class="text-center text-muted py-4" colspan="6">
                  {{ endpointsEmptyMessage }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-else-if="tab === 'sessions'" class="table-responsive">
          <div v-if="report.sessionsTruncated" class="alert alert-secondary small py-2">
            Only the first {{ formatNumber(report.maxSessions) }} sessions are shown.
          </div>
          <table class="table table-sm table-hover align-middle">
            <thead>
              <tr>
                <th scope="col">Session</th>
                <th scope="col">Path</th>
                <th scope="col">Opened</th>
                <th scope="col">State</th>
                <th scope="col">In</th>
                <th scope="col">Out</th>
                <th scope="col">Remote</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="session in filteredSessions" :key="session.id">
                <td class="font-monospace small text-truncate websockets-session-cell">{{ session.id }}</td>
                <td class="font-monospace small text-truncate websockets-path-cell">{{ session.path || '—' }}</td>
                <td class="text-muted small text-nowrap">{{ formatTimestamp(session.openedAt) }}</td>
                <td>
                  <span v-if="session.open" class="badge text-bg-success">open</span>
                  <span v-else class="badge text-bg-secondary" :title="`Close status ${session.closeStatus ?? '—'}`"
                    >closed</span
                  >
                </td>
                <td class="text-nowrap small">
                  {{ formatNumber(session.messagesIn) }} / {{ formatBytes(session.bytesIn) }}
                </td>
                <td class="text-nowrap small">
                  {{ formatNumber(session.messagesOut) }} / {{ formatBytes(session.bytesOut) }}
                </td>
                <td class="font-monospace small text-truncate websockets-address-cell">
                  {{ session.remoteAddress || '—' }}
                </td>
              </tr>
              <tr v-if="!filteredSessions.length">
                <td class="text-center text-muted py-4" colspan="7">{{ sessionsEmptyMessage }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-else-if="tab === 'subscriptions'" class="table-responsive">
          <div v-if="report.subscriptionsTruncated" class="alert alert-secondary small py-2">
            Only the first {{ formatNumber(report.maxSubscriptions) }} subscriptions are shown.
          </div>
          <table class="table table-sm table-hover align-middle">
            <thead>
              <tr>
                <th scope="col">Destination</th>
                <th scope="col">Session</th>
                <th scope="col">Subscription</th>
                <th scope="col">Subscribed</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="subscription in filteredSubscriptions" :key="subscription.id">
                <td class="font-monospace fw-semibold text-truncate websockets-path-cell">
                  {{ subscription.destination || '—' }}
                </td>
                <td class="font-monospace small text-truncate websockets-session-cell">{{ subscription.sessionId }}</td>
                <td class="font-monospace small">{{ subscription.id }}</td>
                <td class="text-muted small text-nowrap">{{ formatTimestamp(subscription.subscribedAt) }}</td>
              </tr>
              <tr v-if="!filteredSubscriptions.length">
                <td class="text-center text-muted py-4" colspan="4">{{ subscriptionsEmptyMessage }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-else class="table-responsive">
          <table class="table table-sm table-hover align-middle">
            <thead>
              <tr>
                <th scope="col">Time</th>
                <th scope="col"><span class="visually-hidden">Direction</span></th>
                <th scope="col">Frame</th>
                <th scope="col">Destination</th>
                <th scope="col">Session</th>
                <th scope="col">Size</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="entry in filteredActivity" :key="entry.id">
                <td class="text-muted small text-nowrap">{{ formatTimestamp(entry.timestamp) }}</td>
                <td class="text-center" :title="directionLabel(entry.direction)">
                  <i class="bi" :class="directionIcon(entry.direction)" aria-hidden="true"></i>
                  <span class="visually-hidden">{{ directionLabel(entry.direction) }}</span>
                </td>
                <td>
                  <span class="badge text-bg-secondary">{{ entry.frameType }}</span>
                </td>
                <td class="font-monospace small text-truncate websockets-path-cell">{{ entry.destination || '—' }}</td>
                <td class="font-monospace small text-truncate websockets-session-cell">{{ entry.sessionId || '—' }}</td>
                <td class="text-nowrap small">{{ formatOptionalBytes(entry.payloadBytes) }}</td>
                <td>
                  <span v-if="entry.success" class="badge text-bg-success">ok</span>
                  <span v-else class="badge text-bg-danger">{{ entry.errorCategory || 'error' }}</span>
                </td>
              </tr>
              <tr v-if="!filteredActivity.length">
                <td class="text-center text-muted py-4" colspan="7">
                  {{ activityEmptyMessage }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.websockets-path-cell {
  max-width: 240px;
}

.websockets-handler-cell {
  max-width: 260px;
}

.websockets-callbacks-cell {
  max-width: 320px;
}

.websockets-session-cell {
  max-width: 150px;
}

.websockets-address-cell {
  max-width: 160px;
}

.websockets-filter-input {
  max-width: 360px;
}

.websockets-direction-select {
  max-width: 160px;
}
</style>
