import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { ChatRoom, Message } from '@/types'
import { chatRoomApi } from '@/api/chatRoom'
import { useAuthStore } from './auth'

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
    
    const wsUrl = `ws://${window.location.host}/ws/chat`
    const socket = new WebSocket(wsUrl)
    
    socket.onopen = () => {
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

    socket.onclose = () => {
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

  function sendMessage(content: string) {
    if (ws.value && ws.value.readyState === WebSocket.OPEN) {
      ws.value.send(JSON.stringify({
        type: 'message',
        content
      }))
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
