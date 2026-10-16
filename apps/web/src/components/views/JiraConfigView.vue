<script setup lang="ts">
import { RotateCcw, Save, Settings, Plus, Trash2 } from '@lucide/vue'
import type { JiraConfig } from '../../domain/jiraConfigRepository'

defineProps<{
  form: JiraConfig
  loading: boolean
  testing: boolean
  projectJqlRows: Array<{ key: string; value: string }>
  load: () => void
  save: () => void
  test: () => void
  addJqlRow: () => void
  removeJqlRow: (index: number) => void
  syncJqlForm: () => void
}>()
</script>

<template>
  <header class="topbar">
    <div>
      <span class="eyebrow">Jira 集成</span>
      <h1>Jira 配置</h1>
    </div>
    <div class="toolbar">
      <el-button :loading="loading" @click="load">
        <RotateCcw :size="16" />
        重新加载
      </el-button>
      <el-button :loading="testing" @click="test">
        <Settings :size="16" />
        连接测试
      </el-button>
      <el-button type="primary" :loading="loading" @click="save">
        <Save :size="16" />
        保存配置
      </el-button>
    </div>
  </header>

  <section class="library-workspace">
    <div class="jira-config-panel">
      <div class="jira-config-switch">
        <el-switch v-model="form.enabled" active-text="启用 Jira 只读查询" />
        <p class="jira-config-hint">启用后，缺陷页将按下方映射读取 Jira 缺陷。Token 仅保存在服务端，返回时脱敏显示。</p>
      </div>

      <div class="studio-form-grid">
        <label class="studio-form-wide">Jira Base URL
          <el-input v-model="form.baseUrl" placeholder="https://jira.example.test" />
        </label>
        <label>账号（用户名）
          <el-input v-model="form.username" placeholder="jira-reader" />
        </label>
        <label>Token / 密码
          <el-input v-model="form.token" show-password placeholder="留空则沿用已保存凭证" />
          <small v-if="form.maskedToken">已保存：{{ form.maskedToken }}</small>
        </label>
      </div>

      <div class="jira-config-jql">
        <div class="jira-config-jql__header">
          <h2>项目 JQL 映射</h2>
          <el-button size="small" @click="addJqlRow">
            <Plus :size="14" />
            新增映射
          </el-button>
        </div>
        <p class="jira-config-hint">键为缺陷页里的项目名，值为对应的 JQL 查询语句。</p>
        <div v-for="(row, index) in projectJqlRows" :key="index" class="jira-config-jql__row">
          <el-input v-model="row.key" placeholder="项目名（如 HAITLIFY_API）" @change="syncJqlForm" />
          <el-input v-model="row.value" placeholder="JQL（如 project = HAITLIFY order by created DESC）" @change="syncJqlForm" />
          <el-button link type="danger" @click="removeJqlRow(index)">
            <Trash2 :size="15" />
          </el-button>
        </div>
        <div v-if="projectJqlRows.length === 0" class="jira-config-jql__empty">
          还没有 JQL 映射，点击「新增映射」添加。
        </div>
      </div>
    </div>
  </section>
</template>
