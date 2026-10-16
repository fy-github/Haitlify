// 环境开关解析：本地网络访问与登录发现私网放行默认开启。
// 仅显式设置为字符串 "false" 时关闭，便于在受控环境收紧策略；
// 未设置、空串或其他取值一律按开启处理。
export const LOCAL_NETWORK_ACCESS_ENV = 'APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS'
export const LOGIN_DISCOVERY_PRIVATE_NETWORKS_ENV = 'APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOW_PRIVATE_NETWORKS'

export function resolveAllowLocalNetworkAccess(env = process.env) {
  return env[LOCAL_NETWORK_ACCESS_ENV] !== 'false'
}

export function resolveAllowPrivateNetworks(env = process.env) {
  return env[LOGIN_DISCOVERY_PRIVATE_NETWORKS_ENV] !== 'false'
}
