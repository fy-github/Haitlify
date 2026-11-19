<script setup lang="ts">
import { computed } from 'vue'
import type { JiraConfig } from '../../domain/jiraConfigRepository'
import { jiraDraftSnapshot } from '../../domain/jiraConfigDraft'

const props = withDefaults(defineProps<{
  /**
   * 当前账号是否可写(由 App.canEdit 逐层透传)。false = 只读角色:
   * 保存 / 连接测试 / 映射增删一律禁用,输入框只读;「重新加载」是读操作,保持可用。
   */
  canEdit?: boolean
  form: JiraConfig
  loading: boolean
  readState?: 'idle' | 'loading' | 'ready' | 'error'
  testing: boolean
  projectJqlRows: Array<{ key: string; value: string }>
  load: () => void
  save: () => void
  test: () => void
  addJqlRow: () => void
  removeJqlRow: (index: number) => void
  syncJqlForm: () => void
  testResult?: { state: 'idle' | 'success' | 'failed' | 'stale'; message: string; target: string }
  savedSnapshot?: string
  validationError?: string
}>(), {
  canEdit: true,
  readState: 'idle',
  testResult: () => ({ state: 'idle', message: '', target: '' }),
  savedSnapshot: '',
  validationError: ''
})

/** 与 App 共用同一快照口径:token、JQL 行(含行内未失焦输入)都计入未保存修改 */
const snapshotOf = computed(() => jiraDraftSnapshot(props.form, props.projectJqlRows))

/** 有未保存修改:与最近一次已保存快照比较(新建页面快照为空时不误报) */
const dirty = computed(() => Boolean(props.savedSnapshot) && snapshotOf.value !== props.savedSnapshot)

const testTitle = computed(() => {
  if (props.testing) return '正在测试连接…'
  if (props.testResult.state === 'success') return '连接测试通过'
  if (props.testResult.state === 'failed') return '连接测试未通过'
  if (props.testResult.state === 'stale') return '连接参数已改变，原测试状态已失效'
  return '连接尚未验证'
})

const testBody = computed(() => {
  if (props.testing) return '正在请求 Jira;请稍候。'
  if (props.testResult.state === 'idle') return '保存配置不会验证连接;点击「连接测试」检查当前 Base URL 与凭据。'
  if (props.testResult.state === 'stale') return '请按当前参数重新测试；旧结果不适用于新连接。'
  return props.testResult.message
})

const testMessageLevel = computed(() => {
  if (props.testing) return 'neutral'
  if (props.testResult.state === 'success') return 'success'
  if (props.testResult.state === 'failed') return 'error'
  if (props.testResult.state === 'stale') return 'warning'
  return 'neutral'
})

const rowsWithContent = computed(() =>
  props.projectJqlRows.filter((row) => row.key.trim() || row.value.trim()).length
)

const readStatus = computed(() => {
  if (props.loading || props.readState === 'loading') return '正在与平台后端同步…'
  if (props.readState === 'ready') return '配置来自平台后端'
  if (props.readState === 'error') return '配置读取失败，尚未确认最新配置；当前表单已保留，请重新加载。'
  return '尚未读取配置，请重新加载。'
})
</script>

<template>
  <header class="settings-page-head jira-head">
    <div>
      <span class="settings-page-kicker">设置中心</span>
      <h1>Jira 配置</h1>
      <p>为缺陷页配置只读查询；不在这里提交或流转 Jira 问题。</p>
    </div>
    <div class="toolbar">
      <el-button :loading="loading" @click="load">重新加载</el-button>
      <el-button :loading="testing" :disabled="!canEdit" @click="test">连接测试</el-button>
      <el-button type="primary" :loading="loading" :disabled="!canEdit" @click="save">保存配置</el-button>
    </div>
  </header>

  <div class="j-state-bar">
    <span data-testid="jira-read-state" :data-state="loading ? 'loading' : readState" role="status" aria-live="polite">{{ readStatus }}</span>
    <span :class="{ 'is-dirty': dirty }">{{ dirty ? '有未保存修改' : '无未保存修改' }}</span>
    <span v-if="!canEdit" class="j-readonly-note" role="status">只读角色：可查看配置，不能保存、测试或增删映射</span>
  </div>

  <div class="j-scroll" tabindex="0" role="region" aria-label="Jira 连接与映射设置">
    <section class="j-card">
      <header class="j-section-head">
        <div>
          <h2>连接设置</h2>
          <p>开关控制只读查询；保存配置不等于连接验证通过。</p>
        </div>
        <label class="j-toggle">
          <input v-model="form.enabled" type="checkbox" aria-label="启用只读查询" :disabled="!canEdit" />
          启用只读查询
        </label>
      </header>

      <div class="j-fields">
        <div class="wide">
          <label for="jira-base-url">Jira Base URL</label>
          <input
            id="jira-base-url"
            v-model="form.baseUrl"
            type="text"
            :readonly="!canEdit"
            autocomplete="off"
            placeholder="https://jira.example.test"
          />
          <small>缺陷页按该地址读取问题;保存后立即生效。</small>
        </div>
        <div>
          <label for="jira-username">账号（用户名）</label>
          <input id="jira-username" v-model="form.username" type="text" :readonly="!canEdit" autocomplete="off" placeholder="jira-reader" />
          <small>使用服务账号只读查询,不需要 Jira 管理权限。</small>
        </div>
        <div>
          <label for="jira-token">Token / 密码</label>
          <input
            id="jira-token"
            v-model="form.token"
            type="password"
            :readonly="!canEdit"
            autocomplete="new-password"
            placeholder="留空则沿用已保存凭证"
          />
          <small v-if="form.maskedToken">已保存：{{ form.maskedToken }}；留空不会清空。</small>
          <small v-else>留空则沿用已保存凭证。</small>
        </div>
      </div>

      <div class="j-credential">
        <span>凭据状态：{{ form.credentialConfigured ? '服务端已保存 Token（脱敏显示）' : '尚未保存 Token' }}</span>
        <span>保存位置：平台后端（加密存储）</span>
      </div>
    </section>

    <section
      class="j-test-state"
      :data-state="testing ? 'idle' : testResult.state"
      :data-message-level="testMessageLevel"
      role="status"
      aria-live="polite"
    >
      <strong>{{ testTitle }}</strong>
      <p>{{ testBody }}</p>
      <small v-if="testResult.target">测试目标：{{ testResult.target }}</small>
    </section>

    <section class="j-card j-mappings">
      <header class="j-section-head">
        <div>
          <h2>项目 JQL 映射</h2>
          <p>项目名对应缺陷页中的项目；一项目一条查询，映射不等于查询执行结果。</p>
        </div>
        <el-button :disabled="!canEdit" @click="addJqlRow">新增映射</el-button>
      </header>

      <div class="j-mapping-scroll">
        <table class="j-table">
          <thead>
            <tr>
              <th>平台项目名</th>
              <th>JQL 查询</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in projectJqlRows" :key="index">
              <td>
                <input
                  v-model="row.key"
                  type="text"
                  :readonly="!canEdit"
                  placeholder="如 HAITLIFY_API"
                  :aria-label="`第 ${index + 1} 行项目名`"
                  @change="syncJqlForm"
                />
              </td>
              <td>
                <textarea
                  v-model="row.value"
                  rows="2"
                  :readonly="!canEdit"
                  placeholder="project = HAITLIFY order by created DESC"
                  :aria-label="`第 ${index + 1} 行 JQL`"
                  @change="syncJqlForm"
                />
              </td>
              <td>
                <button type="button" class="j-remove" :disabled="!canEdit" @click="removeJqlRow(index)">删除</button>
              </td>
            </tr>
            <tr v-if="!projectJqlRows.length">
              <td colspan="3" class="j-empty">还没有 JQL 映射，点击「新增映射」添加。</td>
            </tr>
          </tbody>
        </table>
      </div>

      <footer>
        <span>{{ rowsWithContent }} 条映射 · 共 {{ projectJqlRows.length }} 行</span>
        <span>保存前检查 Base URL、空值与重复项目名；不代替服务端 JQL 语法校验。</span>
      </footer>
    </section>
  </div>

  <p v-if="validationError" class="j-error" role="alert">{{ validationError }}</p>

  <footer class="j-footer">
    <span>只读查询配置；与缺陷提交、Jira CSV 导出字段映射相互独立。</span>
    <span v-if="dirty" class="j-dirty-note">离开前请保存配置</span>
  </footer>
</template>

<style scoped>
.j-state-bar [data-state='error'] {
  color: var(--color-danger);
  overflow-wrap: anywhere;
}

/* 只读角色(VIEWER):写控件置灰、输入只读;「重新加载」仍可读取服务端配置 */
.j-readonly-note {
  color: #8b9a8f;
}

.j-table .j-remove:disabled {
  color: #b9c6bd;
  cursor: not-allowed;
}

.j-fields input[readonly],
.j-table input[readonly],
.j-table textarea[readonly] {
  color: #8496a2;
  background: #f7f9f8;
  cursor: default;
}
</style>