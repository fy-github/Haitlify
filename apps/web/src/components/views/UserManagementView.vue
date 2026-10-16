<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { KeyRound, Plus, Upload, UserPlus } from '@lucide/vue'
import {
  createUser,
  deleteUser,
  listUsers,
  updateUser,
  type PlatformRole,
  type PlatformUserSummary
} from '../../domain/authRepository'

const props = defineProps<{
  currentUsername: string
}>()

const users = ref<PlatformUserSummary[]>([])
const loading = ref(false)
const saving = ref(false)

const roleLabels: Record<PlatformRole, string> = {
  ADMIN: '管理员',
  EDITOR: '编辑者',
  VIEWER: '观察者'
}

const roleDescriptions: Record<PlatformRole, string> = {
  ADMIN: '全部权限 + 用户管理',
  EDITOR: '可创建与执行用例、配置规则',
  VIEWER: '只读,不能执行写操作'
}

const load = async (): Promise<void> => {
  loading.value = true
  try {
    users.value = await listUsers()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message.replace(/^Auth request failed: \d+\s*/, '') : '加载用户失败')
  } finally {
    loading.value = false
  }
}

// ---- 新建用户 ----

const createDialogVisible = ref(false)
const createForm = ref({
  username: '',
  displayName: '',
  avatar: '',
  password: '',
  role: 'EDITOR' as PlatformRole
})
const createError = ref('')

const openCreateDialog = (): void => {
  createForm.value = { username: '', displayName: '', avatar: '', password: '', role: 'EDITOR' }
  createError.value = ''
  createDialogVisible.value = true
}

const readAvatarFile = async (file: File): Promise<string> =>
  await new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(new Error('头像读取失败'))
    reader.readAsDataURL(file)
  })

const updateAvatarFromUpload = async (file: File | undefined, target: 'create' | 'edit'): Promise<void> => {
  if (!file) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return
  }
  const avatar = await readAvatarFile(file)
  if (target === 'create') {
    createForm.value.avatar = avatar
  } else {
    editForm.value.avatar = avatar
  }
}

const handleAvatarUpload = (file: { raw?: File } | undefined, target: 'create' | 'edit'): void => {
  void updateAvatarFromUpload(file?.raw, target).catch((error) => {
    ElMessage.error(error instanceof Error ? error.message : '头像上传失败')
  })
}

const handleCreateAvatarChange = (file: { raw?: File }): void => {
  handleAvatarUpload(file, 'create')
}

const handleEditAvatarChange = (file: { raw?: File }): void => {
  handleAvatarUpload(file, 'edit')
}

const submitCreate = async (): Promise<void> => {
  createError.value = ''
  if (!createForm.value.username.trim()) {
    createError.value = '用户名不能为空'
    return
  }
  if (createForm.value.password.length < 8) {
    createError.value = '初始密码长度至少 8 位'
    return
  }
  saving.value = true
  try {
    await createUser({
      username: createForm.value.username.trim(),
      displayName: createForm.value.displayName.trim(),
      avatar: createForm.value.avatar,
      password: createForm.value.password,
      role: createForm.value.role
    })
    createDialogVisible.value = false
    ElMessage.success('用户已创建,首次登录需修改密码')
    await load()
  } catch (error) {
    createError.value = error instanceof Error
      ? error.message.replace(/^Auth request failed: \d+\s*/, '')
      : '创建失败,请重试'
  } finally {
    saving.value = false
  }
}

// ---- 编辑用户 ----

const editDialogVisible = ref(false)
const editingUser = ref<PlatformUserSummary | null>(null)
const editForm = ref({
  displayName: '',
  avatar: '',
  role: 'VIEWER' as PlatformRole,
  enabled: true,
  resetPassword: ''
})
const editError = ref('')

const openEditDialog = (user: PlatformUserSummary): void => {
  editingUser.value = user
  editForm.value = {
    displayName: user.displayName,
    avatar: user.avatar || '',
    role: user.role,
    enabled: user.enabled,
    resetPassword: ''
  }
  editError.value = ''
  editDialogVisible.value = true
}

const submitEdit = async (): Promise<void> => {
  if (!editingUser.value) return
  editError.value = ''
  if (editForm.value.resetPassword && editForm.value.resetPassword.length < 8) {
    editError.value = '重置密码长度至少 8 位'
    return
  }
  saving.value = true
  try {
    await updateUser(editingUser.value.username, {
      displayName: editForm.value.displayName.trim(),
      avatar: editForm.value.avatar,
      role: editForm.value.role,
      enabled: editForm.value.enabled,
      ...(editForm.value.resetPassword ? { password: editForm.value.resetPassword } : {})
    })
    editDialogVisible.value = false
    ElMessage.success(editForm.value.resetPassword ? '已保存并重置密码' : '已保存')
    await load()
  } catch (error) {
    editError.value = error instanceof Error
      ? error.message.replace(/^Auth request failed: \d+\s*/, '')
      : '保存失败,请重试'
  } finally {
    saving.value = false
  }
}

// ---- 删除用户 ----

const removeUser = async (user: PlatformUserSummary): Promise<void> => {
  if (user.username === props.currentUsername) {
    ElMessage.warning('不能删除当前登录的账号')
    return
  }
  const confirmed = await ElMessageBox.confirm(
    `确认删除用户 ${user.username} 吗？删除后该账号无法再登录。`,
    '删除用户',
    {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
      customClass: 'theme-confirm-box'
    }
  ).then(() => true).catch(() => false)
  if (!confirmed) return
  try {
    await deleteUser(user.username)
    ElMessage.success('用户已删除')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message.replace(/^Auth request failed: \d+\s*/, '') : '删除失败,请重试')
  }
}

const roleTagType = (role: PlatformRole): 'danger' | 'primary' | 'info' =>
  role === 'ADMIN' ? 'danger' : role === 'EDITOR' ? 'primary' : 'info'

onMounted(load)
</script>

<template>
  <header class="topbar">
    <div>
      <span class="eyebrow">系统管理</span>
      <h1>用户管理</h1>
    </div>
    <div class="toolbar">
      <el-button type="primary" @click="openCreateDialog">
        <UserPlus :size="16" />
        新建用户
      </el-button>
    </div>
  </header>

  <section class="library-workspace">
    <div class="studio-panel">
      <div class="jira-config-hint user-management-hint">
        角色权限：管理员可管理与授权全部功能；编辑者可创建、执行用例与配置规则；观察者只读。平台至少保留一个可用的管理员。
      </div>

      <el-table
        v-loading="loading"
        :data="users"
        row-key="username"
        class="user-management-table"
      >
        <el-table-column label="头像" width="88">
          <template #default="{ row }: { row: PlatformUserSummary }">
            <span class="user-management-avatar user-management-avatar--table">
              <img v-if="row.avatar" :src="row.avatar" :alt="`${row.username} 的头像`" />
              <span v-else>{{ (row.displayName || row.username).charAt(0).toUpperCase() }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="用户名" min-width="190">
          <template #default="{ row }: { row: PlatformUserSummary }">
            <div class="user-management-user">
              <div class="user-management-user__text">
                <div class="user-management-user__name">
                  {{ row.username }}
                  <span v-if="row.username === currentUsername" class="user-management-self">当前账号</span>
                </div>
                <small v-if="row.displayName">{{ row.displayName }}</small>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="180">
          <template #default="{ row }: { row: PlatformUserSummary }">
            <el-tag :type="roleTagType(row.role)" size="small">{{ roleLabels[row.role] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }: { row: PlatformUserSummary }">
            <span :class="row.enabled ? 'user-management-ok' : 'user-management-off'">
              {{ row.enabled ? '启用' : '停用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="首次登录改密" width="130">
          <template #default="{ row }: { row: PlatformUserSummary }">
            <span v-if="row.mustChangePassword" class="user-management-pending">待修改</span>
            <span v-else class="user-management-ok">已完成</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="160">
          <template #default="{ row }: { row: PlatformUserSummary }">
            {{ new Date(row.createdAt).toLocaleString() }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }: { row: PlatformUserSummary }">
            <el-button link type="primary" @click="openEditDialog(row)">编辑</el-button>
            <el-button link type="danger" :disabled="row.username === currentUsername" @click="removeUser(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </section>

  <!-- 新建用户 -->
  <el-dialog v-model="createDialogVisible" title="新建用户" width="440">
    <div class="studio-form-grid">
      <label>用户名
        <el-input v-model="createForm.username" placeholder="如 qa.zhang" maxlength="64" />
      </label>
      <label>显示名（可选）
        <el-input v-model="createForm.displayName" placeholder="如 张三" maxlength="128" />
      </label>
      <label class="studio-form-wide">头像
        <div class="user-management-avatar-upload">
          <span class="user-management-avatar user-management-avatar--large">
            <img v-if="createForm.avatar" :src="createForm.avatar" alt="头像预览" />
            <span v-else>{{ (createForm.displayName || createForm.username || 'U').charAt(0).toUpperCase() }}</span>
          </span>
          <div class="user-management-avatar-upload__actions">
            <el-upload
              action="#"
              :show-file-list="false"
              :auto-upload="false"
              accept="image/*"
              :on-change="handleCreateAvatarChange"
            >
              <el-button plain>
                <Upload :size="16" />
                上传头像
              </el-button>
            </el-upload>
            <el-button text :disabled="!createForm.avatar" @click="createForm.avatar = ''">清除头像</el-button>
            <small>支持 PNG、JPG、WebP，保存后会同步到用户资料。</small>
          </div>
        </div>
      </label>
      <label>初始密码
        <el-input v-model="createForm.password" show-password placeholder="至少 8 位,首次登录需修改" />
      </label>
      <label>角色
        <el-select v-model="createForm.role" style="width: 100%">
          <el-option v-for="role in (['ADMIN', 'EDITOR', 'VIEWER'] as const)" :key="role" :label="`${roleLabels[role]} — ${roleDescriptions[role]}`" :value="role" />
        </el-select>
      </label>
    </div>
    <p v-if="createError" class="change-password-form__error" role="alert">{{ createError }}</p>
    <template #footer>
      <el-button @click="createDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submitCreate">
        <Plus :size="16" />
        创建
      </el-button>
    </template>
  </el-dialog>

  <!-- 编辑用户 -->
  <el-dialog v-if="editingUser" v-model="editDialogVisible" :title="`编辑用户 ${editingUser.username}`" width="440">
    <div class="studio-form-grid">
      <label>显示名
        <el-input v-model="editForm.displayName" maxlength="128" />
      </label>
      <label class="studio-form-wide">头像
        <div class="user-management-avatar-upload">
          <span class="user-management-avatar user-management-avatar--large">
            <img v-if="editForm.avatar" :src="editForm.avatar" alt="头像预览" />
            <span v-else>{{ (editForm.displayName || editingUser.username).charAt(0).toUpperCase() }}</span>
          </span>
          <div class="user-management-avatar-upload__actions">
            <el-upload
              action="#"
              :show-file-list="false"
              :auto-upload="false"
              accept="image/*"
              :on-change="handleEditAvatarChange"
            >
              <el-button plain>
                <Upload :size="16" />
                上传头像
              </el-button>
            </el-upload>
            <el-button text :disabled="!editForm.avatar" @click="editForm.avatar = ''">清除头像</el-button>
            <small>头像会随用户资料一起保存。</small>
          </div>
        </div>
      </label>
      <label>角色
        <el-select v-model="editForm.role" style="width: 100%">
          <el-option v-for="role in (['ADMIN', 'EDITOR', 'VIEWER'] as const)" :key="role" :label="`${roleLabels[role]} — ${roleDescriptions[role]}`" :value="role" />
        </el-select>
      </label>
      <label>账号状态
        <el-switch v-model="editForm.enabled" active-text="启用" inactive-text="停用" />
      </label>
      <label>重置密码（可选）
        <el-input v-model="editForm.resetPassword" show-password placeholder="留空则不修改,填写后该用户下次登录需改密" />
      </label>
    </div>
    <p v-if="editError" class="change-password-form__error" role="alert">{{ editError }}</p>
    <template #footer>
      <el-button @click="editDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submitEdit">
        <KeyRound :size="16" />
        保存
      </el-button>
    </template>
  </el-dialog>
</template>
