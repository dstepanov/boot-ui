<script setup>
import {computed} from 'vue'

const props = defineProps({
  // Transient message: {text, type}. When null the banner renders nothing.
  message: {type: Object, default: null},
  // When true, prefix the text with a contextual icon chosen from the message type.
  withIcon: {type: Boolean, default: false}
})

defineEmits(['dismiss'])

const TYPE_ICONS = {
  success: 'bi-check-circle-fill',
  danger: 'bi-exclamation-triangle-fill',
  warning: 'bi-exclamation-triangle-fill',
  info: 'bi-info-circle-fill'
}

const iconClass = computed(() => {
  if (!props.withIcon || !props.message) return null
  return TYPE_ICONS[props.message.type] || 'bi-info-circle-fill'
})

const liveRole = computed(() => (['danger', 'warning'].includes(props.message?.type) ? 'alert' : 'status'))
</script>

<template>
  <div
    :aria-live="liveRole === 'status' ? 'polite' : undefined"
    :class="
      message ? ['alert', 'alert-' + message.type, 'd-flex', 'justify-content-between', 'align-items-center'] : []
    "
    :role="liveRole"
    aria-atomic="true"
  >
    <template v-if="message">
      <div>
        <i v-if="iconClass" :class="['bi', iconClass]" aria-hidden="true"></i>
        <span :class="{'ms-2': iconClass}">{{ message.text }}</span>
      </div>
      <button class="btn-close" type="button" aria-label="Dismiss" @click="$emit('dismiss')"></button>
    </template>
  </div>
</template>
