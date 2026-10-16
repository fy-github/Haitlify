import test from 'node:test'
import assert from 'node:assert/strict'
import { createLoginDiscoveryNetworkPolicy } from './login-discovery-network-policy.mjs'

test('login discovery network policy allows public targets and blocks private addresses', async () => {
  const policy = createLoginDiscoveryNetworkPolicy({
    lookup: async (hostname) => [{
      address: hostname === 'public.example.com' ? '93.184.216.34' : '10.20.30.40'
    }]
  })

  await assert.doesNotReject(() => policy.assertAllowedUrl('https://public.example.com/login'))
  await assert.rejects(() => policy.assertAllowedUrl('https://private.example.com/login'), /network policy/i)
  await assert.rejects(() => policy.assertAllowedUrl('http://169.254.169.254/latest/meta-data'), /network policy/i)
  await assert.rejects(() => policy.assertAllowedUrl('https://user:secret@public.example.com/login'), /without credentials/i)
})

test('login discovery network policy supports explicit private-host allowlists', async () => {
  const policy = createLoginDiscoveryNetworkPolicy({
    allowedHosts: '127.0.0.1,*.internal.example.com',
    lookup: async () => [{ address: '10.20.30.40' }]
  })

  await assert.doesNotReject(() => policy.assertAllowedUrl('http://127.0.0.1:9400/login'))
  await assert.doesNotReject(() => policy.assertAllowedUrl('https://sso.internal.example.com/login'))
  await assert.rejects(() => policy.assertAllowedUrl('https://other.example.com/login'), /network policy/i)
})
