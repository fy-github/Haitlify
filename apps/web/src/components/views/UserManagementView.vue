<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { KeyRound, Plus, Upload } from '@lucide/vue'
import ResponsiveDrawer from '../shell/ResponsiveDrawer.vue'
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
const keyword = ref('')

// ---- 特权写的 authority:挂载代 + 当前登录账号 ----
// 确认框/请求都跨 await;发起时取一次 authority,await 之后再校验同一口径。
// 不再在每个回调里单独补 flag:身份(props.currentUsername)变化或组件卸载都会推进代次,
// 所有挂起的确认随即失效,不再发写。
let writeAuthorityGeneration = 0
let disposed = false

const invalidateWriteAuthority = (): void => {
  writeAuthorityGeneration += 1
}

watch(() => props.currentUsername, invalidateWriteAuthority)

/** await 之前调用;返回的函数在 await 之后调用一次,false = 该动作已不属于当前上下文。 */
const captureWriteAuthority = (): (() => boolean) => {
  const generation = writeAuthorityGeneration
  const owner = props.currentUsername
  return () => !disposed && generation === writeAuthorityGeneration && owner === props.currentUsername
}

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

const roleOrder: readonly PlatformRole[] = ['ADMIN', 'EDITOR', 'VIEWER']

const errorText = (error: unknown, fallback: string): string =>
  error instanceof Error ? error.message.replace(/^Auth request failed: \d+\s*/, '') : fallback

const load = async (): Promise<void> => {
  loading.value = true
  try {
    users.value = await listUsers()
  } catch (error) {
    ElMessage.error(errorText(error, '加载用户失败'))
  } finally {
    loading.value = false
  }
}

const filteredUsers = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  if (!key) return users.value
  return users.value.filter((user) =>
    user.username.toLowerCase().includes(key)
    || user.displayName.toLowerCase().includes(key)
    || roleLabels[user.role].includes(key)
    || user.role.toLowerCase().includes(key)
  )
})

// ---- 列表分页:后端返回全量列表,前端只做展示分页 ----
const pageSize = 10
const page = ref(1)

watch(keyword, () => { page.value = 1 })
watch(users, () => {
  const maxPage = Math.max(1, Math.ceil(filteredUsers.value.length / pageSize))
  if (page.value > maxPage) page.value = maxPage
})

const pagedUsers = computed(() =>
  filteredUsers.value.slice((page.value - 1) * pageSize, page.value * pageSize)
)
const rangeStart = computed(() => (filteredUsers.value.length ? (page.value - 1) * pageSize + 1 : 0))
const rangeEnd = computed(() => Math.min(page.value * pageSize, filteredUsers.value.length))
const totalPages = computed(() => Math.max(1, Math.ceil(filteredUsers.value.length / pageSize)))

// ---- 横向滚动同步(设计稿:窄屏显示提示条 + 可拖动的横向滚动滑块) ----
const tableScrollRef = ref<HTMLElement | null>(null)
const horizontalMax = ref(0)
const horizontalValue = ref(0)

const syncHorizontal = (): void => {
  const box = tableScrollRef.value
  if (!box) return
  horizontalMax.value = Math.max(0, box.scrollWidth - box.clientWidth)
  horizontalValue.value = box.scrollLeft
}

const onHorizontalInput = (event: Event): void => {
  const value = Number((event.target as HTMLInputElement).value)
  const box = tableScrollRef.value
  if (!box) return
  box.scrollLeft = value
  horizontalValue.value = box.scrollLeft
}

const isSelf = (user: PlatformUserSummary): boolean => user.username === props.currentUsername
const enabledAdminCount = computed(() => users.value.filter((user) => user.role === 'ADMIN' && user.enabled).length)
/** 最后一个可用管理员:再停用或降级就没有管理员可用 */
const isLastEnabledAdmin = (user: PlatformUserSummary): boolean =>
  user.role === 'ADMIN' && user.enabled && enabledAdminCount.value <= 1

/**
 * 抽屉里的账号保护(当前账号 / 最后可用管理员)。
 * 校验阶段与「确认框返回后」共用同一判定:确认期间 props.currentUsername 或 users 变了也要重算,
 * 不再依赖确认前算出的结论。
 */
const drawerTargetGuardError = (editing: PlatformUserSummary | null): string => {
  if (!editing) return ''
  if (isSelf(editing) && (!draft.value.enabled || draft.value.role !== 'ADMIN')) {
    return '当前账号受保护:不能停用或降级当前登录的管理员账号'
  }
  if (isLastEnabledAdmin(editing) && (!draft.value.enabled || draft.value.role !== 'ADMIN')) {
    return '至少保留一个可用的管理员账号'
  }
  return ''
}

// ---- 抽屉:新建 / 编辑 ----

type DrawerMode = 'create' | 'edit'

interface UserDraft {
  username: string
  displayName: string
  avatar: string
  password: string
  resetPassword: string
  role: PlatformRole
  enabled: boolean
}

const drawerVisible = ref(false)
const drawerMode = ref<DrawerMode>('create')
const drawerBaseline = ref('')
const drawerError = ref('')
const editingUser = ref<PlatformUserSummary | null>(null)

const draft = ref<UserDraft>({
  username: '',
  displayName: '',
  avatar: '',
  password: '',
  resetPassword: '',
  role: 'EDITOR',
  enabled: true
})

const serializeDraft = (value: UserDraft): string => JSON.stringify(value)
const isDirty = computed(() => serializeDraft(draft.value) !== drawerBaseline.value)

const drawerTitle = computed(() =>
  drawerMode.value === 'create' ? '新建用户' : `编辑用户 · ${editingUser.value?.username ?? ''}`
)

const resetDraft = (next: UserDraft): void => {
  draft.value = { ...next }
  drawerBaseline.value = serializeDraft(draft.value)
  drawerError.value = ''
}

const openCreate = (): void => {
  drawerMode.value = 'create'
  editingUser.value = null
  resetDraft({
    username: '',
    displayName: '',
    avatar: '',
    password: '',
    resetPassword: '',
    role: 'EDITOR',
    enabled: true
  })
  drawerVisible.value = true
}

const openEdit = (user: PlatformUserSummary): void => {
  drawerMode.value = 'edit'
  editingUser.value = user
  resetDraft({
    username: user.username,
    displayName: user.displayName,
    avatar: user.avatar || '',
    password: '',
    resetPassword: '',
    role: user.role,
    enabled: user.enabled
  })
  drawerVisible.value = true
}

/** 关闭抽屉前保护未保存修改(新建/编辑同一套规则) */
const requestCloseDrawer = async (): Promise<void> => {
  if (!isDirty.value || saving.value) {
    drawerVisible.value = false
    return
  }
  const confirmed = await ElMessageBox.confirm(
    '当前修改尚未保存,关闭后这些修改会丢失。',
    '放弃未保存的修改？',
    {
      confirmButtonText: '放弃修改',
      cancelButtonText: '继续编辑',
      type: 'warning',
      customClass: 'theme-confirm-box user-management-confirm'
    }
  ).then(() => true).catch(() => false)
  if (!confirmed) return
  // 先对齐基线再关闭:否则 el-drawer 关闭时再次触发 update 事件,会把守卫重新拉起
  drawerBaseline.value = serializeDraft(draft.value)
  drawerVisible.value = false
}

const handleDrawerModel = (value: boolean): void => {
  if (value) {
    drawerVisible.value = true
    return
  }
  void requestCloseDrawer()
}

const readAvatarFile = async (file: File): Promise<string> =>
  await new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(new Error('头像读取失败'))
    reader.readAsDataURL(file)
  })

const handleAvatarChange = (file: { raw?: File } | undefined): void => {
  const raw = file?.raw
  if (!raw) return
  if (!raw.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return
  }
  void readAvatarFile(raw)
    .then((avatar) => { draft.value.avatar = avatar })
    .catch((error) => ElMessage.error(errorText(error, '头像上传失败')))
}

/** 角色或启停状态变化影响权限,保存前需要一次确认 */
const confirmSensitiveChange = async (): Promise<boolean> => {
  if (drawerMode.value !== 'edit' || !editingUser.value) return true
  const user = editingUser.value
  const changes: string[] = []
  if (draft.value.role !== user.role) {
    changes.push(`角色：${roleLabels[user.role]} → ${roleLabels[draft.value.role]}`)
  }
  if (draft.value.enabled !== user.enabled) {
    changes.push(`账号状态：${user.enabled ? '启用' : '停用'} → ${draft.value.enabled ? '启用' : '停用'}`)
  }
  if (!changes.length) return true
  return await ElMessageBox.confirm(
    `${changes.join('；')}。保存后立即对该账号生效。`,
    '确认权限变更',
    {
      confirmButtonText: '确认保存',
      cancelButtonText: '返回修改',
      type: 'warning',
      customClass: 'theme-confirm-box user-management-confirm'
    }
  ).then(() => true).catch(() => false)
}

const submitDrawer = async (): Promise<void> => {
  drawerError.value = ''
  const nextUsername = draft.value.username.trim()
  const nextDisplayName = draft.value.displayName.trim()
  const editing = drawerMode.value === 'edit' ? editingUser.value : null

  if (drawerMode.value === 'create') {
    if (!nextUsername) {
      drawerError.value = '用户名不能为空'
      return
    }
    if (!nextDisplayName) {
      drawerError.value = '显示名不能为空,请填写后重试'
      return
    }
    if (users.value.some((user) => user.username.toLowerCase() === nextUsername.toLowerCase())) {
      drawerError.value = '用户名已存在,请换一个用户名'
      return
    }
    if (draft.value.password.length < 8) {
      drawerError.value = '初始密码长度至少 8 位'
      return
    }
  } else {
    if (!nextDisplayName) {
      drawerError.value = '显示名不能为空,请填写后重试'
      return
    }
    if (draft.value.resetPassword && draft.value.resetPassword.length < 8) {
      drawerError.value = '重置密码长度至少 8 位'
      return
    }
    // 抽屉路径与列表路径同源保护:当前账号不可停用/降级,最后一位可用管理员不可移除
    const guardError = drawerTargetGuardError(editing)
    if (guardError) {
      drawerError.value = guardError
      return
    }
  }

  // 确认框跨 await:发起时固定 authority,返回后用同一口径校验(身份变化/卸载即失效)
  const isCurrent = captureWriteAuthority()
  const confirmed = await confirmSensitiveChange()
  if (!confirmed) return
  if (!isCurrent()) {
    drawerError.value = '当前账号身份已变更,本次保存已取消,请重新操作'
    return
  }
  // 确认期间 users/currentUsername 可能已变:用最新状态重算同一套账号保护
  const guardAfterConfirm = drawerTargetGuardError(editing)
  if (guardAfterConfirm) {
    drawerError.value = guardAfterConfirm
    return
  }

  saving.value = true
  try {
    if (drawerMode.value === 'create') {
      await createUser({
        username: nextUsername,
        displayName: nextDisplayName,
        avatar: draft.value.avatar,
        password: draft.value.password,
        role: draft.value.role
      })
      ElMessage.success('用户已创建,首次登录需修改密码')
    } else if (editingUser.value) {
      await updateUser(editingUser.value.username, {
        displayName: nextDisplayName,
        avatar: draft.value.avatar,
        role: draft.value.role,
        enabled: draft.value.enabled,
        ...(draft.value.resetPassword ? { password: draft.value.resetPassword } : {})
      })
      ElMessage.success(draft.value.resetPassword ? '已保存并重置密码' : '已保存')
    }
    // 写请求已发出,但提交期间上下文变了:不再改动新上下文的状态
    if (!isCurrent()) return
    drawerBaseline.value = serializeDraft(draft.value)
    drawerVisible.value = false
    await load()
  } catch (error) {
    drawerError.value = errorText(error, drawerMode.value === 'create' ? '创建失败,请重试' : '保存失败,请重试')
  } finally {
    saving.value = false
  }
}

// ---- 列表快捷操作 ----

const toggleEnabled = async (user: PlatformUserSummary): Promise<void> => {
  if (isSelf(user)) {
    ElMessage.warning('不能停用当前登录的账号')
    return
  }
  if (user.enabled && isLastEnabledAdmin(user)) {
    ElMessage.warning('至少保留一个可用的管理员账号')
    return
  }
  const nextEnabled = !user.enabled
  const isCurrent = captureWriteAuthority()
  const confirmed = await ElMessageBox.confirm(
    nextEnabled
      ? `启用 ${user.username} 后该账号可以重新登录。`
      : `停用 ${user.username} 后该账号将无法登录,已有会话按后端规则失效。`,
    nextEnabled ? '启用账号' : '停用账号',
    {
      confirmButtonText: nextEnabled ? '启用' : '停用',
      cancelButtonText: '取消',
      type: 'warning',
      customClass: 'theme-confirm-box user-management-confirm'
    }
  ).then(() => true).catch(() => false)
  if (!confirmed) return
  if (!isCurrent()) return
  // 确认期间登录账号/列表可能已变:重跑同一套列表守卫
  if (isSelf(user)) {
    ElMessage.warning('不能停用当前登录的账号')
    return
  }
  if (user.enabled && isLastEnabledAdmin(user)) {
    ElMessage.warning('至少保留一个可用的管理员账号')
    return
  }

  try {
    await updateUser(user.username, { enabled: nextEnabled })
    if (!isCurrent()) return
    ElMessage.success(nextEnabled ? '账号已启用' : '账号已停用')
    await load()
  } catch (error) {
    ElMessage.error(errorText(error, '账号状态更新失败'))
  }
}

const removeUser = async (user: PlatformUserSummary): Promise<void> => {
  if (isSelf(user)) {
    ElMessage.warning('不能删除当前登录的账号')
    return
  }
  if (isLastEnabledAdmin(user)) {
    ElMessage.warning('至少保留一个可用的管理员账号')
    return
  }
  const isCurrent = captureWriteAuthority()
  const confirmed = await ElMessageBox.confirm(
    `确认删除用户 ${user.username} 吗？删除后该账号无法再登录,且不可恢复。`,
    '删除用户',
    {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
      customClass: 'theme-confirm-box user-management-confirm user-management-danger-confirm'
    }
  ).then(() => true).catch(() => false)
  if (!confirmed) return
  if (!isCurrent()) return
  // 确认期间登录账号/列表可能已变:重跑同一套列表守卫
  if (isSelf(user)) {
    ElMessage.warning('不能删除当前登录的账号')
    return
  }
  if (isLastEnabledAdmin(user)) {
    ElMessage.warning('至少保留一个可用的管理员账号')
    return
  }

  try {
    await deleteUser(user.username)
    if (!isCurrent()) return
    ElMessage.success('用户已删除')
    await load()
  } catch (error) {
    ElMessage.error(errorText(error, '删除失败,请重试'))
  }
}

/** 设计稿的角色胶囊:admin / editor / viewer 三套底色与文字色 */
const rolePillClass = (role: PlatformRole): string =>
  `user-role--${role === 'ADMIN' ? 'admin' : role === 'EDITOR' ? 'editor' : 'viewer'}`

/** 设计稿的时间格式:YYYY-MM-DD HH:mm */
const formatCreatedAt = (value: string): string => {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number): string => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

const handleResize = (): void => { syncHorizontal() }

onMounted(async () => {
  await load()
  await nextTick()
  syncHorizontal()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  // 卸载后所有挂起的确认/请求都不再属于任何上下文
  disposed = true
  invalidateWriteAuthority()
  window.removeEventListener('resize', handleResize)
})

// 筛选/分页后表格宽度可能变化,重新计算可滚动范围
watch([pagedUsers, keyword], async () => {
  await nextTick()
  syncHorizontal()
})
</script>

<template>
  <header class="settings-page-head user-head">
    <div>
      <span class="settings-page-kicker">设置中心 · 管理员</span>
      <h1>用户管理</h1>
      <p>管理平台账号、角色和启停状态；不可删除或停用当前账号。</p>
    </div>
    <div class="toolbar">
      <el-button :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" @click="openCreate">新建用户</el-button>
    </div>
  </header>

  <section class="library-workspace user-body">
    <div class="user-summary">
      <span>{{ filteredUsers.length }} / {{ users.length }} 个账号</span>
      <span>至少保留一个可用管理员</span>
      <span class="user-summary__notice" role="status" aria-live="polite"></span>
    </div>
    <div class="user-panel">
      <div class="user-panel__head">
        <div>
          <strong>平台账号</strong>
          <span>共 {{ users.length }} 个账号 · 当前筛选 {{ filteredUsers.length }} 个</span>
        </div>
        <el-input
          v-model="keyword"
          class="user-search"
          clearable
          placeholder="搜索用户名或显示名"
          aria-label="搜索用户名或显示名"
        />
      </div>

      <p class="user-scroll-hint" aria-hidden="true">← 左右滑动查看角色、状态与操作 · 上下滚动查看全部账号 →</p>
      <div ref="tableScrollRef" class="user-table-scroll" role="region" aria-label="用户列表，可滚动" tabindex="0" @scroll="syncHorizontal">
        <el-table
          v-loading="loading"
          :data="pagedUsers"
          row-key="username"
          class="user-management-table"
          empty-text="没有匹配的账号"
        >
          <el-table-column label="用户" min-width="380">
            <template #default="{ row }: { row: PlatformUserSummary }">
              <div class="user-cell">
                <span class="user-avatar">
                  <img v-if="row.avatar" :src="row.avatar" :alt="`${row.username} 的头像`" />
                  <span v-else>{{ (row.displayName || row.username).charAt(0).toUpperCase() }}</span>
                </span>
                <span class="user-cell__text">
                  <span class="user-cell__name">
                    {{ row.username }}
                    <span v-if="row.username === currentUsername" class="user-self">当前账号</span>
                  </span>
                  <small v-if="row.displayName">{{ row.displayName }}</small>
                </span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="角色" width="180">
            <template #default="{ row }: { row: PlatformUserSummary }">
              <span class="user-role" :class="rolePillClass(row.role)">{{ roleLabels[row.role] }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template #default="{ row }: { row: PlatformUserSummary }">
              <span :class="row.enabled ? 'user-ok' : 'user-off'">{{ row.enabled ? '启用' : '停用' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="首次登录改密" width="210">
            <template #default="{ row }: { row: PlatformUserSummary }">
              <span class="user-pending">{{ row.mustChangePassword ? '待修改' : '已完成' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="190">
            <template #default="{ row }: { row: PlatformUserSummary }">
              {{ formatCreatedAt(row.createdAt) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140">
            <template #default="{ row }: { row: PlatformUserSummary }">
              <div class="user-actions">
                <button type="button" class="user-link" @click="openEdit(row)">编辑</button>
                <button
                  type="button"
                  class="user-link"
                  :class="row.enabled ? 'user-link--stop' : 'user-link--enable'"
                  :disabled="row.username === currentUsername || (row.enabled && isLastEnabledAdmin(row))"
                  :title="row.username === currentUsername ? '不能停用当前登录的账号' : undefined"
                  @click="toggleEnabled(row)"
                >
                  {{ row.enabled ? '停用' : '启用' }}
                </button>
                <button
                  type="button"
                  class="user-link user-link--danger"
                  :disabled="row.username === currentUsername || isLastEnabledAdmin(row)"
                  :title="row.username === currentUsername ? '不能删除当前登录的账号' : undefined"
                  @click="removeUser(row)"
                >
                  删除
                </button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="user-hscroll">
        <span aria-hidden="true">横向滚动</span>
        <input
          type="range"
          min="0"
          :max="horizontalMax"
          :value="horizontalValue"
          step="1"
          :disabled="horizontalMax <= 0"
          aria-label="横向滚动用户列表"
          @input="onHorizontalInput"
        />
      </div>
      <footer class="user-panel__footer">
        <span>{{ filteredUsers.length ? `显示 ${rangeStart}–${rangeEnd} 条` : '无匹配账号' }}</span>
        <div class="user-pager">
          <button type="button" :disabled="page <= 1" @click="page -= 1">上一页</button>
          <span class="user-pager__index">{{ page }} / {{ totalPages }}</span>
          <button type="button" :disabled="page >= totalPages" @click="page += 1">下一页</button>
        </div>
      </footer>
    </div>
    <footer class="user-footer">
      <span>账号数据来自平台认证服务;停用与删除立即生效。</span>
      <span v-if="filteredUsers.length !== users.length">已按关键词筛选</span>
    </footer>
  </section>

  <ResponsiveDrawer
    :model-value="drawerVisible"
    class="user-drawer-shell"
    :title="drawerTitle"
    :description="drawerMode === 'create' ? '新账号首次登录需要修改密码。' : '角色与启停状态在此统一调整。'"
    size="min(740px, calc(100vw - 48px))"
    @update:model-value="handleDrawerModel"
  >
    <div class="user-drawer">
      <div v-if="drawerMode === 'edit' && editingUser" class="user-drawer__identity">
        <span class="user-avatar user-avatar--large">
          <img v-if="draft.avatar" :src="draft.avatar" alt="头像预览" />
          <span v-else>{{ (draft.displayName || draft.username).charAt(0).toUpperCase() }}</span>
        </span>
        <div>
          <strong>{{ editingUser.username }}</strong>
          <small>创建于 {{ new Date(editingUser.createdAt).toLocaleString() }}</small>
        </div>
      </div>

      <div class="studio-form-grid user-drawer__grid">
        <label v-if="drawerMode === 'create'">用户名
          <el-input v-model="draft.username" placeholder="如 qa.zhang" maxlength="64" />
        </label>
        <label v-else>用户名
          <el-input :model-value="draft.username" disabled />
        </label>
        <label>显示名
          <el-input v-model="draft.displayName" placeholder="如 张三" maxlength="128" />
        </label>

        <label class="studio-form-wide">头像
          <div class="user-avatar-upload">
            <el-upload
              action="#"
              :show-file-list="false"
              :auto-upload="false"
              accept="image/*"
              :on-change="handleAvatarChange"
            >
              <el-button plain>
                <Upload :size="16" />
                上传头像
              </el-button>
            </el-upload>
            <el-button text :disabled="!draft.avatar" @click="draft.avatar = ''">清除头像</el-button>
            <small>支持 PNG、JPG、WebP，保存后同步到用户资料。</small>
          </div>
        </label>

        <label class="studio-form-wide">角色
          <div class="user-roles" role="radiogroup" aria-label="角色">
            <button
              v-for="role in roleOrder"
              :key="role"
              type="button"
              role="radio"
              class="user-role-option"
              :class="{ 'user-role-option--active': draft.role === role }"
              :aria-checked="draft.role === role"
              @click="draft.role = role"
            >
              <strong>{{ roleLabels[role] }}</strong>
              <small>{{ roleDescriptions[role] }}</small>
            </button>
          </div>
        </label>

        <label v-if="drawerMode === 'edit'">账号状态
          <el-switch v-model="draft.enabled" active-text="启用" inactive-text="停用" />
        </label>

        <label v-if="drawerMode === 'create'">初始密码
          <el-input v-model="draft.password" show-password placeholder="至少 8 位,首次登录需修改" />
        </label>
        <label v-else>重置密码（可选）
          <el-input v-model="draft.resetPassword" show-password placeholder="留空则不修改,填写后下次登录需改密" />
        </label>
      </div>

      <p v-if="drawerError" class="user-drawer__error" role="alert">{{ drawerError }}</p>

      <div class="drawer-actions user-drawer__actions">
        <span class="user-drawer__status">
          {{ drawerMode === 'create' ? '新账号首次登录需改密' : '角色与启停变更保存后立即生效' }}
        </span>
        <div class="user-drawer__buttons">
          <el-button @click="requestCloseDrawer">取消</el-button>
          <el-button type="primary" :loading="saving" @click="submitDrawer">
            <KeyRound v-if="drawerMode === 'edit'" :size="16" />
            <Plus v-else :size="16" />
            {{ drawerMode === 'create' ? '创建账号' : '保存修改' }}
          </el-button>
        </div>
      </div>
    </div>
  </ResponsiveDrawer>
</template>
