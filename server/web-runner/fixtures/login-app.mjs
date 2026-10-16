import http from 'node:http'

export const createLoginFixtureServer = () => {
  let loginCount = 0
  let logoutCount = 0
  return http.createServer((request, response) => {
    const url = new URL(request.url || '/', `http://${request.headers.host || '127.0.0.1'}`)
    response.setHeader('Cache-Control', 'no-store')

    if (url.pathname === '/session/login' && request.method === 'POST') {
      loginCount += 1
      response.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Set-Cookie': 'fixture-session=authenticated; Path=/; HttpOnly; SameSite=Lax'
      })
      response.end(JSON.stringify({ authenticated: true }))
      return
    }
    if (url.pathname === '/session/logout' && request.method === 'POST') {
      logoutCount += 1
      response.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Set-Cookie': 'fixture-session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax'
      })
      response.end(JSON.stringify({ authenticated: false }))
      return
    }
    if (url.pathname === '/metrics') {
      response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
      response.end(JSON.stringify({ loginCount, logoutCount }))
      return
    }
    if (url.pathname === '/login' || url.pathname === '/') {
      sendHtml(response, loginPage)
      return
    }
    if (url.pathname === '/users') {
      if (!String(request.headers.cookie || '').includes('fixture-session=authenticated')) {
        response.writeHead(302, { Location: '/login' })
        response.end()
        return
      }
      sendHtml(response, usersPage)
      return
    }

    response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
    response.end('Not found')
  })
}

const sendHtml = (response, html) => {
  response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
  response.end(html)
}

const loginPage = `<!doctype html>
<html lang="zh-CN">
  <head><meta charset="utf-8"><title>Runner 登录测试</title></head>
  <body>
    <main>
      <h1>登录</h1>
      <label>用户名 <input data-testid="username" autocomplete="username"></label>
      <label>密码 <input data-testid="password" type="password" autocomplete="current-password"></label>
      <button data-testid="login-submit" type="button">登录</button>
      <p data-testid="login-error" hidden>账号或密码错误</p>
    </main>
    <script>
      document.querySelector('[data-testid="login-submit"]').addEventListener('click', async () => {
        const username = document.querySelector('[data-testid="username"]').value
        const password = document.querySelector('[data-testid="password"]').value
        if (username === 'admin' && password === 'secret') {
          await fetch('/session/login', { method: 'POST' })
          window.location.href = '/users'
          return
        }
        document.querySelector('[data-testid="login-error"]').hidden = false
      })
    </script>
  </body>
</html>`

const usersPage = `<!doctype html>
<html lang="zh-CN">
  <head><meta charset="utf-8"><title>用户管理</title></head>
  <body>
    <main>
      <h1>用户管理</h1>
      <section data-testid="delayed-dashboard" hidden>
        <p>用户列表已加载</p>
        <button data-testid="slow-action" type="button">执行用户操作</button>
        <p data-testid="done" hidden>操作完成</p>
      </section>
      <p data-testid="second-case">共享登录态可用</p>
      <button data-testid="logout" type="button">退出登录</button>
    </main>
    <script>
      window.setTimeout(() => {
        document.querySelector('[data-testid="delayed-dashboard"]').hidden = false
      }, 600)
      document.querySelector('[data-testid="slow-action"]').addEventListener('click', () => {
        window.setTimeout(() => {
          document.querySelector('[data-testid="done"]').hidden = false
        }, 400)
      })
      document.querySelector('[data-testid="logout"]').addEventListener('click', async () => {
        await fetch('/session/logout', { method: 'POST' })
        window.location.href = '/login'
      })
    </script>
  </body>
</html>`
