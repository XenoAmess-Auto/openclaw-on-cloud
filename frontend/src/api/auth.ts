import apiClient from './client'
import { getPublicKey, encryptWithPublicKey } from './crypto'
import type { AuthResponse } from '@/types'

export interface LoginRequest {
  username: string
  password: string
}

export interface RegisterRequest {
  username: string
  email: string
  password: string
}

export const authApi = {
  login: async (data: LoginRequest) => {
    const key = await getPublicKey()
    const encryptedPassword = encryptWithPublicKey(data.password, key)
    return apiClient.post<AuthResponse>('/auth/login', {
      username: data.username,
      password: encryptedPassword
    })
  },
  
  register: async (data: RegisterRequest) => {
    const key = await getPublicKey()
    const encryptedPassword = encryptWithPublicKey(data.password, key)
    return apiClient.post<AuthResponse>('/auth/register', {
      username: data.username,
      email: data.email,
      password: encryptedPassword
    })
  },
  
  refresh: () => 
    apiClient.post<AuthResponse>('/auth/refresh')
}
