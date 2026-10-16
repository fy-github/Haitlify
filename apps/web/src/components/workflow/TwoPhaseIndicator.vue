<template>
  <div class="two-phase-indicator two-phase-indicator--chips" aria-label="Web AI 两阶段模式">
    <span class="is-primary">阶段 1 · 测试设计</span>
    <span>阶段 2 · 自动化落地</span>
  </div>
</template>

<style scoped>
.two-phase-indicator--chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.two-phase-indicator--chips span {
  border: 1px solid var(--brand-primary-border);
  border-radius: 999px;
  padding: 3px 8px;
  color: var(--text-secondary);
  background: var(--surface-subtle);
  font-size: var(--font-size-xs);
  white-space: nowrap;
}

.two-phase-indicator--chips .is-primary {
  border-color: var(--brand-primary);
  color: var(--brand-primary);
  background: var(--brand-primary-soft);
}

</style>
