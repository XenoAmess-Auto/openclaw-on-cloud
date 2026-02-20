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
  
  // 分页相关状态
  const hasMoreMessages = ref(false)
  const loadingMore = ref(false)

  // 重连相关状态
  const reconnectAttempts = ref(0)
  const reconnectTimer = ref<number | null>(null)
  const MAX_RECONNECT_ATTEMPTS = 100 // 增加到100次（约8分钟），几乎无限重连
  const RECONNECT_DELAY = 5000 // 5秒后开始重连

  // 心跳相关状态
  const heartbeatTimer = ref<number | null>(null)
  const HEARTBEAT_INTERVAL = 30000 // 30秒发送一次心跳

  // 网络状态监听
  const currentRoomIdForReconnect = ref<string | null>(null)

  // 清理心跳定时器
  function clearHeartbeatTimer() {
    if (heartbeatTimer.value) {
      clearInterval(heartbeatTimer.value)
      heartbeatTimer.value = null
    }
  }

  // 网络恢复时的处理函数
  function handleOnline() {
    console.log('[WebSocket] Network is online, checking connection...')
    if (!isConnected.value && currentRoomIdForReconnect.value) {
      console.log('[WebSocket] Triggering reconnect due to network recovery')
      reconnectAttempts.value = 0 // 重置计数器，立即重连
      scheduleReconnect(currentRoomIdForReconnect.value)
    }
  }

  // 页面可见性变化处理
  function handleVisibilityChange() {
    if (document.visibilityState === 'visible') {
      console.log('[WebSocket] Page became visible, checking connection...')
      if (!isConnected.value && currentRoomIdForReconnect.value) {
        console.log('[WebSocket] Triggering reconnect due to page visibility')
        reconnectAttempts.value = 0 // 重置计数器，立即重连
        scheduleReconnect(currentRoomIdForReconnect.value)
      }
    }
  }

  // 注册网络状态监听
  function setupNetworkListeners() {
    window.addEventListener('online', handleOnline)
    document.addEventListener('visibilitychange', handleVisibilityChange)
  }

  // 移除网络状态监听
  function cleanupNetworkListeners() {
    window.removeEventListener('online', handleOnline)
    document.removeEventListener('visibilitychange', handleVisibilityChange)
  }

  // 启动心跳
  function startHeartbeat() {
    clearHeartbeatTimer()
    heartbeatTimer.value = window.setInterval(() => {
      if (ws.value && ws.value.readyState === WebSocket.OPEN) {
        ws.value.send(JSON.stringify({ type: 'ping' }))
        console.log('[WebSocket] Heartbeat ping sent')
      }
    }, HEARTBEAT_INTERVAL)
  }

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

  // 清理重连定时器
  function clearReconnectTimer() {
    if (reconnectTimer.value) {
      clearTimeout(reconnectTimer.value)
      reconnectTimer.value = null
    }
  }

  // 执行重连
  function scheduleReconnect(roomId: string) {
    // 避免重复调度重连
    if (reconnectTimer.value) {
      console.log('[WebSocket] Reconnect already scheduled, skipping')
      return
    }
    
    if (reconnectAttempts.value >= MAX_RECONNECT_ATTEMPTS) {
      console.error('[WebSocket] Max reconnect attempts reached, continuing to retry...')
      // 重置计数器继续尝试（几乎无限重连）
      reconnectAttempts.value = 0
    }
    
    reconnectAttempts.value++
    const attemptNum = reconnectAttempts.value
    console.log(`[WebSocket] Scheduling reconnect attempt ${attemptNum} in ${RECONNECT_DELAY}ms`)
    
    // 添加系统消息提示正在重连（只在第一次断开时显示）
    const lastSystemMsg = messages.value[messages.value.length - 1]
    const isRetryingMsg = lastSystemMsg?.senderId === 'system' && lastSystemMsg?.content?.includes('正在尝试重新连接')
    
    if (!isRetryingMsg) {
      messages.value.push({
        id: `system-${Date.now()}`,
        senderId: 'system',
        senderName: '系统',
        content: `未连接到服务器，正在尝试重新连接... (第${attemptNum}次)`,
        timestamp: new Date().toISOString(),
        openclawMentioned: false,
        fromOpenClaw: false,
        isSystem: true
      } as Message)
    } else {
      // 更新最后一条系统消息，显示重试次数
      lastSystemMsg.content = `未连接到服务器，正在尝试重新连接... (第${attemptNum}次)`
    }
    
    reconnectTimer.value = window.setTimeout(() => {
      console.log(`[WebSocket] Executing reconnect attempt ${attemptNum}`)
      connect(roomId)
    }, RECONNECT_DELAY)
  }

  async function connect(roomId: string) {
    const authStore = useAuthStore()
    
    // 保存当前房间ID用于重连
    currentRoomIdForReconnect.value = roomId
    
    // 设置网络状态监听（只设置一次）
    if (!isConnected.value && reconnectAttempts.value === 0) {
      setupNetworkListeners()
    }
    
    // 连接前刷新用户信息，确保 userId 和头像是最新的
    if (authStore.isAuthenticated) {
      try {
        await authStore.refreshUserInfo()
        console.log('[WebSocket] User info refreshed before connect')
      } catch (error) {
        console.warn('[WebSocket] Failed to refresh user info:', error)
      }
    }
    
    // 如果已有连接，先断开
    if (ws.value) {
      ws.value.close()
      ws.value = null
    }
    
    // 首次连接时（非重连）重置消息和状态
    // 或者 currentRoom 为 null 时（确保状态正确）
    if (reconnectAttempts.value === 0 || !currentRoom.value) {
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
    }
    
    // 构建 WebSocket URL
    // 使用相对路径 /ws/chat 通过前端代理连接后端，与 API 请求保持一致
    // 避免直接连接后端 8081 端口可能导致的防火墙/CORS问题
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const wsUrl = `${protocol}//${window.location.host}/ws/chat`
    const socket = new WebSocket(wsUrl)
    
    socket.onopen = () => {
      console.log('[WebSocket] Connected to', wsUrl)
      isConnected.value = true
      
      // 连接成功，重置重连计数
      const wasReconnecting = reconnectAttempts.value > 0
      if (wasReconnecting) {
        console.log('[WebSocket] Reconnected successfully')
        reconnectAttempts.value = 0
        clearReconnectTimer()
        
        // 找到并移除"正在尝试重新连接"的系统消息
        const retryMsgIndex = messages.value.findIndex(
          m => m.senderId === 'system' && m.content?.includes('正在尝试重新连接')
        )
        if (retryMsgIndex !== -1) {
          messages.value.splice(retryMsgIndex, 1)
        }
        
        // 添加系统消息提示重连成功
        messages.value.push({
          id: `system-${Date.now()}`,
          senderId: 'system',
          senderName: '系统',
          content: '已重新连接到服务器',
          timestamp: new Date().toISOString(),
          openclawMentioned: false,
          fromOpenClaw: false,
          isSystem: true
        } as Message)
      }
      
      socket.send(JSON.stringify({
        type: 'join',
        roomId,
        userId: authStore.user?.id,
        userName: authStore.user?.username
      }))

      // 启动心跳
      startHeartbeat()
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
      ws.value = null

      // 清理心跳
      clearHeartbeatTimer()

      // 如果是正常关闭（用户主动离开），不重连
      // code 1000 = 正常关闭, code 1001 = 离开页面
      if (event.code === 1000 || event.code === 1001) {
        console.log('[WebSocket] Normal close, no reconnect needed')
        return
      }

      // 异常关闭，触发重连
      scheduleReconnect(roomId)
    }

    ws.value = socket
  }

  function disconnect() {
    clearReconnectTimer()
    clearHeartbeatTimer()
    reconnectAttempts.value = 0
    currentRoomIdForReconnect.value = null
    cleanupNetworkListeners()

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
    console.log('[chatStore.sendMessage] received attachments:', attachments.length, attachments)
    if (ws.value && ws.value.readyState === WebSocket.OPEN) {
      const payload: any = {
        type: 'message',
        content
      }
      // 如果有附件，添加到消息中
      if (attachments.length > 0) {
        payload.attachments = attachments.map(att => {
          let url = att.dataUrl
          // 过滤掉无效的 URL（blob URL 或 data URL 不应该被发送到后端）
          if (url?.startsWith('blob:') || url?.startsWith('data:')) {
            console.warn('[sendMessage] Invalid URL detected, skipping:', url.substring(0, 50))
            url = ''
          }
          // 将完整 URL 转换为相对路径（后端期望 /uploads/xxx.png 或 /api/files/xxx 格式）
          if (url?.includes('/uploads/')) {
            const uploadsIndex = url.indexOf('/uploads/')
            url = url.substring(uploadsIndex)
          } else if (url?.includes('/api/files/')) {
            const filesIndex = url.indexOf('/api/files/')
            url = url.substring(filesIndex)
          }
          // 根据 MIME 类型确定附件类型
          let type = 'file'
          if (att.mimeType?.startsWith('image/')) {
            type = 'image'
          } else if (att.mimeType === 'application/pdf') {
            type = 'pdf'
          } else if (att.mimeType === 'text/plain') {
            type = 'text'
          }
          return {
            type: type,
            mimeType: att.mimeType,
            url: url
          }
        }).filter(att => att.url) // 过滤掉空 URL 的附件
      }
      console.log('[chatStore.sendMessage] sending payload:', JSON.stringify(payload).substring(0, 500))
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
        // 合并历史消息，避免重复（特别是 WebSocket 重连时）
        {
          const historyMessages = data.messages || []
          const existingIds = new Set(messages.value.map(m => m.id))
          const newMessages = historyMessages.filter((m: Message) => !existingIds.has(m.id))
          if (newMessages.length > 0) {
            // 按时间戳排序后合并
            const combined = [...messages.value, ...newMessages]
            combined.sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime())
            messages.value = combined
            console.log('[WebSocket] history - merged', newMessages.length, 'new messages, total:', messages.value.length)
          } else {
            console.log('[WebSocket] history - no new messages, skipped')
          }
          hasMoreMessages.value = data.hasMore || false
          // 去重确保没有重复消息
          deduplicateMessages()
        }
        break
      case 'message':
        // 检查是否已存在相同ID的消息，避免重复添加
        {
          const existingIndex = messages.value.findIndex(m => m.id === data.message.id)
          if (existingIndex !== -1) {
            console.warn('[WebSocket] message - message already exists, skipping:', data.message.id)
            break
          }
        }
        messages.value.push(data.message)
        // 收到消息时清除该用户的 typing 状态
        if (data.message.senderId) {
          typingUsers.value.delete(data.message.senderId)
        }
        break
      case 'stream_start':
        // 流式消息开始 - 只处理当前房间的消息
        // 兜底：如果 currentRoom 为 null 或 id 缺失，尝试恢复
        if (!currentRoom.value?.id && data.roomId) {
          const room = rooms.value.find(r => r.id === data.roomId)
          if (room) {
            currentRoom.value = room
            console.log('[WebSocket] stream_start - restored currentRoom:', data.roomId)
          }
        }
        if (data.roomId && data.roomId !== currentRoom.value?.id) {
          console.log('[WebSocket] stream_start ignored - room mismatch:', data.roomId, '!=', currentRoom.value?.id)
          break
        }
        console.log('[WebSocket] stream_start:', data.message)
        console.log('[WebSocket] stream_start replyToMessageId:', data.message?.replyToMessageId)
        // 检查是否已存在相同ID的消息，避免重复添加
        {
          const existingIndex = messages.value.findIndex(m => m.id === data.message.id)
          if (existingIndex !== -1) {
            console.warn('[WebSocket] stream_start - message already exists, skipping:', data.message.id)
            break
          }
        }
        messages.value.push(data.message)
        break
      case 'stream_delta':
        // 流式消息增量 - 只处理当前房间的消息
        // 兜底：如果 currentRoom 为 null 或 id 缺失，尝试恢复
        if (!currentRoom.value?.id && data.roomId) {
          const room = rooms.value.find(r => r.id === data.roomId)
          if (room) {
            currentRoom.value = room
            console.log('[WebSocket] stream_delta - restored currentRoom:', data.roomId)
          }
        }
        if (data.roomId && data.roomId !== currentRoom.value?.id) {
          console.log('[WebSocket] stream_delta ignored - room mismatch:', data.roomId, '!=', currentRoom.value?.id)
          break
        }
        // 追加到最新消息
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
      case 'tool_start':
        // 工具调用开始 - 只处理当前房间的消息
        // 兜底：如果 currentRoom 为 null 或 id 缺失，尝试恢复
        if (!currentRoom.value?.id && data.roomId) {
          const room = rooms.value.find(r => r.id === data.roomId)
          if (room) {
            currentRoom.value = room
            console.log('[WebSocket] tool_start - restored currentRoom:', data.roomId)
          }
        }
        if (data.roomId && data.roomId !== currentRoom.value?.id) {
          console.log('[WebSocket] tool_start ignored - room mismatch:', data.roomId, '!=', currentRoom.value?.id)
          break
        }
        // 实时更新工具调用状态
        {
          const index = messages.value.findIndex(m => m.id === data.message.id)
          if (index !== -1) {
            const updatedMsg = { ...messages.value[index] }
            // 合并工具调用列表
            const existingToolCalls = updatedMsg.toolCalls || []
            const newToolCalls = data.message.toolCalls || []
            updatedMsg.toolCalls = [...existingToolCalls, ...newToolCalls]
            updatedMsg.isToolCall = true
            messages.value.splice(index, 1, updatedMsg)
            console.log('[WebSocket] tool_start - added tool call:', newToolCalls[0]?.name)
          } else {
            console.warn('[WebSocket] tool_start - message not found:', data.message.id)
          }
        }
        break
      case 'tool_result':
        // 工具调用完成 - 只处理当前房间的消息
        // 兜底：如果 currentRoom 为 null 或 id 缺失，尝试恢复
        if (!currentRoom.value?.id && data.roomId) {
          const room = rooms.value.find(r => r.id === data.roomId)
          if (room) {
            currentRoom.value = room
            console.log('[WebSocket] tool_result - restored currentRoom:', data.roomId)
          }
        }
        if (data.roomId && data.roomId !== currentRoom.value?.id) {
          console.log('[WebSocket] tool_result ignored - room mismatch:', data.roomId, '!=', currentRoom.value?.id)
          break
        }
        // 更新工具调用状态
        {
          const index = messages.value.findIndex(m => m.id === data.message.id)
          if (index !== -1) {
            const updatedMsg = { ...messages.value[index] }
            // 更新工具调用列表（后端发送完整列表）
            updatedMsg.toolCalls = data.message.toolCalls || []
            updatedMsg.isToolCall = true
            messages.value.splice(index, 1, updatedMsg)
            console.log('[WebSocket] tool_result - updated tool calls:', updatedMsg.toolCalls?.length)
          } else {
            console.warn('[WebSocket] tool_result - message not found:', data.message.id)
          }
        }
        break
            case 'stream_end':
        // 流式消息结束 - 只处理当前房间的消息
        // 兜底：如果 currentRoom 为 null 或 id 缺失，尝试恢复
        if (!currentRoom.value?.id && data.roomId) {
          const room = rooms.value.find(r => r.id === data.roomId)
          if (room) {
            currentRoom.value = room
            console.log('[WebSocket] stream_end - restored currentRoom:', data.roomId)
          }
        }
        if (data.roomId && data.roomId !== currentRoom.value?.id) {
          console.log('[WebSocket] stream_end ignored - room mismatch:', data.roomId, '!=', currentRoom.value?.id)
          break
        }
        // 替换为完整消息
        {
          console.log('[WebSocket] stream_end - received message:', {
            id: data.message?.id,
            contentLength: data.message?.content?.length,
            isToolCall: data.message?.isToolCall,
            toolCallsCount: data.message?.toolCalls?.length
          })
          const index = messages.value.findIndex(m => m.id === data.message.id)
          if (index !== -1) {
            // 保留现有的 toolCalls（如果后端没有发送）
            const existingMsg = messages.value[index]
            if (!data.message.toolCalls && existingMsg.toolCalls && existingMsg.toolCalls.length > 0) {
              data.message.toolCalls = existingMsg.toolCalls
              data.message.isToolCall = true
              console.log('[WebSocket] stream_end - preserved existing toolCalls:', existingMsg.toolCalls.length)
            }
            // 保留 replyToMessageId（如果后端没有发送）
            if (!data.message.replyToMessageId && existingMsg.replyToMessageId) {
              data.message.replyToMessageId = existingMsg.replyToMessageId
              console.log('[WebSocket] stream_end - preserved existing replyToMessageId:', existingMsg.replyToMessageId)
            }
            // 使用 splice 确保响应式更新
            messages.value.splice(index, 1, data.message)
            console.log('[WebSocket] stream_end - message replaced at index:', index)
          } else {
            // 找不到消息，追加为新消息（可能是 WebSocket 重连后的新流式消息）
            messages.value.push(data.message)
            console.log('[WebSocket] stream_end - message appended (not found in existing):', data.message.id)
          }
          // 去重确保没有重复消息
          deduplicateMessages()
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
      case 'user_avatar_updated':
        // 用户头像更新 - 更新消息列表中该用户的所有消息头像
        if (data.userId && data.content) {
          messages.value.forEach((msg, index) => {
            if (msg.senderId === data.userId) {
              messages.value[index] = { ...msg, senderAvatar: data.content }
            }
          })
          console.log('[WebSocket] Updated avatar for user:', data.userId)
        }
        break
    }
  }

  // 正在输入的用户列表
  const typingUserList = computed(() => {
    return Array.from(typingUsers.value.values()).map(u => u.name)
  })

  // 加载更多历史消息
  async function loadMoreMessages(roomId: string): Promise<boolean> {
    if (loadingMore.value || !hasMoreMessages.value || messages.value.length === 0) {
      return false
    }

    loadingMore.value = true
    try {
      // 获取最早一条消息的时间戳作为游标
      const oldestMessage = messages.value[0]
      const before = oldestMessage?.timestamp

      if (!before) {
        return false
      }

      const response = await chatRoomApi.getMessages(roomId, { before, size: 10 })
      const olderMessages = response.data || []

      if (olderMessages.length === 0) {
        hasMoreMessages.value = false
        return false
      }

      // 将旧消息插入到消息列表开头
      messages.value.unshift(...olderMessages)
      
      // 去重确保没有重复消息
      deduplicateMessages()
      
      // 如果返回的消息少于请求的条数，说明没有更多了
      if (olderMessages.length < 10) {
        hasMoreMessages.value = false
      }

      return true
    } catch (err) {
      console.error('Failed to load more messages:', err)
      return false
    } finally {
      loadingMore.value = false
    }
  }

  // 消息去重 - 确保没有重复 ID 的消息
  function deduplicateMessages() {
    const seen = new Set<string>()
    const unique: Message[] = []
    for (const msg of messages.value) {
      if (msg.id && !seen.has(msg.id)) {
        seen.add(msg.id)
        unique.push(msg)
      } else if (!msg.id) {
        // 没有 ID 的消息（如系统消息）保留
        unique.push(msg)
      } else {
        console.warn('[chatStore] Duplicate message removed:', msg.id)
      }
    }
    if (unique.length !== messages.value.length) {
      console.log('[chatStore] Deduplicated messages:', messages.value.length, '->', unique.length)
      messages.value = unique
    }
  }

  return {
    rooms,
    currentRoom,
    messages,
    loading,
    isConnected,
    hasMoreMessages,
    loadingMore,
    typingUserList,
    fetchRooms,
    createRoom,
    connect,
    disconnect,
    sendMessage,
    sendTyping,
    loadMoreMessages
  }
})
