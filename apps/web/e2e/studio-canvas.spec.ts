import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { CASE_DESIGN_DRAFT_STORAGE_KEY } from '../src/domain/caseDesignDraftRepository'
import {
  TEMPLATE_CONFIG_META_STORAGE_KEY,
  TEMPLATE_CONFIG_STORAGE_KEY,
  defaultTemplateConfig
} from '../src/domain/templateConfigRepository'
import {
  caseDesignDraftFixture,
  type CaseDesignFixtureVariant
} from './fixtures/caseDesignDraft'
import { openWebAutomationFixture } from './fixtures/webAutomationWorkspace'

interface InterfaceRuntimeError {
  source: 'pageerror' | 'console.error'
  message: string
  url?: string
}

const knownMockUnavailablePaths = new Set([
  '/api/ai/config',
  '/api/web-automation/capabilities',
  '/api/ai/rules',
  '/api/ai/agents',
  '/api/ai/skills',
  '/api/ai/mcp-servers',
  '/api/ai/call-records',
  '/api/ai/run-policies',
  '/api/ai/multi-agent-runs/review-fixture/graph',
  '/api/ai/multi-agent-runs/review-fixture/conflicts',
  '/api/ai/multi-agent-runs/review-fixture/quality-scores'
])

const knownMockUnavailablePrefixes = [
  /^\/api\/ai\/analysis-runs\/[^/]+\/events$/
]

const isKnownMockUnavailableError = (error: InterfaceRuntimeError): boolean =>
  error.source === 'console.error'
  && /^Failed to load resource: the server responded with a status of 503 \(Service Unavailable\)$/.test(error.message)
  && Boolean(error.url && (() => {
    const pathname = new URL(error.url).pathname
    return knownMockUnavailablePaths.has(pathname)
      || knownMockUnavailablePrefixes.some((pattern) => pattern.test(pathname))
  })())

const interfaceTest = test.extend<{ runtimeErrorGuard: void }>({
  runtimeErrorGuard: [async ({ page }, use) => {
    const runtimeErrors: InterfaceRuntimeError[] = []
    page.on('pageerror', (error) => {
      runtimeErrors.push({ source: 'pageerror', message: error.message })
    })
    page.on('console', (message) => {
      if (message.type() === 'error') {
        runtimeErrors.push({
          source: 'console.error',
          message: message.text(),
          url: message.location().url
        })
      }
    })

    await use()

    const unexpectedErrors = runtimeErrors.filter((error) => !isKnownMockUnavailableError(error))
    expect(
      unexpectedErrors,
      `Interface workflow emitted runtime errors:\n${unexpectedErrors
        .map((error) => `[${error.source}] ${error.message}${error.url ? ` (${error.url})` : ''}`)
        .join('\n')}`
    ).toEqual([])
  }, { auto: true }]
})

const seedDraft = async (
  page: Page,
  stage: 'import' | 'parse' | 'generate' | 'review' | 'deliver',
  variant: CaseDesignFixtureVariant = 'default',
  syncConflict = false
) => {
  await page.addInitScript(({ key, value, templateKey, metaKey, template, conflict }) => {
    if (!localStorage.getItem(key)) localStorage.setItem(key, JSON.stringify(value))
    if (conflict) {
      if (!localStorage.getItem(templateKey)) localStorage.setItem(templateKey, JSON.stringify(template))
      if (!localStorage.getItem(metaKey)) {
        localStorage.setItem(metaKey, JSON.stringify({ updatedAt: '2026-07-16T15:00:00.000Z' }))
      }
    }
  }, {
    key: CASE_DESIGN_DRAFT_STORAGE_KEY,
    value: caseDesignDraftFixture(stage, variant),
    templateKey: TEMPLATE_CONFIG_STORAGE_KEY,
    metaKey: TEMPLATE_CONFIG_META_STORAGE_KEY,
    template: defaultTemplateConfig(),
    conflict: syncConflict
  })
}

const mockBackend = async (page: Page, syncConflict = false) => {
  const template = defaultTemplateConfig()
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/projects') {
      await route.fulfill({ json: { items: [{ id: 1, code: 'PROJECT', name: '默认项目' }] } })
      return
    }
    if (url.pathname === '/api/projects/1/case-template/interface') {
      await route.fulfill({
        json: {
          ...template,
          kind: 'interface',
          updatedAt: syncConflict ? '2026-07-15T10:00:00.000Z' : '2026-07-16T16:00:00.000Z'
        }
      })
      return
    }
    if (url.pathname === '/api/projects/1/interface-cases') {
      await route.fulfill({ json: { items: [], total: 0 } })
      return
    }
    await route.fulfill({ status: 503, json: { message: 'fixture backend unavailable' } })
  })
}

const openFixture = async (options: {
  page: Page
  width: number
  height: number
  stage: 'import' | 'parse' | 'generate' | 'review' | 'deliver'
  variant?: CaseDesignFixtureVariant
  syncConflict?: boolean
}) => {
  await options.page.clock.setFixedTime(new Date('2026-07-16T14:32:00.000Z'))
  await options.page.setViewportSize({ width: options.width, height: options.height })
  await seedDraft(options.page, options.stage, options.variant, Boolean(options.syncConflict))
  await mockBackend(options.page, Boolean(options.syncConflict))
  await options.page.goto('/api')
  await expect(options.page.locator('.studio-case-design-canvas')).toBeVisible()
}

const openInterfaceFixture = async (options: {
  page: Page
  width: number
  height: number
}) => {
  await options.page.clock.setFixedTime(new Date('2026-07-16T14:32:00.000Z'))
  await options.page.setViewportSize({ width: options.width, height: options.height })
  await options.page.addInitScript(({ key, value }) => localStorage.setItem(key, JSON.stringify(value)), {
    key: CASE_DESIGN_DRAFT_STORAGE_KEY,
    value: { ...caseDesignDraftFixture('import'), generationKind: 'interface', projectName: '默认项目' }
  })
  await mockBackend(options.page)
  await options.page.goto('/api')
  await expect(options.page.locator('.studio-interface-case-canvas')).toBeVisible()
}

const importInterfaceOpenApi = async (page: Page) => {
  await page.locator('input[type="file"][accept=".json,application/json"]')
    .setInputFiles('Haitlify.openapi.json')
  await expect(page.getByRole('button', { name: '配置生成，未开始' })).toBeEnabled()
}

const waitForTransientMessages = async (page: Page) => {
  await expect(page.locator('.el-message')).toHaveCount(0, { timeout: 5_000 })
}

const getInterfaceOperationTable = (page: Page) =>
  page.getByTestId('interface-operation-table')

const getVisibleOperationCheckboxes = (page: Page) =>
  getInterfaceOperationTable(page).getByRole('checkbox', { name: /^选择接口 / })

const parseSelectedOperationCount = async (page: Page) => {
  const selectedText = await getInterfaceOperationTable(page)
    .locator('.interface-operation-table__summary span', { hasText: /^已选 \d+ 个$/ })
    .textContent()
  const selectedCount = selectedText?.match(/已选 (\d+) 个/)?.[1]
  expect(selectedCount, `Unexpected selected operation count text: ${selectedText}`).toBeTruthy()
  return Number(selectedCount)
}

const expectSelectedOperationCountGreaterThanZero = async (page: Page) => {
  let selectedCount = 0
  await expect.poll(async () => {
    selectedCount = await parseSelectedOperationCount(page)
    return selectedCount
  }).toBeGreaterThan(0)
  return selectedCount
}

const expectVisibleOperationRowsMatchCheckboxes = async (page: Page) => {
  const operationTable = getInterfaceOperationTable(page)
  const readCounts = () => operationTable.locator('.el-table__body-wrapper').evaluate((body) => {
    const rows = Array.from(body.querySelectorAll('tbody tr'))
      .filter((row) => row.querySelector('input[type="checkbox"]'))
    return {
      rowCount: rows.length,
      checkboxCount: body.querySelectorAll('input[type="checkbox"]').length
    }
  })
  await expect.poll(async () => {
    const counts = await readCounts()
    return counts.rowCount > 0 && counts.checkboxCount === counts.rowCount
  }).toBe(true)
  const counts = await readCounts()
  expect(counts.rowCount).toBeGreaterThan(0)
  expect(counts.checkboxCount).toBe(counts.rowCount)
  return counts.rowCount
}

const expectOperationTablePathsInclude = async (page: Page, pathKeyword: string) => {
  const operationTable = getInterfaceOperationTable(page)
  await expect.poll(() => operationTable
    .locator('.el-table__body-wrapper tbody tr')
    .evaluateAll((rows, keyword) => {
      const rowTexts = rows
        .filter((row) => row.querySelector('input[type="checkbox"]'))
        .map((row) => row.textContent || '')
      return rowTexts.length > 0 && rowTexts.every((text) => text.includes(keyword))
    }, pathKeyword))
    .toBe(true)
}

const selectProjectTreeOperations = async (page: Page) => {
  const pathKeyword = '/v4/pm/project-tree'
  await page.getByLabel('路径关键词').fill(pathKeyword)
  await expect(getInterfaceOperationTable(page)).toBeVisible()
  await expectOperationTablePathsInclude(page, pathKeyword)
  await page.getByTestId('clear-interface-operation-selection').click()
  await expect.poll(() => parseSelectedOperationCount(page)).toBe(0)
  await page.getByTestId('select-filtered-interface-operations').click()
  const selectedCount = await expectSelectedOperationCountGreaterThanZero(page)
  await expect.poll(() => getVisibleOperationCheckboxes(page)
    .evaluateAll((checkboxes) => checkboxes.every((checkbox) => (checkbox as HTMLInputElement).checked)))
    .toBe(true)
  return selectedCount
}

const expectNoAxeViolations = async (page: Page, selectors: string[]) => {
  const builder = new AxeBuilder({ page })
  for (const selector of selectors) builder.include(selector)
  const results = await builder.analyze()
  expect(results.violations).toEqual([])
}

test('wide shell covers expanded, collapsed, review, and delivery states', async ({ page }) => {
  await openFixture({ page, width: 1440, height: 900, stage: 'import' })
  await expect(page.locator('.studio-sidebar')).toHaveCSS('width', '168px')
  await expect(page).toHaveScreenshot('wide-import-expanded-1440.png')
  await page.getByRole('button', { name: '收起侧边栏' }).click()
  await expect(page.locator('.studio-sidebar')).toHaveCSS('width', '56px')
  await expect(page).toHaveScreenshot('wide-import-collapsed-1440.png')
  await page.reload()
  await expect(page.locator('.studio-sidebar')).toHaveCSS('width', '56px')

  await page.evaluate(({ key, value }) => localStorage.setItem(key, JSON.stringify(value)), {
    key: CASE_DESIGN_DRAFT_STORAGE_KEY,
    value: caseDesignDraftFixture('review')
  })
  await page.reload()
  await expect(page.getByLabel('需求与模块上下文')).toBeVisible()
  await expect(page.getByRole('complementary', { name: 'AI 质量评审' })).toBeVisible()
  expect(await page.evaluate(() => getComputedStyle(document.documentElement)
    .getPropertyValue('--brand-primary').trim())).toBe('#1f63c6')
  await expect(page.locator('.studio-review-inspector__score strong'))
    .toHaveCSS('color', 'rgb(31, 99, 198)')
  await expect(page).toHaveScreenshot('wide-review-context-inspector-1440.png')

  await page.evaluate(({ key, value }) => localStorage.setItem(key, JSON.stringify(value)), {
    key: CASE_DESIGN_DRAFT_STORAGE_KEY,
    value: caseDesignDraftFixture('deliver')
  })
  await page.reload()
  await expect(page.getByTestId('case-delivery-stage')).toBeVisible()
  await expect(page).toHaveScreenshot('wide-delivery-1440.png')
})

const webStageLabels = {
  import: '需求导入',
  parse: '内容解析',
  generate: 'AI 生成',
  review: '质量评审',
  deliver: '保存执行'
} as const

for (const stage of ['import', 'parse', 'generate', 'review', 'deliver'] as const) {
  test(`standard desktop renders ${stage} with long Chinese labels`, async ({ page }) => {
    await openFixture({ page, width: 1280, height: 800, stage })
    await expect(page.getByText('华东区域跨境订单履约与退款协同平台').first()).toBeVisible()
    await expect(page.locator('.studio-workflow-header__identity strong')).toHaveText(webStageLabels[stage])
    if (stage === 'import') {
      const labelsFit = await page.locator('.studio-sidebar__label').evaluateAll(
        (labels) => labels.every((label) => label.scrollWidth <= label.clientWidth)
      )
      expect(labelsFit).toBe(true)
    }
    await expect(page).toHaveScreenshot(`standard-${stage}-1280.png`)
  })
}

test('compact desktop exposes context and inspector drawers and table overflow', async ({ page }) => {
  await openFixture({ page, width: 1024, height: 768, stage: 'review' })
  await expect(page.locator('.studio-sidebar')).toHaveCSS('width', '56px')

  const contextTrigger = page.getByTestId('context-pane-drawer-button')
  const contextDrawer = page.getByRole('dialog', { name: '需求与模块' })
  await contextTrigger.click()
  await expect(contextDrawer).toBeVisible()
  await expect(page).toHaveScreenshot('compact-context-drawer-1024.png')
  await page.keyboard.press('Escape')
  await expect(contextDrawer).toBeHidden()
  await expect(contextTrigger).toBeFocused()

  const inspectorTrigger = page.getByTestId('inspector-drawer-button')
  const inspectorDrawer = page.getByRole('dialog', { name: 'AI 质量评审' })
  await inspectorTrigger.click()
  await expect(inspectorDrawer).toBeVisible()
  await expect(page).toHaveScreenshot('compact-inspector-drawer-1024.png')
  await page.keyboard.press('Escape')
  await expect(inspectorDrawer).toBeHidden()

  const tableBody = page.locator('.studio-generated-case-table .el-scrollbar__wrap').first()
  expect(await tableBody.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true)
  await tableBody.evaluate((element) => { element.scrollLeft = element.scrollWidth })
  await expect(page).toHaveScreenshot('compact-table-overflow-1024.png')
})

const stateCases: Array<{
  name: string
  stage: 'import' | 'parse' | 'generate' | 'review'
  variant: CaseDesignFixtureVariant
}> = [
  { name: 'first-empty', stage: 'import', variant: 'first-empty' },
  { name: 'loading', stage: 'parse', variant: 'loading' },
  { name: 'local-error', stage: 'import', variant: 'local-error' },
  { name: 'partial-success', stage: 'generate', variant: 'partial' }
]

for (const state of stateCases) {
  test(`captures ${state.name} workflow feedback`, async ({ page }) => {
    await openFixture({ page, width: 1280, height: 800, stage: state.stage, variant: state.variant })
    if (state.name === 'first-empty') {
      const importStage = page.getByTestId('requirement-import-stage')
      await expect(importStage).toContainText('添加需求文档、Figma 后开始解析')
      await expect(importStage).not.toContainText('导入 OpenAPI')
      await expect(importStage.locator('.studio-source-actions button')).toHaveCount(2)
      await expect(importStage.getByRole('button', { name: '添加需求来源' }))
        .toHaveCSS('background-color', 'rgb(31, 99, 198)')
    }
    await expect(page).toHaveScreenshot(`state-${state.name}-1280.png`)
  })
}

test('captures filtered-empty feedback without clearing filters automatically', async ({ page }) => {
  await openFixture({ page, width: 1280, height: 800, stage: 'review' })
  await page.getByPlaceholder('搜索 Web 用例').fill('完全不存在的筛选关键字')
  await expect(page.getByText('没有符合条件的用例')).toBeVisible()
  await expect(page).toHaveScreenshot('state-filtered-empty-1280.png')
})

test('captures offline local-first feedback', async ({ page, context }) => {
  await openFixture({ page, width: 1280, height: 800, stage: 'import' })
  await context.setOffline(true)
  await expect(page.getByText('当前离线')).toBeVisible()
  await expect(page).toHaveScreenshot('state-offline-1280.png')
  await context.setOffline(false)
})

test('captures local-versus-backend sync conflict recovery', async ({ page }) => {
  await openFixture({ page, width: 1280, height: 800, stage: 'import', syncConflict: true })
  await expect(page.locator('.studio-sync-notice')).toBeVisible()
  await expect(page).toHaveScreenshot('state-sync-conflict-1280.png')
})

test('below 900px preserves navigation and basic commands through the navigation drawer', async ({ page }) => {
  await openFixture({ page, width: 800, height: 700, stage: 'import' })
  await expect(page.locator('.studio-app-shell__sidebar')).toHaveCount(0)
  await expect(page.getByRole('group', { name: '用例设计模式' })).toHaveCount(0)
  await expect(page.locator('.studio-global-topbar__search')).toBeHidden()
  await page.getByRole('button', { name: '展开侧边栏' }).click()
  const navigation = page.getByRole('dialog', { name: '主导航' })
  await expect(navigation).toBeVisible()
  await expect(navigation.getByRole('button', { name: '智能用例设计' })).toHaveAttribute('aria-expanded', 'true')
  await expect(navigation.getByRole('button', { name: 'Web 用例' })).toHaveAttribute('aria-current', 'page')
  await expect(navigation.getByRole('button', { name: '接口用例' })).toBeVisible()
  await navigation.getByRole('button', { name: '用例库' }).click()
  await expect(page).toHaveURL(/\/cases$/)
})

test('default Web workspace uses the approved Studio Canvas breadcrumb', async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-07-16T14:32:00.000Z'))
  await page.setViewportSize({ width: 1280, height: 800 })
  await page.addInitScript(({ key, value }) => localStorage.setItem(key, JSON.stringify(value)), {
    key: CASE_DESIGN_DRAFT_STORAGE_KEY,
    value: { ...caseDesignDraftFixture('import'), projectName: '默认项目' }
  })
  await mockBackend(page)
  await page.goto('/api')

  const breadcrumb = page.getByRole('navigation', { name: '当前位置' })
  await expect(breadcrumb.locator('span').first()).toHaveText('智能用例设计')
  await expect(breadcrumb.locator('strong')).toHaveText('测试设计')
})

interfaceTest('interface workflow uses the shared Studio Canvas header and context pane', async ({ page }) => {
  await openInterfaceFixture({ page, width: 1280, height: 800 })

  await expect(page.locator('.studio-interface-case-canvas')).toBeVisible()
  await expect(page.locator('.studio-case-design-canvas')).toHaveCount(0)
  await expect(page.getByRole('navigation', { name: '测试设计流程' })).toBeVisible()
  await expect(page.getByRole('complementary', { name: '接口与生成配置' })).toBeVisible()
  await expect(page.locator('.topbar, .control-strip, .main-grid')).toHaveCount(0)
  await expectNoAxeViolations(page, ['.studio-interface-case-canvas'])
})

interfaceTest('interface four-stage journey imports, generates, reviews, and reaches delivery', async ({ page }) => {
  await openInterfaceFixture({ page, width: 1440, height: 900 })
  const breadcrumb = page.getByRole('navigation', { name: '当前位置' })
  await expect(breadcrumb.locator('span').first()).toHaveText('智能用例设计')
  await expect(breadcrumb.locator('strong')).toHaveText('用例生成')
  await importInterfaceOpenApi(page)

  const operationTable = getInterfaceOperationTable(page)
  await expect(operationTable).toBeVisible()
  await expect(operationTable).toContainText('接口列表')
  await expect(page.getByRole('complementary', { name: '接口与生成配置' })).not.toContainText('接口列表')
  await expect(page.locator('.studio-interface-case-canvas .studio-workflow-state')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '进入配置生成' })).toHaveCount(0)
  await expectVisibleOperationRowsMatchCheckboxes(page)

  const granularityBox = page.getByRole('radiogroup', { name: '生成粒度' })
  const granularityBounds = await granularityBox.boundingBox()
  expect(granularityBounds?.height).toBeLessThanOrEqual(34)
  expect(granularityBounds).toBeTruthy()
  const granularityItems = granularityBox.locator('.el-segmented__item')
  await expect(granularityItems).toHaveCount(3)
  expect(await granularityItems.evaluateAll((items) => items.map((item) => item.textContent?.trim()))).toEqual([
    '基础',
    '风险',
    '完整'
  ])
  const granularityItemBounds = []
  for (let index = 0; index < 3; index += 1) {
    const item = granularityItems.nth(index)
    await expect(item).toBeVisible()
    const itemBounds = await item.boundingBox()
    expect(itemBounds).toBeTruthy()
    granularityItemBounds.push(itemBounds!)
    expect(itemBounds!.x).toBeGreaterThanOrEqual(granularityBounds!.x - 1)
    expect(itemBounds!.y).toBeGreaterThanOrEqual(granularityBounds!.y - 1)
    expect(itemBounds!.x + itemBounds!.width).toBeLessThanOrEqual(granularityBounds!.x + granularityBounds!.width + 1)
    expect(itemBounds!.y + itemBounds!.height).toBeLessThanOrEqual(granularityBounds!.y + granularityBounds!.height + 1)
  }
  const granularityItemWidths = granularityItemBounds.map((bounds) => bounds.width)
  expect(Math.max(...granularityItemWidths) - Math.min(...granularityItemWidths)).toBeLessThanOrEqual(1)

  const initialSelectedCount = await parseSelectedOperationCount(page)
  const firstOperationCheckbox = getVisibleOperationCheckboxes(page).first()
  await expect(firstOperationCheckbox).toBeChecked()
  await firstOperationCheckbox.focus()
  await page.keyboard.press('Space')
  await expect(firstOperationCheckbox).not.toBeChecked()
  await expect.poll(() => parseSelectedOperationCount(page)).toBe(initialSelectedCount - 1)

  await page.getByTestId('select-filtered-interface-operations').click()
  await expect.poll(() => parseSelectedOperationCount(page)).toBe(initialSelectedCount)

  const selectedAfterSelectAll = await parseSelectedOperationCount(page)
  await page.getByLabel('路径关键词').fill('/v4/pm/project-tree')
  await expectOperationTablePathsInclude(page, '/v4/pm/project-tree')
  await expectVisibleOperationRowsMatchCheckboxes(page)
  await expect.poll(() => parseSelectedOperationCount(page)).toBe(selectedAfterSelectAll)

  await page.getByTestId('clear-interface-operation-selection').click()
  await expect.poll(() => parseSelectedOperationCount(page)).toBe(0)
  await page.getByTestId('select-filtered-interface-operations').click()
  const filteredSelectedCount = await expectSelectedOperationCountGreaterThanZero(page)
  await expectNoAxeViolations(page, ['[data-testid="interface-operation-table"]'])

  await waitForTransientMessages(page)
  await expect(page).toHaveScreenshot('interface-import-1440.png')

  const continueAction = page.getByTestId('workflow-primary-action')
  await expect(continueAction).toHaveText('继续配置')
  await continueAction.focus()
  await page.keyboard.press('Enter')
  const workflow = page.getByRole('navigation', { name: '测试设计流程' })
  await expect(workflow.getByRole('button', { name: '配置生成，当前阶段' }))
    .toHaveAttribute('aria-current', 'step')
  await expect(operationTable).toBeVisible()

  const generateAction = page.getByTestId('workflow-primary-action')
  await expect(generateAction).toHaveText('生成用例')
  await expect(operationTable).toBeVisible()
  await generateAction.focus()
  await page.keyboard.press('Enter')
  await expect(operationTable).toHaveCount(0)
  await expect(page.getByTestId('interface-case-toolbar')).toBeVisible()

  const completedGenerateStage = workflow.getByRole('button', { name: '配置生成，已完成' })
  await completedGenerateStage.focus()
  await page.keyboard.press('Enter')
  await page.setViewportSize({ width: 1280, height: 800 })
  await expect(page.getByRole('searchbox', { name: '搜索标题或接口路径' })).toBeVisible()
  await expect(page.getByTestId('interface-case-bulk-review')).toBeVisible()
  const operationHeader = page.getByRole('columnheader', { name: '操作' })
  await expect(operationHeader).toBeVisible()
  await expect(operationHeader).toHaveClass(/el-table-fixed-column--right/)
  await waitForTransientMessages(page)
  await expect(page).toHaveScreenshot('interface-generate-table-1280.png')

  const reviewStage = workflow.getByRole('button', { name: 'AI 评审，未开始' })
  await reviewStage.focus()
  await page.keyboard.press('Enter')
  await page.setViewportSize({ width: 1440, height: 900 })
  await expect(page.getByRole('complementary', { name: 'AI 质量评审' })).toBeVisible()
  const reviewAction = page.getByTestId('workflow-primary-action')
  await expect(reviewAction).toHaveText('运行 AI 评审')
  await reviewAction.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('complementary', { name: 'AI 质量评审' })).toContainText('评审完成')
  await expectNoAxeViolations(page, ['.studio-interface-case-canvas', '[aria-label="AI 质量评审"]'])
  await waitForTransientMessages(page)
  await expect(page).toHaveScreenshot('interface-review-inspector-1440.png')

  const deliveryStage = workflow.getByRole('button', { name: '保存 / 交付，未开始' })
  await deliveryStage.focus()
  await page.keyboard.press('Enter')
  await page.setViewportSize({ width: 1280, height: 800 })
  await expect(page.getByTestId('interface-case-delivery-stage')).toBeVisible()
  await expect(page.getByTestId('interface-save-action')).toBeEnabled()
  await expect(page.getByTestId('interface-transfer-action')).toBeEnabled()
  await expect(page.getByTestId('interface-export-action')).toBeEnabled()
  await expect(page).toHaveScreenshot('interface-delivery-1280.png')
})

interfaceTest('interface compact layout exposes drawers, focus return, and table overflow', async ({ page }) => {
  await openInterfaceFixture({ page, width: 1024, height: 768 })
  await importInterfaceOpenApi(page)
  await expect(page.locator('.studio-app-shell__context')).toHaveCount(0)
  await expect(getInterfaceOperationTable(page)).toBeVisible()

  const contextTrigger = page.getByTestId('interface-context-pane-drawer-button')
  await contextTrigger.click()
  const contextDialog = page.getByRole('dialog', { name: '接口与配置' })
  await expect(contextDialog).toBeVisible()
  await expect(contextDialog.getByRole('complementary', { name: '接口与生成配置' })).toBeVisible()
  await expect(contextDialog).toContainText('筛选')
  await expect(contextDialog).toContainText('生成配置')
  await expect(contextDialog).not.toContainText('接口列表')
  await expectNoAxeViolations(page, ['[role="dialog"]'])
  await contextDialog.getByLabel('路径关键词').fill('/v4/pm/project-tree')
  await expectOperationTablePathsInclude(page, '/v4/pm/project-tree')
  await page.keyboard.press('Escape')
  await expect(contextDialog).not.toBeVisible()
  await expect(contextTrigger).toBeFocused()

  const operationTable = getInterfaceOperationTable(page)
  await expect(operationTable).toBeVisible()
  await page.getByTestId('clear-interface-operation-selection').click()
  await expect.poll(() => parseSelectedOperationCount(page)).toBe(0)
  await page.getByTestId('select-filtered-interface-operations').click()
  await expectSelectedOperationCountGreaterThanZero(page)
  await expect(operationTable.getByRole('columnheader', { name: '请求路径' })).toBeVisible()
  await expect(operationTable.getByText('/v4/pm/project-tree').first()).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)

  await page.setViewportSize({ width: 600, height: 768 })
  await expect(operationTable).toBeVisible()
  await expect.poll(() => operationTable.evaluate((element) => {
    const stage = element.closest('.studio-interface-case-canvas__stage')
    return Boolean(stage && element.getBoundingClientRect().width <= stage.getBoundingClientRect().width)
  })).toBe(true)
  const operationScroll = operationTable.locator('.el-table__body-wrapper .el-scrollbar__wrap')
  expect(await operationScroll.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true)
  await operationScroll.evaluate((element) => { element.scrollLeft = element.scrollWidth })
  expect(await operationScroll.evaluate((element) => element.scrollLeft)).toBeGreaterThan(0)
  await expect(operationTable.getByRole('columnheader', { name: '请求路径' })).toBeVisible()
  await expect(operationTable.getByText('/v4/pm/project-tree').first()).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  await page.setViewportSize({ width: 1024, height: 768 })
  await expect(operationTable).toBeVisible()

  const workflow = page.getByRole('navigation', { name: '测试设计流程' })
  await workflow.getByRole('button', { name: '配置生成，未开始' }).click()
  await page.getByTestId('workflow-primary-action').click()
  await expect(page.getByTestId('interface-case-toolbar')).toBeVisible()
  await expect(page.locator('.studio-app-shell__inspector')).toHaveCount(0)

  const tableBody = page.locator('.interface-case-table__grid .el-table__body-wrapper .el-scrollbar__wrap')
  expect(await tableBody.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true)
  await tableBody.evaluate((element) => { element.scrollLeft = element.scrollWidth })
  await waitForTransientMessages(page)
  await expect(page).toHaveScreenshot('interface-compact-table-1024.png')

  await page.getByTestId('workflow-primary-action').click()
  await waitForTransientMessages(page)
  const inspectorTrigger = page.getByTestId('interface-inspector-drawer-button')
  await inspectorTrigger.click()
  const inspectorDialog = page.getByRole('dialog', { name: 'AI 评审' })
  await expect(inspectorDialog).toBeVisible()
  await expectNoAxeViolations(page, ['[role="dialog"]'])
  await expect(page).toHaveScreenshot('interface-compact-drawers-1024.png')
  await page.keyboard.press('Escape')
  await expect(inspectorDialog).not.toBeVisible()
  await expect(inspectorTrigger).toBeFocused()
  await expectNoAxeViolations(page, ['.studio-interface-case-canvas'])
})

interfaceTest('interface stage and row actions support case-specific keyboard commands', async ({ page }) => {
  await openInterfaceFixture({ page, width: 1280, height: 800 })
  await importInterfaceOpenApi(page)
  await selectProjectTreeOperations(page)

  const workflow = page.getByRole('navigation', { name: '测试设计流程' })
  const generateStage = workflow.getByRole('button', { name: '配置生成，未开始' })
  await generateStage.focus()
  await page.keyboard.press('Enter')
  await page.getByTestId('workflow-primary-action').press('Enter')
  await expect(page.getByTestId('interface-case-toolbar')).toBeVisible()

  for (const actionName of ['下移 API-0001', '上移 API-0001']) {
    const action = page.getByRole('button', { name: actionName })
    await action.focus()
    await expect(action).toBeFocused()
    await page.keyboard.press('Enter')
  }

  const editAction = page.getByRole('button', { name: '编辑 API-0001' })
  await editAction.focus()
  await page.keyboard.press('Enter')
  const editDialog = page.getByRole('dialog', { name: '编辑用例' })
  await expect(editDialog).toBeVisible()
  await expectNoAxeViolations(page, ['[role="dialog"]'])
  await page.keyboard.press('Escape')

  const deleteAction = page.getByRole('button', { name: '删除 API-0001' })
  await deleteAction.focus()
  await expect(deleteAction).toBeFocused()
  await page.keyboard.press('Enter')
  const deleteDialog = page.getByRole('dialog', { name: '删除生成用例' })
  await expect(deleteDialog).toBeVisible()
  await page.keyboard.press('Escape')

  await expectNoAxeViolations(page, ['.studio-interface-case-canvas'])
})

test('delivery summary shows a shared coverage gap only once', async ({ page }) => {
  await openFixture({ page, width: 1280, height: 800, stage: 'deliver' })
  await expect(page.getByText(
    '覆盖缺口 · 退款最终一致性覆盖缺口 · 退款模块需要校验订单与退款单状态一致',
    { exact: true }
  )).toHaveCount(1)
})

test('whole Studio Canvas shell passes axe checks', async ({ page }) => {
  await openFixture({ page, width: 1440, height: 900, stage: 'import' })
  await expectNoAxeViolations(page, ['.studio-app-shell'])
})

interfaceTest('smart case design expands into Web and interface subpages', async ({ page }) => {
  await openFixture({ page, width: 1280, height: 800, stage: 'import' })
  const sidebar = page.locator('.studio-sidebar')
  const parent = sidebar.getByRole('button', { name: '智能用例设计' })

  await expect(parent).toHaveAttribute('aria-expanded', 'true')
  await expect(sidebar.getByRole('group', { name: '智能用例设计子菜单' })).toBeVisible()
  await expect(sidebar.getByRole('button', { name: 'Web 用例' })).toHaveAttribute('aria-current', 'page')

  await parent.click()
  await expect(parent).toHaveAttribute('aria-expanded', 'false')
  await expect(sidebar.getByRole('button', { name: 'Web 用例' })).toHaveCount(0)

  await parent.click()
  await sidebar.getByRole('button', { name: '接口用例' }).click()
  await expect(page.locator('.studio-interface-case-canvas')).toBeVisible()
  await expect(sidebar.getByRole('button', { name: '接口用例' })).toHaveAttribute('aria-current', 'page')
  await expect(page.getByRole('group', { name: '用例设计模式' })).toHaveCount(0)
})

test('open responsive drawers restore focus and pass axe checks', async ({ page }) => {
  await openFixture({ page, width: 1024, height: 768, stage: 'review' })

  for (const drawer of [
    { triggerId: 'context-pane-drawer-button', title: '需求与模块' },
    { triggerId: 'inspector-drawer-button', title: 'AI 质量评审' }
  ]) {
    const trigger = page.getByTestId(drawer.triggerId)
    await trigger.click()

    const dialog = page.getByRole('dialog', { name: drawer.title })
    await expect(dialog).toBeVisible()
    await expectNoAxeViolations(page, ['[role="dialog"]'])

    await page.keyboard.press('Escape')
    await expect(dialog).not.toBeVisible()
    await expect(trigger).toBeFocused()
  }
})

test('review table and row actions support keyboard use and pass axe checks', async ({ page }) => {
  await openFixture({ page, width: 1280, height: 800, stage: 'review' })

  const caseAction = page.getByRole('button', { name: /查看 WEB-AI-0002/ })
  await caseAction.focus()
  await expect(caseAction).toBeFocused()
  await page.keyboard.press('Enter')

  const selectedRow = page
    .locator('.studio-generated-case-table tr')
    .filter({ hasText: 'WEB-AI-0002' })
    .first()
  await expect(selectedRow).toBeVisible()

  const editAction = selectedRow.getByRole('button', { name: '编辑 WEB-AI-0002' })
  await editAction.focus()
  await page.keyboard.press('Enter')
  const editDialog = page.getByRole('dialog', { name: '编辑用例' })
  await expect(editDialog).toBeVisible()
  await expectNoAxeViolations(page, ['[role="dialog"]'])
  await page.keyboard.press('Escape')

  const removeAction = selectedRow.getByRole('button', { name: '删除 WEB-AI-0002' })
  await removeAction.focus()
  await expect(removeAction).toBeFocused()

  await expectNoAxeViolations(page, ['.studio-review-stage'])
})

test('workflow stepper supports keyboard navigation and passes axe checks', async ({ page }) => {
  await openFixture({ page, width: 1280, height: 800, stage: 'review' })

  const workflow = page.getByRole('navigation', { name: '测试设计流程' })
  await expect(
    workflow.getByRole('button', { name: '质量评审，当前阶段' })
  ).toHaveAttribute('aria-current', 'step')

  const importStep = workflow.getByRole('button', { name: '需求导入，已完成' })
  await importStep.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('requirement-import-stage')).toBeVisible()

  await expectNoAxeViolations(page, ['.studio-workflow-header__steps'])
})

test('delivery choices support keyboard use and pass axe checks', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await openFixture({ page, width: 1280, height: 800, stage: 'deliver' })

  const automation = page.getByRole('radio', { name: /转为 Web 自动化/ })
  await automation.focus()
  await page.keyboard.press('Space')
  await expect(automation).toHaveAttribute('aria-checked', 'true')

  const executeNow = page.getByRole('checkbox', { name: /转换完成后立即执行/ })
  await executeNow.focus()
  await page.keyboard.press('Space')
  await expect(executeNow).toBeChecked()

  const library = page.getByRole('radio', { name: /保存到用例库/ })
  await library.focus()
  await page.keyboard.press('Enter')
  await expect(library).toHaveAttribute('aria-checked', 'true')
  await expect(executeNow).not.toBeVisible()

  await expectNoAxeViolations(page, ['[data-testid="case-delivery-stage"]'])
})

// Accessibility and keyboard tests intentionally add no screenshot assertions.
// The Web matrix remains 19 PNG files; the interface workflow adds 6 fixed baselines.

test('central brand token updates custom and Element Plus primary surfaces', async ({ page }) => {
  await openFixture({ page, width: 1440, height: 900, stage: 'import' })
  const activeNav = page.locator('.studio-sidebar__subitem--active')
  const primary = page.getByTestId('workflow-primary-action')
  const before = await Promise.all([
    activeNav.evaluate((el) => getComputedStyle(el).color),
    primary.evaluate((el) => getComputedStyle(el).backgroundColor)
  ])
  await page.evaluate(() => document.documentElement.style.setProperty('--brand-primary', '#0f766e'))
  await expect(activeNav).toHaveCSS('color', 'rgb(15, 118, 110)')
  await expect(primary).toHaveCSS('background-color', 'rgb(15, 118, 110)')
  const after = await Promise.all([
    activeNav.evaluate((el) => getComputedStyle(el).color),
    primary.evaluate((el) => getComputedStyle(el).backgroundColor)
  ])
  expect(after[0]).not.toBe(before[0])
  expect(after[1]).not.toBe(before[1])
})

const expectNonBlankWebRunImage = async (page: Page, alt: string) => {
  const image = page.getByRole('img', { name: alt }).first()
  await expect(image).toBeVisible()
  await expect.poll(() => image.evaluate((element: HTMLImageElement) => ({
    width: element.naturalWidth,
    height: element.naturalHeight,
    complete: element.complete
  }))).toMatchObject({ complete: true })
  const dimensions = await image.evaluate((element: HTMLImageElement) => ({
    width: element.naturalWidth,
    height: element.naturalHeight
  }))
  expect(dimensions.width).toBeGreaterThan(0)
  expect(dimensions.height).toBeGreaterThan(0)
  expect(await image.evaluate((element: HTMLImageElement) => {
    const canvas = document.createElement('canvas')
    canvas.width = 16
    canvas.height = 16
    const context = canvas.getContext('2d')
    if (!context) return false
    context.drawImage(element, 0, 0, 16, 16)
    const pixels = context.getImageData(0, 0, 16, 16).data
    const first = Array.from(pixels.slice(0, 4)).join(',')
    for (let index = 4; index < pixels.length; index += 4) {
      if (Array.from(pixels.slice(index, index + 4)).join(',') !== first) return true
    }
    return false
  })).toBe(true)
}

const expectNoDocumentOverflow = async (page: Page) => {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth
  }))
  expect(dimensions.scrollWidth, `Document overflowed horizontally: ${JSON.stringify(dimensions)}`)
    .toBeLessThanOrEqual(dimensions.clientWidth)
}

test.describe('Web automation workbench', () => {
  test('wide workbench executes selected scope and returns from monitor with state preserved', async ({ page }) => {
    const fixture = await openWebAutomationFixture(page, { width: 1440, height: 900, variant: 'idle-ready' })

    await expect(page.getByRole('heading', { name: 'Web 自动化' })).toBeVisible()
    await expect(page.getByText('执行前检查')).toBeVisible()
    await expect(page.getByRole('button', { name: '执行当前 3 条' })).toBeEnabled()
    await expect(page.locator('.web-automation-scope-pane')).toHaveCount(1)
    await expect(page.getByText('用例总数')).toHaveCount(0)
    await expect(page).toHaveScreenshot('web-automation-workbench-1440.png')

    await page.getByLabel(`选择 Web 用例 ${fixture.caseIds[0]}`).check()
    await page.getByLabel(`选择 Web 用例 ${fixture.caseIds[1]}`).check()
    await page.getByLabel('搜索 Web 用例').fill('退款')
    await expect(page.getByRole('button', { name: '执行选中 2 条' })).toBeEnabled()

    await page.getByRole('button', { name: '执行选中 2 条' }).click()
    await expect.poll(() => fixture.getCreateBodies().length).toBe(1)
    expect(fixture.getCreateBodies()[0].cases.map((testCase) => testCase.id)).toEqual([
      fixture.caseIds[0],
      fixture.caseIds[1]
    ])
    await expect(page.getByTestId('web-automation-workspace')).toBeVisible()
    await expect(page.getByRole('button', { name: '进入实时监控' })).toBeVisible()
    await expect(page.getByText('执行进度')).toBeVisible()
    await expectNonBlankWebRunImage(page, 'Web 自动化运行截图')
    const tableScroll = page.locator('.web-automation-case-table__scroll')
    await tableScroll.evaluate((element) => { element.scrollLeft = element.scrollWidth })
    const tableScrollLeft = await tableScroll.evaluate((element) => element.scrollLeft)
    expect(tableScrollLeft).toBeGreaterThan(0)

    await page.getByRole('button', { name: '进入实时监控' }).click()
    await expect(page).toHaveURL(/\/automation\/web\?view=monitor&runId=run-fixture$/)
    await expectNonBlankWebRunImage(page, 'Web 自动化实时页面截图')
    await expect(page).toHaveScreenshot('web-automation-monitor-1440.png')

    await page.getByRole('button', { name: '返回 Web 自动化工作台' }).click()
    await expect(page).toHaveURL(/\/automation\/web$/)
    await expect(page.getByLabel('搜索 Web 用例')).toHaveValue('退款')
    await expect(page.getByText('已选 2 个')).toBeVisible()
    await expect(page.getByText('执行进度')).toBeVisible()
    await expect(page.getByRole('progressbar', { name: 'Web 自动化执行进度' })).toBeVisible()
    await expectNonBlankWebRunImage(page, 'Web 自动化运行截图')
    expect(await tableScroll.evaluate((element) => element.scrollLeft)).toBe(tableScrollLeft)
    await expect(page.getByRole('button', { name: '进入实时监控' })).toBeFocused()
    expect(fixture.getMaxConcurrentRunGets()).toBeLessThanOrEqual(1)
  })

  test('recovers stored and queried sessions without creating replacement runs', async ({ page }) => {
    const stored = await openWebAutomationFixture(page, {
      width: 1440,
      height: 900,
      variant: 'running',
      restoreRunId: 'run-fixture'
    })
    await expect(page.getByRole('button', { name: '进入实时监控' })).toBeVisible()
    expect(stored.getCreateBodies()).toEqual([])
    expect(stored.getRunGetCount()).toBeGreaterThanOrEqual(1)
    expect(stored.getMaxConcurrentRunGets()).toBeLessThanOrEqual(1)

    const queriedPage = await page.context().newPage()
    const queried = await openWebAutomationFixture(queriedPage, {
      width: 1440,
      height: 900,
      variant: 'running',
      restoreRunId: 'run-fixture',
      monitorQuery: true
    })
    await expect(queriedPage).toHaveURL(/view=monitor&runId=run-fixture/)
    await expect(queriedPage.getByRole('heading', { name: '实时监控' })).toBeVisible()
    expect(queried.getCreateBodies()).toEqual([])
    expect(queried.getRunGetCount()).toBeGreaterThanOrEqual(1)
    expect(queried.getMaxConcurrentRunGets()).toBeLessThanOrEqual(1)
    await queriedPage.close()

    const retryingPage = await page.context().newPage()
    const retrying = await openWebAutomationFixture(retryingPage, {
      width: 1440,
      height: 900,
      variant: 'retrying',
      restoreRunId: 'run-fixture',
      monitorQuery: true
    })
    await expect(retryingPage).toHaveURL(/view=monitor&runId=run-fixture/)
    await expect(retryingPage.getByRole('heading', { name: '实时监控' })).toBeVisible()
    await expect(retryingPage.getByTestId('web-run-connection-status')).toContainText('正在重连')
    await expectNonBlankWebRunImage(retryingPage, 'Web 自动化实时页面截图')
    expect(retrying.getCreateBodies()).toEqual([])
    expect(retrying.getRunGetCount()).toBeGreaterThanOrEqual(2)
    expect(retrying.getMaxConcurrentRunGets()).toBeLessThanOrEqual(1)
    await retryingPage.close()
  })

  test('clears stale recovery while preserving interrupted evidence', async ({ page }) => {
    const fixture = await openWebAutomationFixture(page, { width: 1440, height: 900, variant: 'interrupted' })
    await page.getByRole('button', { name: '执行当前 3 条' }).click()
    await expect(page.getByText(/执行已中断/)).toBeVisible()
    await expect(page).toHaveURL(/\/automation\/web$/)
    await expectNonBlankWebRunImage(page, 'Web 自动化运行截图')
    expect(await page.evaluate((key) => sessionStorage.getItem(key), 'ai-test-platform.web-active-run.v1')).toBeNull()
    expect(fixture.getCreateBodies()).toHaveLength(1)
    expect(fixture.getMaxConcurrentRunGets()).toBeLessThanOrEqual(1)
  })

  test('keeps scope state after blocked preflight and creation failure', async ({ page }) => {
    await openWebAutomationFixture(page, { width: 1440, height: 900, variant: 'preflight-blocked' })
    await page.getByLabel('搜索 Web 用例').fill('退款')
    await page.getByLabel(/选择 Web 用例 WEB-AUTO-WEB-E2E-0001/).check()
    await expect(page.getByTestId('execute-web-automation')).toBeDisabled()
    await expect(page.getByLabel('搜索 Web 用例')).toHaveValue('退款')
    await expect(page.getByText('已选 1 个')).toBeVisible()

    const failingPage = await page.context().newPage()
    await openWebAutomationFixture(failingPage, { width: 1440, height: 900, variant: 'creation-failed' })
    await failingPage.getByLabel('搜索 Web 用例').fill('退款')
    await failingPage.getByLabel(/选择 Web 用例 WEB-AUTO-WEB-E2E-0002/).check()
    await failingPage.getByRole('button', { name: '执行选中 1 条' }).click()
    await expect(failingPage.getByTestId('web-execution-blocking-reason')).toContainText('Web runner 暂不可用')
    await expect(failingPage.getByLabel('搜索 Web 用例')).toHaveValue('退款')
    await expect(failingPage.getByText('已选 1 个')).toBeVisible()
    await failingPage.close()
  })

  test('shows retry and terminal evidence with log defect and report flows', async ({ page }) => {
    await openWebAutomationFixture(page, { width: 1440, height: 900, variant: 'completed' })
    await page.getByRole('button', { name: '执行当前 3 条' }).click()
    await expect(page.getByRole('button', { name: '进入实时监控' })).toBeVisible()
    await page.getByRole('button', { name: '进入实时监控' }).click()
    await expect(page.getByRole('heading', { name: '实时监控' })).toBeVisible()
    await expect(page.getByTestId('web-run-count-passed')).toContainText('通过 1')
    await expect(page.getByTestId('web-run-count-failed')).toContainText('失败 1')
    await expect(page.getByTestId('web-run-count-error')).toContainText('错误 1')
    await expect(page.getByTestId('web-run-count-running')).toContainText('运行中 0')
    await expect(page.getByTestId('web-run-count-pending')).toContainText('待执行 0')

    await page.getByRole('button', { name: /查看 Web 用例 WEB-AUTO-WEB-E2E-0002 日志/ })
      .evaluate((button: HTMLButtonElement) => button.click())
    await expect(page.getByText('执行日志').first()).toBeVisible()
    await expect(page.locator('.el-drawer').getByText('断言失败：支付渠道状态仍为处理中').first()).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog', { name: '执行日志' })).toBeHidden()

    await page.getByTestId('open-web-run-report').evaluate((button: HTMLButtonElement) => button.click())
    await expect(page).toHaveURL(/\/reports$/)
    await expect(page.getByText('WEB-AUTO-WEB-E2E-0002', { exact: true })).toBeVisible()
    await page.goBack()
    await expect(page.getByRole('heading', { name: '实时监控' })).toBeVisible()

    await page.getByRole('button', { name: /将 Web 用例 WEB-AUTO-WEB-E2E-0002 转为缺陷/ })
      .evaluate((button: HTMLButtonElement) => button.click())
    await expect(page.getByText(/已转入缺陷/)).toBeVisible()
  })

  test('standard desktop keeps three columns and table horizontal scroll', async ({ page }) => {
    await openWebAutomationFixture(page, { width: 1280, height: 800, variant: 'partial-result', restoreRunId: 'run-fixture' })
    await expectNoDocumentOverflow(page)
    const gridColumns = await page.locator('.web-automation-workspace__grid').evaluate((element) =>
      getComputedStyle(element).gridTemplateColumns.split(' ').length
    )
    expect(gridColumns).toBe(3)
    await expect(page.getByText('跨境订单退款状态回补、支付渠道重试').first()).toBeVisible()
    const tableScroll = page.locator('.web-automation-case-table__scroll')
    expect(await tableScroll.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true)
    await tableScroll.evaluate((element) => { element.scrollLeft = element.scrollWidth })
    await expect(page.getByRole('button', { name: '执行当前 3 条' })).toBeVisible()
    await expect(page.getByText('执行进度')).toBeVisible()
    await expectNoDocumentOverflow(page)
    await page.getByRole('button', { name: '进入实时监控' }).click()
    await expect(page.getByRole('heading', { name: '实时监控' })).toBeVisible()
    await expect(page.getByTestId('web-run-count-passed')).toContainText('通过 1')
    await expect(page.getByTestId('web-run-count-failed')).toContainText('失败 1')
    await expect(page.getByTestId('web-run-count-error')).toContainText('错误 0')
    await expect(page.getByTestId('web-run-count-running')).toContainText('运行中 1')
    await expect(page.getByTestId('web-run-count-pending')).toContainText('待执行 0')
    await expectNoDocumentOverflow(page)
  })

  test('compact and narrow layouts use drawers and avoid document overflow', async ({ page }) => {
    await openWebAutomationFixture(page, { width: 1024, height: 768, variant: 'running', restoreRunId: 'run-fixture' })
    await expectNoDocumentOverflow(page)
    await expect(page.locator('.studio-sidebar')).toHaveCSS('width', '56px')
    await expect(page.locator('.web-automation-scope-pane')).toHaveCount(0)
    await expect(page.locator('.web-preflight')).toHaveCount(0)
    await page.getByTestId('open-web-scope').click()
    await expect(page.getByRole('dialog', { name: '执行范围' })).toBeVisible()
    await expectNoDocumentOverflow(page)
    await page.keyboard.press('Escape')
    await page.getByTestId('open-web-context').click()
    await expect(page.getByRole('dialog', { name: '运行监控' })).toBeVisible()
    await expectNoDocumentOverflow(page)
    await page.getByRole('button', { name: '进入实时监控' }).click()
    await expect(page.getByRole('heading', { name: '实时监控' })).toBeVisible()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expectNoDocumentOverflow(page)

    const narrowPage = await page.context().newPage()
    await openWebAutomationFixture(narrowPage, { width: 390, height: 844, variant: 'idle-ready' })
    await expect(narrowPage.getByRole('combobox', { name: 'Web 执行环境' })).toBeVisible()
    await expect(narrowPage.getByRole('combobox', { name: '浏览器选择' })).toBeVisible()
    await expect(narrowPage.getByRole('spinbutton', { name: '重复次数' })).toBeVisible()
    const executeBounds = await narrowPage.getByTestId('execute-web-automation').boundingBox()
    const workspaceBounds = await narrowPage.getByTestId('web-automation-workspace').boundingBox()
    expect(executeBounds && workspaceBounds && executeBounds.width >= workspaceBounds.width * 0.6).toBe(true)
    await narrowPage.getByTestId('open-web-scope').click()
    await expect(narrowPage.getByRole('dialog', { name: '执行范围' })).toBeVisible()
    await narrowPage.keyboard.press('Escape')
    await narrowPage.getByTestId('open-web-context').click()
    await expect(narrowPage.getByRole('dialog', { name: '执行检查' })).toBeVisible()
    expect(await narrowPage.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
    await narrowPage.close()
  })

  test('workspace and monitor pass axe keyboard and reduced-motion checks', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await openWebAutomationFixture(page, { width: 1440, height: 900, variant: 'running', restoreRunId: 'run-fixture' })
    await expectNoAxeViolations(page, ['[data-testid="web-automation-workspace"]'])

    await page.getByLabel(/选择 Web 用例 WEB-AUTO-WEB-E2E-0001/).focus()
    await page.keyboard.press('Space')
    await expect(page.getByLabel(/选择 Web 用例 WEB-AUTO-WEB-E2E-0001/)).toBeChecked()
    await page.getByRole('button', { name: '进入实时监控' }).focus()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('heading', { name: '实时监控' })).toBeVisible()
    await expectNoAxeViolations(page, ['.web-run-monitor-view'])
    await page.getByTestId('pause-web-run').focus()
    await expect(page.getByTestId('pause-web-run')).toBeFocused()
    await page.getByTestId('stop-web-run').focus()
    await page.getByTestId('stop-web-run').evaluate((button: HTMLButtonElement) => button.click())
    await expect(page.getByTestId('web-run-lifecycle-status')).toContainText('已停止')
    await page.getByRole('button', { name: '返回 Web 自动化工作台' }).click()
    await expect(page.getByRole('button', { name: '进入实时监控' })).toBeFocused()
    expect(await page.locator('[data-testid="web-automation-workspace"], .web-run-monitor-view').evaluateAll((elements) =>
      elements.every((element) =>
        getComputedStyle(element).animationDuration.split(', ').every((duration) => duration === '0s')
      )
    )).toBe(true)
  })
})
