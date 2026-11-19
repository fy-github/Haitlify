<template>
  <section class="environment-page" data-testid="environment-management-page">
    <header class="environment-page__head">
      <div>
        <h1>环境配置</h1>
        <p>API 与 Web 运行环境集中管理；执行准备页选择环境，这里维护环境本身。</p>
      </div>
      <div class="environment-page__actions">
        <div class="environment-kind-switch" role="group" aria-label="环境类别" data-testid="environment-kind-switch">
          <button
            type="button"
            :aria-pressed="kind === 'api'"
            @click="emit('update:kind', 'api')"
          >API 环境</button>
          <button
            type="button"
            :aria-pressed="kind === 'web'"
            @click="emit('update:kind', 'web')"
          >Web 环境</button>
        </div>
        <button
          type="button"
          class="environment-page__back"
          data-testid="environment-back-to-preparation"
          @click="emit('back', kind)"
        >返回{{ kind === 'web' ? 'Web' : 'API' }}执行准备</button>
      </div>
    </header>

    <div class="environment-page__body">
      <aside class="environment-page__directory">
        <div class="environment-page__directory-head">
          <strong>环境目录</strong>
          <span>{{ filteredEnvironments.length }} / {{ environments.length }}</span>
        </div>
        <input
          v-model="search"
          class="environment-page__search"
          type="search"
          aria-label="搜索环境"
          placeholder="搜索环境名称或地址"
          data-testid="environment-search"
        />
        <div class="environment-page__directory-list" role="list">
          <button
            v-for="environment in filteredEnvironments"
            :key="environment.id"
            type="button"
            role="listitem"
            class="environment-page__directory-item"
            :class="{ active: environment.id === activeEnvironmentId }"
            :data-testid="'environment-directory-item'"
            :data-environment-id="environment.id"
            @click="emit('select', environment.id)"
          >
            <span>{{ environment.name }}</span>
            <code>{{ environment.baseUrl || '未配置 Base URL' }}</code>
          </button>
          <p
            v-if="filteredEnvironments.length === 0"
            class="environment-page__directory-empty"
            data-testid="environment-directory-empty"
          >没有匹配的环境，请调整搜索词。</p>
        </div>
        <slot name="directory-actions" />
      </aside>

      <div class="environment-page__editor" data-testid="environment-page-editor">
        <slot />
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

const props = defineProps<{
  kind: 'api' | 'web'
  environments: readonly { id: string; name: string; baseUrl?: string }[]
  activeEnvironmentId?: string
}>()

const emit = defineEmits<{
  'update:kind': ['api' | 'web']
  select: [string]
  back: ['api' | 'web']
}>()

const search = ref('')

const filteredEnvironments = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  if (!keyword) return props.environments
  return props.environments.filter((environment) =>
    environment.name.toLowerCase().includes(keyword)
    || (environment.baseUrl || '').toLowerCase().includes(keyword)
  )
})
</script>

<style scoped>
/* 环境配置独立页（仅本页作用域） */
:global(.workspace.is-environment-management) {
  display: block;
  overflow-y: auto;
  overflow-x: hidden;
}

.environment-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 18px clamp(12px, 1.4vw, 20px) 20px;
}

.environment-page__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.environment-page__head h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.6px;
  line-height: 1.35;
}

.environment-page__head p {
  margin: 6px 0 0;
  font-size: 11px;
  color: var(--ink-muted, #6b707a);
}

.environment-page__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.environment-kind-switch {
  display: inline-flex;
  padding: 3px;
  gap: 2px;
  border-radius: 20px;
  background: #f2f4f6;
}

.environment-kind-switch button {
  min-height: 30px;
  padding: 6px 14px;
  border: 0;
  border-radius: 20px;
  background: transparent;
  color: #626a74;
  font-size: 11px;
  cursor: pointer;
}

.environment-kind-switch button[aria-pressed='true'] {
  background: #fff;
  color: #303740;
  box-shadow: 0 1px 3px #333d4910;
}

.environment-page__back {
  min-height: 34px;
  padding: 8px 14px;
  border: 1px solid #e8ebee;
  border-radius: 20px;
  background: #fff;
  color: #3f5a50;
  font-size: 11px;
  cursor: pointer;
}

.environment-page__body {
  display: grid;
  grid-template-columns: minmax(200px, 224px) minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}

.environment-page__directory {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  border: 1px solid #e9ecf0;
  border-radius: 18px;
  background: #fff;
}

.environment-page__directory-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 11px;
  color: #626a74;
}

.environment-page__directory-head strong {
  font-size: 12px;
  color: #303740;
}

.environment-page__search {
  min-height: 32px;
  padding: 6px 10px;
  border: 1px solid #e5e9ed;
  border-radius: 10px;
  background: #f7f8fa;
  font-size: 12px;
  color: #303c3d;
}

.environment-page__search:focus {
  outline: 0;
  border-color: #bdc8c4;
  background: #fafcfb;
}

.environment-page__directory-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 420px;
  overflow-y: auto;
}

.environment-page__directory-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 9px 11px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: #f7f8fa;
  text-align: left;
  font-size: 12px;
  color: #343b45;
  cursor: pointer;
}

.environment-page__directory-item.active {
  border-color: #cfe3da;
  background: #eef6f2;
}

.environment-page__directory-item code {
  font-size: 10px;
  color: #6b707a;
  word-break: break-all;
}

.environment-page__directory-empty {
  margin: 0;
  font-size: 11px;
  color: #6b707a;
}

.environment-page__directory-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.environment-page__editor {
  min-width: 0;
}

@media (max-width: 900px) {
  .environment-page__body {
    grid-template-columns: minmax(0, 1fr);
  }

  .environment-page__directory-list {
    max-height: 240px;
  }
}

</style>
