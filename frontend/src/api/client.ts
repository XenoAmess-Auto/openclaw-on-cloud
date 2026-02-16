import axios, { type AxiosInstance } from 'axios'
import { getApiBaseUrl } from '@/utils/config'

// 创建 API 客户端的工厂函数
function createApiClient(): AxiosInstance {
  const client: AxiosInstance = axios.create({
    baseURL: getApiBaseUrl(),
    headers: {
      'Content-Type': 'application/json'
    }
  })

  client.interceptors.request.use((config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  })

  client.interceptors.response.use(
    (response) => response,
    (error) => {
      const status = error.response?.status
      if (status === 401 || status === 403) {
        localStorage.removeItem('token')
        localStorage.removeItem('user')
        window.location.href = '/login'
      }
      return Promise.reject(error)
    }
  )

  return client
}

// 默认导出动态获取的客户端
// 注意：这需要在 Pinia 初始化后使用
let apiClientInstance: AxiosInstance | null = null

export function initApiClient(): AxiosInstance {
  if (!apiClientInstance) {
    apiClientInstance = createApiClient()
  }
  return apiClientInstance
}

// 重新初始化 API 客户端（配置变更后调用）
export function reinitApiClient(): AxiosInstance {
  apiClientInstance = createApiClient()
  return apiClientInstance
}

// 为了兼容现有代码，导出一个代理对象
// 实际请求时会动态获取 baseURL
const apiClientProxy: AxiosInstance = new Proxy({} as AxiosInstance, {
  get(_, prop: string) {
    // 每次访问都获取最新的客户端实例（支持配置变更后热更新）
    const client = initApiClient()
    const value = client[prop as keyof AxiosInstance]
    
    if (typeof value === 'function') {
      return value.bind(client)
    }
    return value
  }
})

export default apiClientProxy
