import axios, { type AxiosInstance } from 'axios'

// 动态检测后端地址
// 如果当前页面 host 是 localhost，直接访问 localhost:8081
// 否则使用当前页面 host 的 8081 端口（假设前后端同机部署）
const hostname = window.location.hostname
const backendHost = hostname === 'localhost' ? 'localhost' : hostname
const baseURL = `http://${backendHost}:8081/api`

const apiClient: AxiosInstance = axios.create({
  baseURL,
  headers: {
    'Content-Type': 'application/json'
  }
})

apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

apiClient.interceptors.response.use(
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

export default apiClient
