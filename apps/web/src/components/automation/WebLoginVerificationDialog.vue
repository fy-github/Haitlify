<script setup lang="ts">
import { toRefs } from 'vue'
import type { WebLoginVerificationDialogModel } from '../../ui/webLoginVerificationModel'
import { LoaderCircle } from '@lucide/vue'
import type { WebAutomationRunSnapshot } from '../../domain/webAutomationRunRepository'
const props = defineProps<{ model: WebLoginVerificationDialogModel }>()
const { webLoginVerificationVisible, closeWebLoginVerification, webLoginVerificationRunning, webLoginVerificationSnapshot } = toRefs(props.model)

</script>

<template>
<el-dialog
      v-model="webLoginVerificationVisible"
      class="web-login-verification-dialog"
      title="验证 Web 自动登录"
      width="min(900px, 92vw)"
      :close-on-click-modal="false"
      destroy-on-close
      @closed="closeWebLoginVerification"
    >
      <div class="web-login-verification-layout">
        <section class="web-login-verification-preview">
          <div class="web-login-verification-section-head">
            <strong>{{ webLoginVerificationRunning ? '浏览器实时操作' : webLoginVerificationSnapshot?.status === 'completed' ? '登录验证完成' : '登录验证结果' }}</strong>
            <el-tag
              size="small"
              :type="webLoginVerificationRunning ? 'warning' : webLoginVerificationSnapshot?.status === 'completed' ? 'success' : 'danger'"
            >
              {{ webLoginVerificationRunning ? '执行中' : webLoginVerificationSnapshot?.status === 'completed' ? '通过' : '失败' }}
            </el-tag>
          </div>
          <div class="web-login-verification-screenshot">
            <img
              v-if="webLoginVerificationSnapshot?.latestScreenshotUrl"
              :src="webLoginVerificationSnapshot.latestScreenshotUrl"
              alt="自动登录实时页面"
            />
            <div v-else class="web-login-verification-placeholder">
              <LoaderCircle v-if="webLoginVerificationRunning" :size="22" class="is-loading" />
              <span>{{ webLoginVerificationRunning ? '等待浏览器页面截图…' : '暂无页面截图' }}</span>
            </div>
          </div>
          <code class="web-login-verification-page-url" :title="webLoginVerificationSnapshot?.currentPageUrl || ''">
            {{ webLoginVerificationSnapshot?.currentPageUrl || '浏览器尚未返回当前页面地址' }}
          </code>
        </section>

        <section class="web-login-verification-details">
          <div class="web-login-verification-current-step">
            <span>当前步骤</span>
            <strong>{{ webLoginVerificationSnapshot?.currentStep?.name || (webLoginVerificationRunning ? '准备浏览器环境' : '已结束') }}</strong>
            <small v-if="webLoginVerificationSnapshot?.currentStep">
              {{ webLoginVerificationSnapshot.currentStep.phase }} · {{ webLoginVerificationSnapshot.currentStep.action }}
            </small>
          </div>
          <div class="web-login-verification-progress">
            <div><span>执行进度</span><strong>{{ webLoginVerificationSnapshot?.progress?.completedSteps || 0 }} / {{ webLoginVerificationSnapshot?.progress?.totalSteps || 0 }} 步</strong></div>
            <el-progress :percentage="webLoginVerificationSnapshot?.progress?.totalSteps ? Math.round((webLoginVerificationSnapshot.progress.completedSteps / webLoginVerificationSnapshot.progress.totalSteps) * 100) : (webLoginVerificationRunning ? 12 : 0)" :show-text="false" />
          </div>
          <div class="web-login-verification-steps">
            <div v-for="step in webLoginVerificationSnapshot?.stepLogs || []" :key="`${step.stepId}-${step.startedAt || ''}`" class="web-login-verification-step" :data-status="step.status">
              <span>{{ step.status === 'passed' ? '✓' : '!' }}</span>
              <div><strong>{{ step.name }}</strong><small>{{ step.pageUrl || '等待页面地址' }}</small><p v-if="step.errorMessage">{{ step.errorMessage }}</p></div>
            </div>
            <el-empty v-if="!(webLoginVerificationSnapshot?.stepLogs?.length)" description="等待浏览器执行步骤" :image-size="56" />
          </div>
          <div v-if="webLoginVerificationSnapshot?.diagnostics?.length" class="web-login-verification-diagnostics">
            <strong>诊断信息</strong>
            <p v-for="diagnostic in webLoginVerificationSnapshot.diagnostics" :key="diagnostic">{{ diagnostic }}</p>
          </div>
        </section>
      </div>
      <template #footer>
        <div class="drawer-actions">
          <el-button v-if="webLoginVerificationRunning" type="danger" plain @click="closeWebLoginVerification">停止验证</el-button>
          <el-button @click="webLoginVerificationVisible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>
</template>
