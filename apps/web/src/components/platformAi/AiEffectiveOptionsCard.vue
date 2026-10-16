<script setup lang="ts">
import { ref, watch } from 'vue'
import { Gauge, Search } from '@lucide/vue'
import type { EffectiveOptionsResponse, ProfileView } from '../../domain/aiConfigurationRepository'

const props = withDefaults(defineProps<{
  effective?: EffectiveOptionsResponse | null
  taskTypes?: string[] | null
  profiles?: ProfileView[] | null
  loading?: boolean
  error?: string
}>(), { effective: null, taskTypes: () => [], profiles: () => [], loading: false, error: '' })

const emit = defineEmits<{
  load: [taskType: string, profileId?: string]
}>()

const selectedTaskType = ref('requirement_analysis')
const selectedProfile = ref<string | undefined>(undefined)

watch(() => props.taskTypes, (types) => {
  if (types && types.length > 0 && !types.includes(selectedTaskType.value)) {
    selectedTaskType.value = types[0]
  }
}, { immediate: true })

const sourceLabel = (source: string): string => ({
  task_override: '任务显式覆盖',
  connection_default: '连接默认（legacy 全局）',
  platform_limit: '平台上限收紧',
  profile: '档案参数',
  run_snapshot: '运行快照钉版',
  omitted: '未设置（不发送）'
}[source] ?? source)

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
</script>

<template>
  <section class="ai-effective-options" aria-label="有效配置溯源" data-testid="ai-effective-options">
    <header class="effective-head">
      <Gauge :size="16" aria-hidden="true" />
      <strong>有效配置与来源</strong>
      <span class="effective-hint">界面显示有效 timeout 与参数来源——显式覆盖 &gt; 连接默认；平台上限收紧所有来源</span>
    </header>

    <div class="effective-form">
      <el-select v-model="selectedTaskType" size="small" data-testid="effective-task-type">
        <el-option v-for="taskType in props.taskTypes ?? []" :key="taskType" :label="taskType" :value="taskType" />
      </el-select>
      <el-select v-model="selectedProfile" size="small" clearable placeholder="模型档案（可选）" data-testid="effective-profile">
        <el-option v-for="profile in props.profiles ?? []" :key="profile.profileId" :label="`${profile.profileId}（v${profile.version}）`" :value="profile.profileId" />
      </el-select>
      <el-button size="small" :loading="props.loading" data-testid="effective-load" @click="emit('load', selectedTaskType, selectedProfile || undefined)">
        <Search :size="13" aria-hidden="true" />查看有效配置
      </el-button>
    </div>

    <el-alert v-if="props.error" type="error" :closable="false" data-testid="effective-error" :title="props.error" />

    <div v-if="props.effective" class="effective-body" data-testid="effective-body">
      <div class="effective-row">
        <span class="effective-label">有效超时</span>
        <span data-testid="effective-timeout-value">
          {{ props.effective.timeout.present ? `${props.effective.timeout.value} ms` : '未设置（不发送）' }}
        </span>
        <span class="effective-source" data-testid="effective-timeout-source">{{ sourceLabel(props.effective.timeout.source) }}</span>
        <span class="effective-version" data-testid="effective-timeout-version">{{ props.effective.timeout.sourceVersion }}</span>
      </div>
      <div class="effective-row">
        <span class="effective-label">能力声明</span>
        <span data-testid="effective-capabilities">{{ (props.effective.declaredCapabilities ?? []).join(', ') || '未声明（不发送可选参数）' }}</span>
        <span class="effective-source" data-testid="effective-verification">{{ verificationLabel(props.effective.verificationJson) }}</span>
      </div>
      <p v-if="props.effective.notes && Object.keys(props.effective.notes).length > 0" class="effective-notes" data-testid="effective-notes">
        <span v-for="(note, key) in props.effective.notes" :key="key">{{ note }}；</span>
      </p>
    </div>
  </section>
</template>

<style scoped>
.ai-effective-options {
  display: grid;
  gap: 16px;
  margin-top: 24px;
  padding: 20px 22px 22px;
  border: 1px solid var(--border-subtle, #e2eeea);
  border-radius: var(--radius-xl, 16px);
  background: var(--surface-raised, #ffffff);
  box-shadow: var(--shadow-sm, 0 2px 8px rgba(15, 23, 42, 0.04));
}
.effective-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.effective-hint { color: var(--muted-foreground, #6b7280); font-size: 12px; line-height: 18px; margin-right: auto; }
.effective-form {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  align-items: center;
  padding: 16px 18px;
  border-radius: var(--radius-lg, 12px);
  background: var(--surface-subtle, #ecf5f2);
}
.effective-body {
  border: 1px solid var(--border-subtle, #e2eeea);
  border-radius: var(--radius-lg, 12px);
  padding: 16px 18px;
  display: grid;
  gap: 12px;
  background: var(--surface-panel, rgba(255, 255, 255, 0.92));
}
.effective-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; font-size: 13px; line-height: 20px; }
.effective-label { min-width: 72px; color: var(--muted-foreground, #6b7280); }
.effective-source {
  background: var(--surface-hover, #e3f0ec);
  color: var(--text-primary, #0f172a);
  border-radius: var(--radius-sm, 8px);
  padding: 2px 10px;
  font-size: 12px;
  line-height: 18px;
}
.effective-version { color: var(--muted-foreground, #6b7280); font-size: 12px; }
.effective-notes { color: var(--muted-foreground, #6b7280); font-size: 12px; line-height: 19px; margin: 0; }
</style>
