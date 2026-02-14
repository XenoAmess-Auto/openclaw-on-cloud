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

export interface Mention {
  userId: string
  userName: string
}

export interface ToolCall {
  id: string
  name: string
  description?: string
  status: 'running' | 'completed' | 'error'
  result?: string
  timestamp: string
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
  isSystem?: boolean
  isToolCall?: boolean
  isStreaming?: boolean
  delta?: boolean
  toolCalls?: ToolCall[]
  mentions?: Mention[]
  mentionAll?: boolean
  mentionHere?: boolean
  attachments?: Attachment[]
}

export interface MentionRecord {
  id: string
  messageId: string
  mentionedUserId: string
  mentionedUserName: string
  mentionerUserId: string
  mentionerUserName: string
  roomId: string
  roomName: string
  messageContent: string
  isRead: boolean
  readAt?: string
  createdAt: string
}

export interface UserMentionSettings {
  id?: string
  userId?: string
  notifyOnMention: boolean
  notifyOnMentionAll: boolean
  emailNotification: boolean
  pushNotification: boolean
  doNotDisturb: boolean
  dndStartTime?: string
  dndEndTime?: string
  mutedRoomIds: string[]
  blockedUserIds: string[]
  updatedAt?: string
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

export interface MemberDto {
  id: string
  username: string
  nickname?: string
  email: string
  avatar?: string
  isCreator: boolean
  joinedAt?: string
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

export interface FileUploadResponse {
  filename: string
  originalName: string
  url: string
  type: 'IMAGE' | 'PDF' | 'TEXT' | 'FILE'
  contentType: string
  size: number
}

export interface Attachment {
  id: string
  url: string
  name: string
  type: 'IMAGE' | 'PDF' | 'TEXT' | 'FILE' | 'image' | 'pdf' | 'text' | 'file'
  contentType: string
  size: number
}
