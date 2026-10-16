<script setup lang="ts">
import { reactive, ref } from 'vue'
import { Layers, Plus, RotateCcw, TriangleAlert } from '@lucide/vue'
import type { ConnectionView, RegisterConnectionRequest } from '../../domain/aiConfigurationRepository'

const props = withDefaults(defineProps<{
  connections?: ConnectionView[] | null
  loading?: boolean
  saving?: boolean
  error?: string
  conflict?: string
}>(), { connections: () => [], loading: false, saving: false, error: '', conflict: '' })

const emit = defineEmits<{
  create: [request: RegisterConnectionRequest]
  refresh: []
}>()

const formOpen = ref(false)
const draft = reactive({
  connectionId: '',
  baseUrl: '',
  credentialRef: '',
  protocol: 'CHAT_COMPLETIONS',
  enabled: true,
  allowedProjectIds: ''
})

const parseProjectIds = (raw: string): number[] | undefined => {
  const ids = raw.split(',').map((part) => part.trim()).filter((part) => part.length > 0).map(Number)
  if (ids.some((id) => !Number.isFinite(id))) return undefined
  return ids.length > 0 ? ids : undefined
}

const submit = () => {
  const allowedProjectIds = parseProjectIds(draft.allowedProjectIds)
  emit('create', {
    connectionId: draft.connectionId.trim(),
    baseUrl: draft.baseUrl.trim(),
    credentialRef: draft.credentialRef.trim(),
    protocol: draft.protocol,
    enabled: draft.enabled,
    allowedProjectIds
  })
}
</script>

<template>
  <section class="ai-connection-registry" aria-label="版本化连接注册表" data-testid="ai-connection-registry">
    <header class="registry-head">
      <Layers :size="16" aria-hidden="true" />
      <strong>版本化连接注册表</strong>
      <span class="registry-hint">只存 URL / 凭据引用（opaque）/ 协议 / 网络策略；版本追加，历史版本可复读</span>
      <el-button size="small" :loading="props.loading" @click="emit('refresh')"><RotateCcw :size="13" aria-hidden="true" />刷新</el-button>
      <el-button type="primary" size="small" data-testid="connection-form-toggle" @click="formOpen = !formOpen"><Plus :size="13" aria-hidden="true" />新增/更新连接</el-button>
    </header>

    <el-alert
      v-if="props.conflict"
      type="warning"
      :closable="false"
      class="registry-conflict"
      data-testid="ai-connection-conflict"
      :title="`版本冲突：${props.conflict}——请刷新后基于最新版本重试`"
    />
    <el-alert v-if="props.error" type="error" :closable="false" data-testid="ai-connection-error" :title="props.error" />

    <div v-if="formOpen" class="registry-form" data-testid="ai-connection-form">
      <el-input v-model="draft.connectionId" size="small" placeholder="连接 ID（如 prod-openai）" data-testid="connection-id" />
      <el-input v-model="draft.baseUrl" size="small" placeholder="Base URL（如 https://api.example.com/v1）" data-testid="connection-base-url" />
      <el-input v-model="draft.credentialRef" size="small" placeholder="凭据引用（opaque，如 alias:prod-key；不在界面出现密钥本体）" data-testid="connection-credential-ref" />
      <el-select v-model="draft.protocol" size="small" data-testid="connection-protocol">
        <el-option label="Chat Completions" value="CHAT_COMPLETIONS" />
        <el-option label="Responses" value="RESPONSES" />
      </el-select>
      <el-input v-model="draft.allowedProjectIds" size="small" placeholder="允许项目 ID（逗号分隔，可空=不限制）" data-testid="connection-projects" />
      <el-switch v-model="draft.enabled" active-text="启用" />
      <el-button type="primary" size="small" :loading="props.saving" data-testid="connection-submit" @click="submit">提交</el-button>
    </div>

    <div class="registry-table-frame">
      <el-table :data="props.connections ?? []" class="platform-ai-table" data-testid="ai-connection-table">
      <el-table-column prop="connectionId" label="连接 ID" min-width="160" />
      <el-table-column label="版本" width="80"><template #default="{ row }">v{{ row.version }}</template></el-table-column>
      <el-table-column prop="protocol" label="协议" width="150" />
      <el-table-column prop="baseUrl" label="Base URL" min-width="200" />
      <el-table-column prop="credentialRef" label="凭据引用" min-width="150" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <span class="platform-ai-status" :class="row.enabled ? 'is-ok' : 'is-off'">{{ row.enabled ? '启用' : '停用' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="允许项目" min-width="120">
        <template #default="{ row }">{{ (row.allowedProjectIds ?? []).join(', ') || '不限制' }}</template>
      </el-table-column>
      </el-table>
    </div>
    <p v-if="(props.connections ?? []).length === 0 && !props.loading" class="registry-empty" data-testid="ai-connection-empty">
      尚无版本化连接——旧配置可经导入器迁移，或在此注册第一条连接。
    </p>
  </section>
</template>

<style scoped>
.ai-connection-registry {
  display: grid;
  gap: 16px;
  margin-top: 24px;
  padding: 20px 22px 22px;
  border: 1px solid var(--border-subtle, #e2eeea);
  border-radius: var(--radius-xl, 16px);
  background: var(--surface-raised, #ffffff);
  box-shadow: var(--shadow-sm, 0 2px 8px rgba(15, 23, 42, 0.04));
}
.registry-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.registry-hint { color: var(--muted-foreground, #6b7280); font-size: 12px; margin-right: auto; line-height: 18px; }
.registry-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px 14px;
  align-items: center;
  padding: 16px 18px;
  border-radius: var(--radius-lg, 12px);
  background: var(--surface-subtle, #ecf5f2);
}
.registry-table-frame {
  border: 1px solid var(--border-subtle, #e2eeea);
  border-radius: var(--radius-lg, 12px);
  overflow: hidden;
}
.registry-empty { color: var(--muted-foreground, #6b7280); font-size: 13px; margin: 0; line-height: 20px; }
:deep(.el-alert) { border-radius: var(--radius-lg, 12px); }
</style>
