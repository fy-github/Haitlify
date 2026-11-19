export const runner = {
  actionId: 'a', actionType: 'click', evidenceId: 'e',
  before: { pageUrl: '', pageKey: 'k', pageFingerprint: 'fingerprint', additive: false },
  after: { pageUrl: 'after', pageKey: '', pageFingerprint: 'after-f', additive: [] },
  execution: { status: 'failed' as const, durationMs: 0, additive: null },
  resolvedTarget: { locator: '#x', visible: false, enabled: false, receivesEvents: false, unique: false, nested: { zero: 0, empty: '' } },
  locator: '', visible: false, enabled: false, receivesEvents: false, unique: false,
  observationStatus: 'passed' as const, failureType: '',
  future: JSON.parse('{"__proto__":{"inert":true},"constructor":0,"empty":[]}')
}
export const source = {
  caseId: 'web-1', sourceCaseId: 'src', name: 'failure', module: 'm', pageName: '', startUrl: '',
  status: 'failed' as const, durationMs: 0,
  steps: [{ id: 's', name: '', action: 'click' as const, targetType: 'locator' as const, target: '', timeoutMs: 0, future: false }],
  stepLogs: [{ stepId: 's', phase: 'test' as const, name: '', action: 'click' as const, target: '', status: 'failed' as const, durationMs: 0, startedAt: '', endedAt: '', pageUrl: '', errorMessage: '', errorStack: '', future: [false, null, 0] }],
  stepEvidence: [runner], failedStepId: '', pageHtmlSnippet: '', screenshotPath: '', errorMessage: '',
  consoleErrors: [], pageErrors: [''], failedRequests: []
}
export const options = { projectName: 'p', issueType: 'Bug' }
export class TestStorage implements Storage {
  data = new Map<string, string>(); writes = 0; reads = 0
  get length() { return this.data.size }
  getItem(key: string) { this.reads++; return this.data.get(key) ?? null }
  setItem(key: string, value: string) { this.writes++; this.data.set(key, value) }
  removeItem(key: string) { this.data.delete(key) }
  key(index: number) { return [...this.data.keys()][index] ?? null }
  clear() { this.data.clear() }
}

