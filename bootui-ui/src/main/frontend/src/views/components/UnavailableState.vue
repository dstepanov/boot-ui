<script setup>
defineProps({
  message: {type: String, default: ''},
  icon: {type: String, default: 'bi-info-circle'},
  variant: {type: String, default: 'secondary'}
})
</script>

<template>
  <div :class="['alert', `alert-${variant}`, 'bootui-empty-state']" role="alert">
    <span class="bootui-empty-state__icon" aria-hidden="true">
      <i :class="['bi', icon]"></i>
    </span>
    <div class="bootui-empty-state__content">
      <slot>{{ message }}</slot>
    </div>
  </div>
</template>

<style scoped>
.bootui-empty-state {
  align-items: flex-start;
  border-color: color-mix(in srgb, currentColor 16%, transparent);
  display: flex;
  gap: 0.85rem;
  margin-bottom: 0;
  padding: 1rem 1.1rem;
}

.bootui-empty-state__icon {
  align-items: center;
  background: color-mix(in srgb, currentColor 10%, transparent);
  border: 1px solid color-mix(in srgb, currentColor 15%, transparent);
  border-radius: var(--bootui-radius-md);
  display: inline-flex;
  flex: 0 0 auto;
  height: 2.25rem;
  justify-content: center;
  margin-top: -0.15rem;
  width: 2.25rem;
}

.bootui-empty-state__content {
  line-height: 1.5;
  min-width: 0;
  padding-top: 0.2rem;
}
</style>
