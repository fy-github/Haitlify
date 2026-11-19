<script setup lang="ts">
import ResponsiveDrawer from './ResponsiveDrawer.vue'

defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<template>
  <ResponsiveDrawer
    :model-value="modelValue"
    class="local-help-drawer"
    title="帮助文档"
    size="min(520px, 100vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <article class="local-help" data-testid="local-help-content" aria-label="本地使用说明">
      <header>
        <h2>本地使用说明</h2>
        <p>此说明随前端提供，不打开外部网站，不读取或修改当前工作区数据。</p>
      </header>
      <section aria-labelledby="local-help-navigation">
        <h3 id="local-help-navigation">从哪里开始</h3>
        <ul>
          <li>顶栏切换工作台，侧栏选择当前工作台的子功能；窄屏使用顶栏导航按钮。</li>
          <li>用例库管理用例；自动化工作台准备 API / Web 执行范围与环境。</li>
          <li>质量中心查看执行报告与缺陷；设置中心管理模板、Jira 配置及有权限的用户。</li>
        </ul>
      </section>
      <section aria-labelledby="local-help-state">
        <h3 id="local-help-state">如何理解状态</h3>
        <ul>
          <li>“未接入数据”不等于零条数据。概览只检查项目访问，团队与质量统计尚未接入。</li>
          <li>加载失败时使用页面的重试入口；权限不足时联系管理员，重新检查不会修改权限。</li>
          <li>出现保存失败、冲突或结果未知时，先阅读页面的恢复提示，再选择恢复或核对操作。</li>
        </ul>
      </section>
      <section aria-labelledby="local-help-boundary" class="local-help__boundary">
        <h3 id="local-help-boundary">能力与证据边界</h3>
        <p>AI 助手当前仅受理，不会触发模型，也不会生成草稿；手工 JSON 属于独立调试入口。</p>
        <p>页面可访问或前端测试通过，不代表真实后端、认证或 Runner 已通过验收。真实执行结果应以对应任务和报告回执为准。</p>
      </section>
    </article>
  </ResponsiveDrawer>
</template>

<style scoped>
.local-help {
  display: grid;
  min-width: 0;
  gap: 20px;
  color: var(--text-regular);
  font-size: 13px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.local-help h2,
.local-help h3 {
  margin: 0 0 8px;
  color: var(--text-primary);
  font-weight: 600;
}
.local-help h2 { font-size: 16px; }
.local-help h3 { font-size: 14px; }
.local-help p { margin: 0 0 8px; }
.local-help ul { margin: 0; padding-left: 20px; }
.local-help li + li { margin-top: 8px; }
.local-help__boundary {
  padding: 12px;
  background: var(--surface-subtle);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-sm);
}
</style>

<style>
/* Derived from the retained shared drawer, not an archived help-page replica. */
.local-help-drawer .studio-responsive-drawer__header > button:focus-visible {
  outline: none;
  box-shadow: none;
  filter: brightness(0.9);
  background: var(--surface-hover);
}
</style>
