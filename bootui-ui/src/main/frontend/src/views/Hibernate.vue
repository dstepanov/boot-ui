<script setup>
import {computed, ref} from 'vue'
import {getJson} from '../api.js'
import {useAdvisorPanel} from '../utils/useAdvisorPanel.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {formatNumber, formatRelative} from '../utils/format.js'
import {describeLoadError} from '../utils/loadError.js'
import {panelProps} from '../utils/panelState.js'
import AdvisorSummary from './components/AdvisorSummary.vue'
import AutoRefreshToggle from './components/AutoRefreshToggle.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const props = defineProps(panelProps)
const panel = useAdvisorPanel(props, {
  apiPath: 'api/hibernate',
  loadErrorMessage: 'Unable to load Hibernate Advisor report',
  scanErrorMessage: 'Unable to run Hibernate checks',
  emptyScanPrompt: 'Run Hibernate checks to see advisor findings',
  emptyNoFindings: 'No Hibernate Advisor findings',
  countNoun: 'finding'
})

const tab = ref('advisor')
const statistics = ref(null)
const statisticsError = ref(null)
const statisticsLastFetched = ref(null)

async function fetchStatistics() {
  statisticsError.value = null
  try {
    statistics.value = await getJson('api/hibernate/statistics')
    statisticsLastFetched.value = Date.now()
  } catch (e) {
    statisticsError.value = describeLoadError(e, 'Unable to load Hibernate session statistics')
  }
}

const statisticsTabActive = computed(() => tab.value === 'statistics')
const statisticsLastFetchedText = computed(() =>
  statisticsLastFetched.value ? `Updated ${formatRelative(statisticsLastFetched.value)}` : null
)

const {
  autoRefresh: statisticsAutoRefresh,
  loading: statisticsLoading,
  initialLoading: statisticsInitialLoading,
  load: loadStatistics
} = useAutoRefresh(fetchStatistics, {enabled: statisticsTabActive})

function showStatistics() {
  tab.value = 'statistics'
  loadStatistics()
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-database-gear"
      title="Hibernate"
      subtitle="Review mapped JPA entities with bounded Hibernate performance checks."
      :loading="panel.loading"
      :error="panel.error"
    >
      <template #actions>
        <SpinnerButton
          v-if="tab === 'advisor'"
          :loading="panel.loading"
          :disabled="panel.loading || panel.readOnly"
          class="btn btn-primary"
          type="button"
          label="Run Hibernate checks"
          loading-label="Running..."
          @click="panel.runScan"
        />
      </template>
    </PanelHeader>
    <div v-if="tab === 'advisor'" class="alert alert-info d-flex align-items-start gap-2">
      <i class="bi bi-info-circle flex-shrink-0" aria-hidden="true"></i>
      <div>
        Many of those rules are best practices from Vlad Mihalcea, who reviewed the code himself - join him at
        <a href="https://vladmihalcea.com" rel="noopener noreferrer" target="_blank">https://vladmihalcea.com</a>
      </div>
    </div>
    <div v-if="tab === 'advisor' && panel.actionMessage" class="alert alert-warning" role="status" aria-live="polite">
      {{ panel.actionMessage }}
    </div>

    <ul class="nav nav-tabs mb-3" role="tablist">
      <li class="nav-item">
        <button
          id="hibernate-tab-advisor"
          :aria-selected="tab === 'advisor'"
          :class="{active: tab === 'advisor'}"
          :tabindex="tab === 'advisor' ? 0 : -1"
          aria-controls="hibernate-panel-advisor"
          class="nav-link"
          role="tab"
          type="button"
          @click="tab = 'advisor'"
        >
          Advisor
        </button>
      </li>
      <li class="nav-item">
        <button
          id="hibernate-tab-statistics"
          :aria-selected="tab === 'statistics'"
          :class="{active: tab === 'statistics'}"
          :tabindex="tab === 'statistics' ? 0 : -1"
          aria-controls="hibernate-panel-statistics"
          class="nav-link"
          role="tab"
          type="button"
          @click="showStatistics"
        >
          Session Statistics
        </button>
      </li>
    </ul>

    <div
      v-show="tab === 'advisor'"
      id="hibernate-panel-advisor"
      aria-labelledby="hibernate-tab-advisor"
      role="tabpanel"
      tabindex="0"
    >
      <template v-if="panel.report">
        <AdvisorSummary
          :score="panel.score"
          :dismissed-count="panel.dismissedResults.length"
          :scan-status-label="panel.scanStatusLabel(panel.report.scan.status)"
          :scan-status-class="panel.scanStatusBadgeClass(panel.report.scan.status)"
          :scan-time="panel.scanTime()"
          :metrics="[
            {label: 'Rules evaluated', value: panel.report.rulesEvaluated},
            {label: 'Advisor findings', value: panel.report.violationsFound},
            {label: 'Entities analysed', value: panel.report.entitiesAnalyzed}
          ]"
        />
        <div class="alert alert-info">
          <strong>Heuristic Hibernate rules.</strong>
          {{ panel.report.disclaimer }}
          <span v-if="panel.readOnly">Scanning is read-only. {{ panel.readOnlyReason }}</span>
        </div>

        <div class="row g-3 mb-3">
          <div class="col-lg-5">
            <div class="card h-100">
              <div class="card-header fw-semibold">Findings by severity</div>
              <div class="card-body">
                <div v-if="!panel.hasScanData" class="text-center text-muted py-4">
                  <i class="bi bi-search fs-2 d-block mb-2"></i>
                  <div class="fw-semibold text-body">No Hibernate Advisor data yet</div>
                  <div>Run Hibernate checks to populate advisor findings.</div>
                </div>
                <div
                  v-for="item in panel.report.severityCounts"
                  v-else
                  :key="item.severity"
                  class="row align-items-center g-2 mb-2"
                >
                  <div class="col-3">
                    <span :class="panel.severityClass(item.severity)" class="badge">{{ item.severity }}</span>
                  </div>
                  <div class="col">
                    <div :aria-label="`${item.severity} findings: ${item.count}`" class="progress" role="img">
                      <div
                        :class="panel.severityClass(item.severity)"
                        :style="{width: panel.severityWidth(item.count)}"
                        class="progress-bar"
                      ></div>
                    </div>
                  </div>
                  <div class="col-auto small text-muted">{{ item.count }}</div>
                </div>
              </div>
            </div>
          </div>

          <div class="col-lg-7">
            <div class="card h-100">
              <div class="card-header fw-semibold">Entity packages</div>
              <div class="card-body">
                <div v-if="!panel.report.entityPackages || panel.report.entityPackages.length === 0" class="text-muted">
                  No mapped entity package was detected.
                </div>
                <ul v-else class="list-unstyled mb-0">
                  <li v-for="pkg in panel.report.entityPackages" :key="pkg" class="font-monospace small">
                    <i class="bi bi-box me-1"></i>{{ pkg }}
                  </li>
                </ul>
              </div>
            </div>
          </div>
        </div>

        <div class="card">
          <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
            <div>
              <div class="fw-semibold">Rule results</div>
              <div class="text-muted small">
                <template v-if="panel.hasScanData && panel.visibleResults.length > 0">
                  {{ panel.visibleResults.length }}
                  {{ panel.pluralize(panel.visibleResults.length, 'violating rule') }}, sorted by importance
                </template>
                <template v-else>{{ panel.visibleResults.length }} advisor finding(s)</template>
              </div>
            </div>
            <span
              v-if="panel.hasScanData && panel.visibleResults.length === 0 && panel.dismissedResults.length === 0"
              class="badge text-bg-success"
              >No findings</span
            >
          </div>
          <div v-if="panel.visibleResults.length === 0" class="card-body text-center text-muted py-5">
            <i class="bi bi-database-gear fs-2 d-block mb-2"></i>
            <div class="fw-semibold text-body">{{ panel.emptyRuleResultsTitle }}</div>
            <div>Project-specific performance tests and query reviews remain the source of truth.</div>
          </div>
          <div v-else class="list-group list-group-flush">
            <div v-for="result in panel.visibleResults" :key="result.id" class="list-group-item">
              <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
                <span :class="panel.statusClass(result.status)" class="badge">{{ result.status }}</span>
                <span :class="panel.severityClass(result.severity)" class="badge">{{ result.severity }}</span>
                <span class="badge text-bg-light border">{{ result.category }}</span>
                <span class="text-muted small">{{ result.id }}</span>
                <button
                  class="btn btn-sm btn-outline-secondary ms-auto"
                  type="button"
                  :disabled="panel.dismissLoading"
                  @click="panel.dismiss(result.id)"
                  title="Dismiss this rule"
                >
                  <i class="bi bi-eye-slash me-1"></i>Dismiss
                </button>
              </div>
              <h3 class="h6 mb-1">{{ result.name }}</h3>
              <div class="small text-muted mb-2">{{ result.description }}</div>
              <div class="small mb-2">
                <strong>What happened:</strong>
                {{ panel.violationCountLabel(result.violationCount) }} for this rule.
              </div>
              <div v-if="result.sampleViolations && result.sampleViolations.length" class="mb-2">
                <div class="small fw-semibold">
                  Sample details (showing {{ result.sampleViolations.length }} of {{ result.violationCount }})
                </div>
                <ul class="small mb-0">
                  <li v-for="(sample, index) in result.sampleViolations" :key="index" class="font-monospace">
                    {{ sample }}
                  </li>
                </ul>
              </div>
              <div class="small">
                <strong>Recommendation:</strong>
                {{ result.recommendation }}
                <a
                  v-if="result.learnMoreUrl"
                  :href="result.learnMoreUrl"
                  class="ms-1"
                  rel="noopener noreferrer"
                  target="_blank"
                >
                  Learn more
                </a>
              </div>
            </div>
          </div>
          <template v-if="panel.dismissedResults.length > 0">
            <div class="card-header text-muted small">
              <i class="bi bi-eye-slash me-1"></i>Dismissed rules ({{ panel.dismissedResults.length }}) — not counted in
              score
            </div>
            <div class="list-group list-group-flush">
              <div v-for="result in panel.dismissedResults" :key="result.id" class="list-group-item opacity-50">
                <div class="d-flex flex-wrap align-items-center gap-2 mb-1">
                  <span :class="panel.statusClass(result.status)" class="badge">{{ result.status }}</span>
                  <span :class="panel.severityClass(result.severity)" class="badge">{{ result.severity }}</span>
                  <span class="badge text-bg-light border">{{ result.category }}</span>
                  <span class="text-muted small">{{ result.id }}</span>
                  <button
                    class="btn btn-sm btn-outline-secondary ms-auto"
                    type="button"
                    :disabled="panel.dismissLoading"
                    @click="panel.restore(result.id)"
                    title="Restore this rule"
                  >
                    <i class="bi bi-eye me-1"></i>Restore
                  </button>
                </div>
                <div class="small fw-semibold">{{ result.name }}</div>
              </div>
            </div>
          </template>
        </div>
      </template>
    </div>

    <div
      v-show="tab === 'statistics'"
      id="hibernate-panel-statistics"
      aria-labelledby="hibernate-tab-statistics"
      role="tabpanel"
      tabindex="0"
    >
      <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-3">
        <div class="text-muted small">
          Live counters from Hibernate's <code>Statistics</code> API for the application's <code>SessionFactory</code>.
        </div>
        <div class="d-flex align-items-center gap-2">
          <span v-if="statisticsLastFetchedText" class="text-muted small">{{ statisticsLastFetchedText }}</span>
          <button
            class="btn btn-sm btn-outline-secondary"
            type="button"
            :disabled="statisticsLoading"
            @click="loadStatistics"
          >
            <i class="bi bi-arrow-clockwise me-1"></i>Refresh
          </button>
          <AutoRefreshToggle v-model="statisticsAutoRefresh" />
        </div>
      </div>

      <div v-if="statisticsError" class="alert alert-danger">{{ statisticsError }}</div>

      <PanelSkeleton v-else-if="statisticsInitialLoading && !statistics" />

      <template v-else-if="statistics">
        <div v-if="!statistics.available" class="alert alert-secondary">
          <strong>Session statistics are unavailable.</strong>
          {{ statistics.unavailableReason }}
          Set <code>hibernate.generate_statistics=true</code> (Spring) or
          <code>quarkus.hibernate-orm.statistics=true</code> (Quarkus) to enable this panel.
        </div>

        <template v-else>
          <div class="row g-3 mb-3">
            <div class="col-lg-6">
              <div class="card h-100">
                <div class="card-header fw-semibold">Sessions &amp; transactions</div>
                <ul class="list-group list-group-flush">
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Sessions opened</span>
                    <span class="font-monospace">{{ formatNumber(statistics.statistics.sessionOpenCount) }}</span>
                  </li>
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Sessions closed</span>
                    <span class="font-monospace">{{ formatNumber(statistics.statistics.sessionCloseCount) }}</span>
                  </li>
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Flushes</span>
                    <span class="font-monospace">{{ formatNumber(statistics.statistics.flushCount) }}</span>
                  </li>
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Connections</span>
                    <span class="font-monospace">{{ formatNumber(statistics.statistics.connectCount) }}</span>
                  </li>
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Transactions</span>
                    <span class="font-monospace">{{ formatNumber(statistics.statistics.transactionCount) }}</span>
                  </li>
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Successful transactions</span>
                    <span class="font-monospace">{{
                      formatNumber(statistics.statistics.successfulTransactionCount)
                    }}</span>
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
                    <span class="font-monospace">{{ formatNumber(statistics.statistics.entityLoadCount) }}</span>
                  </li>
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Fetched</span>
                    <span class="font-monospace">{{ formatNumber(statistics.statistics.entityFetchCount) }}</span>
                  </li>
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Inserted</span>
                    <span class="font-monospace">{{ formatNumber(statistics.statistics.entityInsertCount) }}</span>
                  </li>
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Updated</span>
                    <span class="font-monospace">{{ formatNumber(statistics.statistics.entityUpdateCount) }}</span>
                  </li>
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Deleted</span>
                    <span class="font-monospace">{{ formatNumber(statistics.statistics.entityDeleteCount) }}</span>
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
                    <span class="font-monospace">{{ formatNumber(statistics.statistics.collectionLoadCount) }}</span>
                  </li>
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Fetched</span>
                    <span class="font-monospace">{{ formatNumber(statistics.statistics.collectionFetchCount) }}</span>
                  </li>
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Recreated</span>
                    <span class="font-monospace">{{
                      formatNumber(statistics.statistics.collectionRecreateCount)
                    }}</span>
                  </li>
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Updated</span>
                    <span class="font-monospace">{{ formatNumber(statistics.statistics.collectionUpdateCount) }}</span>
                  </li>
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Removed</span>
                    <span class="font-monospace">{{ formatNumber(statistics.statistics.collectionRemoveCount) }}</span>
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
                    <span class="font-monospace">{{ formatNumber(statistics.statistics.queryExecutionCount) }}</span>
                  </li>
                  <li class="list-group-item d-flex justify-content-between">
                    <span>Slowest execution</span>
                    <span class="font-monospace"
                      >{{ formatNumber(statistics.statistics.queryExecutionMaxTime) }} ms</span
                    >
                  </li>
                  <li
                    v-if="statistics.statistics.queryExecutionMaxTimeQueryString"
                    class="list-group-item small font-monospace text-break"
                  >
                    {{ statistics.statistics.queryExecutionMaxTimeQueryString }}
                  </li>
                  <template v-if="statistics.statistics.queryCacheEnabled">
                    <li class="list-group-item d-flex justify-content-between">
                      <span>Query cache hits</span>
                      <span class="font-monospace">{{ formatNumber(statistics.statistics.queryCacheHitCount) }}</span>
                    </li>
                    <li class="list-group-item d-flex justify-content-between">
                      <span>Query cache misses</span>
                      <span class="font-monospace">{{ formatNumber(statistics.statistics.queryCacheMissCount) }}</span>
                    </li>
                    <li class="list-group-item d-flex justify-content-between">
                      <span>Query cache puts</span>
                      <span class="font-monospace">{{ formatNumber(statistics.statistics.queryCachePutCount) }}</span>
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
            <div v-if="!statistics.statistics.secondLevelCacheEnabled" class="card-body text-muted small">
              No second-level cache region has recorded activity — it is not configured, or has not been used yet.
            </div>
            <template v-else>
              <ul class="list-group list-group-flush">
                <li class="list-group-item d-flex justify-content-between">
                  <span>Total hits</span>
                  <span class="font-monospace">{{ formatNumber(statistics.statistics.secondLevelCacheHitCount) }}</span>
                </li>
                <li class="list-group-item d-flex justify-content-between">
                  <span>Total misses</span>
                  <span class="font-monospace">{{
                    formatNumber(statistics.statistics.secondLevelCacheMissCount)
                  }}</span>
                </li>
                <li class="list-group-item d-flex justify-content-between">
                  <span>Total puts</span>
                  <span class="font-monospace">{{ formatNumber(statistics.statistics.secondLevelCachePutCount) }}</span>
                </li>
              </ul>
              <div v-if="statistics.statistics.secondLevelCacheRegions.length" class="table-responsive">
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
                    <tr v-for="region in statistics.statistics.secondLevelCacheRegions" :key="region.regionName">
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
  </div>
</template>
