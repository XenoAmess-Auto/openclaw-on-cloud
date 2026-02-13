import apiClient from './client'
import type { MentionRecord, UserMentionSettings } from '@/types'

export interface UnreadCountResponse {
  count: number
}

export const mentionApi = {
  // 获取未读@提及
  getUnread: () =>
    apiClient.get<MentionRecord[]>('/mentions/unread'),

  // 获取@提及历史
  getHistory: (page = 0, size = 20) =>
    apiClient.get<MentionRecord[]>(`/mentions/history?page=${page}&size=${size}`),

  // 获取未读数量
  getUnreadCount: () =>
    apiClient.get<UnreadCountResponse>('/mentions/unread-count'),

  // 标记已读
  markAsRead: (mentionId: string) =>
    apiClient.post(`/mentions/${mentionId}/read`),

  // 标记全部已读
  markAllAsRead: () =>
    apiClient.post('/mentions/read-all'),

  // 获取通知设置
  getSettings: () =>
    apiClient.get<UserMentionSettings>('/mentions/settings'),

  // 更新通知设置
  updateSettings: (settings: UserMentionSettings) =>
    apiClient.put<UserMentionSettings>('/mentions/settings', settings)
}
