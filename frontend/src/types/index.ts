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
  projects?: string[]
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
  position?: number
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
  replyToMessageId?: string
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
  type: 'IMAGE' | 'PDF' | 'TEXT' | 'FILE' | 'image' | 'pdf' | 'text' | 'file' | 'FLOWCHART_VARIABLES'
  contentType: string
  size: number
}

// ==================== Person Trait System ====================

/**
 * 特质类型
 */
export type TraitType = 'PREGNANCY' | string

/**
 * 特质接口
 */
export interface Trait {
  id: string
  type: TraitType
  name: string
  description: string
  createdAt: string
  expiresAt?: string
  clearedOnDeath: boolean
  properties?: Record<string, any>
}

/**
 * 怀孕特质
 */
export interface PregnancyTrait extends Trait {
  type: 'PREGNANCY'
  fatherId?: string
  fatherName?: string
  conceptionDate: string
  dueDate: string
  stage: 'EARLY' | 'MIDDLE' | 'LATE'
}

/**
 * 性别枚举
 */
export type Gender = 'MALE' | 'FEMALE' | 'UNKNOWN'

/**
 * 人物接口
 */
export interface Person {
  id: string
  name: string
  displayName: string
  avatar?: string
  gender: Gender
  age: number
  isAlive: boolean
  deathTime?: string
  deathReason?: string
  traits: Trait[]
  createdAt: string
  updatedAt: string
}

/**
 * 创建人物请求
 */
export interface CreatePersonRequest {
  name: string
  displayName?: string
  gender: Gender
  age: number
}

/**
 * 设置怀孕请求
 */
export interface SetPregnancyRequest {
  fatherId?: string
  fatherName?: string
  daysUntilBirth: number
}
