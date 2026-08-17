<script setup>
import {isPlainObject} from '../../utils/format.js'
import HealthDetails from './HealthDetails.vue'

defineProps({
  node: {type: Object, required: true},
  depth: {type: Number, default: 0}
})

const statusClass = (s) =>
  ({
    UP: 'bg-success',
    DOWN: 'bg-danger',
    OUT_OF_SERVICE: 'bg-warning text-dark',
    UNKNOWN: 'bg-secondary',
    DISABLED: 'bg-secondary'
  })[s] || 'bg-secondary'

const statusIcon = (s) =>
  ({
    UP: 'bi-check-circle-fill text-success',
    DOWN: 'bi-x-circle-fill text-danger',
    OUT_OF_SERVICE: 'bi-exclamation-triangle-fill text-warning',
    UNKNOWN: 'bi-question-circle-fill text-secondary',
    DISABLED: 'bi-slash-circle-fill text-secondary'
  })[s] || 'bi-question-circle-fill text-secondary'

const childCount = (node) => (node.components || []).length

function hasDetailValue(value) {
  if (value === null || value === undefined || value === '') return false
  if (Array.isArray(value)) return value.some(hasDetailValue)
  if (isPlainObject(value)) return Object.values(value).some(hasDetailValue)
  return true
}

const detailCount = (node) => {
  if (!hasDetailValue(node.details)) return 0
  if (Array.isArray(node.details)) return node.details.filter(hasDetailValue).length
  if (isPlainObject(node.details)) return Object.values(node.details).filter(hasDetailValue).length
  return 1
}

const hasDetails = (node) => detailCount(node) > 0
</script>

<template>
  <details :open="depth < 2 || node.status !== 'UP'" :class="['health-node', {'health-node--nested': depth > 0}]">
    <summary class="health-node__summary">
      <span class="d-flex align-items-center gap-2 flex-wrap">
        <i :class="statusIcon(node.status)" class="bi"></i>
        <strong class="health-node__name">{{ node.name }}</strong>
        <span v-if="childCount(node)" class="text-muted small">
          {{ childCount(node) }} {{ childCount(node) === 1 ? 'component' : 'components' }}
        </span>
        <span v-if="hasDetails(node)" class="text-muted small">
          {{ detailCount(node) }} {{ detailCount(node) === 1 ? 'detail' : 'details' }}
        </span>
      </span>
      <span :class="statusClass(node.status)" class="badge">{{ node.status }}</span>
    </summary>

    <div v-if="childCount(node) || hasDetails(node)" class="health-node__body">
      <section v-if="hasDetails(node)" class="mb-3">
        <p class="health-node__section-label">Details</p>
        <HealthDetails :value="node.details" />
      </section>

      <section v-if="childCount(node)">
        <p v-if="hasDetails(node)" class="health-node__section-label">Components</p>
        <HealthNode v-for="c in node.components" :key="c.name" :depth="depth + 1" :node="c" />
      </section>
    </div>
  </details>
</template>

<style scoped>
/* The tree is recursive, so this disclosure must never be a `.card` — that would
   nest a card inside a card at every depth. A hairline-bordered disclosure row
   carries the same separation without the stacked chrome. */
.health-node {
  background: var(--bootui-surface);
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-md);
  margin-bottom: 0.5rem;
}

.health-node--nested {
  background: var(--bootui-surface-alt);
}

.health-node:last-child {
  margin-bottom: 0;
}

.health-node__summary {
  align-items: center;
  cursor: pointer;
  display: flex;
  gap: 0.75rem;
  justify-content: space-between;
  padding: 0.6rem 0.85rem;
}

.health-node__summary:focus-visible {
  outline: 2px solid var(--bootui-blue);
  outline-offset: -2px;
}

/* Contributor names come from the health backend, so they read as machine data. */
.health-node__name {
  font-family: var(--bs-font-monospace);
}

.health-node__body {
  border-top: 1px solid var(--bootui-border);
  padding: 0.85rem;
}

.health-node__section-label {
  color: var(--bootui-text-muted);
  font-size: 0.85rem;
  font-weight: 500;
  margin-bottom: 0.5rem;
}
</style>
