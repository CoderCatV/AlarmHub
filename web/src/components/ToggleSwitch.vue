<script setup lang="ts">
const props = defineProps<{
  modelValue: boolean
  disabled?: boolean
  /** Accessible name; the visual label lives outside the control. */
  label?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

function toggle() {
  if (props.disabled) return
  emit('update:modelValue', !props.modelValue)
}
</script>

<template>
  <button
    type="button"
    class="sw"
    :class="{ 'sw--on': modelValue, 'sw--disabled': disabled }"
    role="switch"
    :aria-checked="modelValue"
    :aria-label="label"
    :disabled="disabled"
    @click.stop="toggle"
  >
    <span class="sw__knob" />
  </button>
</template>

<style scoped>
.sw {
  flex: 0 0 auto;
  inline-size: 48px;
  block-size: 28px;
  border-radius: var(--r-pill);
  background: var(--surface-3);
  border: 1px solid var(--border);
  position: relative;
  transition: background var(--dur-base) var(--ease), border-color var(--dur-base) var(--ease);
}

.sw--on {
  background: var(--accent);
  border-color: var(--accent);
}

.sw--disabled {
  opacity: 0.4;
  cursor: default;
}

.sw__knob {
  position: absolute;
  inset-block-start: 2px;
  inset-inline-start: 2px;
  inline-size: 22px;
  block-size: 22px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 3px #0009;
  transition: transform var(--dur-base) var(--ease);
}

.sw--on .sw__knob {
  transform: translateX(20px);
}
</style>
