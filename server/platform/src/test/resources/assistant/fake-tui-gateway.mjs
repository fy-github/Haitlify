#!/usr/bin/env node
// 测试用假 TUI Gateway：换行分隔 JSON-RPC，按 id 回包；未知方法精确拒绝（-32601）。
// 行为与 A0 §5.4 实测一致的部分：session.create / prompt.submit / session.interrupt 有回执。
//
// 管理面方法 reload.mcp 的回包形状由命令行开关控制（测试覆盖成功 / 要求确认 / 明确报错 / 不回包 / 进程退出）：
//   --reload=reloaded          默认：{status:"reloaded", loaded_rev:"0123456789ab"}
//   --reload=confirm_required  成功的 RPC，但什么都没重载
//   --reload=error             JSON-RPC error 帧
//   --reload=silent            不回包（测超时）
//   --reload=exit              直接退出进程（测 EOF）
//   --slow-ms=<n>              reload.mcp 的 setTimeout 延迟（测并发：慢请求不阻塞其它调用）
//
// 事件（通知帧）开关：--events=<mode>，模拟真实 gateway 在 prompt.submit 之后推送的事件流
//   none（默认）               不推事件
//   delta+complete            1 条 message.delta + 1 条 message.complete
//   many+complete             300 条 message.delta + 1 条 message.complete（测有界缓冲保终态）
//   complete-then-exit        message.complete 之后进程退出（测「终态取走后，空 drain 必须报错」）
import readline from 'node:readline'

const KNOWN_SESSION = 'stored-abc-123'
const rl = readline.createInterface({ input: process.stdin })
const argv = process.argv.slice(2)
const flag = (name, fallback) => {
  const hit = argv.find((arg) => arg.startsWith(`--${name}=`))
  return hit === undefined ? fallback : hit.slice(name.length + 3)
}
const RELOAD_MODE = flag('reload', 'reloaded')
const SLOW_MS = Number(flag('slow-ms', '0'))
const EVENTS_MODE = flag('events', 'none')

const send = (message) => process.stdout.write(JSON.stringify(message) + '\n')

const emit = (sessionId, seq, type, payload) => send({
  jsonrpc: '2.0',
  method: 'event',
  params: { session_id: sessionId, seq, type, payload },
})

const afterSubmit = (sessionId) => {
  if (EVENTS_MODE === 'none') return
  const delta = (seq) => emit(sessionId, seq, 'message.delta', { text: `chunk-${seq}` })
  if (EVENTS_MODE === 'delta+complete') {
    delta(1)
    emit(sessionId, 2, 'message.complete', { text: '完成', status: 'complete' })
    return
  }
  if (EVENTS_MODE === 'many+complete') {
    for (let seq = 1; seq <= 300; seq++) delta(seq)
    emit(sessionId, 301, 'message.complete', { text: '完成', status: 'complete' })
    return
  }
  if (EVENTS_MODE === 'complete-then-exit') {
    emit(sessionId, 1, 'message.complete', { text: '完成', status: 'complete' })
    setTimeout(() => process.exit(0), 50)
  }
}

rl.on('line', (line) => {
  if (!line.trim()) return
  let request
  try {
    request = JSON.parse(line)
  } catch {
    return
  }
  const { id, method, params } = request
  const reply = (result) => send({ jsonrpc: '2.0', id, result })
  const fail = (code, message) => send({ jsonrpc: '2.0', id, error: { code, message } })

  switch (method) {
    case 'session.create':
      reply({ session_id: KNOWN_SESSION, stored_session_id: KNOWN_SESSION, cwd: params?.cwd ?? '' })
      break
    case 'prompt.submit':
      if (params?.session_id !== KNOWN_SESSION) {
        fail(-32001, 'unknown session')
      } else {
        reply({ turn_id: 'turn-1', accepted: true })
        afterSubmit(params.session_id)
      }
      break
    case 'session.interrupt':
      if (params?.session_id !== KNOWN_SESSION) {
        fail(-32001, 'unknown session')
      } else {
        reply({ status: 'interrupted' })
      }
      break
    case 'reload.mcp': {
      if (RELOAD_MODE === 'silent') break
      if (RELOAD_MODE === 'exit') {
        process.exit(0)
        break
      }
      if (RELOAD_MODE === 'confirm_required') {
        reply({ status: 'confirm_required', message: '⚠️ /reload-mcp invalidates the prompt cache' })
        break
      }
      if (RELOAD_MODE === 'error') {
        fail(-32603, 'reload failed')
        break
      }
      // setTimeout 而不是同步等待：慢 reload 期间其它请求仍要被处理（并发用例靠这一点）。
      setTimeout(() => reply({ status: 'reloaded', loaded_rev: '0123456789ab' }), SLOW_MS)
      break
    }
    default:
      fail(-32601, 'Method not found')
  }
})
