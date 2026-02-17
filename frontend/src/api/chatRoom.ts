import apiClient from './client'
import type { ChatRoom, MemberDto, Message } from '@/types'

export interface CreateRoomRequest {
  name: string
  description?: string
}

export interface GetMessagesParams {
  page?: number
  size?: number
  before?: string
}

export interface TaskQueueInfo {
  roomId: string
  isProcessing: boolean
  queueSize: number
  tasks: {
    taskId: string
    status: string
    createdAt: string
    senderName: string
    content: string
  }[]
}

export const chatRoomApi = {
  getMyRooms: () => 
    apiClient.get<ChatRoom[]>('/chat-rooms'),
  
  getRoom: (roomId: string) => 
    apiClient.get<ChatRoom>(`/chat-rooms/${roomId}`),
  
  create: (data: CreateRoomRequest) => 
    apiClient.post<ChatRoom>('/chat-rooms', data),

  getMembers: (roomId: string) =>
    apiClient.get<MemberDto[]>(`/chat-rooms/${roomId}/members`),

  searchMembers: (roomId: string, query: string) =>
    apiClient.get<MemberDto[]>(`/chat-rooms/${roomId}/members/search?q=${encodeURIComponent(query)}`),
  
  addMember: (roomId: string, userId: string) => 
    apiClient.post<ChatRoom>(`/chat-rooms/${roomId}/members?userId=${userId}`),
  
  removeMember: (roomId: string, userId: string) => 
    apiClient.delete<ChatRoom>(`/chat-rooms/${roomId}/members/${userId}`),
  
  deleteRoom: (roomId: string) => 
    apiClient.delete(`/chat-rooms/${roomId}`),

  getMessages: (roomId: string, params?: GetMessagesParams) => {
    const searchParams = new URLSearchParams()
    if (params?.page !== undefined) searchParams.append('page', params.page.toString())
    if (params?.size !== undefined) searchParams.append('size', params.size.toString())
    if (params?.before) searchParams.append('before', params.before)
    
    const queryString = searchParams.toString()
    return apiClient.get<Message[]>(`/chat-rooms/${roomId}/messages${queryString ? '?' + queryString : ''}`)
  },

  getTaskQueue: (roomId: string) =>
    apiClient.get<TaskQueueInfo>(`/chat-rooms/${roomId}/queue`),

  reorderTaskQueue: (roomId: string, taskIds: string[]) =>
    apiClient.post(`/chat-rooms/${roomId}/queue/reorder`, { taskIds }),

  cancelTask: (roomId: string, taskId: string) =>
    apiClient.delete(`/chat-rooms/${roomId}/queue/${taskId}`),

  reorderTaskQueue: (roomId: string, taskIds: string[]) =>
    apiClient.post(`/chat-rooms/${roomId}/queue/reorder`, { taskIds }),

  cancelTask: (roomId: string, taskId: string) =>
    apiClient.delete(`/chat-rooms/${roomId}/queue/${taskId}`)
}
