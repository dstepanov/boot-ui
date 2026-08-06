<script setup>
import {computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {apiFetch} from '../api.js'
import {
  conditionClassFromResource,
  mainApplicationBeanName,
  matchingPositiveConditions,
  MAX_BEAN_CONDITIONS,
  useBeanGraph
} from '../utils/useBeanGraph.js'
import {describeLoadError} from '../utils/loadError.js'
import BeanGraph from './BeanGraph.vue'

const props = defineProps({
  focusRequest: {
    type: Object,
    default: null
  },
  bootUiClassificationAvailable: {
    type: Boolean,
    default: true
  }
})

const MAX_CONDITION_SOURCES = 5
const CONDITION_LOOKUP_LIMIT = 1000

const graphFocusInput = ref('')
const graphFocusInputElement = ref(null)
const graphSearchMessage = ref('')
const graphCenterRequest = ref(0)
const graphDatalistId = 'beans-graph-datalist'
const graphClassification = ref('APPLICATION')
const panels = inject('panels', ref(null))
const platform = computed(() => panels.value?.platform || 'spring-boot')
const isQuarkus = computed(() => platform.value === 'quarkus')
const conditionsPanel = computed(() => panels.value?.panels?.find((panel) => panel.id === 'conditions'))

const {
  allBeans,
  beanNames,
  byName,
  definitionsByName,
  error,
  focusName,
  graph,
  inventoryTotal,
  inventoryTruncated,
  loaded,
  loading,
  loadAll,
  reverseIndex,
  setFocus
} = useBeanGraph(graphClassification)

const conditionEntries = ref([])
const conditionError = ref(null)
const conditionLoading = ref(false)
const conditionMessage = ref('')
const conditionLookupTruncated = ref(false)
let conditionRequestId = 0
let appliedFocusRequestId = null

const focusedDefinitions = computed(() => definitionsByName.value.get(focusName.value) || [])
const focusedBean = computed(() => byName.value.get(focusName.value) || null)
const focusBeanNames = computed(() => beanNames.value)
const focusBeanNameSet = computed(() => new Set(focusBeanNames.value))
const directDependencies = computed(
  () =>
    focusedBean.value?.dependencies?.filter((name) => !byName.value.has(name) || focusBeanNameSet.value.has(name))
      .length || 0
)
const directDependents = computed(
  () => [...(reverseIndex.value.get(focusName.value) || [])].filter((name) => focusBeanNameSet.value.has(name)).length
)
const focusClassificationLabel = computed(() =>
  graphClassification.value ? graphClassification.value.toLowerCase() : 'loaded'
)
const inventoryLabel = computed(() => {
  if (!loaded.value) return null
  const loadedLabel = inventoryTruncated.value
    ? `${allBeans.value.length} of ${inventoryTotal.value} loaded`
    : `${allBeans.value.length} loaded`
  return `${focusBeanNames.value.length} ${focusClassificationLabel.value} · ${loadedLabel}`
})

onMounted(async () => {
  await loadInventoryWithDefaultFocus()
})
async function loadInventoryWithDefaultFocus() {
  await loadAll()
  if (props.focusRequest || focusName.value) return
  const defaultFocus = mainApplicationBeanName(allBeans.value)
  if (!defaultFocus || !focusBeanNameSet.value.has(defaultFocus)) return
  graphFocusInput.value = defaultFocus
  selectFocus(defaultFocus)
}
watch(focusName, loadConditionEvidence)
watch(graphClassification, () => {
  graphSearchMessage.value = ''
  if (focusName.value && !focusBeanNameSet.value.has(focusName.value)) {
    graphFocusInput.value = ''
    setFocus(null)
  }
})
watch(
  [loaded, () => props.focusRequest],
  ([isLoaded, request]) => {
    if (!isLoaded || !request || request.id === appliedFocusRequestId) return
    appliedFocusRequestId = request.id
    graphClassification.value = request.classification || ''
    graphFocusInput.value = request.name
    if (byName.value.has(request.name)) {
      selectFocus(request.name)
      graphSearchMessage.value = ''
    } else {
      setFocus(null)
      graphSearchMessage.value = 'This bean is outside the bounded graph inventory.'
    }
    nextTick(() => graphFocusInputElement.value?.focus())
  },
  {immediate: true}
)
onBeforeUnmount(() => {
  conditionRequestId += 1
})

function onFocusInputChange() {
  const trimmed = graphFocusInput.value.trim()
  if (focusBeanNameSet.value.has(trimmed)) {
    selectFocus(trimmed)
    graphSearchMessage.value = ''
  } else if (!trimmed) {
    setFocus(null)
    graphSearchMessage.value = ''
  }
}

function submitGraphSearch() {
  const query = graphFocusInput.value.trim().toLowerCase()
  if (!query) {
    setFocus(null)
    graphSearchMessage.value = ''
    return
  }
  if (focusBeanNameSet.value.has(graphFocusInput.value.trim())) {
    selectFocus(graphFocusInput.value.trim())
    graphSearchMessage.value = ''
    return
  }
  const matches = [...definitionsByName.value]
    .filter(
      ([name, definitions]) =>
        focusBeanNameSet.value.has(name) &&
        (name.toLowerCase().includes(query) ||
          definitions.some(
            (bean) =>
              (bean.type || '').toLowerCase().includes(query) ||
              (bean.aliases || []).some((alias) => alias.toLowerCase().includes(query))
          ))
    )
    .map(([name]) => byName.value.get(name))
  if (matches.length === 1) {
    graphFocusInput.value = matches[0].name
    selectFocus(matches[0].name)
    graphSearchMessage.value = ''
  } else {
    graphSearchMessage.value =
      matches.length === 0
        ? 'No loaded bean matches that name, alias, or type.'
        : 'More than one bean matches. Choose a bean name.'
  }
}

function onNodeFocus(name) {
  graphFocusInput.value = name
  selectFocus(name)
  graphSearchMessage.value = ''
}

function selectFocus(name) {
  setFocus(name)
  graphCenterRequest.value += 1
}

async function retryInventory() {
  await loadInventoryWithDefaultFocus()
}

async function loadConditionEvidence() {
  const requestId = ++conditionRequestId
  conditionEntries.value = []
  conditionError.value = null
  conditionLoading.value = false
  conditionMessage.value = ''
  conditionLookupTruncated.value = false

  if (!focusName.value) return
  if (isQuarkus.value) {
    conditionMessage.value = 'Quarkus does not expose Spring Boot auto-configuration Conditions data.'
    return
  }
  if (conditionsPanel.value?.available === false || conditionsPanel.value?.enabled === false) {
    conditionMessage.value =
      conditionsPanel.value.unavailableReason || 'The Conditions panel is unavailable for this application.'
    return
  }

  const allLookupKeys = [
    ...new Set(focusedDefinitions.value.map((bean) => conditionClassFromResource(bean.resource)).filter(Boolean))
  ]
  if (!allLookupKeys.length) {
    conditionMessage.value = 'No condition source can be established from this bean’s recorded resource.'
    return
  }
  const lookupKeys = allLookupKeys.slice(0, MAX_CONDITION_SOURCES)
  conditionLookupTruncated.value = allLookupKeys.length > lookupKeys.length

  conditionLoading.value = true
  try {
    const evidence = []
    let responseTruncated = false
    for (const key of lookupKeys) {
      const params = new URLSearchParams({
        outcome: 'positive',
        q: key,
        offset: '0',
        limit: String(CONDITION_LOOKUP_LIMIT)
      })
      const response = await apiFetch(`api/conditions?${params}`)
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const report = await response.json()
      evidence.push(...matchingPositiveConditions(report, key, CONDITION_LOOKUP_LIMIT))
      responseTruncated ||= report.page?.hasMore === true
    }
    if (requestId !== conditionRequestId) return

    const distinct = new Map()
    for (const entry of evidence) {
      const key = `${entry.autoConfigurationClass}\u0000${entry.condition}\u0000${entry.message}\u0000${entry.outcome}`
      distinct.set(key, entry)
    }
    const entries = [...distinct.values()]
    conditionEntries.value = entries.slice(0, MAX_BEAN_CONDITIONS)
    conditionLookupTruncated.value ||= responseTruncated || entries.length > MAX_BEAN_CONDITIONS
    if (!conditionEntries.value.length) {
      conditionMessage.value = 'No matching positive auto-configuration condition was reported for this bean.'
    }
  } catch (loadError) {
    if (requestId === conditionRequestId) {
      conditionError.value = describeLoadError(loadError, 'Could not load condition evidence')
    }
  } finally {
    if (requestId === conditionRequestId) conditionLoading.value = false
  }
}
</script>

<template>
  <div class="beans-graph-mode">
    <div v-if="isQuarkus" class="alert alert-info py-2 small" role="note">
      <i class="bi bi-info-circle me-1" aria-hidden="true"></i>
      Quarkus Arc does not expose inter-bean dependency relationships at runtime. Bean search remains available, but
      wiring and Spring Conditions evidence are not.
    </div>
    <div v-if="inventoryTruncated" class="alert alert-info py-2 small" role="status">
      <i class="bi bi-info-circle me-1" aria-hidden="true"></i>
      Loaded the first {{ allBeans.length }} of {{ inventoryTotal }} beans. Search and graph results are limited to this
      bounded inventory.
    </div>

    <div v-if="error" class="alert alert-danger d-flex align-items-start gap-2" role="alert">
      <i class="bi bi-exclamation-triangle-fill flex-shrink-0" aria-hidden="true"></i>
      <span class="flex-grow-1">
        <strong class="d-block">{{ error.title }}</strong>
        <span class="small">{{ error.message }}</span>
      </span>
      <button class="btn btn-outline-danger btn-sm flex-shrink-0" :disabled="loading" @click="retryInventory">
        <i class="bi bi-arrow-clockwise me-1" aria-hidden="true"></i>Retry
      </button>
    </div>

    <div v-if="loading" class="text-center text-muted py-5" role="status" aria-live="polite">
      <i class="bi bi-hourglass-split me-2" aria-hidden="true"></i>Loading bean graph…
    </div>
    <div v-else-if="loaded && allBeans.length === 0" class="graph-empty-state" role="status" aria-live="polite">
      <i class="bi bi-diagram-2 graph-empty-state__icon" aria-hidden="true"></i>
      <h3>No beans available to graph</h3>
      <p>The Beans endpoint returned an empty inventory. The list view may provide more context about availability.</p>
    </div>

    <template v-else-if="loaded && !error">
      <div class="graph-search-row">
        <div>
          <label :for="graphDatalistId + '-input'" class="form-label">Focus bean</label>
          <input
            ref="graphFocusInputElement"
            :id="graphDatalistId + '-input'"
            v-model="graphFocusInput"
            :list="graphDatalistId"
            class="form-control"
            placeholder="Search for a bean to focus…"
            autocomplete="off"
            :aria-describedby="graphSearchMessage ? 'beans-graph-search-message' : undefined"
            @change="onFocusInputChange"
            @input="onFocusInputChange"
            @keydown.enter.prevent="submitGraphSearch"
          />
          <datalist :id="graphDatalistId">
            <option
              v-for="name in focusBeanNames"
              :key="name"
              :value="name"
              :label="byName.get(name)?.type || `${definitionsByName.get(name)?.length || 1} definitions`"
            />
          </datalist>
          <div v-if="graphSearchMessage" id="beans-graph-search-message" class="form-text text-danger" role="status">
            {{ graphSearchMessage }}
          </div>
        </div>
        <div>
          <label for="beans-graph-classification" class="form-label">Bean classification</label>
          <select id="beans-graph-classification" v-model="graphClassification" class="form-select">
            <option value="">All classifications</option>
            <option value="APPLICATION">Application</option>
            <option value="FRAMEWORK">Framework</option>
            <option v-if="props.bootUiClassificationAvailable" value="BOOTUI">BootUI</option>
            <option value="PLATFORM">Platform</option>
            <option value="OTHER">Other</option>
          </select>
        </div>
        <span class="graph-inventory-count">{{ inventoryLabel }}</span>
      </div>

      <div v-if="!focusName && focusBeanNames.length === 0" class="graph-empty-state" role="status" aria-live="polite">
        <i class="bi bi-funnel graph-empty-state__icon" aria-hidden="true"></i>
        <h3>No {{ focusClassificationLabel }} beans available</h3>
        <p>Choose another bean classification to explore the loaded inventory.</p>
      </div>
      <div v-else-if="!focusName" class="graph-empty-state" role="status" aria-live="polite">
        <i class="bi bi-search graph-empty-state__icon" aria-hidden="true"></i>
        <h3>Choose a bean to inspect</h3>
        <p>
          Search by bean name, alias, or type, or change the classification to broaden the available starting points.
        </p>
      </div>

      <div v-else-if="graph" class="graph-workspace">
        <section aria-label="Dependency neighbourhood">
          <BeanGraph
            :graph="graph"
            :by-name="byName"
            :definitions-by-name="definitionsByName"
            :focus-name="focusName"
            :center-request="graphCenterRequest"
            @focus="onNodeFocus"
          />
        </section>

        <aside class="bean-details" aria-labelledby="bean-details-title">
          <div class="bean-details__heading">
            <div>
              <h3 id="bean-details-title">{{ focusName }}</h3>
              <code v-if="focusedBean?.type">{{ focusedBean.type }}</code>
            </div>
            <span class="badge bg-light text-dark">{{ focusedBean?.classification }}</span>
          </div>

          <dl class="bean-details__facts">
            <div>
              <dt>Scope</dt>
              <dd>{{ focusedBean?.scope || 'Unknown' }}</dd>
            </div>
            <div>
              <dt>Dependencies</dt>
              <dd>{{ directDependencies }}</dd>
            </div>
            <div>
              <dt>Dependents</dt>
              <dd>{{ directDependents }}</dd>
            </div>
            <div>
              <dt>Definitions</dt>
              <dd>{{ focusedDefinitions.length }}</dd>
            </div>
          </dl>

          <div v-if="focusedBean?.resource" class="bean-details__section">
            <h4>Recorded resource</h4>
            <code>{{ focusedBean.resource }}</code>
          </div>
          <div v-if="focusedBean?.aliases?.length" class="bean-details__section">
            <h4>Aliases</h4>
            <ul class="bean-details__compact-list">
              <li v-for="alias in focusedBean.aliases" :key="alias">
                <code>{{ alias }}</code>
              </li>
            </ul>
          </div>

          <div class="bean-details__section">
            <div class="bean-details__section-heading">
              <h4>Why this bean exists</h4>
              <span v-if="conditionLoading" class="text-muted small" role="status">Loading…</span>
            </div>
            <div v-if="conditionError" class="alert alert-warning py-2 small mb-2" role="alert">
              <strong class="d-block">{{ conditionError.title }}</strong>
              {{ conditionError.message }}
              <button class="btn btn-outline-warning btn-sm d-block mt-2" @click="loadConditionEvidence">
                <i class="bi bi-arrow-clockwise me-1" aria-hidden="true"></i>Retry evidence
              </button>
            </div>
            <ul v-else-if="conditionEntries.length" class="condition-evidence">
              <li
                v-for="entry in conditionEntries"
                :key="`${entry.autoConfigurationClass}-${entry.condition}-${entry.message}`"
              >
                <span :class="entry.outcome === 'MATCH' ? 'bg-success' : 'bg-warning text-dark'" class="badge">
                  {{ entry.outcome }}
                </span>
                <div>
                  <code>{{ entry.condition }}</code>
                  <p>{{ entry.message }}</p>
                </div>
              </li>
            </ul>
            <p v-else-if="conditionMessage && !conditionLoading" class="text-muted small mb-0" role="status">
              {{ conditionMessage }}
            </p>
            <p v-if="conditionLookupTruncated" class="text-muted small mt-2 mb-0" role="status">
              Condition evidence is bounded; additional matching sources or entries may exist.
            </p>
          </div>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.beans-graph-mode {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.graph-search-row {
  align-items: end;
  display: grid;
  gap: 1rem;
  grid-template-columns: minmax(0, 1fr) minmax(11rem, 14rem) auto;
}

.graph-inventory-count {
  color: var(--bootui-text-muted);
  font-size: 0.85rem;
  padding-bottom: 0.65rem;
  white-space: nowrap;
}

.graph-empty-state {
  align-items: center;
  border: 1px dashed var(--bootui-border-alt);
  border-radius: var(--bootui-radius-lg);
  display: flex;
  flex-direction: column;
  padding: 3rem 1.25rem;
  text-align: center;
}

.graph-empty-state__icon {
  color: var(--bootui-text-muted);
  font-size: 1.15rem;
  margin-bottom: 0.75rem;
}

.graph-empty-state h3 {
  font-size: 1rem;
  margin-bottom: 0.35rem;
}

.graph-empty-state p {
  color: var(--bootui-text-muted);
  margin: 0;
  max-width: 65ch;
}

.graph-workspace {
  align-items: start;
  display: grid;
  gap: 1rem;
  grid-template-columns: minmax(0, 1fr) minmax(17rem, 22rem);
  min-width: 0;
}

.graph-workspace > section {
  min-width: 0;
}

.bean-details {
  background: var(--bootui-surface);
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-lg);
  box-shadow: var(--bootui-shadow-sm);
  min-width: 0;
  padding: 1.1rem;
}

.bean-details__heading,
.bean-details__section-heading {
  align-items: flex-start;
  display: flex;
  gap: 0.75rem;
  justify-content: space-between;
}

.bean-details__heading h3 {
  font-size: 1rem;
  margin: 0 0 0.3rem;
  overflow-wrap: anywhere;
}

.bean-details__heading code,
.bean-details__section code {
  display: block;
  overflow-wrap: anywhere;
}

.bean-details__facts {
  display: grid;
  gap: 0.65rem;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin: 1rem 0;
}

.bean-details__facts div {
  background: var(--bootui-surface-alt);
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-sm);
  padding: 0.65rem;
}

.bean-details__facts dt,
.bean-details__section h4 {
  color: var(--bootui-text-muted);
  font-size: 0.72rem;
  font-weight: 700;
  margin: 0 0 0.25rem;
}

.bean-details__facts dd {
  font-family: var(--bs-font-monospace);
  margin: 0;
}

.bean-details__section {
  border-top: 1px solid var(--bootui-border);
  margin-top: 1rem;
  padding-top: 1rem;
}

.bean-details__compact-list,
.condition-evidence {
  list-style: none;
  margin: 0;
  padding: 0;
}

.condition-evidence {
  display: flex;
  flex-direction: column;
  gap: 0.8rem;
}

.condition-evidence li {
  align-items: flex-start;
  display: grid;
  gap: 0.55rem;
  grid-template-columns: auto minmax(0, 1fr);
}

.condition-evidence code {
  font-size: 0.85rem;
}

.condition-evidence p {
  font-size: 0.85rem;
  margin: 0.2rem 0 0;
}

@media (max-width: 1199.98px) {
  .graph-workspace {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 767.98px) {
  .graph-search-row {
    align-items: stretch;
    grid-template-columns: 1fr;
  }

  .graph-inventory-count {
    padding-bottom: 0;
  }
}
</style>
