import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User, AuthResponse } from '@/types'
import { authApi } from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const token = ref<string | null>(localStorage.getItem('token'))
  const loading = ref(false)

  // 初始化时从 localStorage 恢复用户信息
  const storedUser = localStorage.getItem('user')
  if (storedUser) {
    try {
      user.value = JSON.parse(storedUser)
    } catch {
      localStorage.removeItem('user')
    }
  }

  const isAuthenticated = computed(() => !!token.value && !!user.value)

  function setAuth(data: AuthResponse) {
    token.value = data.token
    user.value = {
      id: data.userId,
      username: data.username,
      nickname: data.nickname || data.username,
      email: data.email,
      avatar: data.avatar,
      roles: data.roles
    }
    localStorage.setItem('token', data.token)
    localStorage.setItem('user', JSON.stringify(user.value))
  }

  function clearAuth() {
    token.value = null
    user.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('user')
  }

  async function login(username: string, password: string) {
    loading.value = true
    try {
      const response = await authApi.login({ username, password })
      setAuth(response.data)
      return response.data
    } finally {
      loading.value = false
    }
  }

  async function register(username: string, email: string, password: string, nickname?: string) {
    loading.value = true
    try {
      const response = await authApi.register({ username, email, password, nickname })
      setAuth(response.data)
      return response.data
    } finally {
      loading.value = false
    }
  }

  function logout() {
    clearAuth()
  }

  return {
    user,
    token,
    loading,
    isAuthenticated,
    login,
    register,
    logout,
    setAuth,
    clearAuth
  }
})
