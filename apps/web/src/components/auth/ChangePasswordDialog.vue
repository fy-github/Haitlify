<script setup lang="ts">
import { toRefs } from 'vue'
import type { ChangePasswordDialogModel } from '../../ui/changePasswordModel'


const props = defineProps<{ model: ChangePasswordDialogModel }>()
const { changePasswordDialogVisible, currentUser, changePasswordForm, submitChangePassword } = toRefs(props.model)

</script>

<template>
<el-dialog
      v-model="changePasswordDialogVisible"
      class="account-password-dialog"
      title="修改密码"
      width="min(92vw, 420px)"
      :close-on-click-modal="false"
      :close-on-press-escape="!currentUser?.mustChangePassword"
      :show-close="!currentUser?.mustChangePassword"
    >
      <div class="change-password-form">
        <p v-if="currentUser?.mustChangePassword" class="change-password-form__hint">
          首次登录需修改初始密码后才能继续使用。
        </p>
        <el-input
          v-model="changePasswordForm.currentPassword"
          type="password"
          placeholder="当前密码"
          autocomplete="current-password"
        />
        <el-input
          v-model="changePasswordForm.newPassword"
          type="password"
          placeholder="新密码（至少 8 位）"
          autocomplete="new-password"
        />
        <el-input
          v-model="changePasswordForm.confirmPassword"
          type="password"
          placeholder="确认新密码"
          autocomplete="new-password"
        />
        <p v-if="changePasswordForm.error" class="change-password-form__error" role="alert">
          {{ changePasswordForm.error }}
        </p>
      </div>
      <template #footer>
        <el-button v-if="!currentUser?.mustChangePassword" @click="changePasswordDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="changePasswordForm.submitting" @click="submitChangePassword">
          确认修改
        </el-button>
      </template>
    </el-dialog>
</template>
<style scoped>
.change-password-form { display: flex; flex-direction: column; gap: 12px; }
.change-password-form__error { margin: 0; font-size: 13px; color: var(--color-danger); }
.change-password-form__hint { margin: 0; padding: 10px 12px; border-radius: 8px; font-size: 13px; color: var(--status-warning-text); background: var(--color-warning-soft); }
</style>
