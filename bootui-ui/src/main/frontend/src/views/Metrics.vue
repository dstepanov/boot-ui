<script setup>
import {computed, inject, onBeforeUnmount, reactive, ref, watch} from 'vue'
import {getJson} from '../api.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ServerListFooter from './components/ServerListFooter.vue'

const METER_PAGE_SIZE = 200
const SAMPLE_PAGE_SIZE = 100

const data = ref(null)
const detail = ref(null)
const error = ref(null)
const detailError = ref(null)
const search = ref('')
const typeFilter = ref('')
const groupFilter = ref('')
const provenanceFilter = ref('')
const explanationFilter = ref('')
const selectedName = ref('')
const selectedTags = ref([])
const selectedStatistic = ref('')
const sampleOffset = ref(0)
const history = ref([])
const lastUpdated = ref(null)
const meterLoading = ref(false)
const loadingMore = ref(false)
const detailLoading = ref(false)

const injectedPanels = inject('panels', null)
const platform = computed(() => injectedPanels?.value?.platform ?? 'spring-boot')
const metricsUnavailableHelp = computed(() =>
  platform.value === 'quarkus'
    ? 'Micrometer metrics are not available. Add a quarkus-micrometer registry (for example quarkus-micrometer-registry-prometheus) to browse live metrics.'
    : 'Micrometer metrics are not available. Add Actuator or a MeterRegistry to browse live metrics.'
)

const meters = computed(() => data.value?.meters ?? [])
const meterPage = computed(() => data.value?.page ?? null)
const groups = computed(() => data.value?.groups ?? [])
const catalogueVersion = computed(() => data.value?.catalogueVersion ?? '')
const selectedGroup = computed(() => groups.value.find((group) => group.id === groupFilter.value) ?? null)
const groupLabels = reactive({})
const groupChips = computed(() => {
  const chips = groups.value.map((group) => ({id: group.id, label: group.label, meterCount: group.meterCount}))
  if (groupFilter.value && !chips.some((chip) => chip.id === groupFilter.value)) {
    chips.push({id: groupFilter.value, label: groupLabels[groupFilter.value] ?? groupFilter.value, meterCount: 0})
  }
  return chips
})
const provenanceFiltered = computed(() =>
  Boolean(groupFilter.value || provenanceFilter.value || explanationFilter.value)
)
const meterTypes = computed(
  () => data.value?.availableTypes ?? [...new Set(meters.value.map((meter) => meter.type).filter(Boolean))].sort()
)
const meterMatched = computed(() => meterPage.value?.matched ?? meters.value.length)
const meterTotal = computed(() => meterPage.value?.total ?? data.value?.total ?? meters.value.length)
const samplePage = computed(() => detail.value?.samplePage ?? null)
const sampleStart = computed(() => (samplePage.value?.returned > 0 ? samplePage.value.offset + 1 : 0))
const sampleEnd = computed(() =>
  samplePage.value ? samplePage.value.offset + samplePage.value.returned : (detail.value?.samples?.length ?? 0)
)

const selectedMeasurement = computed(() => {
  if (!detail.value?.measurements?.length) return null
  return (
    detail.value.measurements.find((measurement) => measurement.statistic === selectedStatistic.value) ||
    detail.value.measurements[0]
  )
})

const chartPath = computed(() => {
  const points = history.value
  if (points.length < 2) return ''
  const values = points.map((point) => point.value)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || 1
  return points
    .map((point, index) => {
      const x = (index / (points.length - 1)) * 100
      const y = 44 - ((point.value - min) / span) * 36
      return `${index === 0 ? 'M' : 'L'} ${x.toFixed(2)} ${y.toFixed(2)}`
    })
    .join(' ')
})

let meterRequestId = 0
let detailRequestId = 0
let filterTimer = null

function formatNumber(value) {
  if (value == null || Number.isNaN(value)) return 'N/A'
  if (Math.abs(value) >= 1000) return value.toLocaleString(undefined, {maximumFractionDigits: 1})
  if (Math.abs(value) >= 1) return value.toLocaleString(undefined, {maximumFractionDigits: 3})
  return value.toLocaleString(undefined, {maximumSignificantDigits: 4})
}

function tagLabel(tag) {
  return `${tag.key}:${tag.value}`
}

const EXPLANATION_LABELS = {
  NATIVE: 'Native description',
  CURATED: 'BootUI catalogue',
  UNKNOWN: 'Not documented'
}

const EXPLANATION_BADGES = {
  NATIVE: 'text-bg-success',
  CURATED: 'text-bg-info',
  UNKNOWN: 'text-bg-secondary'
}

function explanationLabel(source) {
  return EXPLANATION_LABELS[source] ?? EXPLANATION_LABELS.UNKNOWN
}

function explanationBadge(source) {
  return EXPLANATION_BADGES[source] ?? EXPLANATION_BADGES.UNKNOWN
}

function coverageLabel(group) {
  return `${group.describedMeterCount} of ${group.meterCount} documented by the registry`
}

function isGroupSelected(id) {
  return groupFilter.value === id
}

function toggleGroup(id) {
  groupFilter.value = isGroupSelected(id) ? '' : id
}

function clearProvenanceFilters() {
  groupFilter.value = ''
  provenanceFilter.value = ''
  explanationFilter.value = ''
}

function sampleKey(sample) {
  return sample.tags.length ? sample.tags.map(tagLabel).join('|') : 'no-tags'
}

function resetHistory() {
  history.value = []
}

function resetSelection(name) {
  selectedName.value = name
  selectedTags.value = []
  selectedStatistic.value = ''
  sampleOffset.value = 0
  detail.value = null
  detailError.value = null
  resetHistory()
}

function selectMeter(name) {
  if (selectedName.value === name) return
  resetSelection(name)
  void loadDetail()
}

function toggleTag(key, value) {
  const label = `${key}:${value}`
  const exists = selectedTags.value.includes(label)
  selectedTags.value = exists
    ? selectedTags.value.filter((tag) => tag !== label)
    : [...selectedTags.value.filter((tag) => !tag.startsWith(`${key}:`)), label]
  sampleOffset.value = 0
  resetHistory()
  void loadDetail()
}

function isTagSelected(key, value) {
  return selectedTags.value.includes(`${key}:${value}`)
}

function clearTags() {
  selectedTags.value = []
  sampleOffset.value = 0
  resetHistory()
  void loadDetail()
}

function changeStatistic(event) {
  selectedStatistic.value = event.target.value
  resetHistory()
  appendHistoryPoint()
}

function meterParams(offset, limit) {
  const params = new URLSearchParams({offset: String(offset), limit: String(limit)})
  const query = search.value.trim()
  if (query) params.set('q', query)
  if (typeFilter.value) params.set('type', typeFilter.value)
  if (groupFilter.value) params.set('group', groupFilter.value)
  if (provenanceFilter.value) params.set('provenance', provenanceFilter.value)
  if (explanationFilter.value) params.set('explanation', explanationFilter.value)
  return params
}

async function fetchMetrics({append = false, reset = false} = {}) {
  const requestId = ++meterRequestId
  const offset = append ? meters.value.length : 0
  const currentSize = reset ? 0 : meters.value.length
  const limit = append ? METER_PAGE_SIZE : Math.max(METER_PAGE_SIZE, Math.min(currentSize, 1000))
  const requestUrl = `api/metrics?${meterParams(offset, limit)}`

  if (append) loadingMore.value = true
  else meterLoading.value = true
  error.value = null

  try {
    const response = await getJson(requestUrl)
    if (requestId !== meterRequestId) return false

    if (append) {
      const combinedMeters = [...meters.value, ...(response.meters ?? [])]
      data.value = {
        ...response,
        meters: combinedMeters,
        page: response.page ? {...response.page, offset: 0, returned: combinedMeters.length} : response.page
      }
    } else {
      data.value = response
    }
    for (const group of response.groups ?? []) {
      groupLabels[group.id] = group.label
    }
    return true
  } catch (exception) {
    if (requestId === meterRequestId) {
      error.value = describeLoadError(exception, 'Unable to load metrics')
    }
    return false
  } finally {
    if (requestId === meterRequestId) {
      meterLoading.value = false
      loadingMore.value = false
    }
  }
}

function preferredInitialMeter(availableMeters) {
  return (
    availableMeters.find((meter) => meter.name === 'jvm.memory.used')?.name ||
    availableMeters.find((meter) => meter.name === 'process.uptime')?.name ||
    availableMeters[0]?.name ||
    ''
  )
}

function ensureSelection() {
  if (!meters.value.length) {
    if (selectedName.value) resetSelection('')
    return
  }
  if (!meters.value.some((meter) => meter.name === selectedName.value)) {
    resetSelection(preferredInitialMeter(meters.value))
  }
}

async function refreshMetrics(options = {}) {
  if (!(await fetchMetrics(options))) return
  if (!data.value?.metricsAvailable) {
    resetSelection('')
    return
  }
  ensureSelection()
  await loadDetail()
}

function scheduleFilterReload() {
  meterRequestId++
  detailRequestId++
  if (filterTimer) clearTimeout(filterTimer)
  meterLoading.value = true
  filterTimer = setTimeout(() => {
    filterTimer = null
    void refreshMetrics({reset: true})
  }, 250)
}

async function loadMoreMeters() {
  await fetchMetrics({append: true})
}

async function loadDetail() {
  if (!selectedName.value) return
  const requestId = ++detailRequestId
  detailLoading.value = true
  detailError.value = null

  try {
    const params = new URLSearchParams({
      name: selectedName.value,
      offset: String(sampleOffset.value),
      limit: String(SAMPLE_PAGE_SIZE)
    })
    for (const tag of selectedTags.value) {
      params.append('tag', tag)
    }
    const response = await getJson(`api/metrics/detail?${params}`)
    if (requestId !== detailRequestId) return

    if (!response.samples?.length && response.totalSamples > 0 && sampleOffset.value > 0) {
      sampleOffset.value = Math.floor((response.totalSamples - 1) / SAMPLE_PAGE_SIZE) * SAMPLE_PAGE_SIZE
      return await loadDetail()
    }

    detail.value = response
    sampleOffset.value = response.samplePage?.offset ?? 0
    if (!response.measurements.some((measurement) => measurement.statistic === selectedStatistic.value)) {
      selectedStatistic.value = response.measurements[0]?.statistic || ''
      resetHistory()
    }
    appendHistoryPoint()
    lastUpdated.value = new Date()
  } catch (exception) {
    if (requestId === detailRequestId) {
      detailError.value = formatLoadError(exception, 'Unable to load metric details')
    }
  } finally {
    if (requestId === detailRequestId) {
      detailLoading.value = false
    }
  }
}

function previousSamples() {
  if (!samplePage.value || samplePage.value.offset === 0 || detailLoading.value) return
  sampleOffset.value = Math.max(0, samplePage.value.offset - samplePage.value.limit)
  void loadDetail()
}

function nextSamples() {
  if (!samplePage.value?.hasMore || detailLoading.value) return
  sampleOffset.value = samplePage.value.offset + samplePage.value.limit
  void loadDetail()
}

function appendHistoryPoint() {
  if (!selectedMeasurement.value) return
  history.value = [...history.value, {timestamp: Date.now(), value: selectedMeasurement.value.value}].slice(-60)
}

watch([search, typeFilter, groupFilter, provenanceFilter, explanationFilter], scheduleFilterReload)

onBeforeUnmount(() => {
  meterRequestId++
  detailRequestId++
  if (filterTimer) clearTimeout(filterTimer)
})

const {autoRefresh, loading, initialLoading, load: loadMetrics} = useAutoRefresh(refreshMetrics)
const panelLoading = computed(() => loading.value || meterLoading.value || loadingMore.value || detailLoading.value)
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-activity"
      title="Metrics"
      subtitle="Browse meters, filter tag sets, and watch live values update automatically."
      :loading="panelLoading"
      :error="error"
      :last-fetched="lastUpdated ? lastUpdated.getTime() : null"
      v-model:auto-refresh="autoRefresh"
      @refresh="loadMetrics"
    />

    <PanelSkeleton v-if="initialLoading" />

    <div v-else-if="data && !data.metricsAvailable" class="alert alert-warning">
      {{ metricsUnavailableHelp }}
    </div>

    <template v-else-if="data">
      <div v-if="groups.length" class="card mb-3">
        <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
          <div>
            <div class="fw-semibold">Meter provenance</div>
            <div class="text-muted small">
              Meters grouped by the integration that registered them. Every meter belongs to exactly one group.
            </div>
          </div>
          <div class="d-flex align-items-center gap-2">
            <span v-if="catalogueVersion" class="badge text-bg-light">Catalogue {{ catalogueVersion }}</span>
            <button
              v-if="provenanceFiltered"
              class="btn btn-sm btn-outline-secondary"
              type="button"
              @click="clearProvenanceFilters"
            >
              Clear provenance filters
            </button>
          </div>
        </div>
        <div class="card-body">
          <div class="d-flex flex-wrap gap-2" role="group" aria-label="Filter meters by provenance group">
            <button
              v-for="group in groupChips"
              :key="group.id"
              :aria-label="`${group.label}, ${group.meterCount} meters`"
              :aria-pressed="isGroupSelected(group.id)"
              :class="isGroupSelected(group.id) ? 'btn-primary' : 'btn-outline-primary'"
              class="btn btn-sm provenance-chip"
              type="button"
              @click="toggleGroup(group.id)"
            >
              {{ group.label }}
              <span
                :class="isGroupSelected(group.id) ? 'text-bg-light' : 'text-bg-primary'"
                class="badge rounded-pill provenance-count"
              >
                {{ group.meterCount }}
              </span>
            </button>
          </div>

          <div v-if="selectedGroup" class="provenance-detail mt-3">
            <div class="fw-semibold">{{ selectedGroup.label }}</div>
            <div class="text-muted small">
              Contributed by {{ selectedGroup.contributor }} · {{ coverageLabel(selectedGroup) }}
            </div>
            <p class="mb-2 mt-2">{{ selectedGroup.summary }}</p>
            <p v-if="selectedGroup.interpretation" class="text-muted small mb-2">
              <span class="fw-semibold">How to read this group (BootUI catalogue):</span>
              {{ selectedGroup.interpretation }}
            </p>
            <dl class="row mb-0 small">
              <template v-if="selectedGroup.families.length">
                <dt class="col-sm-3">Families</dt>
                <dd class="col-sm-9">{{ selectedGroup.families.join(', ') }}</dd>
              </template>
              <template v-if="selectedGroup.commonTagKeys.length">
                <dt class="col-sm-3">Common tag keys</dt>
                <dd class="col-sm-9">
                  <code v-for="key in selectedGroup.commonTagKeys" :key="key" class="me-2">{{ key }}</code>
                </dd>
              </template>
              <template v-if="selectedGroup.baseUnits.length">
                <dt class="col-sm-3">Base units</dt>
                <dd class="col-sm-9">{{ selectedGroup.baseUnits.join(', ') }}</dd>
              </template>
            </dl>
          </div>

          <div v-else class="table-responsive mt-3">
            <table class="table table-sm mb-0">
              <thead class="table-light">
                <tr>
                  <th scope="col">Group</th>
                  <th scope="col">Contributed by</th>
                  <th scope="col">Meters</th>
                  <th scope="col">Documented by the registry</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="group in groups" :key="group.id">
                  <td>{{ group.label }}</td>
                  <td class="text-muted small">{{ group.contributor }}</td>
                  <td>{{ group.meterCount }}</td>
                  <td>{{ group.describedMeterCount }} / {{ group.meterCount }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <div class="row g-3">
        <div class="col-lg-4">
          <div class="card h-100" :aria-busy="meterLoading">
            <div class="card-header">
              <div class="fw-semibold">Meters</div>
              <div class="text-muted small" aria-live="polite">
                {{ meters.length }} shown · {{ meterMatched }} matched · {{ meterTotal }} total
              </div>
            </div>
            <div class="card-body border-bottom">
              <input
                v-model="search"
                aria-label="Search meters"
                class="form-control form-control-sm mb-2"
                placeholder="Search names and descriptions"
              />
              <select v-model="typeFilter" aria-label="Filter meters by type" class="form-select form-select-sm mb-2">
                <option value="">All meter types</option>
                <option v-for="type in meterTypes" :key="type" :value="type">{{ type }}</option>
              </select>
              <select
                v-model="provenanceFilter"
                aria-label="Filter meters by provenance"
                class="form-select form-select-sm mb-2"
              >
                <option value="">All provenance</option>
                <option value="classified">Known integration</option>
                <option value="unclassified">Application / unclassified</option>
              </select>
              <select
                v-model="explanationFilter"
                aria-label="Filter meters by explanation source"
                class="form-select form-select-sm"
              >
                <option value="">Any explanation</option>
                <option value="NATIVE">Native description</option>
                <option value="CURATED">BootUI catalogue</option>
                <option value="UNKNOWN">Not documented</option>
              </select>
            </div>
            <div class="list-group list-group-flush meter-list">
              <button
                v-for="meter in meters"
                :key="meter.name"
                :class="{active: meter.name === selectedName}"
                class="list-group-item list-group-item-action"
                type="button"
                @click="selectMeter(meter.name)"
              >
                <div class="d-flex justify-content-between align-items-start gap-2">
                  <code class="meter-name">{{ meter.name }}</code>
                  <span class="badge text-bg-light meter-type">{{ meter.type }}</span>
                </div>
                <div v-if="meter.description" class="small text-muted mt-1">{{ meter.description }}</div>
                <div v-if="meter.provenance" class="small text-muted mt-1 meter-provenance">
                  {{ meter.provenance.groupLabel }} ·
                  <span class="meter-source">{{ explanationLabel(meter.provenance.explanationSource) }}</span>
                </div>
              </button>
              <div v-if="!meters.length && meterLoading" class="p-3 text-muted small" role="status">
                Updating meter list…
              </div>
              <div
                v-else-if="!meters.length && (search.trim() || typeFilter || provenanceFiltered)"
                class="p-3 text-muted small"
              >
                No meters match the current server-side filters.
              </div>
              <div v-else-if="!meters.length" class="p-3 text-muted small">No meters are registered yet.</div>
            </div>
            <div v-if="meterPage" class="card-footer">
              <ServerListFooter
                :shown="meters.length"
                :matched="meterMatched"
                :total="meterTotal"
                :page-size="METER_PAGE_SIZE"
                :loading="loadingMore"
                item-label="meters"
                @load-more="loadMoreMeters"
              />
            </div>
          </div>
        </div>

        <div class="col-lg-8">
          <div v-if="detailError" class="alert alert-danger">{{ detailError }}</div>

          <div v-if="detail" class="card mb-3">
            <div class="card-header d-flex flex-wrap justify-content-between align-items-start gap-2">
              <div>
                <code class="fs-6">{{ detail.name }}</code>
                <div v-if="detail.description" class="text-muted small">{{ detail.description }}</div>
              </div>
              <div class="d-flex gap-2">
                <span class="badge text-bg-secondary">{{ detail.type || 'UNKNOWN' }}</span>
                <span v-if="detail.baseUnit" class="badge text-bg-info">{{ detail.baseUnit }}</span>
              </div>
            </div>
            <div class="card-body">
              <div v-if="detail.provenance" class="provenance-detail mb-3">
                <div class="d-flex flex-wrap align-items-center gap-2">
                  <span class="badge" :class="explanationBadge(detail.provenance.explanationSource)">
                    {{ explanationLabel(detail.provenance.explanationSource) }}
                  </span>
                  <span class="small text-muted">
                    {{ detail.provenance.groupLabel }} · contributed by {{ detail.provenance.contributor }}
                    <template v-if="detail.provenance.familyLabel"> · {{ detail.provenance.familyLabel }}</template>
                  </span>
                </div>
                <p v-if="detail.provenance.explanation" class="mb-1 mt-2">{{ detail.provenance.explanation }}</p>
                <p v-else class="mb-1 mt-2 text-muted">
                  No description is registered for this meter and its name matches no known integration, so BootUI does
                  not explain it.
                </p>
                <p v-if="detail.provenance.interpretation" class="text-muted small mb-0">
                  <span class="fw-semibold">How to read this family (BootUI catalogue):</span>
                  {{ detail.provenance.interpretation }}
                </p>
              </div>
              <div class="row g-3 align-items-stretch">
                <div class="col-md-4">
                  <label class="form-label small text-muted" for="metric-statistic">Statistic</label>
                  <select
                    id="metric-statistic"
                    :value="selectedStatistic"
                    class="form-select"
                    @change="changeStatistic"
                  >
                    <option
                      v-for="measurement in detail.measurements"
                      :key="measurement.statistic"
                      :value="measurement.statistic"
                    >
                      {{ measurement.statistic }}
                    </option>
                  </select>
                  <div class="display-6 mt-3">{{ formatNumber(selectedMeasurement?.value) }}</div>
                  <div class="text-muted small">Current {{ selectedStatistic || 'value' }}</div>
                </div>
                <div class="col-md-8">
                  <div class="chart-box">
                    <svg
                      aria-label="Live metric value graph"
                      preserveAspectRatio="none"
                      role="img"
                      viewBox="0 0 100 48"
                    >
                      <line class="chart-axis" x1="0" x2="100" y1="44" y2="44" />
                      <path v-if="chartPath" :d="chartPath" class="chart-line" />
                    </svg>
                    <div v-if="history.length < 2" class="chart-empty text-muted small">
                      Waiting for another sample…
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div v-if="detail" class="card mb-3">
            <div class="card-header d-flex justify-content-between align-items-center">
              <span>Tag filters</span>
              <button
                v-if="selectedTags.length"
                class="btn btn-sm btn-outline-secondary"
                type="button"
                @click="clearTags"
              >
                Clear filters
              </button>
            </div>
            <div class="card-body">
              <div v-if="!detail.availableTags.length" class="text-muted small">This meter has no tags.</div>
              <div v-for="tag in detail.availableTags" :key="tag.key" class="mb-3">
                <div class="fw-semibold small mb-2">{{ tag.key }}</div>
                <div class="d-flex flex-wrap gap-2">
                  <button
                    v-for="value in tag.values"
                    :key="value"
                    :class="isTagSelected(tag.key, value) ? 'btn-primary' : 'btn-outline-primary'"
                    class="btn btn-sm"
                    type="button"
                    @click="toggleTag(tag.key, value)"
                  >
                    {{ value || '(empty)' }}
                  </button>
                  <span v-if="tag.truncated" class="badge text-bg-warning">first 100 shown</span>
                </div>
              </div>
              <div v-if="selectedTags.length" class="small text-muted">
                Active filters: <code v-for="tag in selectedTags" :key="tag" class="me-2">{{ tag }}</code>
              </div>
            </div>
          </div>

          <div v-if="detail" class="card" :aria-busy="detailLoading">
            <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
              <span>Samples</span>
              <span class="text-muted small" aria-live="polite">
                Showing {{ sampleStart }}–{{ sampleEnd }} of {{ detail.totalSamples }}
              </span>
            </div>
            <div class="table-responsive">
              <table class="table table-sm table-hover mb-0">
                <thead class="table-light">
                  <tr>
                    <th>Tags</th>
                    <th>Measurements</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="sample in detail.samples" :key="sampleKey(sample)">
                    <td>
                      <span v-if="!sample.tags.length" class="text-muted">none</span>
                      <span v-for="tag in sample.tags" :key="tagLabel(tag)" class="badge text-bg-light me-1">
                        {{ tag.key }}={{ tag.value || '(empty)' }}
                      </span>
                    </td>
                    <td>
                      <span v-for="measurement in sample.measurements" :key="measurement.statistic" class="me-3">
                        <span class="text-muted me-1">{{ measurement.statistic }}</span>
                        <code>{{ formatNumber(measurement.value) }}</code>
                      </span>
                    </td>
                  </tr>
                  <tr v-if="!detail.samples.length">
                    <td class="text-muted" colspan="2">No samples match the selected tag filters.</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div
              v-if="samplePage"
              class="card-footer d-flex flex-wrap justify-content-between align-items-center gap-2"
            >
              <span class="text-muted small"> Responses are bounded to {{ SAMPLE_PAGE_SIZE }} samples per page. </span>
              <div class="btn-group btn-group-sm" aria-label="Sample pages">
                <button
                  class="btn btn-outline-secondary"
                  type="button"
                  :disabled="samplePage.offset === 0 || detailLoading"
                  @click="previousSamples"
                >
                  Previous
                </button>
                <button
                  class="btn btn-outline-secondary"
                  type="button"
                  :disabled="!samplePage.hasMore || detailLoading"
                  @click="nextSamples"
                >
                  Next
                </button>
              </div>
            </div>
          </div>

          <div v-else-if="detailLoading" class="text-muted" role="status">Loading metric details…</div>
          <div v-else-if="meters.length" class="text-muted">Select a meter to inspect live values.</div>
        </div>
      </div>
    </template>

    <PanelSkeleton v-else :rows="8" />
  </div>
</template>

<style scoped>
.meter-list {
  max-height: 44rem;
  overflow: auto;
}

.meter-name {
  overflow-wrap: anywhere;
}

.provenance-chip {
  align-items: center;
  display: inline-flex;
  gap: 0.4rem;
}

.provenance-count {
  font-variant-numeric: tabular-nums;
}

.provenance-detail {
  background: var(--bootui-surface-alt);
  border: 1px solid var(--bootui-border-alt);
  border-radius: var(--bootui-radius-lg);
  padding: 0.85rem 1rem;
}

.chart-box {
  background: linear-gradient(180deg, rgba(13, 110, 253, 0.08), rgba(25, 135, 84, 0.06));
  border: 1px solid rgba(13, 110, 253, 0.12);
  border-radius: var(--bootui-radius-lg);
  min-height: 12rem;
  padding: 1rem;
  position: relative;
}

.chart-box svg {
  height: 10rem;
  width: 100%;
}

.chart-axis {
  stroke: var(--bootui-border-alt);
  stroke-width: 0.5;
}

.chart-line {
  fill: none;
  stroke: #0d6efd;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 2;
}

.chart-empty {
  left: 1rem;
  position: absolute;
  top: 1rem;
}
</style>
