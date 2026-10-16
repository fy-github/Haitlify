import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  LOCAL_NETWORK_ACCESS_ENV,
  LOGIN_DISCOVERY_PRIVATE_NETWORKS_ENV,
  resolveAllowLocalNetworkAccess,
  resolveAllowPrivateNetworks,
} from './runner-env.mjs'

test('本地网络访问兼容模式默认开启（未设置环境变量时）', () => {
  assert.equal(resolveAllowLocalNetworkAccess({}), true)
})

test('本地网络访问兼容模式仅在显式 false 时关闭', () => {
  assert.equal(resolveAllowLocalNetworkAccess({ [LOCAL_NETWORK_ACCESS_ENV]: 'false' }), false)
  assert.equal(resolveAllowLocalNetworkAccess({ [LOCAL_NETWORK_ACCESS_ENV]: 'true' }), true)
  assert.equal(resolveAllowLocalNetworkAccess({ [LOCAL_NETWORK_ACCESS_ENV]: '' }), true)
  assert.equal(resolveAllowLocalNetworkAccess({ [LOCAL_NETWORK_ACCESS_ENV]: '0' }), true)
})

test('登录发现私网放行默认开启（未设置环境变量时）', () => {
  assert.equal(resolveAllowPrivateNetworks({}), true)
})

test('登录发现私网放行仅在显式 false 时关闭', () => {
  assert.equal(resolveAllowPrivateNetworks({ [LOGIN_DISCOVERY_PRIVATE_NETWORKS_ENV]: 'false' }), false)
  assert.equal(resolveAllowPrivateNetworks({ [LOGIN_DISCOVERY_PRIVATE_NETWORKS_ENV]: 'true' }), true)
  assert.equal(resolveAllowPrivateNetworks({ [LOGIN_DISCOVERY_PRIVATE_NETWORKS_ENV]: '' }), true)
})
