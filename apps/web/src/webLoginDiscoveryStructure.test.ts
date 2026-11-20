import { describe, expect, it } from 'vitest'
import editorSource from './components/automation/EnvironmentEditorPanel.vue?raw'
import dialogSource from './components/automation/WebLoginDiscoveryDialog.vue?raw'
import discoverySource from './ui/useWebLoginDiscovery.ts?raw'
import verificationSource from './ui/useWebLoginVerification.ts?raw'

describe('Web login discovery UI', () => {
  it('places quick login discovery in the Web automatic-login section', () => {
    expect(editorSource).toContain('快速识别登录')
    expect(editorSource).toContain('@click="startWebLoginDiscovery"')
    expect(dialogSource).toContain('web-login-discovery-dialog')
  })

  it('shows screenshot candidates and confirms them into the login form', () => {
    expect(dialogSource).toContain('webLoginDiscoverySnapshot.screenshotUrl')
    for (const candidate of ['usernameCandidateId', 'passwordCandidateId', 'submitCandidateId']) expect(dialogSource).toContain(candidate)
    expect(discoverySource).toContain('confirmAndApplyWebLoginDiscovery')
    for (const locator of ['usernameLocator', 'passwordLocator', 'submitLocator']) {
      expect(discoverySource).toContain(`deps.editingEnvironment.web.login.${locator}`)
    }
  })

  it('migrates hashed CSS-module login success locators to the final stable route', () => {
    expect(verificationSource).toContain('migrateUnstableWebLoginSuccessTarget')
    expect(verificationSource).toContain('extractStableWebLoginRoute')
    expect(verificationSource).toContain("deps.editingEnvironment.web.login.successType = 'url'")
    expect(verificationSource).toContain('deps.editingEnvironment.web.login.successTarget = stableRoute')
  })

  it('does not duplicate the automatic-login success assertion in the probe case', () => {
    const probeCaseSource = verificationSource.match(
      /function buildWebLoginProbeCase[\s\S]*?\n}\n/
    )?.[0]

    expect(probeCaseSource).toBeDefined()
    expect(probeCaseSource).not.toContain('probe-login-visible')
    expect(probeCaseSource).not.toContain('probe-login-url')
    expect(probeCaseSource).not.toContain('确认登录成功元素')
    expect(probeCaseSource).not.toContain('确认登录成功地址')
    expect(probeCaseSource).toContain('steps: []')
  })

  it('resolves relative Web login urls against hash-route application entrances', () => {
    const resolverSource = discoverySource.match(
      /const resolveWebLoginDiscoveryUrl = [\s\S]*?\n}\n[\s\S]*?const formatWebLoginDiscoveryError/
    )?.[0]

    expect(resolverSource).toBeDefined()
    expect(resolverSource).toContain('resolveHashAwareWebRuntimeUrl')
    expect(resolverSource).toContain("target.startsWith('#/')")
    expect(resolverSource).toContain("url.hash.startsWith('#/')")
  })
})
