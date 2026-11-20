import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { changePassword, type AuthUser } from '../domain/authRepository'
import { getAuthSessionEpoch } from '../domain/http'
import { sidebarGroupsForRole } from './navigation/appSidebar'
import type { Ref } from 'vue'

type JiraConfigTestResult = {
  state: 'idle' | 'success' | 'failed' | 'stale'
  message: string
  target: string
}
interface WorkspaceSyncConflictNotice {
  projectName: string
  template: boolean
  cases: boolean
}
type NavHistoryMode = 'push' | 'replace' | 'skip'
type AuthStatus = 'checking' | 'authenticated' | 'unauthenticated'
type HomeWorkspaceStatus = 'ready' | 'loading' | 'empty' | 'unavailable' | 'forbidden' | 'error'

interface Dependencies {
  currentUser: Ref<{ id?: number | null | undefined; username: string; displayName: string; avatar?: string | undefined; role: string; mustChangePassword: boolean; } | null, AuthUser | { id?: number | null | undefined; username: string; displayName: string; avatar?: string | undefined; role: string; mustChangePassword: boolean; } | null>
  authDisposed: boolean
  authStatus: Ref<AuthStatus, AuthStatus>
  isCurrentAuth: (epoch: number) => boolean
}

export function createAccountSettings(deps: Dependencies) {

let passwordOperation = 0


const currentUserInitials = computed(() => {
  const name = deps.currentUser.value?.displayName || deps.currentUser.value?.username
  if (!name) return 'LY'
  return name.trim().charAt(0).toUpperCase()
})


// VIEWER 只读;EDITOR/ADMIN 可执行写操作(后端仍有角色强制,这里是前端体验门控)
const canEdit = computed(() => deps.currentUser.value?.role !== 'VIEWER')

// 侧边栏按角色过滤:非 ADMIN 隐藏用户管理等 adminOnly 项
const sidebarGroups = computed(() => sidebarGroupsForRole(deps.currentUser.value?.role))


const changePasswordDialogVisible = ref(false)

const changePasswordForm = reactive({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
  error: '',
  submitting: false
})


function openChangePasswordDialog(): void {
  if (deps.authDisposed || deps.authStatus.value !== 'authenticated') return
  passwordOperation += 1
  changePasswordForm.submitting = false
  changePasswordForm.currentPassword = ''
  changePasswordForm.newPassword = ''
  changePasswordForm.confirmPassword = ''
  changePasswordForm.error = ''
  changePasswordDialogVisible.value = true
}


async function submitChangePassword(): Promise<void> {
  if (deps.authDisposed || deps.authStatus.value !== 'authenticated' || changePasswordForm.submitting) return
  changePasswordForm.error = ''
  if (!changePasswordForm.currentPassword || !changePasswordForm.newPassword) {
    changePasswordForm.error = '请输入当前密码和新密码'
    return
  }
  if (changePasswordForm.newPassword.length < 8) {
    changePasswordForm.error = '新密码长度至少 8 位'
    return
  }
  if (changePasswordForm.newPassword !== changePasswordForm.confirmPassword) {
    changePasswordForm.error = '两次输入的新密码不一致'
    return
  }
  changePasswordForm.submitting = true
  const epoch = getAuthSessionEpoch()
  const operation = ++passwordOperation
  const isCurrent = () => deps.isCurrentAuth(epoch) && operation === passwordOperation
  try {
    const updated = await changePassword(changePasswordForm.currentPassword, changePasswordForm.newPassword)
    if (!isCurrent()) return
    deps.currentUser.value = updated
    changePasswordDialogVisible.value = false
    ElMessage.success('密码已更新')
  } catch (error) {
    if (!isCurrent()) return
    changePasswordForm.error = error instanceof Error
      ? error.message.replace(/^Auth request failed: \d+\s*/, '')
      : '修改失败，请重试'
  } finally {
    if (isCurrent()) changePasswordForm.submitting = false
  }
}

const reset = () => {
  passwordOperation += 1
  changePasswordDialogVisible.value = false
  Object.assign(changePasswordForm, { currentPassword: '', newPassword: '', confirmPassword: '', error: '', submitting: false })
}
return {
  reset,
  get passwordOperation() { return passwordOperation }, set passwordOperation(value: typeof passwordOperation) { passwordOperation = value },
  currentUserInitials,
  canEdit,
  sidebarGroups,
  changePasswordDialogVisible,
  changePasswordForm,
  openChangePasswordDialog,
  submitChangePassword,
}
}
