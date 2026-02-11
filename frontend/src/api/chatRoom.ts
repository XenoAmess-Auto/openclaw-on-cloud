import apiClient from './client'
import type { ChatRoom } from '@/types'

export interface CreateRoomRequest {
  name: string
  description?: string
}

export const chatRoomApi = {
  getMyRooms: () => 
    apiClient.get<ChatRoom[]>('/chat-rooms'),
  
  getRoom: (roomId: string) => 
    apiClient.get<ChatRoom>(`/chat-rooms/${roomId}`),
  
  create: (data: CreateRoomRequest) => 
    apiClient.post<ChatRoom>('/chat-rooms', data),
  
  addMember: (roomId: string, userId: string) => 
    apiClient.post<ChatRoom>(`/chat-rooms/${roomId}/members?userId=${userId}`),
  
  removeMember: (roomId: string, userId: string) => 
    apiClient.delete<ChatRoom>(`/chat-rooms/${roomId}/members/${userId}`),
  
  deleteRoom: (roomId: string) => 
    apiClient.delete(`/chat-rooms/${roomId}`)
}
