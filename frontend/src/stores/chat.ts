import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { ChatRoom, Message } from '@/types'
import { chatRoomApi } from '@/api/chatRoom'
import { useAuthStore } from './auth'

// 附件类型定义
export interface Attachment {
  id: string
  dataUrl: string
  mimeType: string
}

export const useChatStore = defineStore('chat', () => {
  const rooms = ref<ChatRoom[]>([])
  const currentRoom = ref<ChatRoom | null>(null)
  const messages = ref<Message[]>([])
  const loading = ref(false)
  const ws = ref<WebSocket | null>(null)
  const isConnected = ref(false)
  
  // 正在输入的用户
  const typingUsers = ref<Map<string, { name: string; timeout: number }>>(new Map())

  async function fetchRooms() {
    loading.value = true
    try {
      const response = await chatRoomApi.getMyRooms()
      rooms.value = response.data
    } finally {
      loading.value = false
    }
  }

  async function createRoom(name: string, description?: string) {
    const response = await chatRoomApi.create({ name, description })
    rooms.value.push(response.data)
    return response.data
  }

  async function connect(roomId: string) {
    const authStore = useAuthStore()
    
    let room = rooms.value.find(r => r.id === roomId)
    
    if (!room) {
      try {
        const response = await chatRoomApi.getRoom(roomId)
        room = response.data
      } catch (err) {
        console.error('Failed to get room:', err)
      }
    }
    
    currentRoom.value = room || null
    messages.value = []
    typingUsers.value.clear()
    
    // 构建 WebSocket URL：优先使用当前页面的 host，但处理端口问题
    // 开发环境：Vite 代理 /ws 到后端，使用相同 host:port
    // 生产环境：通常使用标准端口（80/443），需要指向 API 服务器
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    
    // 检查是否在开发环境（端口 3000 是 Vite 默认端口）
    const isDev = host.includes(':3000')
    
    // 构建 WebSocket URL
    // 开发环境：ws://localhost:3000/ws/chat（通过 Vite 代理）
    // 生产环境：直接使用 /ws 路径，让浏览器根据当前 host 连接
    const wsUrl = `${protocol}//${host}/ws/chat`
    const socket = new WebSocket(wsUrl)
    
    socket.onopen = () => {
      console.log('[WebSocket] Connected to', wsUrl)
      isConnected.value = true
      socket.send(JSON.stringify({
        type: 'join',
        roomId,
        userId: authStore.user?.id,
        userName: authStore.user?.username
      }))
    }

    socket.onmessage = (event) => {
      const data = JSON.parse(event.data)
      handleMessage(data)
    }

    socket.onerror = (error) => {
      console.error('[WebSocket] Error:', error)
    }

    socket.onclose = (event) => {
      console.log('[WebSocket] Closed:', event.code, event.reason)
      isConnected.value = false
    }

    ws.value = socket
  }

  function disconnect() {
    if (ws.value) {
      ws.value.close()
      ws.value = null
      isConnected.value = false
      currentRoom.value = null
      messages.value = []
      typingUsers.value.clear()
    }
  }

  function sendMessage(content: string, attachments: Attachment[] = []) {
    if (ws.value && ws.value.readyState === WebSocket.OPEN) {
      const payload: any = {
        type: 'message',
        content
      }
      // 如果有附件，添加到消息中
      if (attachments.length > 0) {
        payload.attachments = attachments.map(att => ({
          type: 'image',
          mimeType: att.mimeType,
          content: att.dataUrl.replace(/^data:[^;]+;base64,/, '') // 移除 data URL 前缀，只保留 base64 内容
        }))
      }
      ws.value.send(JSON.stringify(payload))
    }
  }
  
  // 发送正在输入状态
  function sendTyping() {
    if (ws.value && ws.value.readyState === WebSocket.OPEN) {
      ws.value.send(JSON.stringify({ type: 'typing' }))
    }
  }

  function handleMessage(data: any) {
    switch (data.type) {
      case 'history':
        messages.value = data.messages || []
        break
      case 'message':
        messages.value.push(data.message)
        // 收到消息时清除该用户的 typing 状态
        if (data.message.senderId) {
          typingUsers.value.delete(data.message.senderId)
        }
        break
      case 'stream_start':
        // 流式消息开始
        console.log('[WebSocket] stream_start:', data.message)
        messages.value.push(data.message)
        break
      case 'stream_delta':
        // 流式消息增量 - 追加到最新消息
        {
          const index = messages.value.findIndex(m => m.id === data.message.id)
          if (index !== -1) {
            // 创建新对象以确保响应式更新
            const updatedMsg = { ...messages.value[index] }
            updatedMsg.content = (updatedMsg.content || '') + (data.message.content || '')
            messages.value.splice(index, 1, updatedMsg)
            console.log('[WebSocket] stream_delta - appended content, total length:', updatedMsg.content.length)
          } else {
            console.warn('[WebSocket] stream_delta - message not found:', data.message.id)
          }
        }
        break
      case 'stream_end':
        // 流式消息结束 - 替换为完整消息
        {
          console.log('[WebSocket] stream_end - received message:', {
            id: data.message?.id,
            contentLength: data.message?.content?.length,
            isToolCall: data.message?.isToolCall,
            toolCallsCount: data.message?.toolCalls?.length
          })
          const index = messages.value.findIndex(m => m.id === data.message.id)
          if (index !== -1) {
            // 使用 splice 确保响应式更新
            messages.value.splice(index, 1, data.message)
            console.log('[WebSocket] stream_end - message replaced at index:', index)
          } else {
            // 如果找不到消息（异常情况），直接追加
            messages.value.push(data.message)
            console.log('[WebSocket] stream_end - message appended (not found in existing)')
          }
        }
        break
      case 'typing':
        // 处理正在输入状态
        if (data.userId && data.userName) {
          const existing = typingUsers.value.get(data.userId)
          if (existing) {
            clearTimeout(existing.timeout)
          }
          const timeout = window.setTimeout(() => {
            typingUsers.value.delete(data.userId)
          }, 3000)
          typingUsers.value.set(data.userId, { name: data.userName, timeout })
        }
        break
      case 'user_joined':
        // 添加系统消息
        messages.value.push({
          id: `system-${Date.now()}`,
          senderId: 'system',
          senderName: '系统',
          content: `${data.userName} 加入了聊天室`,
          timestamp: new Date().toISOString(),
          openclawMentioned: false,
          fromOpenClaw: false,
          isSystem: true
        } as Message)
        break
      case 'user_left':
        messages.value.push({
          id: `system-${Date.now()}`,
          senderId: 'system',
          senderName: '系统',
          content: `${data.userName} 离开了聊天室`,
          timestamp: new Date().toISOString(),
          openclawMentioned: false,
          fromOpenClaw: false,
          isSystem: true
        } as Message)
        break
    }
  }

  // 正在输入的用户列表
  const typingUserList = computed(() => {
    return Array.from(typingUsers.value.values()).map(u => u.name)
  })

  return {
    rooms,
    currentRoom,
    messages,
    loading,
    isConnected,
    typingUserList,
    fetchRooms,
    createRoom,
    connect,
    disconnect,
    sendMessage,
    sendTyping
  }
})
