<script setup>
import {defineAsyncComponent, ref, watch} from 'vue'
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

    <KeepAlive>
      <BeansGraphMode v-if="graphMode" />
    </KeepAlive>
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
