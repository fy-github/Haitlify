> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# 接口用例 Studio Canvas 设计资产

这些文件是接口用例页实现与视觉验收的固定基准。源 HTML 用于继续浏览或调整设计，PNG 用于截图对照。

| 基准 | 源文件 | 截图 |
| --- | --- | --- |
| 方案方向 | `01-layout-directions.html` | `01-layout-directions.png` |
| 信息架构（已修订） | `02-information-architecture.html` | `02-information-architecture.png` |
| 阶段行为与数据流 | `03-stage-behavior.html` | `03-stage-behavior.png` |
| 视觉规范与响应式 | `04-visual-responsive.html` | `04-visual-responsive.png` |

## 验收顺序

1. 先比对 `02-information-architecture.png`：顶部阶段图必须与 Web `WorkflowHeader` 一致，生成用例必须有顶部操作栏和右侧操作列。
2. 再比对 `03-stage-behavior.png`：四阶段主操作、状态恢复和保存/交付边界必须一致。
3. 最后比对 `04-visual-responsive.png`：深蓝主操作、柔和浅色表面、字体、侧栏和抽屉行为必须一致。

任何实现偏离都应先更新设计规范与本目录资产，再修改页面代码。
