export interface User {
  id: string
  username: string
  nickname: string
  email: string
  avatar?: string
  roles: string[]
}

export interface ChatRoom {
  id: string
  name: string
  description?: string
  memberIds: string[]
  creatorId: string
  createdAt: string
  updatedAt: string
}

export interface Message {
  id: string
  senderId: string
  senderName: string
  senderAvatar?: string
  content: string
  timestamp: string
  openclawMentioned: boolean
  fromOpenClaw: boolean
}

export interface OocSession {
  id: string
  chatRoomId: string
  chatRoomName: string
  summary?: string
  messages: SessionMessage[]
  messageCount: number
  archived: boolean
  createdAt: string
  updatedAt: string
}

export interface SessionMessage {
  id: string
  senderId: string
  senderName: string
  content: string
  timestamp: string
  fromOpenClaw: boolean
}

export interface AuthResponse {
  token: string
  userId: string
  username: string
  nickname: string
  email: string
  avatar?: string
  roles: string[]
}
