// @vitest-environment jsdom
import { beforeEach, expect, it, vi } from 'vitest'
import { createWorkspaceStorage } from './workspaceStorage'
import { advanceAuthSessionEpoch } from '../domain/http'
beforeEach(() => { localStorage.clear(); advanceAuthSessionEpoch() })
it.each(['B', 'ABA', 'same', 'epoch'])('strict template authority never revives after %s', boundary => {
  const ws = createWorkspaceStorage(localStorage); ws.activate('A')
  // Existing capture is the behavioral baseline until strict capture is supplied.
  const a = (ws as any).captureStrict?.(() => true)
  const storage = a?.storage ?? ws.capture()
  storage.setItem('k', 'original')
  if (boundary === 'B') ws.activate('B')
  if (boundary === 'ABA') { ws.activate('B'); ws.invalidate(); ws.activate('A') }
  if (boundary === 'same') ws.activate('A')
  if (boundary === 'epoch') advanceAuthSessionEpoch()
  const before = { ...localStorage }; storage.setItem('k', 'late'); storage.removeItem('k'); storage.clear()
  expect({ ...localStorage }).toEqual(before)
  expect(a?.state()).toBe('superseded')
})
it('identity checking performs zero backing reads and writes', () => {
  const backing = { getItem: vi.fn(() => { throw Error('unreadable') }), setItem: vi.fn(), length: 0 } as unknown as Storage
  const ws = createWorkspaceStorage(backing)
  expect((ws as any).captureStrict?.(() => true) ?? null).toBeNull()
  ws.activate('A'); const a = (ws as any).captureStrict?.(() => true)
  expect(a?.state()).toBe('usable'); expect(backing.getItem).not.toHaveBeenCalled(); expect(backing.setItem).not.toHaveBeenCalled()
})
