<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import type { AuthUser } from '../../domain/authRepository'
import type { BackendProject } from '../../domain/platformBackendRepository'
import { submitGeneration, type GenerationReceipt } from '../../domain/generationRepository'

const props = defineProps<{ user?: AuthUser | null; project?: BackendProject | null }>()
const fields = reactive({ requirementRef: '', requirementText: '', targetUrl: '', targetRevision: '' })
const receipt = ref<GenerationReceipt | null>(null)
const busy = ref(false)
const error = ref('')
let pendingRequestId: string | null = null
let version = 0
let disposed = false
let controller: AbortController | null = null
// Aborting the transport cannot undo a server admission; the version also guards
// continuations when a response settles despite cancellation.
const clearDisplay = () => {
  version += 1
  controller?.abort()
  controller = null
  receipt.value = null
  error.value = ''
  busy.value = false
}
const invalidate = () => {
  clearDisplay()
  pendingRequestId = null
}
watch(fields, invalidate, { flush: 'sync' })
watch(() => [props.user?.id, props.user?.username, props.project?.id, props.project?.tenantId], () => {
  invalidate()
  Object.assign(fields, { requirementRef: '', requirementText: '', targetUrl: '', targetRevision: '' })
}, { flush: 'sync' })
onBeforeUnmount(() => { disposed = true; invalidate() })
const positiveId = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0
const contextError = computed(() => {
  if (!props.user?.username || !positiveId(props.user.id)) return '认证上下文缺失，禁止提交需求。'
  if (!positiveId(props.project?.id) || !positiveId(props.project?.tenantId)) return '请选择具有有效租户信息的项目；上下文缺失时禁止提交需求。'
  return ''
})
const complete = computed(() => {
  const limits = { requirementRef: 512, requirementText: 65536, targetUrl: 2048, targetRevision: 128 }
  if (!Object.entries(fields).every(([key, value]) => value.trim().length > 0 && value.length <= limits[key as keyof typeof limits])) return false
  try {
    const url = new URL(fields.targetUrl)
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password
  } catch { return false }
})
const submit = async () => {
  if (disposed || busy.value || contextError.value || !complete.value) return
  busy.value = true
  error.value = ''
  const currentVersion = version
  const current = () => !disposed && currentVersion === version
  controller = new AbortController()
  try {
    const result = await submitGeneration({}, {
      ...fields, projectId: props.project!.id,
      requestId: pendingRequestId ?? (pendingRequestId = crypto.randomUUID())
    }, controller.signal)
    if (current()) receipt.value = result
  } catch (value) {
    if (current()) {
      const status = value instanceof Error ? (value as Error & { status?: number }).status : undefined
      const messages: Record<number, string> = {
        400: '请检查需求字段及目标 URL；目标地址还需满足服务端安全策略。',
        401: '登录状态失效，请重新登录。',
        403: '请检查项目权限或会话验证状态。',
        409: '受理请求冲突，请核对上下文与输入，不要盲目重复提交。'
      }
      error.value = status
        ? `受理未确认（HTTP ${status}）。${messages[status] ?? '服务暂不可用；保留输入重试将复用本次请求标识。'}`
        : '需求受理未确认，可能已在服务端受理；保留输入重试将复用本次请求标识。'
    }
  } finally {
    if (current()) {
      busy.value = false
      controller = null
    }
  }
}
</script>

<template>
  <section class="generation" data-testid="generation-entry" aria-label="需求受理">
    <h3>需求受理</h3>
    <p>提交需求快照以待后续处理；当前仅受理，不会触发模型，也不会生成草稿。</p>
    <p v-if="contextError" class="error" role="alert">{{ contextError }}</p>
    <form @submit.prevent="submit">
      <label>需求引用 <input v-model="fields.requirementRef" name="requirementRef" required maxlength="512" /></label>
      <label>需求原文 <textarea v-model="fields.requirementText" name="requirementText" rows="5" required maxlength="65536" /></label>
      <label>目标 URL <input v-model="fields.targetUrl" name="targetUrl" type="url" required maxlength="2048" placeholder="https://example.com/" /></label>
      <label>目标版本 <input v-model="fields.targetRevision" name="targetRevision" required maxlength="128" /></label>
      <button type="submit" :disabled="busy || !!contextError || !complete">{{ busy ? '正在受理…' : error ? '重试受理' : '提交需求' }}</button>
      <button type="button" data-testid="generation-cancel" @click="clearDisplay">清除当前受理显示</button>
      <p>清除显示、修改输入或切换上下文不会撤销服务端已受理的记录。</p>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="receipt" role="status">
      <p>受理编号：{{ receipt.id }}</p>
      <p v-if="receipt.state === 'WAITING_WORKER'">WAITING_WORKER · 待分配隔离执行单元 / 未生成草稿</p>
      <p v-else>受理状态：{{ receipt.state }}（不代表生成成功）</p>
    </div>
  </section>
</template>

<style scoped>
.generation { min-width: 0; border: 1px solid var(--border-default); border-radius: 10px; padding: 14px; margin: 14px 0; background: var(--surface-raised); color: var(--text-primary); }
.generation *, .generation *::before, .generation *::after { box-sizing: border-box; }
h3 { margin-top: 0; }
p, label, button { font-size: 12px; overflow-wrap: anywhere; }
label { display: flex; flex-direction: column; gap: 4px; min-width: 0; margin-bottom: 8px; }
input, textarea { min-width: 0; max-width: 100%; width: 100%; font: inherit; padding: 8px 10px; border: 1px solid var(--border-default); border-radius: 6px; background: var(--surface-raised); color: var(--text-primary); }
textarea { resize: vertical; }
button { max-width: 100%; margin: 0 8px 8px 0; font: inherit; padding: 7px 12px; border: 1px solid var(--border-default); border-radius: 18px; background: var(--surface-raised); color: var(--text-regular); cursor: pointer; }
button:not(:disabled):hover { filter: brightness(.96); }
button:focus-visible, input:focus-visible, textarea:focus-visible { outline: none; box-shadow: none; background: var(--surface-subtle); }
button:focus-visible { filter: brightness(.9); }
button:disabled { opacity: .5; cursor: not-allowed; }
.error { color: var(--color-danger); }
@media (max-width: 768px) {
  button, input { min-height: 40px; }
}
</style>
