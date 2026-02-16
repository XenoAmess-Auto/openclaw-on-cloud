// 后端地址配置工具 - 不依赖 Pinia，登录前也能使用
const STORAGE_KEY = 'ooc_backend_config'

export interface BackendConfig {
  /** 后端地址，如 http://localhost:8081 或 https://api.example.com */
  baseUrl: string | null
}

const defaultConfig: BackendConfig = {
  baseUrl: null
}

export function getDefaultBaseUrl(): string {
  const hostname = window.location.hostname
  const protocol = window.location.protocol === 'https:' ? 'https:' : 'http:'
  return `${protocol}//${hostname}:8081`
}

export function loadConfig(): BackendConfig {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored) {
      return { ...defaultConfig, ...JSON.parse(stored) }
    }
  } catch {
    console.warn('[Config] Failed to load config from localStorage')
  }
  return { ...defaultConfig }
}

export function saveConfig(config: Partial<BackendConfig>): void {
  try {
    const current = loadConfig()
    const newConfig = { ...current, ...config }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(newConfig))
  } catch {
    console.warn('[Config] Failed to save config to localStorage')
  }
}

export function resetConfig(): void {
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    console.warn('[Config] Failed to clear config from localStorage')
  }
}

export function getBaseUrl(): string {
  const config = loadConfig()
  return config.baseUrl || getDefaultBaseUrl()
}

export function getApiBaseUrl(): string {
  return `${getBaseUrl()}/api`
}

export function getWsBaseUrl(): string {
  const baseUrl = getBaseUrl()
  const url = new URL(baseUrl)
  const protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${url.host}`
}
