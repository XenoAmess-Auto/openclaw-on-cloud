import apiClient from './client'

export interface UpdateUserRequest {
  nickname?: string
  email?: string
  avatar?: string
  currentPassword?: string
  newPassword?: string
}

export interface CurrentUserResponse {
  id: string
  username: string
  nickname: string
  email: string
  avatar: string
  roles: string[]
  enabled: boolean
}

export const userApi = {
  getCurrentUser: () =>
    apiClient.get<CurrentUserResponse>('/users/me'),

  updateCurrentUser: (data: UpdateUserRequest) =>
    apiClient.put<CurrentUserResponse>('/users/me', data),
}
