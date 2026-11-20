

export interface ChangePasswordDialogModel {
  changePasswordDialogVisible: boolean
  currentUser: { id?: number | null | undefined; username: string; displayName: string; avatar?: string | undefined; role: string; mustChangePassword: boolean; } | null
  changePasswordForm: { currentPassword: string; newPassword: string; confirmPassword: string; error: string; submitting: boolean; }
  submitChangePassword: () => Promise<void>
}
