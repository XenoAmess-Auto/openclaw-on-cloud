import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

const STORAGE_KEY = 'ooc_backend_config'

export interface BackendConfig {
  /** 后端地址，如 http://localhost:8081 或 https://api.example.com */
  baseUrl: string | null
}

function getDefaultBaseUrl(): string {
  const hostname = window.location.hostname
  const protocol = window.location.protocol === 'https:' ? 'https:' : 'http:'
  return `${protocol}//${hostname}:8081`
}

export const useConfigStore = defineStore('config', () => {
  // 从 localStorage 加载配置
  const loadConfig = (): BackendConfig => {
    try {
      const stored = localStorage.getItem(STORAGE_KEY)
      if (stored) {
        return JSON.parse(stored)
      }
    } catch {
      console.warn('[ConfigStore] Failed to load config from localStorage')
    }
    return { baseUrl: null }
  }

  const config = ref<BackendConfig>(loadConfig())

  // 实际使用的后端地址（配置值或默认值）
  const baseUrl = computed(() => {
    return config.value.baseUrl || getDefaultBaseUrl()
  })

  // API 基础路径
  const apiBaseUrl = computed(() => {
    return `${baseUrl.value}/api`
  })

  // WebSocket 基础路径
  const wsBaseUrl = computed(() => {
    const url = new URL(baseUrl.value)
    const protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${protocol}//${url.host}`
  })

  // 保存配置
  function saveConfig(newConfig: Partial<BackendConfig>) {
    config.value = { ...config.value, ...newConfig }
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(config.value))
    } catch {
      console.warn('[ConfigStore] Failed to save config to localStorage')
    }
  }

  // 重置为默认
  function resetToDefault() {
    config.value = { baseUrl: null }
    try {
      localStorage.removeItem(STORAGE_KEY)
    } catch {
      console.warn('[ConfigStore] Failed to clear config from localStorage')
    }
  }

  return {
    config,
    baseUrl,
    apiBaseUrl,
    wsBaseUrl,
    saveConfig,
    resetToDefault
  }
})
