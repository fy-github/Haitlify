<script setup lang="ts">
import { Plus, RotateCcw, Save, Sparkles } from '@lucide/vue'
import RunPolicyEditor from '../platformAi/RunPolicyEditor.vue'
import AgentAssetsPanel from '../platformAi/AgentAssetsPanel.vue'
import AiConnectionRegistryPanel from '../platformAi/AiConnectionRegistryPanel.vue'
import AiModelProfileRegistryPanel from '../platformAi/AiModelProfileRegistryPanel.vue'
import AiEffectiveOptionsCard from '../platformAi/AiEffectiveOptionsCard.vue'
import ResponsiveDrawer from '../workflow/ResponsiveDrawer.vue'
import { providerPresets } from '../../domain/aiConfigRepository'
import type { RegisterConnectionRequest, RegisterProfileRequest } from '../../domain/aiConfigurationRepository'
import { useAiConfig } from '../../ui/useAiConfig'
import { useAiConfiguration } from '../../ui/useAiConfiguration'

const {
  aiConfigReady,
  aiRunPolicies,
  aiRunPoliciesError,
  aiSettingsForm,
  platformAiStatusText,
  platformAiPage,
  platformAiPages,
  agentAssetsProjectId,
  prepareAgentAssetsProject,
  connectionEditorOpen,
  connectionEditorProvider,
  rulesExtensionTab,
  rulesExtensionItems,
  openConnectionEditor,
  connectionSearch,
  filteredAiProviders,
  connectionStatusLabel,
  connectionStatusClass,
  aiConfigLoading,
  aiConnectionTesting,
  aiRulesSaving,
  aiAgentsLoading,
  aiAgentSaving,
  aiSkillsLoading,
  aiSkillSaving,
  aiMcpLoading,
  aiCallRecordsLoading,
  aiAgentDialogVisible,
  aiSkillDialogVisible,
  aiSkillEditingId,
  aiRulesForm,
  aiAgentForm,
  aiSkillForm,
  aiCallRecords,
  aiAgentRows,
  aiSkillRows,
  aiMcpRows,
  refreshAiRunPolicies,
  saveAiRunPolicyEntry,
  saveAiRunPolicyOverrideEntry,
  createAiRunPolicyOverride,
  deleteAiRunPolicyOverrideEntry,
  addAiProviderFromCommand,
  removeAiProvider,
  updateActiveAiProviderModel,
  updateActiveAiProviderPreset,
  saveAiSettings,
  testAiConnection,
  saveAiRuleSettings,
  refreshAiAgents,
  refreshAiSkills,
  refreshAiMcpServers,
  refreshAiCallRecords,
  openCreateAiAgentDialog,
  createCustomAiAgent,
  openCreateAiSkillDialog,
  openEditCustomAiSkillDialog,
  saveCustomAiSkill,
  editBuiltInAiSkill,
  restoreBuiltInAiSkill
} = useAiConfig()

// T11：版本化连接/档案注册表 + 有效配置溯源（/api/ai/configuration 增量面）。
const {
  connections: registryConnections,
  profiles: registryProfiles,
  effectiveOptions: registryEffectiveOptions,
  configurationLoading,
  configurationSaving,
  configurationError,
  configurationConflict,
  refreshConfiguration,
  saveConnection,
  saveProfile,
  loadEffectiveOptions
} = useAiConfiguration()

const registryTaskTypes = [
  'requirement_analysis', 'testcase_generation', 'bug_analysis', 'log_triage',
  'sql_analysis', 'regression_planning', 'report_draft', 'prompt_evaluation'
]

const createRegistryConnection = (request: RegisterConnectionRequest) => {
  void saveConnection(request)
}
const createRegistryProfile = (request: RegisterProfileRequest) => {
  void saveProfile(request)
}

void refreshConfiguration()
</script>

<template>
  <section class="platform-ai-workbench" data-testid="platform-ai-workbench">
    <header class="platform-ai-workbench__header">
      <div class="platform-ai-workbench__identity">
        <h1>平台 AI</h1>
        <p>团队共享的模型策略、连接与运行诊断</p>
      </div>
      <nav class="platform-ai-workbench__nav" aria-label="Platform AI 页面导航">
        <button
          v-for="page in platformAiPages"
          :key="page.key"
          type="button"
          class="platform-ai-workbench__nav-item"
          :class="{ 'is-active': platformAiPage === page.key }"
          :aria-current="platformAiPage === page.key ? 'page' : undefined"
          @click="platformAiPage = page.key"
        >{{ page.label }}</button>
      </nav>
      <div class="platform-ai-workbench__actions">
        <span class="platform-ai-workbench__status" :class="aiConfigReady ? 'is-ready' : 'is-attention'">
          <span class="platform-ai-workbench__dot" aria-hidden="true"></span>{{ platformAiStatusText }}
        </span>
        <el-button :loading="aiConnectionTesting" @click="testAiConnection">
          <Sparkles :size="15" aria-hidden="true" />连接测试
        </el-button>
        <el-button type="primary" :loading="aiConfigLoading" @click="saveAiSettings">
          <Save :size="15" aria-hidden="true" />保存变更
        </el-button>
      </div>
    </header>

    <div class="platform-ai-workbench__canvas">
      <section class="platform-ai-global-control" data-testid="platform-ai-global-switch" aria-label="平台 AI 总开关">
        <div>
          <strong>AI 总开关</strong>
          <p id="platform-ai-global-switch-help">控制平台 AI 是否启用；点击「保存变更」后生效。连接测试不会自动启用。</p>
        </div>
        <el-switch v-model="aiSettingsForm.enabled" aria-label="AI 总开关" aria-describedby="platform-ai-global-switch-help" active-text="开启" inactive-text="关闭" :disabled="aiConfigLoading" />
      </section>
      <div v-if="platformAiPage === 'policies'" class="platform-ai-surface" data-testid="platform-ai-policies">
        <RunPolicyEditor
          :policies="aiRunPolicies?.items"
          :providers="aiSettingsForm.providers"
          :loading="aiConfigLoading"
          :error="aiRunPoliciesError"
          @save="saveAiRunPolicyEntry"
          @save-override="saveAiRunPolicyOverrideEntry"
          @create-override="createAiRunPolicyOverride"
          @delete-override="deleteAiRunPolicyOverrideEntry"
          @view-connections="platformAiPage = 'connections'"
          @refresh="refreshAiRunPolicies"
        />
      </div>

      <div v-else-if="platformAiPage === 'connections'" class="platform-ai-surface" data-testid="platform-ai-connections">
        <div class="platform-ai-toolbar">
          <el-input v-model="connectionSearch" placeholder="搜索名称或模型" size="small" clearable class="platform-ai-toolbar__search" />
          <el-dropdown trigger="click" @command="addAiProviderFromCommand">
            <el-button type="primary" size="small"><Plus :size="14" aria-hidden="true" />新增连接</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="preset in providerPresets" :key="preset.providerId" :command="preset.providerId">{{ preset.name }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <el-table :data="filteredAiProviders" class="platform-ai-table" :row-class-name="() => 'platform-ai-connection-row'">
          <el-table-column label="名称" min-width="180">
            <template #default="{ row }"><strong>{{ row.name || row.providerId }}</strong></template>
          </el-table-column>
          <el-table-column prop="providerId" label="类型" min-width="160" />
          <el-table-column prop="defaultModel" label="默认模型" min-width="140" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <span class="platform-ai-status" :class="connectionStatusClass(row)"><span class="platform-ai-status__dot" aria-hidden="true"></span>{{ connectionStatusLabel(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button link @click="openConnectionEditor(row.id)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>

        <ResponsiveDrawer v-model="connectionEditorOpen" class="platform-ai-drawer" title="编辑模型连接" size="min(90vw,520px)" direction="rtl">
          <div class="platform-ai-drawer">
            <p class="platform-ai-drawer__hint">连接的密钥仅保存在服务端</p>
            <section v-if="connectionEditorProvider" class="platform-ai-section">
              <h3>基础信息</h3>
              <div class="studio-form-grid">
                <label>名称<el-input v-model="connectionEditorProvider.name" placeholder="例如：生产 OpenAI 网关" /></label>
                <label>Provider 类型
                  <el-select v-model="connectionEditorProvider.providerId" @change="updateActiveAiProviderPreset">
                    <el-option v-for="preset in providerPresets" :key="preset.providerId" :label="preset.name" :value="preset.providerId" />
                  </el-select>
                </label>
              </div>
            </section>
            <section v-if="connectionEditorProvider" class="platform-ai-section">
              <h3>连接参数</h3>
              <label class="studio-form-wide">Base URL<el-input v-model="connectionEditorProvider.baseURL" placeholder="https://api.example.com/v1" /></label>
              <label class="studio-form-wide">API Key
                <el-input v-model="connectionEditorProvider.apiKey" show-password placeholder="留空则沿用后端已保存密钥" />
                <small v-if="connectionEditorProvider.maskedApiKey">已保存：{{ connectionEditorProvider.maskedApiKey }}</small>
              </label>
            </section>
            <section v-if="connectionEditorProvider" class="platform-ai-section">
              <h3>模型与限制</h3>
              <div class="studio-form-grid">
                <label>默认模型<el-input v-model="aiSettingsForm.activeModel" @change="updateActiveAiProviderModel" placeholder="gpt-5.5" /></label>
                <label>单次调用超时<el-input-number v-model="connectionEditorProvider.requestTimeoutMs" :min="30000" :step="10000" controls-position="right" /></label>
                <label>上下文窗口<el-input-number v-model="connectionEditorProvider.contextWindow" :min="0" :step="1000" controls-position="right" placeholder="自动" /></label>
              </div>
              <div class="platform-ai-drawer__row">
                <el-switch v-model="connectionEditorProvider.enabled" active-text="启用该连接" />
                <el-button v-if="aiSettingsForm.providers.length > 1" link type="danger" @click="removeAiProvider(connectionEditorProvider); connectionEditorOpen = false">删除连接</el-button>
              </div>
            </section>
            <div class="platform-ai-drawer__footer">
              <el-button :loading="aiConnectionTesting" @click="testAiConnection"><Sparkles :size="14" aria-hidden="true" />连接测试</el-button>
              <div>
                <el-button @click="connectionEditorOpen = false">取消</el-button>
                <el-button type="primary" :loading="aiConfigLoading" @click="async () => { await saveAiSettings(); connectionEditorOpen = false }"><Save :size="14" aria-hidden="true" />保存连接</el-button>
              </div>
            </div>
          </div>
        </ResponsiveDrawer>

        <div class="platform-ai-registry" data-testid="platform-ai-versioned-registry">
          <AiConnectionRegistryPanel
            :connections="registryConnections"
            :loading="configurationLoading"
            :saving="configurationSaving"
            :error="configurationError"
            :conflict="configurationConflict"
            @create="createRegistryConnection"
            @refresh="refreshConfiguration"
          />
          <AiModelProfileRegistryPanel
            :profiles="registryProfiles"
            :connections="registryConnections"
            :loading="configurationLoading"
            :saving="configurationSaving"
            :error="configurationError"
            :conflict="configurationConflict"
            @create="createRegistryProfile"
            @refresh="refreshConfiguration"
          />
          <AiEffectiveOptionsCard
            :effective="registryEffectiveOptions"
            :task-types="registryTaskTypes"
            :profiles="registryProfiles"
            :loading="configurationLoading"
            :error="configurationError"
            @load="loadEffectiveOptions"
          />
        </div>
      </div>

      <div v-else-if="platformAiPage === 'diagnostics'" class="platform-ai-surface" data-testid="platform-ai-diagnostics">
        <div class="platform-ai-toolbar">
          <el-button :loading="aiCallRecordsLoading" @click="refreshAiCallRecords"><RotateCcw :size="14" aria-hidden="true" />刷新</el-button>
        </div>
        <el-table v-if="aiCallRecords.length" :data="aiCallRecords" class="platform-ai-table">
          <el-table-column prop="recordId" label="任务 / 记录" min-width="180" />
          <el-table-column prop="runId" label="运行" min-width="150" />
          <el-table-column label="实际路由" min-width="180">
            <template #default="{ row }"><span class="platform-ai-route">{{ row.providerType }} · {{ row.modelName }}</span></template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="110" />
          <el-table-column label="用时" width="110"><template #default="{ row }">{{ row.durationMs }}ms</template></el-table-column>
          <el-table-column prop="createdAt" label="开始时间" min-width="170" />
        </el-table>
        <el-empty v-else description="暂无运行与诊断记录" />
      </div>

      <div v-else-if="platformAiPage === 'rules'" class="platform-ai-surface platform-ai-rules" data-testid="platform-ai-rules">
        <aside class="platform-ai-rules__list">
          <button v-for="item in rulesExtensionItems" :key="item.key" type="button"
            class="platform-ai-rules__item" :class="{ 'is-active': rulesExtensionTab === item.key }"
            @click="rulesExtensionTab = item.key">{{ item.label }}</button>
        </aside>
        <section class="platform-ai-rules__detail">
          <div v-if="rulesExtensionTab === 'rules'" class="ai-rule-grid">
            <el-checkbox v-model="aiRulesForm.forcedHumanReview">强制人工评审</el-checkbox>
            <el-checkbox v-model="aiRulesForm.sourceEvidenceRequired">必须保留来源证据</el-checkbox>
            <el-checkbox v-model="aiRulesForm.inferredRequiresConfirmation">推断测试点必须确认</el-checkbox>
            <el-tag type="success">允许生成：confirmed / modified</el-tag>
            <el-button type="primary" :loading="aiRulesSaving" @click="saveAiRuleSettings"><Save :size="14" aria-hidden="true" />保存规则配置</el-button>
          </div>
          <template v-else-if="rulesExtensionTab === 'agent'">
            <div class="ai-rule-grid">
              <el-tag type="info">内置</el-tag>
              <el-tag type="success">自定义</el-tag>
              <el-button :loading="aiAgentsLoading" @click="refreshAiAgents"><RotateCcw :size="14" aria-hidden="true" />刷新 Agent</el-button>
              <el-button type="primary" @click="openCreateAiAgentDialog"><Plus :size="14" aria-hidden="true" />新增 Agent</el-button>
            </div>
            <el-table :data="aiAgentRows" class="platform-ai-table">
              <el-table-column prop="name" label="Agent" min-width="180" />
              <el-table-column prop="agentRole" label="角色" min-width="150" />
              <el-table-column prop="skillPathText" label="Skill 路径" min-width="240" show-overflow-tooltip />
              <el-table-column prop="skillIdsText" label="关联 Skill" min-width="180" show-overflow-tooltip />
              <el-table-column prop="customizedText" label="来源" min-width="140" />
              <el-table-column prop="statusText" label="状态" width="90" />
            </el-table>
          </template>
          <template v-else-if="rulesExtensionTab === 'skill'">
            <div class="ai-rule-grid">
              <el-tag type="info">内置</el-tag>
              <el-tag type="success">内置 / 已自定义</el-tag>
              <el-button :loading="aiSkillsLoading" @click="refreshAiSkills"><RotateCcw :size="14" aria-hidden="true" />刷新 Skill</el-button>
              <el-button type="primary" @click="openCreateAiSkillDialog"><Plus :size="14" aria-hidden="true" />新增 Skill</el-button>
            </div>
            <el-table :data="aiSkillRows" class="platform-ai-table">
              <el-table-column prop="name" label="Skill" min-width="180" />
              <el-table-column prop="badge" label="来源" width="110"><template #default="{ row }"><el-tag size="small" type="info">{{ row.badge }}</el-tag></template></el-table-column>
              <el-table-column prop="customizedText" label="覆盖状态" min-width="150" />
              <el-table-column prop="skillPathText" label="Skill 路径" min-width="240" show-overflow-tooltip />
              <el-table-column prop="version" label="版本" width="90" />
              <el-table-column prop="statusText" label="状态" width="90" />
              <el-table-column label="操作" width="220" fixed="right">
                <template #default="{ row }">
                  <div class="table-actions">
                    <template v-if="row.builtIn">
                      <el-button link @click.stop="editBuiltInAiSkill(row)">编辑内置 Skill</el-button>
                      <el-button link @click.stop="restoreBuiltInAiSkill(row)">恢复默认</el-button>
                    </template>
                    <el-button v-else link @click.stop="openEditCustomAiSkillDialog(row)">编辑 Skill</el-button>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </template>
          <template v-else-if="rulesExtensionTab === 'mcp'">
            <div class="ai-rule-grid">
              <el-tag type="warning">写操作默认关闭</el-tag>
              <el-button :loading="aiMcpLoading" @click="refreshAiMcpServers"><RotateCcw :size="14" aria-hidden="true" />刷新 MCP</el-button>
            </div>
            <el-table :data="aiMcpRows" class="platform-ai-table">
              <el-table-column prop="name" label="服务" min-width="170" />
              <el-table-column prop="type" label="类型" min-width="130" />
              <el-table-column prop="writePolicy" label="写入策略" min-width="150" />
              <el-table-column prop="sandbox" label="沙箱" min-width="150" />
              <el-table-column prop="status" label="状态" width="100" />
            </el-table>
          </template>
        </section>
      </div>

      <div v-else-if="platformAiPage === 'agentAssets'" class="platform-ai-surface" data-testid="platform-ai-agent-assets">
        <AgentAssetsPanel :project-id="agentAssetsProjectId" @ensure-project="prepareAgentAssetsProject" />
      </div>
    </div>
  </section>

  <el-dialog v-model="aiAgentDialogVisible" title="新增Agent" width="680px">
    <div class="studio-dialog-body">
      <div class="studio-scope-line">
        <el-tag type="info">Skill查找</el-tag>
        <span>Agent 可通过 Skill 路径或 Skill ID 找到对应能力。</span>
      </div>
      <div class="studio-form-grid">
        <label>
          <span>Agent ID</span>
          <el-input v-model="aiAgentForm.agentId" placeholder="custom-web-review" />
        </label>
        <label>
          <span>名称</span>
          <el-input v-model="aiAgentForm.name" placeholder="自定义Web评审Agent" />
        </label>
        <label>
          <span>角色</span>
          <el-input v-model="aiAgentForm.agentRole" placeholder="web_review" />
        </label>
        <label>
          <span>启用</span>
          <el-switch v-model="aiAgentForm.enabled" />
        </label>
        <label class="studio-form-wide">
          <span>Skill路径</span>
          <el-input v-model="aiAgentForm.skillPath" placeholder="/opt/haitlify/skills/web/review.md" />
        </label>
        <label class="studio-form-wide">
          <span>关联Skill ID</span>
          <el-input v-model="aiAgentForm.skillIds" placeholder="custom-web-login-skill，可用逗号或空格分隔多个" />
        </label>
        <label class="studio-form-wide">
          <span>描述</span>
          <el-input v-model="aiAgentForm.description" type="textarea" :rows="2" placeholder="说明这个 Agent 的评审或生成职责" />
        </label>
        <label class="studio-form-wide">
          <span>提示词模板</span>
          <el-input v-model="aiAgentForm.promptTemplate" type="textarea" :rows="4" placeholder="Agent 运行时使用的提示词" />
        </label>
      </div>
    </div>
    <template #footer>
      <div class="drawer-actions">
        <el-button @click="aiAgentDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="aiAgentSaving" @click="createCustomAiAgent">保存Agent</el-button>
      </div>
    </template>
  </el-dialog>

  <el-dialog v-model="aiSkillDialogVisible" :title="aiSkillEditingId ? '编辑Skill' : '新增Skill'" width="680px">
    <div class="studio-dialog-body">
      <div class="studio-scope-line">
        <el-tag type="success">指定路径</el-tag>
        <span>保存后 Agent 可以按路径引用这个 Skill。</span>
      </div>
      <div class="studio-form-grid">
        <label>
          <span>Skill ID</span>
          <el-input v-model="aiSkillForm.skillId" :disabled="Boolean(aiSkillEditingId)" placeholder="custom-web-login-skill" />
        </label>
        <label>
          <span>名称</span>
          <el-input v-model="aiSkillForm.name" placeholder="Web登录补充Skill" />
        </label>
        <label>
          <span>启用</span>
          <el-switch v-model="aiSkillForm.enabled" />
        </label>
        <label class="studio-form-wide">
          <span>Skill路径</span>
          <el-input v-model="aiSkillForm.skillPath" placeholder="/opt/haitlify/skills/web/login.md" />
        </label>
        <label class="studio-form-wide">
          <span>描述</span>
          <el-input v-model="aiSkillForm.description" type="textarea" :rows="2" placeholder="说明 Skill 能补充的场景、模板或规则" />
        </label>
        <label class="studio-form-wide">
          <span>提示词模板</span>
          <el-input v-model="aiSkillForm.promptTemplate" type="textarea" :rows="5" placeholder="Skill 的执行提示词或模板内容" />
        </label>
      </div>
    </div>
    <template #footer>
      <div class="drawer-actions">
        <el-button @click="aiSkillDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="aiSkillSaving" @click="saveCustomAiSkill">保存Skill</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.platform-ai-global-control {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px 24px;
  padding: 16px;
  margin-bottom: 16px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-xl, 12px);
  background: var(--surface-panel);
}
.platform-ai-global-control strong { color: var(--text-primary); font-size: 14px; }
.platform-ai-global-control p { margin: 6px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.6; }
</style>
