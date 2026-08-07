<script setup>
import {computed} from 'vue'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ProgressBar from './components/ProgressBar.vue'
import UnavailableState from './components/UnavailableState.vue'
import {formatBytes, memoryProgressClass, useMemoryReport} from '../utils/memoryReport.js'
import {useDataState} from '../utils/panelState.js'

const {data, error, lastUpdated, autoRefresh, loading, hasLoaded, load} = useMemoryReport()
const panelState = useDataState({
  loading,
  loaded: hasLoaded,
  error,
  hasData: computed(() => data.value !== null)
})
const staleMessage = {
  text: 'Live memory could not be refreshed. Showing the last successful snapshot.',
  type: 'warning'
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-memory"
      title="Live Memory"
      subtitle="Inspect live JVM heap, non-heap, and memory pool usage."
      :loading="loading"
      :error="panelState.retryableError.value ? error : null"
      :last-fetched="lastUpdated ? lastUpdated.getTime() : null"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <PanelSkeleton v-if="panelState.initialLoading.value" label="Loading live memory…" />

    <template v-else-if="data">
      <FlashBanner v-if="panelState.stale.value" :dismissible="false" :message="staleMessage" with-icon />
      <div class="row g-3 mb-4">
        <div class="col-md-6">
          <div class="card h-100">
            <div class="card-header d-flex align-items-center gap-2">
              <i class="bi bi-stack text-success"></i>
              <strong>Heap Memory</strong>
            </div>
            <div class="card-body">
              <div class="d-flex justify-content-between mb-1">
                <span class="text-muted small">Used</span>
                <span class="fw-semibold">{{ formatBytes(data.heap.usedBytes) }}</span>
              </div>
              <ProgressBar
                :bar-class="memoryProgressClass(data.heap.usedPercent)"
                class="mb-3"
                label="Heap memory used"
                :value="data.heap.usedPercent"
                :value-text="`${data.heap.usedPercent}% of maximum used`"
                style="height: 10px"
              />
              <div class="row text-center g-2">
                <div class="col-4">
                  <div class="text-muted small">Used</div>
                  <div class="fw-semibold">{{ formatBytes(data.heap.usedBytes) }}</div>
                </div>
                <div class="col-4">
                  <div class="text-muted small">Committed</div>
                  <div class="fw-semibold">{{ formatBytes(data.heap.committedBytes) }}</div>
                </div>
                <div class="col-4">
                  <div class="text-muted small">Max</div>
                  <div class="fw-semibold">{{ formatBytes(data.heap.maxBytes) }}</div>
                </div>
              </div>
            </div>
            <div class="card-footer text-muted small">{{ data.heap.usedPercent }}% of max used</div>
          </div>
        </div>

        <div class="col-md-6">
          <div class="card h-100">
            <div class="card-header d-flex align-items-center gap-2">
              <i class="bi bi-cpu text-info"></i>
              <strong>Non-Heap Memory</strong>
            </div>
            <div class="card-body">
              <div class="d-flex justify-content-between mb-1">
                <span class="text-muted small">Used</span>
                <span class="fw-semibold">{{ formatBytes(data.nonHeap.usedBytes) }}</span>
              </div>
              <ProgressBar
                bar-class="bg-info"
                class="mb-3"
                label="Non-heap memory used"
                :value="data.nonHeap.usedPercent"
                :value-text="`${data.nonHeap.usedPercent}% used`"
                style="height: 10px"
              />
              <div class="row text-center g-2">
                <div class="col-4">
                  <div class="text-muted small">Used</div>
                  <div class="fw-semibold">{{ formatBytes(data.nonHeap.usedBytes) }}</div>
                </div>
                <div class="col-4">
                  <div class="text-muted small">Committed</div>
                  <div class="fw-semibold">{{ formatBytes(data.nonHeap.committedBytes) }}</div>
                </div>
                <div class="col-4">
                  <div class="text-muted small">Max</div>
                  <div class="fw-semibold">
                    {{ data.nonHeap.maxBytes < 0 ? 'Unlimited' : formatBytes(data.nonHeap.maxBytes) }}
                  </div>
                </div>
              </div>
            </div>
            <div class="card-footer text-muted small">Metaspace, code cache, and JIT buffers</div>
          </div>
        </div>
      </div>

      <div class="card mb-4">
        <div class="card-header"><i class="bi bi-table me-2"></i>Memory Pools</div>
        <div class="table-responsive">
          <table class="table table-sm table-hover mb-0">
            <thead class="table-light">
              <tr>
                <th>Pool</th>
                <th class="text-end">Used</th>
                <th class="text-end">Committed</th>
                <th class="text-end">Max</th>
                <th style="width: 140px">Usage</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="pool in data.pools" :key="pool.name">
                <td>
                  <code>{{ pool.name }}</code>
                </td>
                <td class="text-end">{{ formatBytes(pool.usedBytes) }}</td>
                <td class="text-end">{{ formatBytes(pool.committedBytes) }}</td>
                <td class="text-end">{{ pool.maxBytes < 0 ? '∞' : formatBytes(pool.maxBytes) }}</td>
                <td>
                  <div class="d-flex align-items-center gap-2">
                    <ProgressBar
                      :bar-class="memoryProgressClass(pool.usedPercent)"
                      class="flex-grow-1"
                      :label="`${pool.name} memory pool used`"
                      :value="pool.usedPercent"
                      :value-text="`${pool.usedPercent}% used`"
                      style="height: 6px"
                    />
                    <span class="text-muted small" style="width: 32px; text-align: right">{{ pool.usedPercent }}%</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>

    <UnavailableState
      v-else-if="panelState.unavailable.value"
      message="Live memory data is unavailable. Retry or refresh this panel."
    />
  </div>
</template>
