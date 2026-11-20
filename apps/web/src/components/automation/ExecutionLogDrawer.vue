<script setup lang="ts">
import { toRefs } from 'vue'
import type { ExecutionLogDrawerModel } from '../../ui/executionLogModel'
import WebStepEvidenceCard from './WebStepEvidenceCard.vue'
import ActionIrViewerDialog from './ActionIrViewerDialog.vue'
import { automationStatusLabel, automationStatusTagType, formatLogValue, reportStepEvidence, reportWebStepLogs } from '../../ui/automationFormat'
import type { WebAutomationBrowser } from '../../domain/webAutomationCase'
import type { WebAutomationAction } from '../../domain/webAutomationCase'
import type { WebAutomationMockOutcome } from '../../domain/webAutomation'
import type { WebAutomationAuthenticationMode } from '../../domain/webAutomation'
import type { AutomationRunResult } from '../../domain/apiAutomation'
const props = defineProps<{ model: ExecutionLogDrawerModel }>()
const { automationLogVisible, selectedAutomationResult, actionIrViewerVisible, selectedActionIrCase } = toRefs(props.model)

</script>

<template>
<el-drawer v-model="automationLogVisible" class="web-automation-drawer web-automation-log-drawer" size="720px" title="执行日志">
      <div class="curl-preview" v-if="selectedAutomationResult">
        <div class="request-summary">
          <span class="method-pill" :data-method="selectedAutomationResult.method">{{ selectedAutomationResult.method }}</span>
          <code>{{ selectedAutomationResult.request.url }}</code>
          <el-tag :type="automationStatusTagType(selectedAutomationResult.status)">
            {{ automationStatusLabel(selectedAutomationResult.status) }}
          </el-tag>
        </div>
        <div v-if="selectedAutomationResult.method === 'WEB'" class="log-grid">
          <div class="log-card">
            <strong>执行摘要</strong>
            <pre>{{ formatLogValue({
              caseId: selectedAutomationResult.caseId,
              sourceCaseId: selectedAutomationResult.sourceCaseId,
              module: selectedAutomationResult.module,
              status: selectedAutomationResult.status,
              durationMs: selectedAutomationResult.durationMs,
              failedStepId: selectedAutomationResult.failedStepId,
              screenshotPath: selectedAutomationResult.screenshotPath
            }) }}</pre>
          </div>
          <div class="log-card">
            <strong>失败原因</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.errorMessage) }}</pre>
          </div>
          <div class="log-card full-width" v-if="selectedAutomationResult.screenshotPath">
            <strong>失败截图</strong>
            <a :href="selectedAutomationResult.screenshotPath" target="_blank" rel="noreferrer">
              {{ selectedAutomationResult.screenshotPath }}
            </a>
          </div>
          <div class="log-card full-width">
            <strong>步骤日志</strong>
            <div v-if="reportWebStepLogs(selectedAutomationResult).length" class="report-web-step-items">
              <div
                v-for="step in reportWebStepLogs(selectedAutomationResult)"
                :key="step.stepId"
                class="report-web-step-item expanded"
              >
                <span class="report-web-step-action">{{ step.action }}</span>
                <el-tag v-if="step.phase" size="small" effect="plain">{{ step.phase }}</el-tag>
                <code class="report-web-step-target">{{ step.target }}</code>
                <el-tag :type="automationStatusTagType(step.status)" effect="light">
                  {{ automationStatusLabel(step.status) }}
                </el-tag>
                <span>{{ step.durationMs }} ms</span>
                <span v-if="step.pageUrl">页面：{{ step.pageUrl }}</span>
                <span v-if="step.name">步骤：{{ step.name }}</span>
                <pre v-if="step.errorMessage" class="report-html-snippet">{{ step.errorMessage }}</pre>
              </div>
            </div>
            <pre v-else>{{ formatLogValue([]) }}</pre>
          </div>
          <div class="log-card full-width" v-if="reportStepEvidence(selectedAutomationResult).length">
            <strong>步骤证据</strong>
            <WebStepEvidenceCard :evidence="reportStepEvidence(selectedAutomationResult)" />
          </div>
          <div class="log-card full-width">
            <strong>Action IR</strong>
            <el-button size="small" data-testid="open-action-ir-viewer" @click="actionIrViewerVisible = true">
              查看 Action IR / 用例步骤
            </el-button>
            <ActionIrViewerDialog
              v-model:visible="actionIrViewerVisible"
              :title="`Action IR / 用例步骤 · ${selectedAutomationResult.name || selectedAutomationResult.caseId}`"
              :actions="selectedActionIrCase?.actions"
              :steps="selectedActionIrCase?.steps"
            />
          </div>
          <div class="log-card full-width" v-if="selectedAutomationResult.pageHtmlSnippet">
            <strong>页面片段</strong>
            <pre>{{ selectedAutomationResult.pageHtmlSnippet }}</pre>
          </div>
          <div class="log-card" v-if="selectedAutomationResult.consoleErrors?.length">
            <strong>Console Errors</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.consoleErrors) }}</pre>
          </div>
          <div class="log-card" v-if="selectedAutomationResult.pageErrors?.length">
            <strong>Page Errors</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.pageErrors) }}</pre>
          </div>
          <div class="log-card full-width" v-if="selectedAutomationResult.failedRequests?.length">
            <strong>Failed Requests</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.failedRequests) }}</pre>
          </div>
        </div>
        <div v-else class="log-grid">
          <div class="log-card">
            <strong>请求头</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.request.headers) }}</pre>
          </div>
          <div class="log-card">
            <strong>请求体</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.request.body) }}</pre>
          </div>
          <div class="log-card">
            <strong>断言规则</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.request.assertions) }}</pre>
          </div>
          <div class="log-card">
            <strong>预期返回体</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.expectedResponseBody) }}</pre>
          </div>
          <div class="log-card">
            <strong>实际返回体</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.actualResponseBody ?? selectedAutomationResult.responseBody) }}</pre>
          </div>
          <div class="log-card">
            <strong>错误信息</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.errorMessage) }}</pre>
          </div>
          <div class="log-card">
            <strong>执行摘要</strong>
            <pre>{{ formatLogValue({
              caseId: selectedAutomationResult.caseId,
              expectedStatus: selectedAutomationResult.expectedStatus,
              actualStatus: selectedAutomationResult.actualStatus,
              durationMs: selectedAutomationResult.durationMs,
              responseBodyMatch: selectedAutomationResult.responseBodyMatch
            }) }}</pre>
          </div>
        </div>
      </div>
    </el-drawer>
</template>
