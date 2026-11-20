import { browserSafeHeaders, buildProxyExecutionPayload, type ExecutableRequest } from '../domain/apiExecution'

interface Dependencies {
}

export function createBrowserExecution(deps: Dependencies) {

function downloadTextFile(filename: string, content: string, type: string) {
  const blob = new Blob([content], { type })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}

async function executeBrowserRequest(request: ExecutableRequest, isCurrent: () => boolean = () => true) {
  if (!isCurrent()) throw new Error('本次 API 执行上下文已失效')
  const proxiedResponse = await executeViaLocalProxy(request)
  if (!isCurrent()) throw new Error('本次 API 执行上下文已失效')
  if (proxiedResponse) return proxiedResponse

  let response: Response
  try {
    response = await fetch(request.url, {
      method: request.method,
      headers: browserSafeHeaders(request.headers),
      body: request.body === undefined ? undefined : JSON.stringify(request.body)
    })
  } catch (error) {
    const message = error instanceof Error ? error.message : '网络请求失败'
    throw new Error(`${message}。请检查 Base URL、请求头/Cookie、登录态，以及目标接口是否允许当前页面跨域访问。`)
  }
  const contentType = response.headers.get('content-type') || ''
  const body = contentType.includes('application/json')
    ? await response.json().catch(() => undefined)
    : await response.text().catch(() => undefined)

  return {
    status: response.status,
    body
  }
}

async function executeViaLocalProxy(request: ExecutableRequest) {
  try {
    const response = await fetch('/__api-test-platform/execute', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(buildProxyExecutionPayload(request))
    })

    if (response.status === 404) return undefined

    const payload = await response.json().catch(() => undefined)
    if (!response.ok) {
      throw new Error(payload?.error || `本地代理执行失败：HTTP ${response.status}`)
    }
    return {
      status: payload.status,
      body: payload.body
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : '本地代理执行失败'
    throw new Error(`${message}。如果目标接口依赖登录 Cookie，请确认请求头/Cookie 已粘贴，并重启本地开发服务以加载代理。`)
  }
}

return {
  downloadTextFile,
  executeBrowserRequest,
  executeViaLocalProxy,
}
}
