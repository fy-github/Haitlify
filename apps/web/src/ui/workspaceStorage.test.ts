// @vitest-environment jsdom
import { beforeEach, expect, it } from 'vitest'
import { createWorkspaceStorage } from './workspaceStorage'
import { advanceAuthSessionEpoch } from '../domain/http'
beforeEach(() => { localStorage.clear(); advanceAuthSessionEpoch() })
it('refuses unowned legacy reads and writes when identity is unavailable', () => {
  localStorage.setItem('secret', 'legacy')
  const scope = createWorkspaceStorage(localStorage)
  scope.activate(undefined)
  expect(scope.storage.getItem('secret')).toBeNull()
  scope.storage.setItem('secret', 'replacement')
  expect(localStorage.getItem('secret')).toBe('legacy')
  expect(localStorage.length).toBe(1)
})
it('captured A storage never reads or writes B after an auth epoch change', () => {
  const scope = createWorkspaceStorage(localStorage)
  scope.activate('user:1'); scope.storage.setItem('secret', 'A')
  const old = scope.capture()
  advanceAuthSessionEpoch(); scope.activate('user:2'); scope.storage.setItem('secret', 'B')
  old.setItem('secret', 'late A'); old.clear()
  expect(old.getItem('secret')).toBeNull()
  expect(scope.storage.getItem('secret')).toBe('B')
  advanceAuthSessionEpoch(); scope.activate('user:1')
  expect(scope.storage.getItem('secret')).toBe('A')
})
it('same-session superseded operation cannot write even its own namespace', () => {
  const scope = createWorkspaceStorage(localStorage); scope.activate('user:1')
  let current = true
  const old = scope.capture(() => current)
  old.setItem('key', 'first'); current = false
  scope.storage.setItem('key', 'newest'); old.setItem('key', 'late'); old.removeItem('key')
  expect(scope.storage.getItem('key')).toBe('newest')
})
it('clear only removes the current owner namespace', () => {
  localStorage.setItem('legacy', 'preserved')
  const scope = createWorkspaceStorage(localStorage); scope.activate('user:1'); scope.storage.setItem('key', 'A')
  advanceAuthSessionEpoch(); scope.activate('user:2'); scope.storage.setItem('key', 'B'); scope.storage.clear()
  expect(scope.storage.length).toBe(0); expect(localStorage.getItem('legacy')).toBe('preserved')
  advanceAuthSessionEpoch(); scope.activate('user:1'); expect(scope.storage.getItem('key')).toBe('A')
})
