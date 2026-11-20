// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { computed } from 'vue'
import { createJiraSettings } from './useJiraSettings'

afterEach(() => vi.unstubAllGlobals())

it.each([200, 503])('an obsolete Jira read (HTTP %s) cannot replace the newer verified config', async status => {
  let release!: (response: Response) => void
  const pending = new Promise<Response>(resolve => { release = resolve })
  let requests = 0
  vi.stubGlobal('fetch', vi.fn(async () => {
    requests += 1
    return requests === 1 ? pending : response(200, 'newest-reader')
  }))
  let operation = 0
  const settings = createJiraSettings({
    beginWorkspaceOperation: () => {
      const current = ++operation
      return () => current === operation
    },
    canEdit: computed(() => true),
    confirmDangerAction: async () => true
  })

  const first = settings.loadJiraSettingsFromBackend()
  await vi.waitFor(() => expect(requests).toBe(1))
  await settings.loadJiraSettingsFromBackend()
  release(response(status, 'obsolete-reader'))
  await first

  expect(requests).toBe(2)
  expect(settings.jiraConfigReadState.value).toBe('ready')
  expect(settings.jiraConfigForm.username).toBe('newest-reader')
  expect(settings.jiraConfigLoading.value).toBe(false)
})

function response(status: number, username: string): Response {
  return new Response(JSON.stringify(status === 200
    ? { enabled: true, baseUrl: 'https://jira.fixture.example.test', username, credentialConfigured: false, projectJql: {} }
    : { message: 'read failed' }), { status, headers: { 'Content-Type': 'application/json' } })
}
