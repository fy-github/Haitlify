<script setup lang="ts">
import { toRefs } from 'vue'
import type { WebLoginDiscoveryDialogModel } from '../../ui/webLoginDiscoveryModel'

import type { WebLoginDiscoveryCandidateRole } from '../../domain/webLoginDiscoveryRepository'
import type { WebLoginDiscoveryCandidate } from '../../domain/webLoginDiscoveryRepository'
import type { WebLoginDiscoverySnapshot } from '../../domain/webLoginDiscoveryRepository'
const props = defineProps<{ model: WebLoginDiscoveryDialogModel }>()
const { webLoginDiscoveryDialogVisible, closeWebLoginDiscovery, webLoginDiscoveryConfirming, webLoginDiscoverySnapshot, confirmAndApplyWebLoginDiscovery, webLoginDiscoveryLoading, canEdit, startWebLoginDiscovery, webLoginDiscoverySelection, selectedWebLoginDiscoveryCandidate } = toRefs(props.model)

</script>

<template>
<el-dialog
      v-model="webLoginDiscoveryDialogVisible"
      class="web-login-discovery-dialog"
      title="快速识别登录"
      width="1040px"
      destroy-on-close
      @closed="closeWebLoginDiscovery"
    >
      <div
        v-if="webLoginDiscoveryLoading && webLoginDiscoverySnapshot?.status !== 'completed'"
        class="web-login-discovery-status"
      >
        <el-progress
          :percentage="webLoginDiscoverySnapshot?.status === 'running' ? 68 : 28"
          :show-text="false"
          :stroke-width="6"
        />
        <strong>{{ webLoginDiscoverySnapshot?.status === 'running' ? '正在分析登录页' : '正在启动浏览器' }}</strong>
        <span>{{ webLoginDiscoverySnapshot?.request?.url || '正在提交识别任务' }}</span>
      </div>

      <div
        v-else-if="webLoginDiscoverySnapshot?.status === 'failed'"
        class="web-login-discovery-status is-failed"
      >
        <strong>登录页识别失败</strong>
        <span>{{ webLoginDiscoverySnapshot.diagnostics?.[0] || 'Runner 未返回错误详情' }}</span>
        <el-button type="primary" plain :disabled="!canEdit" @click="startWebLoginDiscovery">重新识别</el-button>
      </div>

      <div
        v-else-if="webLoginDiscoverySnapshot?.status === 'completed'"
        class="web-login-discovery-layout"
      >
        <section class="web-login-discovery-preview">
          <div class="web-login-discovery-section-head">
            <strong>页面识别结果</strong>
            <el-tag size="small" type="success">已完成</el-tag>
          </div>
          <div class="web-login-discovery-screenshot">
            <img
              v-if="webLoginDiscoverySnapshot.screenshotUrl"
              :src="webLoginDiscoverySnapshot.screenshotUrl"
              alt="登录控件识别截图"
            />
            <el-empty v-else description="无页面截图" :image-size="72" />
          </div>
          <code class="web-login-discovery-page-url">
            {{ webLoginDiscoverySnapshot.pageUrl || webLoginDiscoverySnapshot.request?.url }}
          </code>
        </section>

        <section class="web-login-discovery-candidates">
          <div class="web-login-discovery-section-head">
            <strong>定位器候选</strong>
            <span>规则识别</span>
          </div>

          <label class="web-login-discovery-field">
            <span>账号输入框</span>
            <el-select
              v-model="webLoginDiscoverySelection.usernameCandidateId"
              placeholder="未识别到候选项"
              filterable
            >
              <el-option
                v-for="candidate in webLoginDiscoverySnapshot.candidates?.username || []"
                :key="candidate.id"
                :label="`${candidate.label || candidate.locator} · ${candidate.confidence}%`"
                :value="candidate.id"
              />
            </el-select>
            <div v-if="selectedWebLoginDiscoveryCandidate('username')" class="web-login-discovery-candidate-detail">
              <code>{{ selectedWebLoginDiscoveryCandidate('username')?.locator }}</code>
              <span>{{ selectedWebLoginDiscoveryCandidate('username')?.reason }}</span>
            </div>
          </label>

          <label class="web-login-discovery-field">
            <span>密码输入框</span>
            <el-select
              v-model="webLoginDiscoverySelection.passwordCandidateId"
              placeholder="未识别到候选项"
              filterable
            >
              <el-option
                v-for="candidate in webLoginDiscoverySnapshot.candidates?.password || []"
                :key="candidate.id"
                :label="`${candidate.label || candidate.locator} · ${candidate.confidence}%`"
                :value="candidate.id"
              />
            </el-select>
            <div v-if="selectedWebLoginDiscoveryCandidate('password')" class="web-login-discovery-candidate-detail">
              <code>{{ selectedWebLoginDiscoveryCandidate('password')?.locator }}</code>
              <span>{{ selectedWebLoginDiscoveryCandidate('password')?.reason }}</span>
            </div>
          </label>

          <label class="web-login-discovery-field">
            <span>登录按钮</span>
            <el-select
              v-model="webLoginDiscoverySelection.submitCandidateId"
              placeholder="未识别到候选项"
              filterable
            >
              <el-option
                v-for="candidate in webLoginDiscoverySnapshot.candidates?.submit || []"
                :key="candidate.id"
                :label="`${candidate.label || candidate.locator} · ${candidate.confidence}%`"
                :value="candidate.id"
              />
            </el-select>
            <div v-if="selectedWebLoginDiscoveryCandidate('submit')" class="web-login-discovery-candidate-detail">
              <code>{{ selectedWebLoginDiscoveryCandidate('submit')?.locator }}</code>
              <span>{{ selectedWebLoginDiscoveryCandidate('submit')?.reason }}</span>
            </div>
          </label>

          <div
            v-if="webLoginDiscoverySnapshot.diagnostics?.length"
            class="web-login-discovery-diagnostics"
          >
            <span v-for="diagnostic in webLoginDiscoverySnapshot.diagnostics" :key="diagnostic">
              {{ diagnostic }}
            </span>
          </div>
        </section>
      </div>

      <template #footer>
        <div class="drawer-actions">
          <el-button @click="webLoginDiscoveryDialogVisible = false">取消</el-button>
          <el-button
            type="primary"
            :loading="webLoginDiscoveryConfirming"
            :disabled="webLoginDiscoverySnapshot?.status !== 'completed'"
            @click="confirmAndApplyWebLoginDiscovery"
          >
            确认并回填
          </el-button>
        </div>
      </template>
    </el-dialog>
</template>
