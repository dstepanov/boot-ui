<script setup>
import {defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {apiFetch} from '../api.js'
import {isAbortError} from '../utils/loadError.js'
import PanelHeader from './components/PanelHeader.vue'
import {useServerPagedList} from '../utils/useServerPagedList.js'
import ServerListFooter from './components/ServerListFooter.vue'

// Keep the complete graph workspace in its own route-level async chunk.
const BeansGraphMode = defineAsyncComponent(() => import('./BeansGraphMode.vue'))

// ── List mode ──────────────────────────────────────────────────────────────────
const filter = ref('')
const classification = ref('')

const {
  error,
  items: visibleBeans,
  load,
  loadMore,
  loading,
  loadingMore,
  matchedCount,
  pageSize,
  scheduleReload,
  shownCount,
  totalCount
} = useServerPagedList(
  'api/beans',
  'beans',
  () => {
    return {
      q: filter.value.trim(),
      classification: classification.value
    }
  },
  {errorContext: 'Could not load beans'}
)

watch([filter, classification], scheduleReload)

const graphMode = ref(true)
const listActivated = ref(false)
const graphFocusRequest = ref(null)
const bootUiClassificationAvailable = ref(false)
let graphFocusRequestId = 0
let classificationProbe = null

onMounted(loadClassificationAvailability)
onBeforeUnmount(() => classificationProbe?.abort())

async function loadClassificationAvailability() {
  const ac = new AbortController()
  classificationProbe = ac
  try {
    const response = await apiFetch('api/beans?classification=BOOTUI&offset=0&limit=1', {signal: ac.signal})
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const report = await response.json()
    if (classificationProbe === ac) {
      bootUiClassificationAvailable.value = (report.page?.matched ?? report.beans?.length ?? 0) > 0
    }
  } catch (error) {
    if (!isAbortError(error)) {
      console.warn('Could not determine whether BootUI beans are available', error)
      bootUiClassificationAvailable.value = true
    }
  } finally {
    if (classificationProbe === ac) classificationProbe = null
  }
}

function activateGraphMode() {
  graphMode.value = true
}

function deactivateGraphMode() {
  graphMode.value = false
  if (!listActivated.value) {
    listActivated.value = true
    load()
  }
}

function showBeanGraph(bean) {
  graphFocusRequest.value = {
    id: ++graphFocusRequestId,
    name: bean.name,
    classification: bean.classification || ''
  }
  graphMode.value = true
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-diagram-3"
      title="Beans"
      :subtitle="graphMode ? 'Focused dependency neighbourhood' : `${totalCount} beans · ${matchedCount} matched`"
      :error="graphMode ? null : error"
    >
      <template #actions>
        <div class="btn-group btn-group-sm" role="group" aria-label="View mode">
          <button
            :class="['btn', graphMode ? 'btn-outline-secondary' : 'btn-secondary']"
            title="List view"
            aria-label="List view"
            :aria-pressed="!graphMode"
            @click="deactivateGraphMode"
          >
            <i class="bi bi-list-ul" aria-hidden="true"></i>
          </button>
          <button
            :class="['btn', graphMode ? 'btn-secondary' : 'btn-outline-secondary']"
            title="Dependency graph"
            aria-label="Dependency graph"
            :aria-pressed="graphMode"
            @click="activateGraphMode"
          >
            <i class="bi bi-diagram-3" aria-hidden="true"></i>
          </button>
        </div>
      </template>
    </PanelHeader>

    <!-- ── LIST MODE ── -->
    <template v-if="!graphMode">
      <div class="row g-2 mb-3">
        <div class="col-md-8">
          <input v-model="filter" class="form-control" placeholder="Filter by name or type…" />
        </div>
        <div class="col-md-4">
          <select v-model="classification" class="form-select">
            <option value="">All classifications</option>
            <option value="APPLICATION">Application</option>
            <option value="FRAMEWORK">Framework</option>
            <option v-if="bootUiClassificationAvailable" value="BOOTUI">BootUI</option>
            <option value="PLATFORM">Platform</option>
            <option value="OTHER">Other</option>
          </select>
        </div>
      </div>

      <div class="table-responsive">
        <table class="table table-sm table-hover beans-table">
          <colgroup>
            <col class="beans-table-name" />
            <col class="beans-table-type" />
            <col class="beans-table-scope" />
            <col class="beans-table-classification" />
            <col class="beans-table-dependencies" />
          </colgroup>
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Scope</th>
              <th>Class.</th>
              <th>Dependencies</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in visibleBeans" :key="b.name">
              <td>
                <button
                  class="bean-graph-link"
                  type="button"
                  :title="`Show dependency graph for ${b.name}`"
                  :aria-label="`Show dependency graph for ${b.name}`"
                  @click="showBeanGraph(b)"
                >
                  <code class="text-truncate d-block">{{ b.name }}</code>
                </button>
              </td>
              <td>
                <small :title="b.type" class="text-truncate d-block">{{ b.type }}</small>
              </td>
              <td>{{ b.scope }}</td>
              <td>
                <span class="badge bg-light text-dark">{{ b.classification }}</span>
              </td>
              <td>
                <small :title="b.dependencies.join(', ')" class="text-muted text-truncate d-block">{{
                  b.dependencies.join(', ')
                }}</small>
              </td>
            </tr>
            <tr v-if="!loading && matchedCount === 0">
              <td class="text-center text-muted py-4" colspan="5">No beans match your filters.</td>
            </tr>
          </tbody>
        </table>
      </div>
      <ServerListFooter
        v-if="!loading"
        :loading="loadingMore"
        :matched="matchedCount"
        :page-size="pageSize"
        :shown="shownCount"
        :total="totalCount"
        item-label="beans"
        @load-more="loadMore"
      />
    </template>

    <KeepAlive>
      <BeansGraphMode
        v-if="graphMode"
        :focus-request="graphFocusRequest"
        :boot-ui-classification-available="bootUiClassificationAvailable"
      />
    </KeepAlive>
  </div>
</template>

<style scoped>
.beans-table {
  table-layout: fixed;
}

.bean-graph-link {
  background: none;
  border: 0;
  color: var(--bootui-blue);
  display: block;
  max-width: 100%;
  padding: 0;
  text-align: left;
}

.bean-graph-link:hover code {
  text-decoration: underline;
}

.bean-graph-link:focus-visible {
  border-radius: var(--bootui-radius-sm);
  outline: 2px solid var(--bootui-blue);
  outline-offset: 2px;
}

.beans-table-name {
  width: 22%;
}

.beans-table-type {
  width: 34%;
}

.beans-table-scope {
  width: 10%;
}

.beans-table-classification {
  width: 14%;
}

.beans-table-dependencies {
  width: 20%;
}
</style>
