<script setup>
import {computed, ref} from 'vue'
import {getJson} from '../api.js'
import {formatNumber} from '../utils/format.js'
import {describeLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason, manifestAvailable, manifestUnavailableReason} = usePanelState(props)

const report = ref(null)
const error = ref(null)
const lastFetched = ref(null)
const enabling = ref(false)
const {message: banner, flash, clear} = useFlashMessage(8000)

async function fetchStatistics() {
  if (!manifestAvailable.value) return
  error.value = null
  try {
    report.value = await getJson('api/hibernate-statistics')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Hibernate session statistics')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchStatistics, {
  enabled: manifestAvailable,
  initialLoading: false
})

const statistics = computed(() => report.value?.statistics ?? null)
const available = computed(() => manifestAvailable.value && report.value?.available !== false)
const enableAvailable = computed(() => manifestAvailable.value && report.value?.enableAvailable === true)
const unavailableReason = computed(() => {
  if (!manifestAvailable.value) return manifestUnavailableReason.value
  return report.value?.unavailableReason || 'Hibernate session statistics are unavailable.'
})

async function enableStatistics() {
  if (readOnly.value) {
    flash(readOnlyReason.value, 'warning')
    return
  }
  enabling.value = true
  try {
    report.value = await getJson('api/hibernate-statistics/enable', {method: 'POST'})
    lastFetched.value = Date.now()
    flash('Hibernate statistics enabled for this runtime. Counters start collecting now.', 'success')
  } catch (e) {
    flash(describeLoadError(e, 'Could not enable Hibernate statistics'), 'danger')
  } finally {
    enabling.value = false
  }
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-graph-up"
      title="Hibernate Statistics"
      subtitle="Live counters from Hibernate's Statistics API for the application's SessionFactory."
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      :refreshable="manifestAvailable"
      :auto-refreshable="manifestAvailable"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <FlashBanner :message="banner" with-icon @dismiss="clear" />

    <ReadOnlyNotice v-if="readOnly && enableAvailable" :reason="readOnlyReason">
      Runtime activation is disabled.
    </ReadOnlyNotice>

    <PanelSkeleton v-if="initialLoading && manifestAvailable" />

    <template v-else-if="!manifestAvailable || report">
      <div v-if="!available" class="alert alert-secondary">
        <strong>Session statistics are unavailable.</strong>
        {{ unavailableReason }}
        <div v-if="enableAvailable" class="mt-3 d-flex flex-wrap align-items-center gap-3">
          <SpinnerButton
            id="enable-hibernate-statistics"
            :loading="enabling"
            :disabled="readOnly || enabling"
            class="btn btn-primary"
            icon="bi-play-circle"
            label="Enable for this runtime"
            loading-label="Enabling…"
            @click="enableStatistics"
          />
          <span class="small text-muted">
            Collection starts now and lasts until this application stops. Configuration files are not changed.
          </span>
        </div>
        <div class="small mt-3">
          To collect from startup, set <code>hibernate.generate_statistics=true</code> (Spring) or
          <code>quarkus.hibernate-orm.statistics=true</code> (Quarkus).
        </div>
      </div>

      <template v-else>
        <div class="row g-3 mb-3">
          <div class="col-lg-6">
            <div class="card h-100">
              <div class="card-header fw-semibold">Sessions &amp; transactions</div>
              <ul class="list-group list-group-flush">
                <li class="list-group-item d-flex justify-content-between">
                  <span>Sessions opened</span>
                  <span class="font-monospace">{{ formatNumber(statistics.sessionOpenCount) }}</span>
                </li>
                <li class="list-group-item d-flex justify-content-between">
                  <span>Sessions closed</span>
                  <span class="font-monospace">{{ formatNumber(statistics.sessionCloseCount) }}</span>
                </li>
                <li class="list-group-item d-flex justify-content-between">
                  <span>Flushes</span>
                  <span class="font-monospace">{{ formatNumber(statistics.flushCount) }}</span>
                </li>
                <li class="list-group-item d-flex justify-content-between">
                  <span>Connections</span>
                  <span class="font-monospace">{{ formatNumber(statistics.connectCount) }}</span>
                </li>
                <li class="list-group-item d-flex justify-content-between">
                  <span>Transactions</span>
                  <span class="font-monospace">{{ formatNumber(statistics.transactionCount) }}</span>
                </li>
                <li class="list-group-item d-flex justify-content-between">
                  <span>Successful transactions</span>
                  <span class="font-monospace">{{ formatNumber(statistics.successfulTransactionCount) }}</span>
                </li>
              </ul>
            </div>
          </div>

          <div class="col-lg-6">
            <div class="card h-100">
              <div class="card-header fw-semibold">Entities</div>
              <ul class="list-group list-group-flush">
                <li class="list-group-item d-flex justify-content-between">
                  <span>Loaded</span>
                  <span class="font-monospace">{{ formatNumber(statistics.entityLoadCount) }}</span>
                </li>
                <li class="list-group-item d-flex justify-content-between">
                  <span>Fetched</span>
                  <span class="font-monospace">{{ formatNumber(statistics.entityFetchCount) }}</span>
                </li>
                <li class="list-group-item d-flex justify-content-between">
                  <span>Inserted</span>
                  <span class="font-monospace">{{ formatNumber(statistics.entityInsertCount) }}</span>
                </li>
                <li class="list-group-item d-flex justify-content-between">
                  <span>Updated</span>
                  <span class="font-monospace">{{ formatNumber(statistics.entityUpdateCount) }}</span>
                </li>
                <li class="list-group-item d-flex justify-content-between">
                  <span>Deleted</span>
                  <span class="font-monospace">{{ formatNumber(statistics.entityDeleteCount) }}</span>
                </li>
              </ul>
            </div>
          </div>
        </div>

        <div class="row g-3 mb-3">
          <div class="col-lg-6">
            <div class="card h-100">
              <div class="card-header fw-semibold">Collections</div>
              <ul class="list-group list-group-flush">
                <li class="list-group-item d-flex justify-content-between">
                  <span>Loaded</span>
                  <span class="font-monospace">{{ formatNumber(statistics.collectionLoadCount) }}</span>
                </li>
                <li class="list-group-item d-flex justify-content-between">
                  <span>Fetched</span>
                  <span class="font-monospace">{{ formatNumber(statistics.collectionFetchCount) }}</span>
                </li>
                <li class="list-group-item d-flex justify-content-between">
                  <span>Recreated</span>
                  <span class="font-monospace">{{ formatNumber(statistics.collectionRecreateCount) }}</span>
                </li>
                <li class="list-group-item d-flex justify-content-between">
                  <span>Updated</span>
                  <span class="font-monospace">{{ formatNumber(statistics.collectionUpdateCount) }}</span>
                </li>
                <li class="list-group-item d-flex justify-content-between">
                  <span>Removed</span>
                  <span class="font-monospace">{{ formatNumber(statistics.collectionRemoveCount) }}</span>
                </li>
              </ul>
            </div>
          </div>

          <div class="col-lg-6">
            <div class="card h-100">
              <div class="card-header fw-semibold">Queries</div>
              <ul class="list-group list-group-flush">
                <li class="list-group-item d-flex justify-content-between">
                  <span>Executions</span>
                  <span class="font-monospace">{{ formatNumber(statistics.queryExecutionCount) }}</span>
                </li>
                <li class="list-group-item d-flex justify-content-between">
                  <span>Slowest execution</span>
                  <span class="font-monospace">{{ formatNumber(statistics.queryExecutionMaxTime) }} ms</span>
                </li>
                <li
                  v-if="statistics.queryExecutionMaxTimeQueryString"
                  class="list-group-item small font-monospace text-break"
                >
                  {{ statistics.queryExecutionMaxTimeQueryString }}
                </li>
                <template v-if="statistics.queryCacheEnabled">
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Query cache hits</span>
                    <span class="font-monospace">{{ formatNumber(statistics.queryCacheHitCount) }}</span>
                  </li>
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Query cache misses</span>
                    <span class="font-monospace">{{ formatNumber(statistics.queryCacheMissCount) }}</span>
                  </li>
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Query cache puts</span>
                    <span class="font-monospace">{{ formatNumber(statistics.queryCachePutCount) }}</span>
                  </li>
                </template>
                <li v-else class="list-group-item text-muted small">
                  Query cache is not in use — no query has been marked cacheable yet, or it is disabled.
                </li>
              </ul>
            </div>
          </div>
        </div>

        <div class="card">
          <div class="card-header fw-semibold">Second-level cache</div>
          <div v-if="!statistics.secondLevelCacheEnabled" class="card-body text-muted small">
            No second-level cache region has recorded activity — it is not configured, or has not been used yet.
          </div>
          <template v-else>
            <ul class="list-group list-group-flush">
              <li class="list-group-item d-flex justify-content-between">
                <span>Total hits</span>
                <span class="font-monospace">{{ formatNumber(statistics.secondLevelCacheHitCount) }}</span>
              </li>
              <li class="list-group-item d-flex justify-content-between">
                <span>Total misses</span>
                <span class="font-monospace">{{ formatNumber(statistics.secondLevelCacheMissCount) }}</span>
              </li>
              <li class="list-group-item d-flex justify-content-between">
                <span>Total puts</span>
                <span class="font-monospace">{{ formatNumber(statistics.secondLevelCachePutCount) }}</span>
              </li>
            </ul>
            <div v-if="statistics.secondLevelCacheRegions.length" class="table-responsive">
              <table class="table table-sm table-hover align-middle mb-0">
                <thead>
                  <tr>
                    <th>Region</th>
                    <th class="text-end">Hits</th>
                    <th class="text-end">Misses</th>
                    <th class="text-end">Puts</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="region in statistics.secondLevelCacheRegions" :key="region.regionName">
                    <td class="font-monospace">{{ region.regionName }}</td>
                    <td class="text-end font-monospace">{{ formatNumber(region.hitCount) }}</td>
                    <td class="text-end font-monospace">{{ formatNumber(region.missCount) }}</td>
                    <td class="text-end font-monospace">{{ formatNumber(region.putCount) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </template>
        </div>
      </template>
    </template>
  </div>
</template>
