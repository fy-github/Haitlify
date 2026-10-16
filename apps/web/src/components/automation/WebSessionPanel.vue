<script setup lang="ts">
import { ref, watch } from 'vue'
import { RefreshCcw } from '@lucide/vue'
import { listWebAutomationSessions } from '../../domain/webAutomationRunRepository'
import type { WebAutomationSessionView } from '../../domain/webAutomation'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ 'update:visible': [boolean] }>()

const loading = ref(false)
const sessions = ref<WebAutomationSessionView[]>([])
const error = ref('')

const fetchSessions = async () => {
  loading.value = true
  error.value = ''
  try {
    sessions.value = await listWebAutomationSessions()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '会话列表加载失败'
  } finally {
    loading.value = false
  }
}

const remainingLabel = (expiresAt?: number) => {
  if (!expiresAt) return '—'
  const remaining = Math.max(0, expiresAt - Date.now())
  return `${Math.max(1, Math.ceil(remaining / 60_000))} 分钟`
}

watch(() => props.visible, (visible) => {
  if (visible) void fetchSessions()
}, { immediate: true })
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="Session 状态"
    width="720px"
    :close-on-click-modal="false"
    :teleported="false"
    @update:model-value="(value: boolean) => emit('update:visible', value)"
  >
    <div class="web-session-panel" data-testid="web-session-panel">
      <div class="web-session-panel__toolbar">
        <span class="web-session-panel__count" data-testid="web-session-count">活跃会话 {{ sessions.length }} 个</span>
        <button type="button" class="web-session-panel__refresh" data-testid="web-session-refresh" @click="fetchSessions">
          <RefreshCcw :size="13" /> 刷新
        </button>
      </div>
      <div v-if="loading" class="web-session-panel__state" data-testid="web-session-loading">加载中…</div>
      <div v-else-if="error" class="web-session-panel__error" role="alert" data-testid="web-session-error">{{ error }}</div>
      <el-empty v-else-if="!sessions.length" description="当前没有活跃的 runner 会话" :image-size="44" data-testid="web-session-empty" />
      <table v-else class="web-session-panel__table" data-testid="web-session-table">
        <thead>
          <tr>
            <th>Session</th>
            <th>项目</th>
            <th>环境</th>
            <th>浏览器</th>
            <th>状态</th>
            <th>占用</th>
            <th>剩余有效期</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="session in sessions" :key="session.sessionId" :data-testid="`web-session-row-${session.sessionId}`">
            <td :title="session.sessionId">{{ session.sessionId.length > 18 ? `${session.sessionId.slice(0, 18)}…` : session.sessionId }}</td>
            <td>{{ session.projectId ?? '—' }}</td>
            <td>{{ session.environmentId || '—' }}</td>
            <td>{{ session.browser }}</td>
            <td>{{ session.state }}</td>
            <td>{{ session.leased ? '占用中' : '空闲' }}</td>
            <td>{{ remainingLabel(session.expiresAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </el-dialog>
</template>

<style scoped>
.web-session-panel__toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.web-session-panel__count { color: var(--brand-text-secondary, #5f6b66); font-size: 12px; }
.web-session-panel__refresh { display: inline-flex; align-items: center; gap: 4px; min-height: 28px; padding: 0 10px; color: var(--brand-primary, #0f766e); background: transparent; border: 1px solid rgba(183, 210, 202, 0.82); border-radius: 10px; cursor: pointer; font-size: 12px; }
.web-session-panel__refresh:hover { background: rgba(15, 118, 110, 0.06); }
.web-session-panel__state { padding: 18px 0; color: var(--brand-text-secondary, #5f6b66); font-size: 13px; }
.web-session-panel__error { padding: 12px; color: #b91c1c; font-size: 13px; }
.web-session-panel__table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.web-session-panel__table th, .web-session-panel__table td { padding: 8px 10px; text-align: left; border-bottom: 1px solid rgba(183, 210, 202, 0.5); }
.web-session-panel__table th { color: var(--brand-text-secondary, #5f6b66); font-weight: 600; background: rgba(15, 118, 110, 0.04); }
</style>
