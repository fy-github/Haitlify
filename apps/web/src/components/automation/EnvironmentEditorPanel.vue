<script setup lang="ts">
import { toRefs } from 'vue'
import type { EnvironmentEditorPanelModel } from '../../ui/environmentEditorModel'
import { Plus, Search, Trash2, ArrowUp, ArrowDown } from '@lucide/vue'
import EnvironmentEditorSurface from './EnvironmentEditorSurface.vue'
import type { WebAutomationAction } from '../../domain/webAutomationCase'
import type { WebAutomationStep } from '../../domain/webAutomationCase'
import type { EnvironmentEntryType } from '../../domain/executionEnvironmentRepository'
import type { SavedExecutionEnvironment } from '../../domain/executionEnvironmentRepository'
import type { ExecutionEnvironmentLibrary } from '../../domain/executionEnvironmentRepository'
import type { NavKey } from '../../domain/appNavigation'
import type { EnvironmentKind } from '../../ui/appPorts'
import type { AuthStatus } from '../../ui/useAppSession'
const props = defineProps<{ model: EnvironmentEditorPanelModel }>()
const { authStatus, environmentVisible, activeNav, environmentEditorKind, addExecutionEnvironment, currentEnvironmentLibrary, editingEnvironment, openEnvironmentEditor, editingWebNetworkPolicyMode, editingWebAllowedRequestOriginsText, webLoginDiscoveryLoading, canEdit, startWebLoginDiscovery, webEnvironmentVerifying, verifyEditingWebEnvironmentLogin, addWebLogoutStep, updateWebLogoutStepAction, webLogoutActionOptions, webLogoutTargetPlaceholder, moveWebLogoutStep, removeWebLogoutStep, webEnvironmentVerification, clearWebLoginSession, curlImportText, importCurlToEnvironment, addEnvironmentEntry, deleteEnvironmentEntry, deleteExecutionEnvironment, cancelEnvironmentEditing, saveEditingEnvironment } = toRefs(props.model)

</script>

<template>
<EnvironmentEditorSurface
      v-if="authStatus === 'authenticated'"
      v-model="environmentVisible"
      :inline="activeNav === 'environments'"
      :web="environmentEditorKind === 'web'"
      :title="environmentEditorKind === 'web' ? 'Web 运行环境配置' : 'API 运行环境配置'"
    >
      <div class="environment-drawer">
        <aside class="environment-list">
          <div class="environment-list-head">
            <strong>环境</strong>
            <el-button type="primary" @click="addExecutionEnvironment()">
              <Plus :size="14" />
              新增
            </el-button>
          </div>
          <button
            v-for="environment in currentEnvironmentLibrary.environments"
            :key="environment.id"
            class="environment-list-item"
            :class="{ active: editingEnvironment.id === environment.id }"
            @click="openEnvironmentEditor(environment.id)"
          >
            <span>{{ environment.name }}</span>
            <code>{{ environment.baseUrl || '未配置 Base URL' }}</code>
          </button>
        </aside>

        <section class="environment-editor">
          <template v-if="environmentEditorKind === 'web' && editingEnvironment.web">
            <section class="environment-section environment-section--runtime">
              <header class="environment-section__head">
                <div>
                  <strong>运行参数</strong>
                  <span>浏览器、视口与执行等待策略</span>
                </div>
              </header>
              <div class="environment-form-grid web-environment-grid">
                <label>
                  <span>环境名称</span>
                  <el-input v-model="editingEnvironment.name" placeholder="Web 测试环境" />
                </label>
                <label class="environment-form-wide">
                  <span>Web 访问入口</span>
                  <el-input v-model="editingEnvironment.baseUrl" placeholder="https://haitlify.example.com" />
                </label>
                <label>
                   <span>浏览器</span>
                   <el-select v-model="editingEnvironment.web.browser">
                     <el-option label="跟随用例" value="follow-case" />
                     <el-option label="Chromium" value="chromium" />
                    <el-option label="Google Chrome" value="chrome" />
                    <el-option label="Microsoft Edge" value="msedge" />
                    <el-option label="Firefox" value="firefox" />
                    <el-option label="WebKit" value="webkit" />
                  </el-select>
                </label>
                <label>
                  <span>视口</span>
                  <el-input v-model="editingEnvironment.web.viewport" placeholder="1440x900" />
                </label>
                <label>
                  <span>Headless</span>
                  <el-switch v-model="editingEnvironment.web.headless" active-text="开启" inactive-text="关闭" />
                </label>
                <label>
                  <span>默认等待超时</span>
                  <el-input-number v-model="editingEnvironment.web.defaultTimeoutMs" :min="1000" :step="1000" controls-position="right" />
                </label>
                <label>
                  <span>页面加载超时</span>
                  <el-input-number v-model="editingEnvironment.web.navigationTimeoutMs" :min="1000" :step="1000" controls-position="right" />
                </label>
                <label>
                  <span>等待页面状态</span>
                  <el-select v-model="editingEnvironment.web.waitUntil">
                    <el-option label="load" value="load" />
                    <el-option label="domcontentloaded" value="domcontentloaded" />
                    <el-option label="networkidle" value="networkidle" />
                  </el-select>
                </label>
                <label>
                  <span>截图策略</span>
                  <el-select v-model="editingEnvironment.web.screenshotStrategy">
                    <el-option label="不截图" value="none" />
                    <el-option label="失败截图" value="on_failure" />
                    <el-option label="每步截图" value="always" />
                  </el-select>
                </label>
                <label>
                  <span>视频录制</span>
                  <el-switch v-model="editingEnvironment.web.videoRecording" active-text="开启" inactive-text="关闭" />
                </label>
                <label>
                  <span>失败重试次数</span>
                  <el-input-number v-model="editingEnvironment.web.retryCount" :min="0" :max="10" controls-position="right" />
                </label>
                <label class="environment-form-wide">
                  <span>网络放行策略</span>
                  <el-select v-model="editingWebNetworkPolicyMode" class="environment-policy-select">
                    <el-option label="黑名单（默认：只拦截名单内的请求，其余放行）" value="blocklist" />
                    <el-option label="白名单（只放行名单内的请求，其余拦截）" value="allowlist" />
                  </el-select>
                </label>
                <label class="environment-form-wide">
                  <span>{{ editingWebNetworkPolicyMode === 'blocklist' ? '拦截名单（每行一个）' : '放行名单（每行一个）' }}</span>
                  <el-input
                    v-model="editingWebAllowedRequestOriginsText"
                    type="textarea"
                    :rows="3"
                    :placeholder="editingWebNetworkPolicyMode === 'blocklist'
                      ? 'https://ads.example.com\nhttps://api.example.com/internal/write（带路径 = 只拦这一条）'
                      : 'https://auth.example.com\nhttps://api.example.com/v4/uc/n/auth/login（带路径 = 精确放行，含 POST）'"
                  />
                  <small class="environment-form-hint">{{ editingWebNetworkPolicyMode === 'blocklist'
                    ? '取证默认不拦截任何请求；仅拦截下列条目（Origin 拦整个来源，带路径 URL 只拦该请求）。适合可信的测试环境。'
                    : '仅放行下列条目（Origin 放行页面资源与 GET 请求；带路径的完整 URL 精确放行，含 POST），其余请求一律拦截，失败信息会点名被拦来源。' }}</small>
                </label>
              </div>
            </section>

            <div class="environment-section environment-section--lifecycle">
              <div class="environment-entries-head">
                <strong>自动登录</strong>
                <div class="environment-inline-actions">
                  <el-switch v-model="editingEnvironment.web.login.enabled" active-text="开启" inactive-text="关闭" />
                  <el-button
                    :loading="webLoginDiscoveryLoading"
                    :disabled="!canEdit"
                    @click="startWebLoginDiscovery"
                  >
                    <Search :size="15" />
                    快速识别登录
                  </el-button>
                  <el-button
                    type="primary"
                    plain
                    :loading="webEnvironmentVerifying"
                    @click="verifyEditingWebEnvironmentLogin"
                  >
                    验证登录
                  </el-button>
                </div>
              </div>

              <div class="environment-login-tip">
                <span>用于真实浏览器先执行登录，再进入 Web 用例步骤。</span>
              </div>

              <div v-if="editingEnvironment.web.login.enabled" class="environment-form-grid web-environment-grid">
                <label class="environment-form-wide">
                  <span>登录页地址</span>
                  <el-input v-model="editingEnvironment.web.login.loginUrl" placeholder="/#/login 或 https://haitlify.example.com/#/login" />
                </label>
                <label>
                  <span>登录账号</span>
                  <el-input v-model="editingEnvironment.web.login.username" placeholder="admin" />
                </label>
                <label>
                  <span>登录密码</span>
                  <el-input v-model="editingEnvironment.web.login.password" show-password placeholder="请输入登录密码" />
                </label>
                <label>
                  <span>账号输入框定位器</span>
                  <el-input v-model="editingEnvironment.web.login.usernameLocator" placeholder="css=input[name='username']" />
                </label>
                <label>
                  <span>密码输入框定位器</span>
                  <el-input v-model="editingEnvironment.web.login.passwordLocator" placeholder="css=input[type='password']" />
                </label>
                <label class="environment-form-wide">
                  <span>登录按钮定位器</span>
                  <el-input v-model="editingEnvironment.web.login.submitLocator" placeholder="role=button[name='登录']" />
                </label>
                <label>
                  <span>成功判定方式</span>
                  <el-select v-model="editingEnvironment.web.login.successType">
                    <el-option label="URL 命中" value="url" />
                    <el-option label="元素可见" value="visible" />
                  </el-select>
                </label>
                <label class="environment-form-wide">
                  <span>成功判定目标</span>
                  <el-input
                    v-model="editingEnvironment.web.login.successTarget"
                    :placeholder="editingEnvironment.web.login.successType === 'url' ? '/user-center' : 'text=用户中心'"
                  />
                </label>
              </div>

              <div class="environment-lifecycle-divider"></div>

              <div class="environment-entries-head">
                <div>
                  <strong>退出登录后置条件</strong>
                  <div class="environment-login-tip">每个浏览器完成全部用例后执行一次。</div>
                </div>
                <div class="environment-inline-actions">
                  <el-switch v-model="editingEnvironment.web.logout.enabled" active-text="开启" inactive-text="关闭" />
                  <el-button
                    :disabled="!editingEnvironment.web.logout.enabled"
                    @click="addWebLogoutStep"
                  >
                    <Plus :size="15" />
                    添加步骤
                  </el-button>
                </div>
              </div>

              <div
                v-if="editingEnvironment.web.logout.enabled"
                class="web-logout-step-list"
              >
                <div
                  v-for="(step, index) in editingEnvironment.web.logout.steps"
                  :key="step.id"
                  class="web-logout-step-row"
                >
                  <span class="web-logout-step-index">{{ index + 1 }}</span>
                  <el-input
                    v-model="step.name"
                    class="web-logout-step-name"
                    aria-label="后置步骤名称"
                    placeholder="步骤名称"
                  />
                  <el-select
                    v-model="step.action"
                    class="web-logout-step-action"
                    aria-label="后置步骤动作"
                    @change="(action: WebAutomationAction) => updateWebLogoutStepAction(step, action)"
                  >
                    <el-option
                      v-for="option in webLogoutActionOptions"
                      :key="option.value"
                      :label="option.label"
                      :value="option.value"
                    />
                  </el-select>
                  <el-input
                    v-model="step.target"
                    class="web-logout-step-target"
                    aria-label="后置步骤目标"
                    :placeholder="webLogoutTargetPlaceholder(step)"
                  />
                  <div class="web-logout-step-actions">
                    <el-button
                      text
                      title="上移"
                      aria-label="上移后置步骤"
                      :disabled="index === 0"
                      @click="moveWebLogoutStep(index, -1)"
                    >
                      <ArrowUp :size="15" />
                    </el-button>
                    <el-button
                      text
                      title="下移"
                      aria-label="下移后置步骤"
                      :disabled="index === editingEnvironment.web.logout.steps.length - 1"
                      @click="moveWebLogoutStep(index, 1)"
                    >
                      <ArrowDown :size="15" />
                    </el-button>
                    <el-button
                      text
                      type="danger"
                      title="删除"
                      aria-label="删除后置步骤"
                      @click="removeWebLogoutStep(index)"
                    >
                      <Trash2 :size="15" />
                    </el-button>
                  </div>
                </div>
                <el-empty
                  v-if="editingEnvironment.web.logout.steps.length === 0"
                  :image-size="48"
                  description="请添加退出登录步骤"
                />
              </div>

              <div
                v-if="webEnvironmentVerification"
                class="environment-verification"
                :data-status="webEnvironmentVerification.status"
              >
                <strong>{{ webEnvironmentVerification.message }}</strong>
                <span v-if="webEnvironmentVerification.detail">{{ webEnvironmentVerification.detail }}</span>
              </div>

              <div
                v-if="editingEnvironment.web && editingEnvironment.web.sessionId"
                class="environment-verification"
                data-status="success"
              >
                <strong>已保存登录会话</strong>
                <span>{{ editingEnvironment.web.sessionId.slice(0, 18) }}…（编译取证优先复用；约 10 分钟有效，过期后自动回退到自动登录）</span>
                <el-button text type="danger" size="small" @click="clearWebLoginSession()">清除会话</el-button>
              </div>
            </div>

          </template>

          <template v-else>
            <div class="environment-form-grid">
              <label>
                <span>环境名称</span>
                <el-input v-model="editingEnvironment.name" placeholder="测试环境" />
              </label>
              <label>
                <span>Base URL</span>
                <el-input v-model="editingEnvironment.baseUrl" placeholder="https://api.example.com" />
              </label>
              <label>
                <span>认证方式</span>
                <el-select v-model="editingEnvironment.auth.type">
                  <el-option label="无" value="none" />
                  <el-option label="Bearer Token" value="bearer" />
                  <el-option label="自定义 Header" value="custom" />
                </el-select>
              </label>
              <label v-if="editingEnvironment.auth.type === 'bearer'">
                <span>Bearer Token</span>
                <el-input v-model="editingEnvironment.auth.token" show-password placeholder="token" />
              </label>
              <label v-if="editingEnvironment.auth.type === 'custom'">
                <span>认证 Header</span>
                <el-input v-model="editingEnvironment.auth.headerName" placeholder="Authorization" />
              </label>
              <label v-if="editingEnvironment.auth.type === 'custom'">
                <span>认证值</span>
                <el-input v-model="editingEnvironment.auth.headerValue" show-password placeholder="Bearer token" />
              </label>
            </div>

            <div class="curl-import-box">
              <el-input
                v-model="curlImportText"
                type="textarea"
                :rows="3"
                placeholder="粘贴 curl 中的 -H 和 -b 行，自动解析为 Header / Cookie"
              />
              <el-button @click="importCurlToEnvironment">解析到环境</el-button>
            </div>

            <div class="environment-entries-head">
              <strong>参数</strong>
              <div>
                <el-button @click="addEnvironmentEntry('header')">新增 Header</el-button>
                <el-button @click="addEnvironmentEntry('cookie')">新增 Cookie</el-button>
                <el-button @click="addEnvironmentEntry('variable')">新增变量</el-button>
              </div>
            </div>

            <el-table class="case-table" :data="editingEnvironment.entries" height="320" border>
            <el-table-column label="启用" width="72">
              <template #default="{ row }">
                <el-switch v-model="row.enabled" />
              </template>
            </el-table-column>
            <el-table-column label="类型" width="120">
              <template #default="{ row }">
                <el-select v-model="row.type">
                  <el-option label="Header" value="header" />
                  <el-option label="Cookie" value="cookie" />
                  <el-option label="变量" value="variable" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="参数名" min-width="160">
              <template #default="{ row }">
                <el-input v-model="row.name" placeholder="参数名" />
              </template>
            </el-table-column>
            <el-table-column label="参数值" min-width="260">
              <template #default="{ row }">
                <el-input v-model="row.value" placeholder="参数值" show-password />
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="180">
              <template #default="{ row }">
                <el-input v-model="row.description" placeholder="可选" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="82" fixed="right">
              <template #default="{ row }">
                <el-button link type="danger" @click="deleteEnvironmentEntry(row.id)">删除</el-button>
              </template>
            </el-table-column>
            </el-table>
          </template>

          <div class="drawer-actions environment-drawer-actions">
            <el-button type="danger" plain @click="deleteExecutionEnvironment(editingEnvironment.id)">删除环境</el-button>
            <el-button @click="cancelEnvironmentEditing">取消</el-button>
            <el-button type="primary" @click="saveEditingEnvironment()">保存环境</el-button>
          </div>
        </section>
      </div>
    </EnvironmentEditorSurface>
</template>
