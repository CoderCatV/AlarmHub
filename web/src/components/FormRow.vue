<script setup lang="ts">
/**
 * One labelled row of the editor form.
 *
 * `stacked` puts the control on its own line, which is what the wide controls (chips, the
 * time wheel, the weekday picker) need; the default side-by-side layout suits switches and
 * short value buttons.
 */
defineProps<{
  label: string
  hint?: string
  stacked?: boolean
}>()
</script>

<template>
  <div class="row" :class="{ 'row--stacked': stacked }">
    <div class="row__text">
      <span class="row__label">{{ label }}</span>
      <span v-if="hint" class="row__hint">{{ hint }}</span>
    </div>
    <div class="row__control">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  min-block-size: 56px;
}

.row--stacked {
  flex-direction: column;
  align-items: stretch;
  gap: var(--sp-3);
}

.row + .row {
  border-block-start: 1px solid var(--border);
}

.row__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-inline-size: 0;
}

.row__label {
  font-size: var(--fs-sm);
}

.row__hint {
  font-size: var(--fs-2xs);
  color: var(--text-faint);
  line-height: 1.5;
}

.row__control {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-shrink: 0;
}

.row--stacked .row__control {
  flex-shrink: 1;
  flex-wrap: wrap;
}
</style>
