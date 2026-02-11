import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ChatRoom, Message } from '@/types'
import { chatRoomApi } from '@/api/chatRoom'

export const useChatStore = defineStore('chat', () => {
  const rooms = ref<ChatRoom[]>([])
  const currentRoom = ref<ChatRoom | null>(null)
  const messages = ref<Message[]>([])
  const loading = ref(false)
  const ws = ref<WebSocket | null>(null)
  const isConnected = ref(false)

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

  function connect(roomId: string) {
    // 先找到当前房间信息
    currentRoom.value = rooms.value.find(r => r.id === roomId) || null
    messages.value = []
    
    const wsUrl = `ws://${window.location.host}/ws/chat`
    const socket = new WebSocket(wsUrl)
    
    socket.onopen = () => {
      isConnected.value = true
      // 加入房间
      socket.send(JSON.stringify({
        type: 'join',
        roomId,
        userId: localStorage.getItem('userId'),
        userName: localStorage.getItem('username')
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

  function handleMessage(data: any) {
    switch (data.type) {
      case 'history':
        messages.value = data.messages || []
        break
      case 'message':
        messages.value.push(data.message)
        break
      case 'user_joined':
        // 处理用户加入
        break
      case 'user_left':
        // 处理用户离开
        break
    }
  }

  return {
    rooms,
    currentRoom,
    messages,
    loading,
    isConnected,
    fetchRooms,
    createRoom,
    connect,
    disconnect,
    sendMessage
  }
})
