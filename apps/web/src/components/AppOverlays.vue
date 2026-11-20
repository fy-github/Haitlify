<script setup lang="ts">
import CaseEditorDrawer from './cases/CaseEditorDrawer.vue'
import CurlPreviewDrawer from './automation/CurlPreviewDrawer.vue'
import EnvironmentEditorPanel from './automation/EnvironmentEditorPanel.vue'
import WebLoginDiscoveryDialog from './automation/WebLoginDiscoveryDialog.vue'
import WebLoginVerificationDialog from './automation/WebLoginVerificationDialog.vue'
import ExecutionLogDrawer from './automation/ExecutionLogDrawer.vue'
import AutomationConversionDialog from './automation/AutomationConversionDialog.vue'
import ChangePasswordDialog from './auth/ChangePasswordDialog.vue'
import type { CaseEditorDrawerModel } from '../ui/caseEditorModel'
import type { CurlPreviewDrawerModel } from '../ui/curlPreviewModel'
import type { EnvironmentEditorPanelModel } from '../ui/environmentEditorModel'
import type { WebLoginDiscoveryDialogModel } from '../ui/webLoginDiscoveryModel'
import type { WebLoginVerificationDialogModel } from '../ui/webLoginVerificationModel'
import type { ExecutionLogDrawerModel } from '../ui/executionLogModel'
import type { AutomationConversionDialogModel } from '../ui/automationConversionModel'
import type { ChangePasswordDialogModel } from '../ui/changePasswordModel'

defineProps<{
  caseEditor: CaseEditorDrawerModel
  curlPreview: CurlPreviewDrawerModel
  environmentEditor: EnvironmentEditorPanelModel
  loginDiscovery: WebLoginDiscoveryDialogModel
  loginVerification: WebLoginVerificationDialogModel
  executionLog: ExecutionLogDrawerModel
  automationConversion: AutomationConversionDialogModel
  changePassword: ChangePasswordDialogModel
}>()
</script>

<template>
  <CaseEditorDrawer :model="caseEditor" />
  <CurlPreviewDrawer :model="curlPreview" />
  <EnvironmentEditorPanel :model="environmentEditor" />
  <WebLoginDiscoveryDialog :model="loginDiscovery" />
  <WebLoginVerificationDialog :model="loginVerification" />
  <ExecutionLogDrawer :model="executionLog" />
  <AutomationConversionDialog :model="automationConversion" />
  <ChangePasswordDialog :model="changePassword" />
</template>
