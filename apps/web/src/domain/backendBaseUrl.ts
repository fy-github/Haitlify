const LOCAL_BACKEND_BASE_URL = 'http://127.0.0.1:18080'

export const resolveBackendBaseUrl = (configuredBaseUrl?: string): string => {
  if (configuredBaseUrl) {
    return configuredBaseUrl.replace(/\/$/, '')
  }

  if (typeof window !== 'undefined') {
    return ''
  }

  return LOCAL_BACKEND_BASE_URL
}

export const joinBackendPath = (path: string, configuredBaseUrl?: string): string => {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  return `${resolveBackendBaseUrl(configuredBaseUrl)}${normalizedPath}`
}
