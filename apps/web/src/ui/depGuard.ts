/**
 * 单例 composable 的必选依赖守卫。
 *
 * 所有 ui/ 单例(useCases / useReports / useTemplateConfig / useAiConfig /
 * useDefects)必须由 App.vue 以完整 deps 创建;任何未注入的必选依赖默认值
 * 都不再静默 no-op(否则点击无反应 / 删除不弹确认 / 保存不落盘),而是显式抛错,
 * 便于在接线遗漏时第一时间暴露问题。
 */
export const missingDep = (composable: string, dep: string): never => {
  throw new Error(`${composable} 缺少必选依赖「${dep}」：该单例必须由 App.vue 以完整 deps 创建，请检查接线`)
}
