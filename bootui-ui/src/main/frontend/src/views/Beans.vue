<script setup>
import {defineAsyncComponent, onMounted, ref, watch} from 'vue'
import PanelHeader from './components/PanelHeader.vue'
import {useServerPagedList} from '../utils/useServerPagedList.js'
import ServerListFooter from './components/ServerListFooter.vue'
import {useBeanGraph} from '../utils/useBeanGraph.js'

// Lazy-load the graph visualization so the list view is unaffected on initial render.
const BeanGraph = defineAsyncComponent(() => import('./BeanGraph.vue'))

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

onMounted(load)
watch([filter, classification], scheduleReload)

// ── Graph mode ─────────────────────────────────────────────────────────────────
const graphMode = ref(false)
const graphFocusInput = ref('')
const graphDatalistId = 'beans-graph-datalist'

const {
  allBeans,
  beanNames,
  byName,
  error: graphError,
  focusName,
  graph,
  loading: graphLoading,
  loadAll,
  setFocus
} = useBeanGraph()

async function activateGraphMode() {
  graphMode.value = true
  await loadAll()
}

function deactivateGraphMode() {
  graphMode.value = false
}

function onFocusInputChange() {
  const trimmed = graphFocusInput.value.trim()
  if (byName.value.has(trimmed)) {
    setFocus(trimmed)
  }
}

function onNodeFocus(name) {
  graphFocusInput.value = name
  setFocus(name)
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-diagram-3"
      title="Beans"
      :subtitle="graphMode ? `${allBeans.length} beans loaded` : `${totalCount} beans · ${matchedCount} matched`"
      :error="graphMode ? graphError : error"
    >
      <template #actions>
        <div class="btn-group btn-group-sm" role="group" aria-label="View mode">
          <button
            :class="['btn', graphMode ? 'btn-outline-secondary' : 'btn-secondary']"
            title="List view"
            aria-label="List view"
            @click="deactivateGraphMode"
          >
            <i class="bi bi-list-ul" aria-hidden="true"></i>
          </button>
          <button
            :class="['btn', graphMode ? 'btn-secondary' : 'btn-outline-secondary']"
            title="Dependency graph"
            aria-label="Dependency graph"
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
            <option value="BOOTUI">BootUI</option>
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
                <code :title="b.name" class="text-truncate d-block">{{ b.name }}</code>
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

    <!-- ── GRAPH MODE ── -->
    <template v-else>
      <!-- Focus-bean search -->
      <div class="row g-2 mb-3">
        <div class="col-md-10">
          <label :for="graphDatalistId + '-input'" class="visually-hidden">Focus bean</label>
          <input
            :id="graphDatalistId + '-input'"
            v-model="graphFocusInput"
            :list="graphDatalistId"
            class="form-control"
            placeholder="Search for a bean to focus…"
            autocomplete="off"
            @change="onFocusInputChange"
            @input="onFocusInputChange"
          />
          <datalist :id="graphDatalistId">
            <option v-for="name in beanNames" :key="name" :value="name" />
          </datalist>
        </div>
        <div class="col-md-2 d-flex align-items-center">
          <span v-if="graphLoading" class="text-muted small">
            <i class="bi bi-hourglass-split me-1" aria-hidden="true"></i>Loading…
          </span>
          <span v-else-if="allBeans.length" class="text-muted small">{{ allBeans.length }} beans</span>
        </div>
      </div>

      <!-- Empty / loading state -->
      <div v-if="graphLoading" class="text-center text-muted py-5" role="status" aria-live="polite">
        <i class="bi bi-hourglass-split me-2" aria-hidden="true"></i>Loading bean graph…
      </div>
      <div v-else-if="!focusName" class="text-center text-muted py-5">
        <i class="bi bi-search me-2" aria-hidden="true"></i>
        Search for a bean above to explore its dependency neighbourhood.
      </div>

      <!-- Graph visualization -->
      <BeanGraph v-else-if="graph" :graph="graph" :by-name="byName" :focus-name="focusName" @focus="onNodeFocus" />
    </template>
  </div>
</template>

<style scoped>
.beans-table {
  table-layout: fixed;
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
