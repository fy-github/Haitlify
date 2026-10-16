import { describe, expect, it } from 'vitest'
import appSource from './App.vue?raw'

describe('Web login discovery UI', () => {
  it('places quick login discovery in the Web automatic-login section', () => {
    expect(appSource).toContain('快速识别登录')
    expect(appSource).toContain('@click="startWebLoginDiscovery"')
    expect(appSource).toContain('web-login-discovery-dialog')
  })

  it('shows screenshot candidates and confirms them into the login form', () => {
    expect(appSource).toContain('webLoginDiscoverySnapshot.screenshotUrl')
    expect(appSource).toContain('usernameCandidateId')
    expect(appSource).toContain('passwordCandidateId')
    expect(appSource).toContain('submitCandidateId')
    expect(appSource).toContain('confirmAndApplyWebLoginDiscovery')
    expect(appSource).toContain('editingEnvironment.web.login.usernameLocator')
    expect(appSource).toContain('editingEnvironment.web.login.passwordLocator')
    expect(appSource).toContain('editingEnvironment.web.login.submitLocator')
  })

  it('migrates hashed CSS-module login success locators to the final stable route', () => {
    expect(appSource).toContain('migrateUnstableWebLoginSuccessTarget')
    expect(appSource).toContain('extractStableWebLoginRoute')
    expect(appSource).toContain('editingEnvironment.web.login.successType = \'url\'')
    expect(appSource).toContain('editingEnvironment.web.login.successTarget = stableRoute')
  })

  it('does not duplicate the automatic-login success assertion in the probe case', () => {
    const probeCaseSource = appSource.match(
      /function buildWebLoginProbeCase[\s\S]*?\n}\n\nconst applyConfiguredCaseTypeName/
    )?.[0]

    expect(probeCaseSource).toBeDefined()
    expect(probeCaseSource).not.toContain('probe-login-visible')
    expect(probeCaseSource).not.toContain('probe-login-url')
    expect(probeCaseSource).not.toContain('确认登录成功元素')
    expect(probeCaseSource).not.toContain('确认登录成功地址')
    expect(probeCaseSource).toContain('steps: []')
  })

  it('resolves relative Web login urls against hash-route application entrances', () => {
    const resolverSource = appSource.match(
      /const resolveWebLoginDiscoveryUrl = [\s\S]*?\n}\n\nconst formatWebLoginDiscoveryError/
    )?.[0]

    expect(resolverSource).toBeDefined()
    expect(resolverSource).toContain('resolveHashAwareWebRuntimeUrl')
    expect(resolverSource).toContain("target.startsWith('#/')")
    expect(resolverSource).toContain("url.hash.startsWith('#/')")
  })
})
