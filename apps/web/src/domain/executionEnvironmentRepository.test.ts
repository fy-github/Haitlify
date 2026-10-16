import { describe, expect, it } from 'vitest'
import {
  buildWebExecutionRuntime,
  buildExecutionEnvironment,
  defaultExecutionEnvironmentLibrary,
  defaultWebExecutionRuntimeConfig,
  defaultWebExecutionEnvironmentLibrary,
  loadExecutionEnvironmentLibrary,
  normalizeWebExecutionRuntimeConfig,
  parseCurlEnvironmentImport,
  parseCurlIntoEnvironmentEntries,
  saveExecutionEnvironmentLibrary,
  validateExecutionEnvironment,
  validateWebExecutionEnvironment,
  WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY,
  type SavedExecutionEnvironment,
  type WebExecutionRuntimeConfig
} from './executionEnvironmentRepository'

const environment: SavedExecutionEnvironment = {
  id: 'env-test',
  name: '测试环境',
  baseUrl: 'https://api.example.test',
  auth: {
    type: 'bearer',
    token: 'token-1'
  },
  entries: [
    { id: 'header-accept', enabled: true, type: 'header', name: 'Accept', value: 'application/json', description: '' },
    { id: 'header-ajax', enabled: true, type: 'header', name: 'isAjax', value: '1', description: '' },
    { id: 'cookie-token', enabled: true, type: 'cookie', name: 'haitlify-token', value: 'cookie-token', description: '' },
    { id: 'cookie-disabled', enabled: false, type: 'cookie', name: 'disabled', value: 'ignored', description: '' },
    { id: 'variable-tenant', enabled: true, type: 'variable', name: 'tenantId', value: '100509316', description: '' }
  ],
  createdAt: '2026-06-11T07:00:00.000Z',
  updatedAt: '2026-06-11T07:00:00.000Z'
}

describe('executionEnvironmentRepository', () => {
  const createStorage = (): Storage => {
    const values = new Map<string, string>()
    return {
      get length() {
        return values.size
      },
      clear: () => values.clear(),
      getItem: (key: string) => values.get(key) ?? null,
      key: (index: number) => Array.from(values.keys())[index] ?? null,
      removeItem: (key: string) => {
        values.delete(key)
      },
      setItem: (key: string, value: string) => {
        values.set(key, value)
      }
    }
  }

  it('creates a default environment library with one editable environment', () => {
    const library = defaultExecutionEnvironmentLibrary()

    expect(library.activeEnvironmentId).toBe('env-default')
    expect(library.environments).toHaveLength(1)
    expect(library.environments[0]).toMatchObject({
      id: 'env-default',
      name: '默认环境',
      baseUrl: '',
      auth: {
        type: 'none'
      },
      entries: []
    })
  })

  it('creates a default Web runtime environment library with browser execution defaults', () => {
    const library = defaultWebExecutionEnvironmentLibrary()

    expect(library.activeEnvironmentId).toBe('web-env-default')
    expect(library.environments[0]).toMatchObject({
      id: 'web-env-default',
      name: 'Web 默认环境',
      baseUrl: '',
      auth: { type: 'none' },
      web: {
        browser: 'chromium',
        viewport: '1440x900',
        headless: true,
        defaultTimeoutMs: 30000,
        navigationTimeoutMs: 45000,
        waitUntil: 'networkidle',
        screenshotStrategy: 'on_failure',
        videoRecording: false,
        retryCount: 0,
        login: {
          enabled: false,
          loginUrl: '',
          usernameLocator: '',
          passwordLocator: '',
          submitLocator: '',
          username: '',
          password: '',
          successType: 'url',
          successTarget: ''
        },
        logout: {
          enabled: false,
          steps: []
        }
      }
    })
  })

  it('migrates legacy allowedRequestOriginsText and preserves origins on round-trip', () => {
    const storage = createStorage()
    const legacy = defaultWebExecutionEnvironmentLibrary()
    legacy.environments[0].web = {
      ...defaultWebExecutionRuntimeConfig(),
      allowedRequestOriginsText: ' https://auth.example.com\nhttps://api.example.com, https://cdn.example.com  '
    } as WebExecutionRuntimeConfig & { allowedRequestOriginsText: string }
    storage.setItem(WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY, JSON.stringify(legacy))

    const loaded = loadExecutionEnvironmentLibrary(storage, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY)
    expect(loaded.environments[0].web?.allowedRequestOrigins).toEqual([
      'https://auth.example.com',
      'https://api.example.com',
      'https://cdn.example.com'
    ])

    saveExecutionEnvironmentLibrary(storage, loaded, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY)
    const roundTrip = loadExecutionEnvironmentLibrary(storage, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY)
    expect(roundTrip.environments[0].web?.allowedRequestOrigins).toEqual(loaded.environments[0].web?.allowedRequestOrigins)
  })

  it('keeps path-bearing allowlist entries so exact request URLs can be allowed (POST logins)', () => {
    // 用户实测回归：白名单条目曾被折叠为 Origin，带路径的「精确放行 URL」通道在 UI 侧根本不可达，
    // 导致登录 POST 在取证阶段必被网络策略拦截。
    const storage = createStorage()
    const lib = defaultWebExecutionEnvironmentLibrary()
    lib.environments[0].web = {
      ...defaultWebExecutionRuntimeConfig(),
      allowedRequestOrigins: [
        'https://api.example.com',
        'https://api.example.com/v4/uc/n/auth/login',
        'https://api.example.com/v4/uc/n/auth/login/',
        'https://user:pass@api.example.com/x',
        'not-a-url'
      ]
    }
    storage.setItem(WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY, JSON.stringify(lib))

    const loaded = loadExecutionEnvironmentLibrary(storage, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY)
    expect(loaded.environments[0].web?.allowedRequestOrigins).toEqual([
      'https://api.example.com',
      'https://api.example.com/v4/uc/n/auth/login'
    ])

    saveExecutionEnvironmentLibrary(storage, loaded, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY)
    expect(loadExecutionEnvironmentLibrary(storage, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY).environments[0].web?.allowedRequestOrigins)
      .toEqual(['https://api.example.com', 'https://api.example.com/v4/uc/n/auth/login'])
  })

  it('defaults the web network policy to blocklist and normalizes unknown values', () => {
    // 用户要求：默认不拦截任何请求（黑名单模式），仅当用户显式切换为白名单时做严格放行。
    expect(normalizeWebExecutionRuntimeConfig(undefined).networkPolicyMode).toBe('blocklist')
    expect(normalizeWebExecutionRuntimeConfig({}).networkPolicyMode).toBe('blocklist')
    expect(normalizeWebExecutionRuntimeConfig({ networkPolicyMode: 'allowlist' as never }).networkPolicyMode).toBe('allowlist')
    expect(normalizeWebExecutionRuntimeConfig({ networkPolicyMode: 'wild' as never }).networkPolicyMode).toBe('blocklist')

    const env = defaultWebExecutionEnvironmentLibrary().environments[0]
    env.web = { ...defaultWebExecutionRuntimeConfig(), networkPolicyMode: 'allowlist' }
    expect(buildWebExecutionRuntime(env).networkPolicyMode).toBe('allowlist')
    expect(buildWebExecutionRuntime({ ...env, web: defaultWebExecutionRuntimeConfig() }).networkPolicyMode).toBe('blocklist')
  })

  it('carries the verified login session id through normalize and runtime build', () => {
    const env = defaultWebExecutionEnvironmentLibrary().environments[0]
    env.web = { ...defaultWebExecutionRuntimeConfig(), sessionId: 'session-abc-123' }
    expect(normalizeWebExecutionRuntimeConfig(env.web).sessionId).toBe('session-abc-123')
    expect(buildWebExecutionRuntime(env).sessionId).toBe('session-abc-123')
    const withoutSession = buildWebExecutionRuntime({ ...env, web: defaultWebExecutionRuntimeConfig() })
    expect('sessionId' in withoutSession).toBe(false)
  })

  it('normalizes explicit default ports to canonical origins during round-trip', () => {
    const storage = createStorage()
    const legacy = defaultWebExecutionEnvironmentLibrary()
    legacy.environments[0].web = {
      ...defaultWebExecutionRuntimeConfig(),
      allowedRequestOrigins: ['https://auth.example.com:443/', 'http://api.example.com:80']
    }
    storage.setItem(WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY, JSON.stringify(legacy))

    const loaded = loadExecutionEnvironmentLibrary(storage, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY)
    expect(loaded.environments[0].web?.allowedRequestOrigins).toEqual([
      'https://auth.example.com',
      'http://api.example.com'
    ])
    saveExecutionEnvironmentLibrary(storage, loaded, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY)
    expect(loadExecutionEnvironmentLibrary(storage, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY).environments[0].web?.allowedRequestOrigins)
      .toEqual(['https://auth.example.com', 'http://api.example.com'])
  })

  it('preserves Chrome and Edge Web driver choices while normalizing unknown values', () => {
    expect(normalizeWebExecutionRuntimeConfig({
      ...defaultWebExecutionRuntimeConfig(),
      browser: 'follow-case'
    }).browser).toBe('follow-case')
    expect(normalizeWebExecutionRuntimeConfig({
      ...defaultWebExecutionRuntimeConfig(),
      browser: 'chrome'
    }).browser).toBe('chrome')
    expect(normalizeWebExecutionRuntimeConfig({
      ...defaultWebExecutionRuntimeConfig(),
      browser: 'msedge'
    }).browser).toBe('msedge')
    expect(normalizeWebExecutionRuntimeConfig({
      ...defaultWebExecutionRuntimeConfig(),
      browser: 'safari' as never
    }).browser).toBe('chromium')
  })

  it('validates required environment fields before execution', () => {
    expect(validateExecutionEnvironment(undefined)).toEqual({
      valid: false,
      message: '请先选择运行环境'
    })
    expect(validateExecutionEnvironment({ ...environment, baseUrl: '' })).toEqual({
      valid: false,
      message: '运行环境「测试环境」的 Base URL 不能为空'
    })
    expect(validateExecutionEnvironment({ ...environment, auth: { type: 'bearer', token: '' } })).toEqual({
      valid: false,
      message: '运行环境「测试环境」的 Bearer Token 不能为空'
    })
    expect(validateExecutionEnvironment({
      ...environment,
      auth: { type: 'none' },
      entries: [{ id: 'bad', enabled: true, type: 'header', name: '', value: 'x', description: '' }]
    })).toEqual({
      valid: false,
      message: '运行环境「测试环境」存在启用但参数名为空的配置'
    })
  })

  it('validates Web runtime environment fields before execution', () => {
    const library = defaultWebExecutionEnvironmentLibrary()
    const webEnvironment: SavedExecutionEnvironment & { web: WebExecutionRuntimeConfig } = {
      ...library.environments[0],
      name: 'Web 测试环境',
      baseUrl: 'https://haitlify.example.com',
      web: library.environments[0].web as WebExecutionRuntimeConfig
    }

    expect(validateWebExecutionEnvironment(undefined)).toEqual({
      valid: false,
      message: '请先选择 Web 运行环境'
    })
    expect(validateWebExecutionEnvironment({ ...webEnvironment, baseUrl: '' })).toEqual({
      valid: false,
      message: 'Web 运行环境「Web 测试环境」的应用 Base URL 不能为空'
    })
    expect(validateWebExecutionEnvironment({ ...webEnvironment, web: { ...webEnvironment.web, defaultTimeoutMs: 0 } })).toEqual({
      valid: false,
      message: 'Web 运行环境「Web 测试环境」的默认等待超时必须大于 0'
    })
    expect(validateWebExecutionEnvironment({ ...webEnvironment, web: { ...webEnvironment.web, retryCount: -1 } })).toEqual({
      valid: false,
      message: 'Web 运行环境「Web 测试环境」的失败重试次数不能小于 0'
    })
    expect(validateWebExecutionEnvironment({
      ...webEnvironment,
      web: {
        ...webEnvironment.web,
        login: {
          ...defaultWebExecutionRuntimeConfig().login,
          enabled: true,
          usernameLocator: 'css=#username',
          passwordLocator: 'css=#password',
          submitLocator: 'role=button[name="登录"]',
          username: 'admin',
          password: 'secret',
          successTarget: '/user-center'
        }
      }
    })).toEqual({
      valid: false,
      message: 'Web 运行环境「Web 测试环境」已开启自动登录，请填写登录页面地址'
    })
    expect(validateWebExecutionEnvironment({
      ...webEnvironment,
      web: {
        ...webEnvironment.web,
        login: {
          ...defaultWebExecutionRuntimeConfig().login,
          enabled: true,
          loginUrl: '/#/login',
          passwordLocator: 'css=#password',
          submitLocator: 'role=button[name="登录"]',
          username: 'admin',
          password: 'secret',
          successTarget: '/user-center'
        }
      }
    })).toEqual({
      valid: false,
      message: 'Web 运行环境「Web 测试环境」已开启自动登录，请填写账号输入框定位器'
    })
    expect(validateWebExecutionEnvironment({
      ...webEnvironment,
      web: {
        ...webEnvironment.web,
        login: {
          ...defaultWebExecutionRuntimeConfig().login,
          enabled: true,
          loginUrl: '/#/login',
          usernameLocator: 'css=#username',
          passwordLocator: 'css=#password',
          submitLocator: 'role=button[name="登录"]',
          password: 'secret',
          successTarget: '/user-center'
        }
      }
    })).toEqual({
      valid: false,
      message: 'Web 运行环境「Web 测试环境」已开启自动登录，请填写登录账号'
    })
    expect(validateWebExecutionEnvironment({
      ...webEnvironment,
      web: {
        ...webEnvironment.web,
        login: {
          ...defaultWebExecutionRuntimeConfig().login,
          enabled: true,
          loginUrl: '/#/login',
          usernameLocator: 'css=#username',
          passwordLocator: 'css=#password',
          submitLocator: 'role=button[name="登录"]',
          username: 'admin',
          password: 'secret',
          successType: 'visible'
        }
      }
    })).toEqual({
      valid: false,
      message: 'Web 运行环境「Web 测试环境」已开启自动登录，请填写登录成功判定目标'
    })
    expect(validateWebExecutionEnvironment({
      ...webEnvironment,
      auth: { type: 'bearer', token: '' },
      entries: [{ id: 'legacy', enabled: true, type: 'header', name: '', value: 'x', description: '' }]
    })).toEqual({ valid: true })
  })

  it('validates enabled Web logout teardown steps', () => {
    const library = defaultWebExecutionEnvironmentLibrary()
    const webEnvironment: SavedExecutionEnvironment & { web: WebExecutionRuntimeConfig } = {
      ...library.environments[0],
      name: 'Web 测试环境',
      baseUrl: 'https://haitlify.example.com',
      web: library.environments[0].web as WebExecutionRuntimeConfig
    }

    expect(validateWebExecutionEnvironment({
      ...webEnvironment,
      web: { ...webEnvironment.web, logout: { enabled: true, steps: [] } }
    })).toEqual({
      valid: false,
      message: 'Web 运行环境「Web 测试环境」已开启退出登录，请至少添加一个后置步骤'
    })
    expect(validateWebExecutionEnvironment({
      ...webEnvironment,
      web: {
        ...webEnvironment.web,
        logout: {
          enabled: true,
          steps: [
            { id: 'logout', name: '退出登录', action: 'click', targetType: 'locator', target: '   ' }
          ]
        }
      }
    })).toEqual({
      valid: false,
      message: 'Web 运行环境「Web 测试环境」的退出登录步骤「退出登录」缺少目标'
    })
  })

  it('builds execution parameters from auth, enabled headers, cookies, and variables', () => {
    expect(buildExecutionEnvironment(environment)).toEqual({
      baseUrl: 'https://api.example.test',
      authToken: 'token-1',
      extraHeaders: {
        Accept: 'application/json',
        isAjax: '1',
        Cookie: 'haitlify-token=cookie-token'
      },
      variables: {
        tenantId: '100509316'
      }
    })
  })

  it('builds Web runtime with browser login-state headers cookies and variables', () => {
    const webEnvironment = {
      ...defaultWebExecutionEnvironmentLibrary().environments[0],
      baseUrl: ' https://haitlify.example.com ',
      auth: {
        type: 'custom' as const,
        headerName: 'x-auth-token',
        headerValue: 'token-1'
      },
      entries: [
        { id: 'tenant', enabled: true, type: 'variable' as const, name: 'tenantId', value: '1001', description: '' },
        { id: 'ajax', enabled: true, type: 'header' as const, name: 'isAjax', value: '1', description: '' },
        { id: 'cookie-token', enabled: true, type: 'cookie' as const, name: 'haitlify-token', value: 'cookie-token', description: '' },
        { id: 'empty', enabled: false, type: 'variable' as const, name: 'ignored', value: 'x', description: '' }
      ],
      web: {
        browser: 'firefox' as const,
        viewport: '1280x720',
        headless: false,
        defaultTimeoutMs: 25000,
        navigationTimeoutMs: 40000,
        waitUntil: 'domcontentloaded' as const,
        screenshotStrategy: 'always' as const,
        videoRecording: true,
        retryCount: 2,
        cookieOrigins: ['https://api.haitlify.example.com'],
        login: {
          enabled: true,
          loginUrl: '/#/login',
          usernameLocator: 'css=input[name="username"]',
          passwordLocator: 'css=input[type="password"]',
          submitLocator: 'role=button[name="登录"]',
          username: 'admin',
          password: 'secret',
          successType: 'url' as const,
          successTarget: '/user-center'
        },
        logout: {
          enabled: true,
          steps: [
            {
              id: ' logout ',
              name: ' 退出登录 ',
              action: 'click' as const,
              targetType: 'locator' as const,
              target: ' testid=logout '
            }
          ]
        }
      }
    }

    expect(buildWebExecutionRuntime(webEnvironment)).toEqual({
      environmentId: 'web-env-default',
      sessionKey: 'web-env-default:firefox',
      networkPolicyMode: 'blocklist',
      baseUrl: 'https://haitlify.example.com',
      browser: 'firefox',
      viewport: '1280x720',
      headless: false,
      defaultTimeoutMs: 25000,
      navigationTimeoutMs: 40000,
      waitUntil: 'domcontentloaded',
      screenshotStrategy: 'always',
      videoRecording: true,
      retryCount: 2,
      cookieOrigins: ['https://api.haitlify.example.com'],
      login: {
        enabled: true,
        loginUrl: '/#/login',
        usernameLocator: 'css=input[name="username"]',
        passwordLocator: 'css=input[type="password"]',
        submitLocator: 'role=button[name="登录"]',
        username: 'admin',
        password: 'secret',
        successType: 'url',
        successTarget: '/user-center'
      },
      logout: {
        enabled: true,
        steps: [
          {
            id: 'logout',
            name: '退出登录',
            action: 'click',
            targetType: 'locator',
            target: 'testid=logout'
          }
        ]
      },
      authToken: '',
      extraHeaders: {
        'x-auth-token': 'token-1',
        isAjax: '1',
        Cookie: 'haitlify-token=cookie-token'
      },
      variables: {
        tenantId: '1001'
      }
    })
  })

  it('normalizes legacy Web environment data without login config', () => {
    const storage = createStorage()
    storage.setItem(WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY, JSON.stringify({
      activeEnvironmentId: 'web-env-legacy',
      environments: [
        {
          id: 'web-env-legacy',
          name: 'Web 旧环境',
          baseUrl: 'https://haitlify.example.com',
          auth: { type: 'none' },
          entries: [],
          web: {
            browser: 'chromium',
            viewport: '1440x900',
            headless: true,
            defaultTimeoutMs: 30000,
            navigationTimeoutMs: 45000,
            waitUntil: 'networkidle',
            screenshotStrategy: 'on_failure',
            videoRecording: false,
            retryCount: 0
          },
          createdAt: '2026-07-13T00:00:00.000Z',
          updatedAt: '2026-07-13T00:00:00.000Z'
        }
      ]
    }))

    const library = loadExecutionEnvironmentLibrary(storage, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY)

    expect(library.environments[0].web).toMatchObject({
      login: {
        enabled: false,
        loginUrl: '',
        usernameLocator: '',
        passwordLocator: '',
        submitLocator: '',
        username: '',
        password: '',
        successType: 'url',
        successTarget: ''
      },
      logout: { enabled: false, steps: [] }
    })
  })

  it('keeps Web login and logout lifecycle config across save and load', () => {
    const storage = createStorage()
    const library = defaultWebExecutionEnvironmentLibrary()
    library.environments[0].web = {
      ...defaultWebExecutionRuntimeConfig(),
      login: {
        enabled: true,
        loginUrl: '/#/login',
        usernameLocator: 'css=input[name="username"]',
        passwordLocator: 'css=input[type="password"]',
        submitLocator: 'role=button[name="登录"]',
        username: 'admin',
        password: 'secret',
        successType: 'visible',
        successTarget: 'text=用户中心'
      },
      logout: {
        enabled: true,
        steps: [
          {
            id: 'logout',
            name: '退出登录',
            action: 'click',
            targetType: 'locator',
            target: 'testid=logout'
          }
        ]
      }
    }

    saveExecutionEnvironmentLibrary(storage, library, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY)
    const reloaded = loadExecutionEnvironmentLibrary(storage, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY)

    expect(reloaded.environments[0].web?.login).toEqual({
      enabled: true,
      loginUrl: '/#/login',
      usernameLocator: 'css=input[name="username"]',
      passwordLocator: 'css=input[type="password"]',
      submitLocator: 'role=button[name="登录"]',
      username: 'admin',
      password: 'secret',
      successType: 'visible',
      successTarget: 'text=用户中心'
    })
    expect(reloaded.environments[0].web?.logout).toEqual({
      enabled: true,
      steps: [
        {
          id: 'logout',
          name: '退出登录',
          action: 'click',
          targetType: 'locator',
          target: 'testid=logout'
        }
      ]
    })
  })

  it('parses copied curl header and cookie lines into environment entries', () => {
    const entries = parseCurlIntoEnvironmentEntries([
      "curl 'https://api.example.test/v4/pm/project-tree' \\",
      "  -H 'Accept: application/json, text/plain, */*' \\",
      "  -H 'Authorization: Bearer token-from-curl' \\",
      "  -H 'Origin: https://app.example.test' \\",
      "  -H 'isAjax: 1' \\",
      "  -b 'haitlify-token=cookie-token; tenant_switch_sync=tenant-1'"
    ].join('\n'))

    expect(entries.map(({ type, name, value }) => ({ type, name, value }))).toEqual([
      { type: 'header', name: 'Accept', value: 'application/json, text/plain, */*' },
      { type: 'header', name: 'Authorization', value: 'Bearer token-from-curl' },
      { type: 'header', name: 'Origin', value: 'https://app.example.test' },
      { type: 'header', name: 'isAjax', value: '1' },
      { type: 'cookie', name: 'haitlify-token', value: 'cookie-token' },
      { type: 'cookie', name: 'tenant_switch_sync', value: 'tenant-1' }
    ])
  })

  it('detects base url and auth mode when importing a curl request', () => {
    const parsed = parseCurlEnvironmentImport([
      "curl 'https://api.example.test/v4/pm/project-tree' \\",
      "  -H 'Authorization: Bearer token-from-curl' \\",
      "  -H 'Origin: https://app.example.test' \\",
      "  -b 'haitlify-token=cookie-token'"
    ].join('\n'))

    expect(parsed.baseUrl).toBe('https://api.example.test')
    expect(parsed.auth).toEqual({
      type: 'bearer',
      token: 'token-from-curl'
    })
    expect(parsed.entries.map(({ type, name, value }) => ({ type, name, value }))).toEqual([
      { type: 'header', name: 'Origin', value: 'https://app.example.test' },
      { type: 'cookie', name: 'haitlify-token', value: 'cookie-token' }
    ])
  })

  it('imports Web login state from curl without using the API request host as the application url', () => {
    const parsed = parseCurlEnvironmentImport([
      "curl 'https://api.example.test/v4/pm/project-tree' \\",
      "  -H 'Authorization: Bearer token-from-curl' \\",
      "  -H 'Origin: https://app.example.test' \\",
      "  -H 'Referer: https://app.example.test/project/list' \\",
      "  -b 'haitlify-token=cookie-token'"
    ].join('\n'), { target: 'web' })

    expect(parsed.baseUrl).toBe('https://app.example.test')
    expect(parsed.requestOrigin).toBe('https://api.example.test')
    expect(parsed.auth).toEqual({
      type: 'bearer',
      token: 'token-from-curl'
    })
    expect(parsed.entries.map(({ type, name, value }) => ({ type, name, value }))).toEqual([
      { type: 'header', name: 'Origin', value: 'https://app.example.test' },
      { type: 'header', name: 'Referer', value: 'https://app.example.test/project/list' },
      { type: 'cookie', name: 'haitlify-token', value: 'cookie-token' }
    ])
  })

  it('leaves Web application url empty when curl has no browser origin evidence', () => {
    const parsed = parseCurlEnvironmentImport([
      "curl 'https://api.example.test/v4/pm/project-tree' \\",
      "  -H 'Authorization: Bearer token-from-curl'"
    ].join('\n'), { target: 'web' })

    expect(parsed.baseUrl).toBeUndefined()
    expect(parsed.requestOrigin).toBe('https://api.example.test')
  })
})
