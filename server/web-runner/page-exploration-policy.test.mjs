import test from 'node:test'
import assert from 'node:assert/strict'
import {
  EXPLORATION_DEFAULTS,
  expandIntentTerms,
  isForbiddenBusinessAction,
  normalizeIntent,
  normalizeText,
  scoreObservation
} from './page-exploration-policy.mjs'

test('exposes bounded exploration defaults', () => {
  assert.deepEqual(EXPLORATION_DEFAULTS, {
    maxRounds: 5,
    maxDurationMs: 90_000,
    maxSameFailureRetries: 2,
    maxLoginRetries: 1,
    maxReloads: 1,
    maxPaginationSteps: 3,
    minimumTotalScore: 70,
    minimumElementScore: 25
  })
})

test('normalizes every intent field and rejects generic business element terms', () => {
  const intent = normalizeIntent({
    pageAliases: [' 威胁情报业务列表页 ', '威胁 情报'],
    routeHints: [' /threat-intelligence ', '/threat-intelligence'],
    elementTerms: ['停用', '项目', '设备', '应用', '系统', '服务', '用户', '首页', '管理', '页面', '列表'],
    actionTerms: [' 查看 ', '查看'],
    stateTerms: [' 即将 到期 '],
    expectedTexts: [' 操作 成功 '],
    negativeTerms: [' 全局 导航 ', '项目']
  })

  assert.equal(normalizeText(' 威胁 情报业务列表页 '), '威胁情报')
  assert.deepEqual(intent.pageAliases, ['威胁情报'])
  assert.deepEqual(intent.routeHints, ['/threat-intelligence'])
  assert.deepEqual(intent.elementTerms, ['停用'])
  assert.deepEqual(intent.actionTerms, ['查看'])
  assert.deepEqual(intent.stateTerms, ['即将到期'])
  assert.deepEqual(intent.expectedTexts, ['操作成功'])
  assert.deepEqual(intent.explicitNegativeTerms, ['全局导航', '项目'])
  assert.ok(intent.negativeTerms.includes('全局导航'))
  assert.ok(intent.negativeTerms.includes('项目'))
})

test('keeps compound business elements that only contain automatic generic terms', () => {
  for (const elementTerm of ['用户组', '云服务', '设备组', '项目模板']) {
    const targetElement = { label: elementTerm, contextText: '状态正常' }
    const score = scoreObservation(normalizeIntent({
      pageAliases: ['资源中心'],
      elementTerms: [elementTerm],
      stateTerms: ['状态正常']
    }), {
      title: '资源中心',
      headings: ['资源中心'],
      elements: [targetElement]
    })

    assert.equal(score.elementScore, 25, elementTerm)
    assert.deepEqual(score.relevantElements, [targetElement], elementTerm)
    assert.equal(score.stateScore, 10, elementTerm)
    assert.equal(score.matched, true, elementTerm)
  }
})

test('expands only the bounded business synonym groups', () => {
  assert.deepEqual(expandIntentTerms(['停用']), ['停用', '关闭', '禁用'])
  assert.deepEqual(expandIntentTerms(['重新开通']), ['重新开通', '恢复', '启用'])
  assert.deepEqual(expandIntentTerms(['删除']), ['删除', '移除'])
  assert.deepEqual(expandIntentTerms(['筛选']), ['筛选'])
})

test('forbids mutation labels while allowing cancel', () => {
  for (const label of ['确定', '提交', '保存', '删除', '停用', '启用', '开通', '支付', '发送', '上传']) {
    assert.equal(isForbiddenBusinessAction(`立即${label}`), true, label)
  }
  assert.equal(isForbiddenBusinessAction('取消'), false)
})

test('forbids confirmation labels before they can trigger business mutations', () => {
  assert.equal(isForbiddenBusinessAction('确认'), true)
  assert.equal(isForbiddenBusinessAction('确认删除'), true)
  assert.equal(isForbiddenBusinessAction('确认停用'), true)
})

test('requires business element evidence instead of global navigation', () => {
  const score = scoreObservation(normalizeIntent({
    pageAliases: ['威胁情报'],
    elementTerms: ['停用'],
    stateTerms: ['即将到期'],
    negativeTerms: ['项目', '服务']
  }), {
    url: 'https://example.test/#/project',
    title: '项目',
    breadcrumbs: [],
    activeMenus: ['项目'],
    headings: [],
    elements: [{ label: '项目', role: 'button', contextText: '服务管理' }]
  })

  assert.equal(score.matched, false)
  assert.equal(score.elementScore, 0)
  assert.deepEqual(score.relevantElements, [])
})

test('excludes target-looking elements and state evidence from negative navigation context', () => {
  const score = scoreObservation(normalizeIntent({
    pageAliases: ['威胁情报'],
    elementTerms: ['停用'],
    stateTerms: ['即将到期'],
    negativeTerms: ['项目', '服务']
  }), {
    title: '威胁情报',
    headings: ['威胁情报'],
    elements: [{
      label: '停用',
      role: 'button',
      contextText: '项目服务即将到期'
    }]
  })

  assert.deepEqual(score.relevantElements, [])
  assert.equal(score.elementScore, 0)
  assert.equal(score.stateScore, 0)
  assert.equal(score.totalScore, 40)
  assert.equal(score.matched, false)
})

test('prioritizes matching row context over global navigation text', () => {
  const targetElement = {
    label: '查看详情',
    role: 'button',
    rowContext: '冻结资格客户',
    contextText: '全局导航项目管理'
  }
  const score = scoreObservation(normalizeIntent({
    pageAliases: ['权益中心'],
    elementTerms: ['查看详情'],
    stateTerms: ['冻结'],
    negativeTerms: ['全局导航', '项目管理']
  }), {
    title: '权益中心',
    headings: ['权益中心'],
    elements: [targetElement]
  })

  assert.deepEqual(score.relevantElements, [targetElement])
  assert.equal(score.elementScore, 25)
  assert.equal(score.stateScore, 10)
})

test('excludes explicit negative page state text from state evidence', () => {
  const score = scoreObservation(normalizeIntent({
    pageAliases: ['威胁情报'],
    elementTerms: ['停用'],
    stateTerms: ['即将到期'],
    negativeTerms: ['项目服务']
  }), {
    title: '威胁情报',
    headings: ['威胁情报'],
    elements: [{ label: '停用', contextText: '' }],
    stateTexts: ['项目服务即将到期']
  })

  assert.equal(score.pageScore, 40)
  assert.equal(score.elementScore, 25)
  assert.equal(score.stateScore, 0)
  assert.equal(score.totalScore, 65)
  assert.equal(score.matched, false)
})

test('matches title and main heading with target element and state evidence', () => {
  const targetElement = {
    label: '停用',
    role: 'button',
    contextText: '许可证即将到期'
  }
  const score = scoreObservation(normalizeIntent({
    pageAliases: ['威胁情报'],
    elementTerms: ['停用'],
    stateTerms: ['即将到期']
  }), {
    url: 'https://example.test/#/threat-intelligence',
    title: '威胁情报',
    breadcrumbs: [],
    activeMenus: [],
    headings: ['威胁情报'],
    elements: [targetElement]
  })

  assert.deepEqual(score, {
    pageScore: 40,
    elementScore: 25,
    stateScore: 10,
    totalScore: 75,
    relevantElements: [targetElement],
    matched: true
  })
})

test('counts URL, title, breadcrumb, active menu, and heading as independent capped page signals', () => {
  const score = scoreObservation(normalizeIntent({
    pageAliases: ['威胁情报'],
    elementTerms: ['停用']
  }), {
    url: 'https://example.test/#/threat-intelligence',
    title: '威胁情报',
    breadcrumbs: ['威胁情报'],
    activeMenus: ['威胁情报'],
    headings: ['威胁情报'],
    elements: [{ label: '关闭', contextText: '' }]
  })

  assert.equal(score.pageScore, 40)
  assert.equal(score.elementScore, 25)
})

test('does not treat an action label as state context', () => {
  const score = scoreObservation(normalizeIntent({
    pageAliases: ['威胁情报'],
    elementTerms: ['停用'],
    stateTerms: ['停用']
  }), {
    title: '威胁情报',
    headings: ['威胁情报'],
    elements: [{ label: '停用', role: 'button', contextText: '' }]
  })

  assert.equal(score.elementScore, 25)
  assert.equal(score.stateScore, 0)
})

test('returns a stable zero score when inputs are missing', () => {
  assert.deepEqual(normalizeIntent(), {
    pageAliases: [],
    routeHints: [],
    elementTerms: [],
    actionTerms: [],
    stateTerms: [],
    expectedTexts: [],
    explicitNegativeTerms: [],
    negativeTerms: ['项目', '设备', '应用', '系统', '服务', '用户', '首页', '管理', '列表']
  })
  assert.deepEqual(scoreObservation(), {
    pageScore: 0,
    elementScore: 0,
    stateScore: 0,
    totalScore: 0,
    relevantElements: [],
    matched: false
  })
})
