import vue from '@vitejs/plugin-vue'
import type { IncomingMessage } from 'node:http'
import { defineConfig, type Plugin } from 'vite'

interface ProxyExecutionPayload {
  method?: string
  url?: string
  headers?: Record<string, string>
  body?: unknown
}

export default defineConfig({
  // Frontend verification and local runs must never auto-load repository .env files.
  envDir: false,
  plugins: [vue(), apiExecutionProxy()],
  server: {
    // 非宿主常用端口,避免被其他项目占用
    port: 51730,
    proxy: {
      // 默认直连隔离栈 platform 宿主端口(deploy/isolated/compose.yaml 的 38082);其他后端用 VITE_API_PROXY_TARGET 覆盖
      '^/api/': {
        target: process.env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:38082',
        changeOrigin: true
      },
      '/artifacts': {
        target: process.env.VITE_RUNNER_PROXY_TARGET || 'http://127.0.0.1:19324',
        changeOrigin: true
      },
      '/fixtures': {
        target: process.env.VITE_RUNNER_PROXY_TARGET || 'http://127.0.0.1:19324',
        changeOrigin: true
      }
    }
  },
  test: {
    environment: 'node',
    globals: true,
    exclude: ['e2e/**', '**/node_modules/**', '**/dist/**']
  },
  resolve: {
    alias: {
      '@': new URL('./src', import.meta.url).pathname
    }
  }
})

function apiExecutionProxy(): Plugin {
  return {
    name: 'ai-test-platform-api-execution-proxy',
    configureServer(server) {
      server.middlewares.use('/__api-test-platform/execute', async (request, response) => {
        if (request.method !== 'POST') {
          response.statusCode = 405
          response.end(JSON.stringify({ error: '只支持 POST 执行请求' }))
          return
        }

        try {
          const payload = JSON.parse(await readRequestBody(request)) as ProxyExecutionPayload
          if (!payload.url || !/^https?:\/\//i.test(payload.url)) {
            response.statusCode = 400
            response.end(JSON.stringify({ error: '执行 URL 必须是 http 或 https 完整地址' }))
            return
          }

          const upstream = await fetch(payload.url, {
            method: payload.method || 'GET',
            headers: payload.headers,
            body: payload.body === undefined ? undefined : JSON.stringify(payload.body)
          })
          const contentType = upstream.headers.get('content-type') || ''
          const body = contentType.includes('application/json')
            ? await upstream.json().catch(() => undefined)
            : await upstream.text().catch(() => undefined)

          response.setHeader('Content-Type', 'application/json;charset=utf-8')
          response.end(JSON.stringify({
            status: upstream.status,
            headers: Object.fromEntries(upstream.headers.entries()),
            body
          }))
        } catch (error) {
          response.statusCode = 502
          response.setHeader('Content-Type', 'application/json;charset=utf-8')
          response.end(JSON.stringify({
            error: error instanceof Error ? error.message : '代理执行失败'
          }))
        }
      })
    }
  }
}

function readRequestBody(request: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    let body = ''
    request.setEncoding('utf8')
    request.on('data', (chunk) => {
      body += chunk
    })
    request.on('end', () => resolve(body))
    request.on('error', reject)
  })
}
