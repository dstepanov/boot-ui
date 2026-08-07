<script setup>
import {computed} from 'vue'

const props = defineProps({
  value: {type: Number, required: true},
  min: {type: Number, default: 0},
  max: {type: Number, default: 100},
  label: {type: String, required: true},
  valueText: {type: String, default: null},
  barClass: {type: [String, Array, Object], default: ''},
  minVisiblePercent: {type: Number, default: 0}
})

const normalizedValue = computed(() => Math.min(props.max, Math.max(props.min, props.value)))
const percent = computed(() => {
  if (props.max <= props.min) return 0
  return ((normalizedValue.value - props.min) / (props.max - props.min)) * 100
})
const visiblePercent = computed(() => Math.min(100, Math.max(props.minVisiblePercent, percent.value)))
</script>

<template>
  <div
    :aria-label="label"
    :aria-valuemax="max"
    :aria-valuemin="min"
    :aria-valuenow="normalizedValue"
    :aria-valuetext="valueText"
    class="progress"
    role="progressbar"
  >
    <div :class="barClass" :style="{width: visiblePercent + '%'}" class="progress-bar"></div>
  </div>
</template>
