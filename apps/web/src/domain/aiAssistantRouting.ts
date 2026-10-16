/**
 * T12 对话入口的确定性路由：按关键词给候选任务（不触发任何写操作），
 * 显式选择与输入补全由用户在运行卡里完成——这里不猜、不自动运行。
 */
import type { AiTaskDefinition } from './aiAssistantRepository'

interface TaskKeyword {
  taskType: string
  words: string[]
}

/** 关键词表是确定性排序依据：命中次数多的任务排前；全部未命中时保留任务目录顺序。 */
const TASK_KEYWORDS: TaskKeyword[] = [
  { taskType: 'bug_analysis', words: ['缺陷', 'bug', '报错', '崩溃', '白屏', '异常', '失败', '问题', '闪退', '不生效'] },
  { taskType: 'log_triage', words: ['日志', 'log', '堆栈', '超时', '502', '504', 'timeout', '排查'] },
  { taskType: 'sql_analysis', words: ['sql', '查询语句', '数据库', '慢查询', '索引'] },
  { taskType: 'regression_planning', words: ['回归', '影响面', '改动范围', '变更', '发版范围'] },
  { taskType: 'requirement_analysis', words: ['需求', '需求文档', '需求分析', '文档分析'] },
  { taskType: 'testcase_generation', words: ['用例', '测试点', '生成用例', '补用例'] },
  { taskType: 'report_draft', words: ['报告', '汇总', '日报', '周报', '测试总结'] },
  { taskType: 'prompt_evaluation', words: ['prompt', '评测', '提示词'] }
]

export const suggestTaskTypes = (text: string, tasks: ReadonlyArray<AiTaskDefinition>): AiTaskDefinition[] => {
  const haystack = (text || '').toLowerCase()
  const scored = TASK_KEYWORDS.map((entry) => {
    const hits = entry.words.filter((word) => haystack.includes(word.toLowerCase())).length
    return { taskType: entry.taskType, hits }
  }).filter((entry) => entry.hits > 0)
  scored.sort((left, right) => right.hits - left.hits)
  const ranked = scored
    .map((entry) => tasks.find((task) => task.taskType === entry.taskType))
    .filter((task): task is AiTaskDefinition => Boolean(task))
  // 未命中的任务按目录顺序补在候选尾部，用户仍可显式改选。
  const rest = tasks.filter((task) => !ranked.some((entry) => entry.taskType === task.taskType))
  return [...ranked, ...rest]
}

const INPUT_LABELS: Record<string, string> = {
  symptom: '现象描述',
  logExcerpt: '日志片段',
  timeRange: '时间范围',
  sqlText: 'SQL 文本',
  changeSummary: '变更摘要',
  documentIds: '需求文档 ID（逗号分隔）',
  targetCaseKind: '目标用例类型',
  draftPointIds: '草稿测试点 ID（逗号分隔）',
  caseKind: '用例类型',
  sourceRunIds: '来源运行 ID（逗号分隔）',
  promptVersion: 'Prompt 版本',
  samples: '样例说明'
}

/** 未知字段回退原始字段名——不编造中文含义。 */
export const inputLabel = (field: string): string => INPUT_LABELS[field] ?? field

const LIST_INPUT_FIELDS = new Set(['documentIds', 'draftPointIds', 'sourceRunIds'])

/** 把运行卡的原始文本整理成任务输入：已知列表字段按逗号拆分并去空白，其余原样传递。 */
export const buildTaskInputs = (
  task: AiTaskDefinition,
  rawValues: Record<string, string>
): Record<string, unknown> => {
  const inputs: Record<string, unknown> = {}
  for (const field of task.requiredInputs) {
    const raw = (rawValues[field] ?? '').trim()
    if (raw.length === 0) continue
    if (LIST_INPUT_FIELDS.has(field)) {
      inputs[field] = raw.split(',').map((part) => part.trim()).filter((part) => part.length > 0)
    } else {
      inputs[field] = raw
    }
  }
  return inputs
}

/** 具备目录数据源的列表输入：documents=项目文档目录；recent-runs=最近产物（取其 runId）。 */
export const PICKER_INPUTS: Record<string, 'documents' | 'recent-runs' | 'draft-points'> = {
  documentIds: 'documents',
  sourceRunIds: 'recent-runs',
  draftPointIds: 'draft-points'
}

export const pickerKindFor = (field: string): 'documents' | 'recent-runs' | 'draft-points' | undefined =>
  PICKER_INPUTS[field]

/** 运行卡列表字段的选中值 ↔ 逗号串（values 统一存字符串，提交时由 buildTaskInputs 拆分）。 */
export const listValueOf = (values: Record<string, string>, field: string): string[] =>
  (values[field] ?? '').split(',').map((part) => part.trim()).filter((part) => part.length > 0)

export const setListValue = (values: Record<string, string>, field: string, selected: string[]): void => {
  values[field] = (selected ?? []).filter((entry) => entry.trim().length > 0).join(',')
}

/** 运行卡是否可提交：任务已显式选择即可（缺失输入由后端 missing_inputs 裁决，前端不替它闭门）。 */
export const canLaunch = (taskType: string): boolean => taskType.trim().length > 0
