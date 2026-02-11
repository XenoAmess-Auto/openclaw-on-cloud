import apiClient from './client'
import type { OocSession } from '@/types'

export const sessionApi = {
  getHistory: (roomId: string) => 
    apiClient.get<OocSession[]>(`/sessions/chat-room/${roomId}`),
  
  archive: (sessionId: string) => 
    apiClient.post<OocSession>(`/sessions/${sessionId}/archive`),
  
  copy: (sessionId: string, newChatRoomId: string) => 
    apiClient.post<OocSession>(`/sessions/${sessionId}/copy?newChatRoomId=${newChatRoomId}`)
}
