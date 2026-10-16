<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ClipboardCheck,
  Play,
  Send,
  Sparkles,
  User
} from '@lucide/vue'
import type { NavKey } from '../../domain/appNavigation'
import AiAssistantReviewPanel from '../platformAi/AiAssistantReviewPanel.vue'
import { useAssistantRunLauncher } from '../../ui/useAssistantRunLauncher'
import { canLaunch, inputLabel, listValueOf, pickerKindFor, setListValue } from '../../domain/aiAssistantRouting'
import type { AiAssistantRepositoryOptions, AiAssistantRun } from '../../domain/aiAssistantRepository'
import * as defaultRepository from '../../domain/aiAssistantRepository'

/** 首页保留统一导航事件契约，后续自然语言入口可继续复用。 */
export interface HomeNavigateTarget {
  navKey: NavKey
  automationKind?: 'api' | 'web'
  caseDesignKind?: 'interface' | 'web_automation'
}

const props = withDefaults(defineProps<{
  /** 测试注入点：默认走真实仓库与默认项目解析。 */
  assistantDeps?: {
    options?: AiAssistantRepositoryOptions
    repository?: typeof defaultRepository
    resolveProject?: (projectName: string) => Promise<{ id: number }>
  }
}>(), { assistantDeps: undefined })

interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
}

/** 运行卡：任务显式选择 + 必填输入；提交前不产生任何写操作。 */
interface DraftMessage {
  role: 'assistant'
  kind: 'draft'
  draftId: number
  taskType: string
  values: Record<string, string>
  prechecking?: boolean
  precheck?: { taskType: string; missingInputs: string[]; complete: boolean; alternatives: string[] }
}

/** 运行结果卡：状态/违规/接收状态一律来自后端裁决。 */
interface RunMessage {
  role: 'assistant'
  kind: 'run'
  run: AiAssistantRun
}

type HomeMessage = ChatMessage | DraftMessage | RunMessage

const emit = defineEmits<{
  navigate: [target: HomeNavigateTarget]
}>()

const launcher = useAssistantRunLauncher(props.assistantDeps ?? {})

const input = ref('')
const panelRefreshToken = ref(0)
let nextDraftId = 1

const messages = ref<HomeMessage[]>([
  {
    role: 'assistant',
    text: '你好，我是你的 AI 测试助手。描述你的目标（缺陷现象、日志片段、回归范围、需求分析…），我会按关键词给出候选任务；确认任务、补全必填输入后点击运行，产物会进入下方评审面板等待人工确认。'
  }
])

const canSend = computed(() => input.value.trim().length > 0)

const sendMessage = () => {
  const text = input.value.trim()
  if (!text) return
  messages.value.push({ role: 'user', text })
  const suggestion = launcher.suggest(text)[0]
  messages.value.push({
    role: 'assistant',
    kind: 'draft',
    draftId: nextDraftId++,
    taskType: suggestion?.taskType ?? '',
    values: {}
  })
  input.value = ''
}

const draftTask = (message: DraftMessage) =>
  launcher.catalog.value.find((task) => task.taskType === message.taskType)

const runPrecheck = async (message: DraftMessage) => {
  const task = draftTask(message)
  if (!task) return
  message.prechecking = true
  const result = await launcher.precheck(task, message.values)
  message.prechecking = false
  message.precheck = result ?? undefined
}

const runDraft = async (message: DraftMessage) => {
  const task = draftTask(message)
  if (!task || !canLaunch(message.taskType)) return
  const run = await launcher.launch(task, message.values)
  if (run) {
    messages.value.push({ role: 'assistant', kind: 'run', run })
    panelRefreshToken.value += 1
  } else {
    messages.value.push({ role: 'assistant', text: `运行未提交：${launcher.error.value || '未知错误'}` })
  }
}

const runStatusText = (run: AiAssistantRun): string => {
  if (run.status === 'blocked') return `运行被阻断：${run.blockReason || '未提供原因'}`
  if (run.status === 'accepted') return `运行完成，产物已被人工确认（${run.acceptanceState}）`
  if (run.status === 'rejected') return `运行产物已被人工驳回（${run.acceptanceState}）`
  return `运行完成，等待人工确认（${run.acceptanceState}）`
}

onMounted(() => {
  void launcher.loadCatalog()
  void launcher.loadDocuments()
  void launcher.loadRecentRuns()
  void launcher.loadDraftPoints()
})
</script>

<template>
  <section class="home-view">
    <div class="home-view__panel">
      <header class="home-view__header">
        <span class="home-view__logo" aria-hidden="true">
          <Sparkles :size="20" />
        </span>
        <div class="home-view__heading">
          <h1>AI 测试助手</h1>
          <p>自然语言驱动测试构思、生成、执行与复盘</p>
        </div>
      </header>

      <div class="home-view__thread">
        <div
          v-for="(msg, index) in messages"
          :key="index"
          class="home-view__msg"
          :class="`home-view__msg--${msg.role}`"
        >
          <span class="home-view__msg-mark" :class="{ 'is-user': msg.role === 'user' }" aria-hidden="true">
            <Sparkles v-if="msg.role === 'assistant'" :size="14" />
            <User v-else :size="14" />
          </span>

          <div
            v-if="msg.role === 'assistant' && 'kind' in msg && msg.kind === 'draft'"
            class="home-view__draft"
            data-testid="home-draft"
          >
            <p class="home-view__draft-tip">按你的描述推荐的任务（可改选）；补全必填输入后点击运行。</p>
            <el-select v-model="msg.taskType" size="small" data-testid="home-draft-task" placeholder="选择任务">
              <el-option
                v-for="task in launcher.catalog.value"
                :key="task.taskType"
                :label="`${task.displayName}（${task.taskType}）`"
                :value="task.taskType"
              />
            </el-select>
            <template v-if="draftTask(msg as never)">
              <div
                v-for="field in (draftTask(msg as never)?.requiredInputs ?? [])"
                :key="field"
                class="home-view__draft-field"
              >
                <span class="home-view__draft-label">{{ inputLabel(field) }}</span>
                <el-select
                  v-if="pickerKindFor(field) === 'documents'"
                  multiple
                  collapse-tags
                  size="small"
                  placeholder="从项目文档目录中选择"
                  :model-value="listValueOf(msg.values, field)"
                  data-testid="home-draft-picker-documents"
                  @update:model-value="(value: string[]) => setListValue(msg.values, field, value)"
                >
                  <el-option
                    v-for="doc in launcher.documents.value"
                    :key="doc.documentId"
                    :label="`${doc.fileName}（${doc.documentId.slice(0, 16)}…）`"
                    :value="doc.documentId"
                  />
                </el-select>
                <el-select
                  v-else-if="pickerKindFor(field) === 'draft-points'"
                  multiple
                  collapse-tags
                  size="small"
                  placeholder="从已确认的草稿测试点中选择"
                  :model-value="listValueOf(msg.values, field)"
                  data-testid="home-draft-picker-draft-points"
                  @update:model-value="(value: string[]) => setListValue(msg.values, field, value)"
                >
                  <el-option
                    v-for="point in launcher.draftPoints.value"
                    :key="point.pointId"
                    :label="`${point.title}（${point.moduleName || point.caseKind || '未分类'}）`"
                    :value="point.pointId"
                  />
                </el-select>
                <el-select
                  v-else-if="pickerKindFor(field) === 'recent-runs'"
                  multiple
                  collapse-tags
                  size="small"
                  placeholder="从最近产物中选择（取 runId）"
                  :model-value="listValueOf(msg.values, field)"
                  data-testid="home-draft-picker-runs"
                  @update:model-value="(value: string[]) => setListValue(msg.values, field, value)"
                >
                  <el-option
                    v-for="item in launcher.recentRuns.value"
                    :key="item.runId"
                    :label="`${item.taskType} · ${item.runId.slice(0, 18)}…`"
                    :value="item.runId"
                  />
                </el-select>
                <el-input
                  v-else
                  v-model="msg.values[field]"
                  size="small"
                  :placeholder="field"
                  :data-testid="`home-draft-input-${field}`"
                />
              </div>
            </template>
            <div class="home-view__draft-actions">
              <el-button
                size="small"
                :loading="msg.prechecking"
                :disabled="!canLaunch(msg.taskType)"
                data-testid="home-draft-precheck"
                @click="runPrecheck(msg as never)"
              >
                <ClipboardCheck :size="13" aria-hidden="true" />预检
              </el-button>
              <el-button
                type="primary"
                size="small"
                :loading="launcher.launching.value"
                :disabled="!canLaunch(msg.taskType)"
                data-testid="home-draft-run"
                @click="runDraft(msg as never)"
              >
                <Play :size="13" aria-hidden="true" />运行
              </el-button>
            </div>
            <div v-if="msg.precheck" class="home-view__precheck" data-testid="home-draft-precheck-result">
              <p v-if="msg.precheck.complete" class="home-view__precheck-ok">预检通过：必填输入齐全，可以运行。</p>
              <p v-else class="home-view__precheck-missing">
                预检：仍缺 {{ msg.precheck.missingInputs.map((field: string) => inputLabel(field)).join('、') }}。
              </p>
              <p v-if="msg.precheck.alternatives.length > 0" class="home-view__precheck-alt">
                按当前输入也可运行：{{ msg.precheck.alternatives.join('、') }}。
              </p>
            </div>
          </div>

          <div
            v-else-if="msg.role === 'assistant' && 'kind' in msg && msg.kind === 'run'"
            class="home-view__run"
            data-testid="home-run-result"
          >
            <p class="home-view__run-status">{{ runStatusText(msg.run) }}</p>
            <p v-if="(msg.run.violations ?? []).length > 0" class="home-view__run-violations" data-testid="home-run-violations">
              校验提示：{{ msg.run.violations.join('；') }}
            </p>
            <p v-if="msg.run.artifactId" class="home-view__run-artifact" data-testid="home-run-artifact">
              产物 {{ msg.run.artifactId }}（v{{ msg.run.revision }}）已同步到下方评审面板。
            </p>
          </div>

          <p v-else>{{ 'text' in msg ? msg.text : '' }}</p>
        </div>
      </div>

      <footer class="home-view__composer">
        <el-input
          v-model="input"
          :placeholder="'描述你想做的事,例如:把用例库中的接口用例批量执行一遍…'"
          clearable
          data-testid="home-assistant-input"
          @keyup.enter="sendMessage"
        />
        <el-button type="primary" :disabled="!canSend" data-testid="home-assistant-send" @click="sendMessage">
          <Send :size="15" />
          发送
        </el-button>
      </footer>
    </div>

    <div class="home-view__review">
      <AiAssistantReviewPanel
        :refresh-token="panelRefreshToken"
        :repository="assistantDeps?.repository"
        :resolve-project="assistantDeps?.resolveProject"
        :options="assistantDeps?.options"
      />
    </div>
  </section>
</template>

<style scoped>
.home-view {
  height: 100%;
  min-height: 0;
  display: grid;
  justify-items: center;
  align-content: start;
  gap: 28px;
  padding: 32px 28px 40px;
  overflow: auto;
}

.home-view__review {
  width: min(864px, 100%);
}

.home-view__draft {
  display: grid;
  gap: 12px;
  margin-top: 4px;
  padding: 14px 16px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  background: var(--surface-raised);
  width: min(560px, 100%);
}

.home-view__draft-tip {
  margin: 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-secondary);
}

.home-view__draft-field {
  display: grid;
  gap: 5px;
}

.home-view__draft-label {
  font-size: 12px;
  line-height: 18px;
  color: var(--text-secondary);
}

.home-view__draft-actions {
  display: flex;
  gap: 10px;
}

.home-view__precheck {
  display: grid;
  gap: 6px;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  background: var(--surface-panel);
  border: 1px solid var(--border-subtle);
}

.home-view__precheck-ok {
  margin: 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-primary);
}

.home-view__precheck-missing {
  margin: 0;
  font-size: 12px;
  line-height: 18px;
  color: #b45309;
}

.home-view__precheck-alt {
  margin: 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-secondary);
}

.home-view__run {
  display: grid;
  gap: 8px;
  margin-top: 4px;
  padding: 14px 16px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  background: var(--surface-subtle);
  width: min(560px, 100%);
}

.home-view__run-status {
  margin: 0;
  font-size: 13px;
  line-height: 20px;
  color: var(--text-primary);
}

.home-view__run-violations {
  margin: 0;
  font-size: 12px;
  line-height: 18px;
  color: #b45309;
}

.home-view__run-artifact {
  margin: 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-secondary);
}

.home-view__panel {
  width: min(864px, 100%);
  height: min(620px, calc(100vh - 420px));
  min-height: 420px;
  display: flex;
  flex-direction: column;
  background: var(--surface-glass);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-md);
  backdrop-filter: blur(16px);
  overflow: hidden;
}

.home-view__header {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 24px;
  border-bottom: 1px solid var(--border-subtle);
}

.home-view__logo {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  place-items: center;
  border-radius: var(--radius-lg);
  color: var(--text-inverse);
  background: var(--gradient-primary);
  box-shadow: 0 4px 12px var(--brand-primary-shadow);
}

.home-view__heading h1 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.25;
  color: var(--text-primary);
}

.home-view__heading p {
  margin: 3px 0 0;
  font-size: 12px;
  line-height: 1.4;
  color: var(--text-secondary);
}

.home-view__thread {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 20px 24px;
  overflow-y: auto;
}

.home-view__msg {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  max-width: 80%;
}

.home-view__msg--user {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.home-view__msg-mark {
  display: grid;
  width: 24px;
  height: 24px;
  flex: 0 0 24px;
  place-items: center;
  border-radius: 8px;
  color: var(--text-inverse);
  background: var(--gradient-primary);
}

.home-view__msg-mark.is-user {
  background: var(--text-tertiary);
}

.home-view__msg p {
  margin: 0;
  padding: 9px 12px;
  border-radius: 12px;
  border: 1px solid var(--border-subtle);
  background: var(--surface-raised);
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.55;
}

.home-view__msg--user p {
  background: var(--brand-primary-soft);
  border-color: var(--brand-primary-border);
}

.home-view__composer {
  display: flex;
  gap: 10px;
  padding: 12px 24px 18px;
  border-top: 1px solid var(--border-subtle);
  background: var(--surface-page);
}
</style>

<style>
/* 首页占满整个 canvas:workspace 网格改为块级 + 页面级青绿氛围。 */
.workspace.is-home {
  display: block;
  overflow: auto;
  background:
    radial-gradient(900px 480px at 8% -12%, rgba(15, 118, 110, 0.08), transparent 60%),
    radial-gradient(760px 420px at 96% 108%, rgba(14, 116, 144, 0.08), transparent 55%),
    var(--surface-page);
}
</style>
