import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  loadConfig,
  saveConfig as saveConfigUtil,
  resetConfig as resetConfigUtil,
  getBaseUrl,
  getApiBaseUrl,
  getWsBaseUrl,
  type BackendConfig
} from '@/utils/config'

export { type BackendConfig }

export const useConfigStore = defineStore('config', () => {
  // 从 localStorage 加载配置
  const config = ref<BackendConfig>(loadConfig())

  // 实际使用的后端地址（配置值或默认值）
  const baseUrl = computed(() => getBaseUrl())

  // API 基础路径
  const apiBaseUrl = computed(() => getApiBaseUrl())

  // WebSocket 基础路径
  const wsBaseUrl = computed(() => getWsBaseUrl())

  // 保存配置
  function saveConfig(newConfig: Partial<BackendConfig>) {
    config.value = { ...config.value, ...newConfig }
    saveConfigUtil(newConfig)
  }

  // 重置为默认
  function resetToDefault() {
    config.value = { baseUrl: null }
    resetConfigUtil()
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
