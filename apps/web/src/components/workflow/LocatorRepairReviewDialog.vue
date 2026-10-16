<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { RefreshCcw, ShieldCheck, ShieldX } from '@lucide/vue'
import {
  acceptLocatorRepairProposal,
  listLocatorRepairProposals,
  rejectLocatorRepairProposal,
  type LocatorRepairProposal
} from '../../domain/locatorKnowledgeRepository'

const props = defineProps<{ projectId: number; visible: boolean }>()
const emit = defineEmits<{ 'update:visible': [boolean] }>()

const loading = ref(false)
const proposals = ref<LocatorRepairProposal[]>([])
const error = ref('')
const acting = ref('')

const fetchProposals = async () => {
  if (!props.projectId) {
    proposals.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    proposals.value = await listLocatorRepairProposals(props.projectId, 'pending_review')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '修复提案加载失败'
  } finally {
    loading.value = false
  }
}

const review = async (proposal: LocatorRepairProposal, action: 'accept' | 'reject') => {
  try {
    await ElMessageBox.confirm(
      action === 'accept'
        ? `接受后 locator 将更新为「${proposal.newLocator}」（原值「${proposal.oldLocator}」）。`
        : `拒绝该修复建议，「${proposal.newLocator}」不会写入知识库。`,
      action === 'accept' ? '确认接受修复建议' : '确认拒绝修复建议',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  acting.value = proposal.proposalId
  try {
    if (action === 'accept') {
      await acceptLocatorRepairProposal(proposal.proposalId)
      ElMessage.success('已接受修复建议')
    } else {
      await rejectLocatorRepairProposal(proposal.proposalId)
      ElMessage.success('已拒绝修复建议')
    }
    await fetchProposals()
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '操作失败')
  } finally {
    acting.value = ''
  }
}

watch(() => props.visible, (visible) => {
  if (visible) void fetchProposals()
}, { immediate: true })
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="Locator 修复审核"
    width="760px"
    :close-on-click-modal="false"
    :teleported="false"
    @update:model-value="(value: boolean) => emit('update:visible', value)"
  >
    <div class="locator-repair-review" data-testid="locator-repair-review">
      <div class="locator-repair-review__toolbar">
        <span class="locator-repair-review__count" data-testid="locator-repair-count">待审核 {{ proposals.length }} 条</span>
        <button type="button" class="locator-repair-review__refresh" data-testid="locator-repair-refresh" @click="fetchProposals">
          <RefreshCcw :size="13" /> 刷新
        </button>
      </div>
      <div v-if="loading" class="locator-repair-review__state" data-testid="locator-repair-loading">加载中…</div>
      <div v-else-if="error" class="locator-repair-review__error" role="alert" data-testid="locator-repair-error">{{ error }}</div>
      <el-empty v-else-if="!proposals.length" description="没有待审核的修复建议" :image-size="44" data-testid="locator-repair-empty" />
      <table v-else class="locator-repair-review__table" data-testid="locator-repair-table">
        <thead>
          <tr>
            <th>条目</th>
            <th>原 locator</th>
            <th>建议 locator</th>
            <th>置信度</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="proposal in proposals" :key="proposal.proposalId" :data-testid="`locator-repair-row-${proposal.proposalId}`">
            <td :title="proposal.entryId">{{ proposal.entryId }}</td>
            <td class="locator-repair-review__locator">{{ proposal.oldLocator }}</td>
            <td class="locator-repair-review__locator">{{ proposal.newLocator }}</td>
            <td>{{ proposal.confidence ?? '—' }}</td>
            <td>{{ proposal.createdAt || '—' }}</td>
            <td class="locator-repair-review__actions">
              <button
                type="button"
                class="locator-repair-review__accept"
                :disabled="acting === proposal.proposalId"
                :data-testid="`locator-repair-accept-${proposal.proposalId}`"
                @click="review(proposal, 'accept')"
              >
                <ShieldCheck :size="13" /> 接受
              </button>
              <button
                type="button"
                class="locator-repair-review__reject"
                :disabled="acting === proposal.proposalId"
                :data-testid="`locator-repair-reject-${proposal.proposalId}`"
                @click="review(proposal, 'reject')"
              >
                <ShieldX :size="13" /> 拒绝
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </el-dialog>
</template>

<style scoped>
.locator-repair-review__toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.locator-repair-review__count { color: var(--brand-text-secondary, #5f6b66); font-size: 12px; }
.locator-repair-review__refresh { display: inline-flex; align-items: center; gap: 4px; min-height: 28px; padding: 0 10px; color: var(--brand-primary, #0f766e); background: transparent; border: 1px solid rgba(183, 210, 202, 0.82); border-radius: 10px; cursor: pointer; font-size: 12px; }
.locator-repair-review__state { padding: 18px 0; color: var(--brand-text-secondary, #5f6b66); font-size: 13px; }
.locator-repair-review__error { padding: 12px; color: #b91c1c; font-size: 13px; }
.locator-repair-review__table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.locator-repair-review__table th, .locator-repair-review__table td { padding: 8px 10px; text-align: left; border-bottom: 1px solid rgba(183, 210, 202, 0.5); vertical-align: top; }
.locator-repair-review__table th { color: var(--brand-text-secondary, #5f6b66); font-weight: 600; background: rgba(15, 118, 110, 0.04); }
.locator-repair-review__locator { max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.locator-repair-review__actions { display: flex; gap: 6px; }
.locator-repair-review__accept, .locator-repair-review__reject { display: inline-flex; align-items: center; gap: 4px; min-height: 26px; padding: 0 9px; border-radius: 9px; cursor: pointer; font-size: 12px; }
.locator-repair-review__accept { color: #0f766e; background: rgba(15, 118, 110, 0.08); border: 1px solid rgba(15, 118, 110, 0.35); }
.locator-repair-review__reject { color: #b91c1c; background: rgba(185, 28, 28, 0.06); border: 1px solid rgba(185, 28, 28, 0.3); }
.locator-repair-review__accept:disabled, .locator-repair-review__reject:disabled { opacity: 0.55; cursor: not-allowed; }
</style>
