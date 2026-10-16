<script setup lang="ts">
import { reactive, ref } from 'vue'
import { BadgeCheck, Plus, RotateCcw } from '@lucide/vue'
import type { ConnectionView, ProfileView, RegisterProfileRequest } from '../../domain/aiConfigurationRepository'

const props = withDefaults(defineProps<{
  profiles?: ProfileView[] | null
  connections?: ConnectionView[] | null
  loading?: boolean
  saving?: boolean
  error?: string
  conflict?: string
}>(), { profiles: () => [], connections: () => [], loading: false, saving: false, error: '', conflict: '' })

const emit = defineEmits<{
  create: [request: RegisterProfileRequest]
  refresh: []
}>()

/** 能力目录（与后端枚举一致）——未知能力名不可声明（fail-closed）。 */
const capabilityOptions = ['TEXT', 'JSON_OBJECT', 'JSON_SCHEMA', 'IMAGE_INPUT', 'TOOL_CALLS', 'REASONING', 'STREAMING']

const formOpen = ref(false)
const draft = reactive({
  profileId: '',
  connectionId: '',
  modelId: '',
  capabilities: [] as string[],
  parameterPolicyJson: ''
})

const verificationLabel = (raw: string): string => {
  try {
    const parsed = JSON.parse(raw || '{}') as { status?: string }
    const status = String(parsed.status ?? 'UNKNOWN').toUpperCase()
    if (status === 'VERIFIED') return '已验证'
    if (status === 'DECLARED') return '已声明（未验证）'
    if (status === 'UNSUPPORTED') return '不支持'
    return '未验证'
  } catch {
    return '未验证'
  }
}

const submit = () => {
  emit('create', {
    profileId: draft.profileId.trim(),
    connectionId: draft.connectionId,
    modelId: draft.modelId.trim(),
    capabilities: [...draft.capabilities],
    parameterPolicyJson: draft.parameterPolicyJson.trim() || undefined
  })
}
</script>

<template>
  <section class="ai-profile-registry" aria-label="版本化模型档案注册表" data-testid="ai-profile-registry">
    <header class="registry-head">
      <BadgeCheck :size="16" aria-hidden="true" />
      <strong>模型档案注册表</strong>
      <span class="registry-hint">模型 / 能力声明 / 参数策略——未声明字段不发送；能力验证状态单独展示</span>
      <el-button size="small" :loading="props.loading" @click="emit('refresh')"><RotateCcw :size="13" aria-hidden="true" />刷新</el-button>
      <el-button type="primary" size="small" data-testid="profile-form-toggle" @click="formOpen = !formOpen"><Plus :size="13" aria-hidden="true" />新增/更新档案</el-button>
    </header>

    <el-alert
      v-if="props.conflict"
      type="warning"
      :closable="false"
      data-testid="ai-profile-conflict"
      :title="`版本冲突：${props.conflict}——请刷新后基于最新版本重试`"
    />
    <el-alert v-if="props.error" type="error" :closable="false" data-testid="ai-profile-error" :title="props.error" />

    <div v-if="formOpen" class="registry-form" data-testid="ai-profile-form">
      <el-input v-model="draft.profileId" size="small" placeholder="档案 ID（如 prod-openai-text）" data-testid="profile-id" />
      <el-select v-model="draft.connectionId" size="small" placeholder="选择连接" data-testid="profile-connection">
        <el-option v-for="connection in props.connections ?? []" :key="connection.connectionId" :label="`${connection.connectionId}（v${connection.version}）`" :value="connection.connectionId" />
      </el-select>
      <el-input v-model="draft.modelId" size="small" placeholder="模型 ID" data-testid="profile-model" />
      <el-checkbox-group v-model="draft.capabilities" data-testid="profile-capabilities">
        <el-checkbox v-for="capability in capabilityOptions" :key="capability" :value="capability" :label="capability" />
      </el-checkbox-group>
      <el-input v-model="draft.parameterPolicyJson" size="small" type="textarea" :rows="2" placeholder='参数策略 JSON（如 {"temperature": 0}；留空=不发送）' data-testid="profile-parameters" />
      <el-button type="primary" size="small" :loading="props.saving" data-testid="profile-submit" @click="submit">提交</el-button>
    </div>

    <div class="registry-table-frame">
      <el-table :data="props.profiles ?? []" class="platform-ai-table" data-testid="ai-profile-table">
      <el-table-column prop="profileId" label="档案 ID" min-width="160" />
      <el-table-column label="版本" width="80"><template #default="{ row }">v{{ row.version }}</template></el-table-column>
      <el-table-column prop="connectionId" label="连接" min-width="130" />
      <el-table-column prop="modelId" label="模型" min-width="140" />
      <el-table-column label="能力声明" min-width="180">
        <template #default="{ row }">{{ (row.capabilities ?? []).join(', ') || '未声明（不发送可选参数）' }}</template>
      </el-table-column>
      <el-table-column label="验证状态" width="130">
        <template #default="{ row }"><span data-testid="profile-verification">{{ verificationLabel(row.verificationJson) }}</span></template>
      </el-table-column>
    </el-table>
    </div>
    <p v-if="(props.profiles ?? []).length === 0 && !props.loading" class="registry-empty" data-testid="ai-profile-empty">
      尚无模型档案——请先注册连接，再声明模型与能力。
    </p>
  </section>
</template>

<style scoped>
.ai-profile-registry {
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
