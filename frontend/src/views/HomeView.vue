<template>
  <div class="home-view">
    <header class="header">
      <div class="logo">OOC</div>
      <div class="user-info">
        <router-link v-if="authStore.user?.roles?.includes('ROLE_ADMIN')" to="/admin" class="admin-link">管理</router-link>
        <span>{{ authStore.user?.username }}</span>
        <button @click="logout">退出</button>
      </div>
    </header>
    
    <div class="container">
      <!-- 左侧聊天室列表 -->
      <aside class="sidebar">
        <div class="section-header">
          <h2>聊天室</h2>
          <button class="btn-add" @click="showCreateDialog = true">+</button>
        </div>
        
        <div v-if="chatStore.loading" class="loading">加载中...</div>
        
        <ul v-else class="room-list">
          <li
            v-for="room in chatStore.rooms"
            :key="room.id"
            @click="enterRoom(room.id)"
            :class="['room-item', { active: currentRoomId === room.id }]"
          >
            <div class="room-name">{{ room.name }}</div>
            <div class="room-meta">{{ room.memberIds.length }} 成员</div>
          </li>
        </ul>
        
        <div v-if="chatStore.rooms.length === 0 && !chatStore.loading" class="empty">
          暂无聊天室，创建一个吧
        </div>
      </aside>
      
      <!-- 右侧内容区 -->
      <main class="main-content">
        <!-- 欢迎页面 -->
        <div v-if="!currentRoomId" class="welcome">
          <h2>欢迎使用 OOC</h2>
          <p>选择一个聊天室开始对话，或创建一个新的聊天室。</p>
          <p class="hint">
            提示：在群聊中使用 @openclaw 来召唤 AI 助手
          </p>
        </div>
        
        <!-- 聊天界面 -->
        <div v-else class="chat-container">
          <div class="chat-header">
            <div class="room-info">
              <h3>{{ chatStore.currentRoom?.name || '聊天室' }}</h3>
              <span :class="['status', { connected: chatStore.isConnected }]">
                {{ chatStore.isConnected ? '已连接' : '连接中...' }}
              </span>
            </div>
            <div class="chat-actions">
              <button v-if="isCreator" class="btn-danger" @click="confirmDismiss">解散</button>
              <button @click="showMembers = true">成员</button>
              <button @click="showSessions = true">会话</button>
            </div>
          </div>
          
          <div class="message-container" ref="messageContainer">
            <!-- 加载更多提示 -->
            <div v-if="chatStore.hasMoreMessages || chatStore.loadingMore" class="load-more-container">
              <button 
                v-if="!chatStore.loadingMore" 
                class="load-more-btn"
                @click="loadMoreMessages"
              >
                ↑ 加载更多历史消息
              </button>
              <div v-else class="load-more-loading">
                <span class="loading-spinner"></span>
                加载中...
              </div>
            </div>
            
            <template v-for="(msg, index) in chatStore.messages" :key="msg.id">
              <!-- 时间分隔线 -->
              <div v-if="shouldShowDateSeparator(index)" class="date-separator">
                <span>{{ formatDateSeparator(msg.timestamp) }}</span>
              </div>
              
              <!-- 系统消息 -->
              <div v-if="msg.isSystem" class="system-message">
                <span class="system-text">{{ msg.content }}</span>
              </div>
              
              <!-- 工具调用消息 -->
              <div v-else-if="msg.isToolCall || msg.toolCalls?.length" class="tool-call-message">
                <div class="tool-call-header">
                  <span class="tool-icon">🔧</span>
                  <span class="tool-title">工具调用</span>
                </div>
                <div class="tool-call-list">
                  <div 
                    v-for="tool in (msg.toolCalls || [])" 
                    :key="tool.id" 
                    :class="['tool-item', tool.status || 'completed']"
                  >
                    <div class="tool-name">
                      <span class="tool-icon-small">{{ getToolIcon(tool.name) }}</span>
                      <code>{{ tool.name }}</code>
                      <span v-if="tool.status === 'running'" class="tool-status running">
                        <span class="tool-spinner"></span>
                        运行中...
                      </span>
                      <span v-else-if="tool.status === 'completed'" class="tool-status completed">✓ 完成</span>
                      <span v-else class="tool-status completed">✓ 完成</span>
                    </div>
                    <div v-if="tool.description" class="tool-description">{{ formatToolDescription(tool.description) }}</div>
                    <div v-if="tool.result" class="tool-result">
                      <pre>{{ tool.result }}</pre>
                    </div>
                  </div>
                </div>
                <!-- 显示完整的回复内容 -->
                <div class="message-content tool-call-content" v-html="renderContent(msg)"></div>
              </div>
              
              <!-- OpenClaw 消息（可能包含工具调用） -->
              <div 
                v-else-if="msg.fromOpenClaw" 
                class="tool-call-message openclaw-message"
              >
                <!-- 如果有工具调用，显示工具块 -->
                <div v-if="msg.toolCalls?.length" class="tool-call-list">
                  <div 
                    v-for="tool in msg.toolCalls" 
                    :key="tool.id" 
                    :class="['tool-item', tool.status || 'completed']"
                  >
                    <div class="tool-name">
                      <span class="tool-icon-small">{{ getToolIcon(tool.name) }}</span>
                      <code>{{ tool.name }}</code>
                      <span v-if="tool.status === 'running'" class="tool-status running">
                        <span class="tool-spinner"></span>
                        运行中...
                      </span>
                      <span v-else class="tool-status completed">✓ 完成</span>
                    </div>
                    <div v-if="tool.description" class="tool-description">{{ formatToolDescription(tool.description) }}</div>
                  </div>
                </div>
                <!-- 显示回复内容 -->
                <div class="message-content tool-call-content" v-html="renderContent(msg)"></div>
              </div>
              
              <!-- 普通消息 -->
              <div
                v-else
                :class="[
                  'message',
                  {
                    'from-me': msg.senderId === authStore.user?.id,
                    'mentioned-me': isMentionedMe(msg)
                  }
                ]"
              >
                <!-- 头像 -->
                <div class="message-avatar">
                  <img v-if="msg.senderAvatar" :src="msg.senderAvatar" :alt="msg.senderName" />
                  <div v-else class="avatar-placeholder">{{ getInitials(msg.senderName) }}</div>
                </div>
                
                <div class="message-body">
                  <div class="message-header">
                    <span class="sender">{{ msg.senderName }}</span>
                    <span v-if="msg.mentionAll" class="mention-tag mention-all">@所有人</span>
                    <span v-else-if="msg.mentionHere" class="mention-tag mention-here">@在线</span>
                    <span class="time">{{ formatTime(msg.timestamp) }}</span>
                  </div>
                  <div class="message-content" v-html="renderContent(msg)"></div>
                  
                  <!-- 被@提示 -->
                  <div v-if="isMentionedMe(msg) && msg.senderId !== authStore.user?.id" class="mention-notice">
                    👤 @了你
                  </div>
                </div>
              </div>
            </template>
            
            <div v-if="chatStore.messages.length === 0" class="empty-messages">
              发送消息开始对话
              <br/>
              <span>在群聊中使用 @openclaw 召唤 AI</span>
            </div>
            
            <!-- 正在输入提示 -->
            <div v-if="chatStore.typingUserList.length > 0" class="typing-indicator">
              <span class="typing-dots">
                <span></span>
                <span></span>
                <span></span>
              </span>
              <span class="typing-text">{{ formatTypingUsers(chatStore.typingUserList) }}</span>
            </div>
          </div>
          
          <div class="input-area">
            <!-- 附件预览 -->
            <div v-if="attachments.length > 0" class="attachments-preview">
              <div v-for="(file, index) in attachments" :key="index" class="attachment-item">
                <img v-if="file.type === 'IMAGE'" :src="file.previewUrl" class="attachment-preview-img" />
                <div v-else class="attachment-file">
                  <span class="file-icon">📎</span>
                  <span class="file-name">{{ file.originalName }}</span>
                </div>
                <button class="remove-attachment" @click="removeAttachment(index)">×</button>
              </div>
            </div>
            
            <div class="input-wrapper">
              <input
                type="file"
                ref="fileInputRef"
                @change="handleFileSelect"
                accept="image/*,.pdf,.txt"
                style="display: none"
              />
              <button 
                class="attach-btn" 
                @click="fileInputRef?.click()"
                :disabled="isUploading"
                title="上传附件"
              >
                📎
              </button>
              <textarea
                v-model="inputMessage"
                @keydown="handleKeydown"
                @input="handleInput"
                @paste="handlePaste"
                :placeholder="isUploading ? '上传中...' : '输入消息... 使用 @ 提及他人'"
                rows="1"
                ref="inputRef"
                :disabled="isUploading"
              />
              <div class="send-section">
                <button
                  @click="sendMessage"
                  :disabled="(!inputMessage.trim() && attachments.length === 0) || !chatStore.isConnected || isUploading"
                >
                  {{ isUploading ? '上传中...' : '发送' }}
                </button>
                <span v-if="sendDisabledReason" class="send-disabled-hint">{{ sendDisabledReason }}</span>
              </div>
            </div>
            
            <!-- @提及下拉列表 -->
            <div v-if="showMentionList" class="mention-list" ref="mentionListRef">
              <div class="mention-list-header">
                <span v-if="mentionQuery">搜索 "{{ mentionQuery }}"</span>
                <span v-else>选择要@的人</span>
              </div>
              
              <!-- 快捷选项 -->
              <div class="mention-shortcuts">
                <div 
                  class="mention-item shortcut" 
                  :class="{ active: mentionSelectedIndex === 0 }"
                  @click="insertMentionOpenClaw"
                  @mouseenter="mentionSelectedIndex = 0"
                >
                  <span class="shortcut-icon">🤖</span>
                  <span>@openclaw</span>
                </div>
                <div 
                  class="mention-item shortcut" 
                  :class="{ active: mentionSelectedIndex === 1 }"
                  @click="insertMentionAll"
                  @mouseenter="mentionSelectedIndex = 1"
                >
                  <span class="shortcut-icon">👥</span>
                  <span>@所有人</span>
                </div>
                <div 
                  class="mention-item shortcut" 
                  :class="{ active: mentionSelectedIndex === 2 }"
                  @click="insertMentionHere"
                  @mouseenter="mentionSelectedIndex = 2"
                >
                  <span class="shortcut-icon">🟢</span>
                  <span>@在线</span>
                </div>
              </div>
              
              <!-- 分隔线 -->
              <div v-if="roomMembers.length > 0 || mentionQuery" class="mention-divider"></div>
              
              <!-- 加载中状态 -->
              <div v-if="roomMembers.length === 0 && !mentionQuery" class="mention-loading">
                正在加载成员列表...
              </div>
              
              <!-- 用户列表 -->
              <div
                v-for="(user, index) in filteredMentionUsers"
                :key="user.id"
                :class="['mention-item', { active: index + 3 === mentionSelectedIndex }]"
                @click="insertMention(user)"
                @mouseenter="mentionSelectedIndex = index + 3"
              >
                <img v-if="user.avatar" :src="user.avatar" class="mention-avatar" />
                <div v-else class="mention-avatar-placeholder">{{ getInitials(user.nickname || user.username) }}</div>
                <div class="mention-info">
                  <div class="mention-name">{{ user.nickname || user.username }}</div>
                  <div class="mention-username">@{{ user.username }}</div>
                </div>
                <span v-if="user.id === authStore.user?.id" class="mention-self">自己</span>
              </div>
              
              <div v-if="filteredMentionUsers.length === 0 && mentionQuery" class="mention-empty">
                未找到用户
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>
    
    <!-- 创建聊天室弹窗 -->
    <div v-if="showCreateDialog" class="modal" @click="showCreateDialog = false">
      <div class="modal-content" @click.stop>
        <h3>创建新聊天室</h3>
        
        <div class="form-group">
          <label>名称</label>
          <input v-model="newRoom.name" placeholder="聊天室名称" />
        </div>
        
        <div class="form-group">
          <label>描述（可选）</label>
          <input v-model="newRoom.description" placeholder="简要描述" />
        </div>
        
        <div class="modal-actions">
          <button @click="showCreateDialog = false">取消</button>
          <button @click="createRoom" :disabled="!newRoom.name">创建</button>
        </div>
      </div>
    </div>
    
    <!-- 解散群确认弹窗 -->
    <div v-if="showDismissDialog" class="modal" @click="showDismissDialog = false">
      <div class="modal-content" @click.stop>
        <h3>解散聊天室</h3>
        <p class="warning-text">
          确定要解散「{{ chatStore.currentRoom?.name }}」吗？<br/>
          <strong>此操作不可撤销</strong>，所有消息记录将被删除。
        </p>
        <div class="modal-actions">
          <button @click="showDismissDialog = false">取消</button>
          <button class="btn-danger" @click="dismissRoom">确认解散</button>
        </div>
      </div>
    </div>
    
    <MemberManager
      v-if="showMembers && currentRoomId && chatStore.currentRoom"
      :room-id="currentRoomId"
      :current-user-id="authStore.user?.username || ''"
      :creator-id="chatStore.currentRoom?.creatorId || ''"
      @close="showMembers = false"
      @update="chatStore.fetchRooms()"
    />
    
    <SessionManager 
      v-if="showSessions && currentRoomId" 
      :room-id="currentRoomId"
      @close="showSessions = false"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted, watch, nextTick, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import { chatRoomApi } from '@/api/chatRoom'
import SessionManager from '@/components/SessionManager.vue'
import MemberManager from '@/components/MemberManager.vue'
import { fileApi } from '@/api/file'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import type { MemberDto, Message, FileUploadResponse } from '@/types'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const chatStore = useChatStore()

const showCreateDialog = ref(false)
const newRoom = reactive({
  name: '',
  description: ''
})

// 聊天相关
const currentRoomId = computed(() => route.params.roomId as string | undefined)
const inputMessage = ref('')
const messageContainer = ref<HTMLDivElement>()
const inputRef = ref<HTMLTextAreaElement>()
const showMembers = ref(false)
const showSessions = ref(false)
const showDismissDialog = ref(false)

// @提及相关状态
const showMentionList = ref(false)
const mentionQuery = ref('')
const mentionSelectedIndex = ref(0)
const roomMembers = ref<MemberDto[]>([])
const mentionStartIndex = ref(-1)
const mentionListRef = ref<HTMLDivElement>()

// 文件上传相关
const fileInputRef = ref<HTMLInputElement>()
const attachments = ref<Array<FileUploadResponse & { previewUrl?: string }>>([])
const isUploading = ref(false)

// 是否为当前聊天室群主
const isCreator = computed(() => {
  return chatStore.currentRoom?.creatorId === authStore.user?.username
})

// 计算不能发送的原因
const sendDisabledReason = computed(() => {
  if (isUploading.value) {
    return '文件上传中，请稍候...'
  }
  if (!chatStore.isConnected) {
    return '未连接到服务器，请检查网络'
  }
  if (!inputMessage.value.trim() && attachments.value.length === 0) {
    return '请输入消息或上传附件'
  }
  return ''
})

// 所有可选项（快捷选项 + 用户）用于键盘导航
type MentionOption =
  | { type: 'shortcut'; key: 'openclaw' | 'all' | 'here'; label: string; icon: string }
  | { type: 'user'; user: MemberDto }

const allMentionOptions = computed<MentionOption[]>(() => {
  const options: MentionOption[] = [
    { type: 'shortcut', key: 'openclaw', label: '@openclaw', icon: '🤖' },
    { type: 'shortcut', key: 'all', label: '@所有人', icon: '👥' },
    { type: 'shortcut', key: 'here', label: '@在线', icon: '🟢' }
  ]
  filteredMentionUsers.value.forEach(user => {
    options.push({ type: 'user', user })
  })
  return options
})

// 过滤后的用户列表
const filteredMentionUsers = computed(() => {
  const members = roomMembers.value || []
  if (!mentionQuery.value) {
    const me = authStore.user
    return [...members].sort((a, b) => {
      if (a.id === me?.id) return 1
      if (b.id === me?.id) return -1
      return 0
    })
  }
  const query = mentionQuery.value.toLowerCase()
  return members.filter(user => {
    const nickname = (user.nickname || user.username || '').toLowerCase()
    const username = (user.username || '').toLowerCase()
    return nickname.includes(query) || username.includes(query)
  })
})

onMounted(async () => {
  await chatStore.fetchRooms()
  if (currentRoomId.value) {
    await chatStore.connect(currentRoomId.value)
    loadRoomMembers()
  }
  // 绑定滚动事件
  messageContainer.value?.addEventListener('scroll', handleScroll)
})

onUnmounted(() => {
  chatStore.disconnect()
  // 清理滚动事件和定时器
  messageContainer.value?.removeEventListener('scroll', handleScroll)
  if (scrollLoadDebounceTimer) {
    clearTimeout(scrollLoadDebounceTimer)
  }
})

// 监听路由变化，切换聊天室
watch(() => route.params.roomId, async (newRoomId) => {
  // 清理旧的事件绑定
  messageContainer.value?.removeEventListener('scroll', handleScroll)
  
  if (newRoomId) {
    chatStore.disconnect()
    await chatStore.connect(newRoomId as string)
    loadRoomMembers()
    // 新容器创建后绑定滚动事件
    nextTick(() => {
      messageContainer.value?.addEventListener('scroll', handleScroll)
    })
  } else {
    chatStore.disconnect()
    roomMembers.value = []
  }
})

// 监听消息变化，自动滚动到底部
watch(() => chatStore.messages.length, () => {
  nextTick(() => {
    scrollToBottom()
  })
})

async function loadRoomMembers() {
  if (!currentRoomId.value) return
  try {
    const response = await chatRoomApi.getMembers(currentRoomId.value)
    roomMembers.value = response.data || []
  } catch (err) {
    console.error('Failed to load room members:', err)
    roomMembers.value = []
  }
}

function enterRoom(roomId: string) {
  router.push(`/chat/${roomId}`)
}

async function createRoom() {
  await chatStore.createRoom(newRoom.name, newRoom.description)
  showCreateDialog.value = false
  newRoom.name = ''
  newRoom.description = ''
}

function logout() {
  authStore.logout()
  router.push('/login')
}

// 解散群
function confirmDismiss() {
  showDismissDialog.value = true
}

async function dismissRoom() {
  if (!currentRoomId.value) return
  
  try {
    await chatRoomApi.deleteRoom(currentRoomId.value)
    showDismissDialog.value = false
    router.push('/')
    await chatStore.fetchRooms()
  } catch (err) {
    console.error('Failed to dismiss room:', err)
    alert('解散群失败，请重试')
  }
}

// 聊天功能
function scrollToBottom() {
  if (messageContainer.value) {
    messageContainer.value.scrollTop = messageContainer.value.scrollHeight
  }
}

// 滚动位置记录，用于加载更多后保持位置
let scrollHeightBeforeLoad = 0
let scrollTopBeforeLoad = 0

// 防抖定时器
let scrollLoadDebounceTimer: number | null = null

// 处理滚动事件 - 当滚动到顶部附近时自动加载更多
function handleScroll() {
  if (!messageContainer.value) return
  
  const container = messageContainer.value
  // 当距离顶部小于 100px 且有更多消息时自动加载（增加阈值减少误触发）
  if (container.scrollTop < 100 && chatStore.hasMoreMessages && !chatStore.loadingMore) {
    // 防抖：避免快速滚动时多次触发
    if (scrollLoadDebounceTimer) {
      clearTimeout(scrollLoadDebounceTimer)
    }
    scrollLoadDebounceTimer = window.setTimeout(() => {
      // 再次检查条件，因为定时器期间状态可能变化
      if (messageContainer.value && messageContainer.value.scrollTop < 100) {
        loadMoreMessages()
      }
    }, 150)
  }
}

// 加载更多历史消息
async function loadMoreMessages() {
  if (!currentRoomId.value || !messageContainer.value) return
  
  // 记录当前滚动位置和高度
  const container = messageContainer.value
  scrollHeightBeforeLoad = container.scrollHeight
  scrollTopBeforeLoad = container.scrollTop
  
  // 暂时禁用滚动事件监听，防止加载过程中触发更多请求
  container.removeEventListener('scroll', handleScroll)
  
  const success = await chatStore.loadMoreMessages(currentRoomId.value)
  
  if (success) {
    // 加载完成后，在 nextTick 后恢复滚动位置
    nextTick(() => {
      const newContainer = messageContainer.value
      if (!newContainer) return
      
      const newScrollHeight = newContainer.scrollHeight
      const heightDiff = newScrollHeight - scrollHeightBeforeLoad
      
      // 恢复滚动位置：保持在同一视觉位置（新内容高度 + 原来的 scrollTop）
      newContainer.scrollTop = heightDiff + scrollTopBeforeLoad
      
      // 延迟重新绑定滚动事件，等待内容稳定（特别是图片加载）
      setTimeout(() => {
        newContainer.addEventListener('scroll', handleScroll)
        // 再次微调滚动位置，处理图片加载后的高度变化
        const finalHeightDiff = newContainer.scrollHeight - scrollHeightBeforeLoad
        if (Math.abs(finalHeightDiff - heightDiff) > 10) {
          newContainer.scrollTop = finalHeightDiff + scrollTopBeforeLoad
        }
      }, 100)
    })
  } else {
    // 加载失败也重新绑定事件
    nextTick(() => {
      messageContainer.value?.addEventListener('scroll', handleScroll)
    })
  }
}

function sendMessage() {
  const content = inputMessage.value.trim()
  if ((!content && attachments.value.length === 0) || !chatStore.isConnected || !currentRoomId.value) return

  // 转换附件格式以匹配 chatStore.sendMessage 期望的格式
  const chatAttachments = attachments.value.map(att => ({
    id: att.filename, // 使用文件名作为唯一标识
    dataUrl: att.previewUrl || att.url, // 使用预览URL或上传后的URL
    mimeType: att.contentType || 'image/png'
  }))

  // 发送消息（内容 + 附件）
  chatStore.sendMessage(content, chatAttachments)
  inputMessage.value = ''
  attachments.value = []
  showMentionList.value = false
  adjustTextareaHeight()
}

// 处理粘贴事件
async function handlePaste(event: ClipboardEvent) {
  const items = event.clipboardData?.items
  if (!items) return

  const imageFiles: File[] = []
  
  for (let i = 0; i < items.length; i++) {
    const item = items[i]
    if (item.type.startsWith('image/')) {
      const file = item.getAsFile()
      if (file) {
        imageFiles.push(file)
      }
    }
  }

  if (imageFiles.length === 0) return

  event.preventDefault()
  isUploading.value = true

  try {
    for (const file of imageFiles) {
      // 先上传文件到服务器
      const response = await fileApi.upload(file)
      // 使用上传后返回的 URL（服务器可访问），而不是本地 blob URL
      const previewUrl = response.data.url
      attachments.value.push({
        ...response.data,
        previewUrl
      })
    }
  } catch (err: any) {
    console.error('Paste upload failed:', err)
    alert('图片上传失败: ' + (err.response?.data?.message || err.message))
  } finally {
    isUploading.value = false
  }
}

// 文件处理
async function handleFileSelect(event: Event) {
  const target = event.target as HTMLInputElement
  const files = target.files
  if (!files || files.length === 0) return

  isUploading.value = true

  try {
    for (const file of Array.from(files)) {
      // 先上传文件到服务器
      const response = await fileApi.upload(file)
      // 使用上传后返回的 URL（服务器可访问），而不是本地 blob URL
      const previewUrl = response.data.url

      attachments.value.push({
        ...response.data,
        previewUrl
      })
    }
  } catch (err: any) {
    console.error('File upload failed:', err)
    alert('文件上传失败: ' + (err.response?.data?.message || err.message))
  } finally {
    isUploading.value = false
    // 清空input以允许重复选择同一文件
    if (fileInputRef.value) {
      fileInputRef.value.value = ''
    }
  }
}

function removeAttachment(index: number) {
  const attachment = attachments.value[index]
  if (attachment.previewUrl) {
    URL.revokeObjectURL(attachment.previewUrl)
  }
  attachments.value.splice(index, 1)
}

function handleKeydown(event: KeyboardEvent) {
  if (showMentionList.value) {
    const options = allMentionOptions.value
    
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault()
        mentionSelectedIndex.value = (mentionSelectedIndex.value + 1) % options.length
        scrollMentionIntoView()
        return
      case 'ArrowUp':
        event.preventDefault()
        mentionSelectedIndex.value = (mentionSelectedIndex.value - 1 + options.length) % options.length
        scrollMentionIntoView()
        return
      case 'Enter':
        event.preventDefault()
        const selectedOption = options[mentionSelectedIndex.value]
        if (selectedOption) {
          if (selectedOption.type === 'shortcut') {
            if (selectedOption.key === 'openclaw') {
              insertMentionOpenClaw()
            } else if (selectedOption.key === 'all') {
              insertMentionAll()
            } else {
              insertMentionHere()
            }
          } else {
            insertMention(selectedOption.user)
          }
        }
        return
      case 'Escape':
        showMentionList.value = false
        return
    }
  }
  
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    sendMessage()
  }
}

function scrollMentionIntoView() {
  nextTick(() => {
    const list = mentionListRef.value
    if (!list) return
    const items = list.querySelectorAll('.mention-item')
    const activeItem = items[mentionSelectedIndex.value] as HTMLElement
    if (activeItem) {
      activeItem.scrollIntoView({ block: 'nearest' })
    }
  })
}

function handleInput() {
  adjustTextareaHeight()
  
  // 发送正在输入状态
  chatStore.sendTyping()
  
  const text = inputMessage.value
  const cursorPos = inputRef.value?.selectionStart || 0
  
  // 查找光标前最近的 @
  const textBeforeCursor = text.slice(0, cursorPos)
  const lastAtIndex = textBeforeCursor.lastIndexOf('@')
  
  if (lastAtIndex >= 0) {
    // 检查 @ 和光标之间是否有空格
    const textBetween = textBeforeCursor.slice(lastAtIndex + 1)
    if (!textBetween.includes(' ')) {
      mentionStartIndex.value = lastAtIndex
      mentionQuery.value = textBetween
      showMentionList.value = true
      mentionSelectedIndex.value = 0
      return
    }
  }
  
  showMentionList.value = false
}

function insertMention(user: MemberDto) {
  const beforeMention = inputMessage.value.slice(0, mentionStartIndex.value)
  const afterCursor = inputMessage.value.slice(inputRef.value?.selectionStart || 0)
  inputMessage.value = beforeMention + '@' + (user.nickname || user.username) + ' ' + afterCursor
  showMentionList.value = false
  inputRef.value?.focus()
  
  nextTick(() => {
    const newPos = mentionStartIndex.value + (user.nickname || user.username).length + 2
    inputRef.value?.setSelectionRange(newPos, newPos)
  })
}

function insertMentionOpenClaw() {
  const beforeMention = inputMessage.value.slice(0, mentionStartIndex.value)
  const afterCursor = inputMessage.value.slice(inputRef.value?.selectionStart || 0)
  inputMessage.value = beforeMention + '@openclaw ' + afterCursor
  showMentionList.value = false
  inputRef.value?.focus()
}

function insertMentionAll() {
  const beforeMention = inputMessage.value.slice(0, mentionStartIndex.value)
  const afterCursor = inputMessage.value.slice(inputRef.value?.selectionStart || 0)
  inputMessage.value = beforeMention + '@所有人 ' + afterCursor
  showMentionList.value = false
  inputRef.value?.focus()
}

function insertMentionHere() {
  const beforeMention = inputMessage.value.slice(0, mentionStartIndex.value)
  const afterCursor = inputMessage.value.slice(inputRef.value?.selectionStart || 0)
  inputMessage.value = beforeMention + '@在线 ' + afterCursor
  showMentionList.value = false
  inputRef.value?.focus()
}

function adjustTextareaHeight() {
  if (inputRef.value) {
    inputRef.value.style.height = 'auto'
    inputRef.value.style.height = Math.min(inputRef.value.scrollHeight, 120) + 'px'
  }
}

function formatTime(timestamp: string) {
  const date = new Date(timestamp)
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function isMentionedMe(msg: Message): boolean {
  if (!authStore.user) return false
  if (msg.mentionAll) return true
  if (msg.mentions?.some(m => m.userId === authStore.user?.id)) return true
  return false
}

function renderContent(msg: Message) {
  // 防御性处理：确保 content 不为 null/undefined
  let content = msg.content || ''

  // 处理转义字符：将字符串 \n \t 转为真正的换行和制表符
  content = content.replace(/\\n/g, '\n').replace(/\\t/g, '\t')

  // Step 1: 渲染 Markdown（不进行 @提及替换，DOMPurify 会清理特殊标记）
  let htmlContent: string
  try {
    // 使用 marked.marked 进行同步解析（marked v17+）
    const parsed = (marked as any).marked?.(content) || marked.parse(content, { async: false })
    htmlContent = String(parsed)

    // 安全检查：如果解析结果看起来像 Promise 或没有 HTML 标签，使用 fallback
    if (htmlContent === '[object Promise]' || !htmlContent.includes('<')) {
      throw new Error('Invalid parsed content')
    }
  } catch (e) {
    // 解析失败时的 fallback
    htmlContent = content
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/\*(.+?)\*/g, '<em>$1</em>')
      .replace(/`(.+?)`/g, '<code>$1</code>')
      .replace(/~~(.+?)~~/g, '<del>$1</del>')
      .replace(/^### (.+)$/gm, '<h3>$1</h3>')
      .replace(/^## (.+)$/gm, '<h2>$1</h2>')
      .replace(/^# (.+)$/gm, '<h1>$1</h1>')
      .replace(/^- (.+)$/gm, '<li>$1</li>')
      .replace(/\n/g, '<br>')
  }

  // XSS 清理
  htmlContent = DOMPurify.sanitize(htmlContent, {
    ALLOWED_TAGS: [
      'p', 'br', 'hr',
      'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
      'ul', 'ol', 'li',
      'strong', 'em', 'code', 'pre', 'blockquote',
      'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td',
      'del', 'ins', 'sup', 'sub'
    ],
    ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'target', 'class']
  })

  // Step 2: 在 HTML 中查找并高亮 @提及（在 sanitization 之后进行）
  // 使用正则匹配文本节点中的 @提及
  htmlContent = htmlContent.replace(/(@所有人|@everyone|@all)/gi, '<span class="mention mention-all">$1</span>')
  htmlContent = htmlContent.replace(/(@在线|@here)/gi, '<span class="mention mention-here">$1</span>')
  htmlContent = htmlContent.replace(/(@openclaw)/gi, '<span class="mention">$1</span>')
  
  // 处理其他用户提及
  if (msg.mentions) {
    msg.mentions.forEach(mention => {
      const regex = new RegExp(`@${mention.userName}`, 'g')
      htmlContent = htmlContent.replace(regex, `<span class="mention">@${mention.userName}</span>`)
    })
  }

  // Step 4: 渲染附件图片
  let attachmentsHtml = ''
  if (msg.attachments && msg.attachments.length > 0) {
    attachmentsHtml = '<div class="message-attachments">' +
      msg.attachments.map(att => {
        // 更可靠的图片检测：检查 type、contentType 或 url
        const typeStr = (att.type || '').toUpperCase()
        const contentTypeStr = (att.contentType || '').toLowerCase()
        const urlStr = (att.url || '').toLowerCase()

        // 多种方式检测图片
        const isImage = typeStr === 'IMAGE' ||
                       contentTypeStr.startsWith('image/') ||
                       urlStr.startsWith('data:image/') ||
                       urlStr.endsWith('.png') ||
                       urlStr.endsWith('.jpg') ||
                       urlStr.endsWith('.jpeg') ||
                       urlStr.endsWith('.gif') ||
                       urlStr.endsWith('.webp')

        if (isImage) {
          return `<img src="${att.url}" alt="${att.name || '图片'}" class="message-image" loading="lazy" />`
        }
        return `<a href="${att.url}" target="_blank" class="message-file">${att.name || '附件'}</a>`
      }).join('') +
      '</div>'
  }

  return htmlContent + attachmentsHtml
}

function getInitials(name: string): string {
  return name.slice(0, 2).toUpperCase()
}

// 获取工具图标
function getToolIcon(toolName: string): string {
  const iconMap: Record<string, string> = {
    'read': '📄',
    'write': '✏️',
    'edit': '🔧',
    'exec': '⚡',
    'web_search': '🔍',
    'weather': '🌤️',
    'browser': '🌐',
    'canvas': '🎨',
    'nodes': '📱',
    'cron': '⏰',
    'message': '💬',
    'gateway': '🔌',
    'sessions_spawn': '🚀',
    'memory_search': '🧠',
    'tts': '🔊',
    'github': '🐙',
    'gh': '🐙',
  }
  return iconMap[toolName] || '🔧'
}

// 格式化工具描述（截断过长的参数）
function formatToolDescription(description: string): string {
  if (!description) return ''
  // 如果是 JSON 格式的参数，尝试解析并简化
  try {
    const parsed = JSON.parse(description)
    // 提取关键信息
    const keys = Object.keys(parsed)
    if (keys.length === 0) return ''
    
    // 优先显示 file_path 或 command
    if (parsed.file_path) {
      const path = parsed.file_path as string
      // 截断过长的路径
      if (path.length > 60) {
        return '...' + path.slice(-57)
      }
      return path
    }
    if (parsed.command) {
      const cmd = parsed.command as string
      if (cmd.length > 60) {
        return cmd.slice(0, 57) + '...'
      }
      return cmd
    }
    if (parsed.query) {
      return `搜索: ${parsed.query}`
    }
    
    // 显示第一个非空值
    for (const key of keys) {
      const value = parsed[key]
      if (value && typeof value === 'string') {
        if (value.length > 60) {
          return `${key}: ${value.slice(0, 57)}...`
        }
        return `${key}: ${value}`
      }
    }
  } catch (e) {
    // 不是 JSON，直接返回并截断
    if (description.length > 80) {
      return description.slice(0, 77) + '...'
    }
  }
  return description
}

// 格式化正在输入提示
function formatTypingUsers(users: string[]): string {
  if (users.length === 1) {
    return `${users[0]} 正在输入...`
  } else if (users.length === 2) {
    return `${users[0]} 和 ${users[1]} 正在输入...`
  } else {
    return `${users.slice(0, 2).join('、')} 等 ${users.length} 人正在输入...`
  }
}

// 判断是否需要显示日期分隔线
function shouldShowDateSeparator(index: number): boolean {
  if (index === 0) return true
  const current = new Date(chatStore.messages[index].timestamp)
  const prev = new Date(chatStore.messages[index - 1].timestamp)
  return !isSameDay(current, prev)
}

// 格式化日期分隔线
function formatDateSeparator(timestamp: string): string {
  const date = new Date(timestamp)
  const now = new Date()
  const yesterday = new Date(now)
  yesterday.setDate(yesterday.getDate() - 1)

  if (isSameDay(date, now)) {
    return '今天'
  } else if (isSameDay(date, yesterday)) {
    return '昨天'
  } else {
    return date.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric' })
  }
}

// 判断是否同一天
function isSameDay(d1: Date, d2: Date): boolean {
  return d1.getFullYear() === d2.getFullYear() &&
         d1.getMonth() === d2.getMonth() &&
         d1.getDate() === d2.getDate()
}
</script>

<style scoped>
.home-view {
  height: 100vh;
  display: flex;
  flex-direction: column;
}

.header {
  height: 60px;
  background: var(--surface-color);
  border-bottom: 1px solid var(--border-color);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 1.5rem;
}

.logo {
  font-size: 1.5rem;
  font-weight: bold;
  color: var(--primary-color);
}

.user-info {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.user-info button {
  padding: 0.5rem 1rem;
  background: transparent;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  cursor: pointer;
}

.admin-link {
  padding: 0.5rem 1rem;
  background: var(--primary-color);
  color: white;
  text-decoration: none;
  border-radius: 6px;
  font-size: 0.875rem;
}

.container {
  flex: 1;
  display: flex;
  overflow: hidden;
}

/* 左侧边栏 */
.sidebar {
  width: 280px;
  background: var(--bg-color);
  border-right: 1px solid var(--border-color);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.section-header {
  padding: 1rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--border-color);
}

.section-header h2 {
  font-size: 1rem;
  color: var(--text-primary);
}

.btn-add {
  width: 28px;
  height: 28px;
  background: var(--primary-color);
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 1.25rem;
  display: flex;
  align-items: center;
  justify-content: center;
}

.room-list {
  list-style: none;
  overflow-y: auto;
  flex: 1;
}

.room-item {
  padding: 1rem;
  cursor: pointer;
  border-bottom: 1px solid var(--border-color);
  transition: background 0.2s;
}

.room-item:hover {
  background: var(--surface-color);
}

.room-item.active {
  background: var(--surface-color);
  border-left: 3px solid var(--primary-color);
}

.room-name {
  font-weight: 500;
  color: var(--text-primary);
  margin-bottom: 0.25rem;
}

.room-meta {
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.loading, .empty {
  padding: 2rem;
  text-align: center;
  color: var(--text-secondary);
}

/* 右侧内容区 */
.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--surface-color);
  overflow: hidden;
}

/* 欢迎页面 */
.welcome {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  max-width: 400px;
  margin: 0 auto;
}

.welcome h2 {
  color: var(--text-primary);
  margin-bottom: 1rem;
}

.welcome p {
  color: var(--text-secondary);
  line-height: 1.6;
}

.hint {
  margin-top: 1rem;
  padding: 0.75rem;
  background: var(--bg-color);
  border-radius: 6px;
  font-size: 0.875rem;
}

/* 聊天界面 */
.chat-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-header {
  height: 60px;
  background: var(--surface-color);
  border-bottom: 1px solid var(--border-color);
  display: flex;
  align-items: center;
  padding: 0 1rem;
  gap: 1rem;
  flex-shrink: 0;
}

.room-info {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.room-info h3 {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
}

.status {
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.status.connected {
  color: #22c55e;
}

.chat-actions {
  display: flex;
  gap: 0.5rem;
}

.chat-actions button {
  padding: 0.5rem 0.75rem;
  background: transparent;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 0.875rem;
  cursor: pointer;
}

.chat-actions button.btn-danger {
  background: #ef4444;
  color: white;
  border-color: #ef4444;
}

.chat-actions button.btn-danger:hover {
  background: #dc2626;
}

.message-container {
  flex: 1;
  overflow-y: auto;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

/* 加载更多消息 */
.load-more-container {
  display: flex;
  justify-content: center;
  padding: 0.5rem 0;
  min-height: 40px;
}

.load-more-btn {
  padding: 0.5rem 1rem;
  background: var(--bg-color);
  border: 1px solid var(--border-color);
  border-radius: 20px;
  font-size: 0.875rem;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s;
}

.load-more-btn:hover {
  background: var(--surface-color);
  border-color: var(--primary-color);
  color: var(--primary-color);
}

.load-more-loading {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.loading-spinner {
  display: inline-block;
  width: 16px;
  height: 16px;
  border: 2px solid var(--border-color);
  border-top-color: var(--primary-color);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.message {
  display: flex;
  gap: 0.75rem;
  max-width: 80%;
  min-width: 0;
  align-self: flex-start;
}

.message.from-me {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.message.mentioned-me .message-body {
  background: #fef3c7;
  border: 2px solid #f59e0b;
}

.message.from-me.mentioned-me .message-body {
  background: var(--primary-color);
  border: 2px solid #f59e0b;
}

.message-avatar {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
}

.message-avatar img {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  object-fit: cover;
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  background: var(--primary-color);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.875rem;
  font-weight: 600;
}

.message-body {
  background: var(--bg-color);
  padding: 0.75rem 1rem;
  border-radius: 12px;
  min-width: 0;
  flex: 1;
}

.message.from-me .message-body {
  background: var(--primary-color);
  color: white;
}

.message.from-openclaw .message-body {
  background: #e0e7ff;
  border: 1px solid var(--primary-color);
}

.message-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.25rem;
  font-size: 0.75rem;
}

.message.from-me .message-header {
  color: rgba(255,255,255,0.8);
}

.message:not(.from-me) .message-header {
  color: var(--text-secondary);
}

.sender {
  font-weight: 500;
}

.mention-tag {
  font-size: 0.625rem;
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 600;
}

.mention-tag.mention-all {
  background: #fef3c7;
  color: #92400e;
}

.mention-tag.mention-here {
  background: #dbeafe;
  color: #1e40af;
}

.message.from-me .mention-tag.mention-all,
.message.from-me .mention-tag.mention-here {
  background: rgba(255,255,255,0.3);
  color: white;
}

.message-content {
  line-height: 1.5;
  word-break: break-word;
  overflow-wrap: break-word;
  max-width: 100%;
  min-width: 0;
}

/* 代码块样式 - 严格防止溢出 */
.message-content :deep(pre) {
  max-width: 100%;
  width: 100%;
  overflow-x: auto;
  white-space: pre-wrap !important;
  word-wrap: break-word !important;
  word-break: break-all !important;
  box-sizing: border-box;
}

.message-content :deep(pre code) {
  white-space: pre-wrap !important;
  word-wrap: break-word !important;
  word-break: break-all !important;
  display: block;
  max-width: 100%;
}

.message-content :deep(code) {
  word-wrap: break-word;
  white-space: pre-wrap;
  word-break: break-all;
  max-width: 100%;
}

/* 确保所有子元素不溢出 */
.message-content :deep(*) {
  max-width: 100%;
  box-sizing: border-box;
}

/* 特别处理可能溢出的元素 */
.message-content :deep(p),
.message-content :deep(div),
.message-content :deep(span) {
  max-width: 100%;
  word-break: break-word;
}

.message-content :deep(.mention) {
  color: var(--primary-color);
  font-weight: 500;
  background: rgba(59, 130, 246, 0.1);
  padding: 0 2px;
  border-radius: 3px;
}

.message-content :deep(.mention.mention-all) {
  color: #f59e0b;
  background: rgba(245, 158, 11, 0.1);
}

.message-content :deep(.mention.mention-here) {
  color: #3b82f6;
  background: rgba(59, 130, 246, 0.1);
}

.message.from-me .message-content :deep(.mention) {
  color: rgba(255,255,255,0.95);
  background: rgba(255,255,255,0.2);
}

/* 消息中的图片 */
.message-content :deep(.message-image) {
  margin-top: 0.5rem;
  max-width: 100%;
  max-height: 300px;
  border-radius: 8px;
  cursor: pointer;
  transition: transform 0.2s;
  object-fit: contain;
}

.message-content :deep(.message-image:hover) {
  transform: scale(1.02);
}

/* 文件链接 */
.message-content :deep(.file-link) {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  background: var(--bg-color);
  border-radius: 6px;
  color: var(--primary-color);
  text-decoration: none;
  font-size: 0.875rem;
  margin-top: 0.25rem;
}

.message-content :deep(.file-link:hover) {
  background: rgba(59, 130, 246, 0.1);
}

.message.from-me .message-content :deep(.file-link) {
  background: rgba(255,255,255,0.2);
  color: white;
}

.mention-notice {
  font-size: 0.75rem;
  color: #f59e0b;
  margin-top: 0.5rem;
  font-weight: 500;
}

.message.from-me .mention-notice {
  color: rgba(255,255,255,0.9);
}

.empty-messages {
  text-align: center;
  color: var(--text-secondary);
  padding: 3rem 1rem;
}

.empty-messages span {
  display: block;
  margin-top: 0.5rem;
  font-size: 0.875rem;
}

/* 系统消息 */
.system-message {
  text-align: center;
  padding: 0.5rem 1rem;
  margin: 0.5rem 0;
}

.system-text {
  font-size: 0.75rem;
  color: var(--text-secondary);
  background: var(--bg-color);
  padding: 0.25rem 0.75rem;
  border-radius: 12px;
}

/* 工具调用消息 */
.tool-call-message {
  background: var(--bg-color);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 1rem;
  margin: 0.5rem 1rem;
  max-width: 80%;
  align-self: flex-start;
}

.tool-call-content {
  margin-top: 0.75rem;
  padding-top: 0.75rem;
  border-top: 1px solid var(--border-color);
}

.tool-call-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
  padding-bottom: 0.5rem;
  border-bottom: 1px solid var(--border-color);
}

.tool-icon {
  font-size: 1rem;
}

.tool-title {
  font-weight: 600;
  font-size: 0.875rem;
  color: var(--text-primary);
}

.tool-call-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.tool-item {
  background: var(--surface-color);
  border-radius: 8px;
  padding: 0.75rem;
  border-left: 3px solid var(--border-color);
  transition: all 0.2s ease;
}

.tool-item.running {
  border-left-color: #3b82f6;
  background: rgba(59, 130, 246, 0.05);
  animation: tool-pulse 2s infinite;
}

@keyframes tool-pulse {
  0%, 100% {
    background: rgba(59, 130, 246, 0.05);
  }
  50% {
    background: rgba(59, 130, 246, 0.1);
  }
}

.tool-item.completed {
  border-left-color: #22c55e;
}

.tool-item.error {
  border-left-color: #ef4444;
  background: rgba(239, 68, 68, 0.05);
}

.tool-name {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
  margin-bottom: 0.25rem;
}

.tool-icon-small {
  font-size: 0.9rem;
  margin-right: 0.25rem;
}

.tool-name code {
  background: rgba(0, 0, 0, 0.08);
  padding: 0.125rem 0.375rem;
  border-radius: 4px;
  font-family: monospace;
  font-size: 0.8rem;
  font-weight: 600;
}

.tool-spinner {
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 2px solid rgba(59, 130, 246, 0.3);
  border-top-color: #3b82f6;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin-right: 4px;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.tool-status {
  font-size: 0.75rem;
  padding: 0.125rem 0.5rem;
  border-radius: 4px;
  margin-left: auto;
  display: flex;
  align-items: center;
  font-weight: 500;
}

.tool-status.running {
  color: #3b82f6;
  background: rgba(59, 130, 246, 0.1);
}

.tool-status.completed {
  color: #22c55e;
  background: rgba(34, 197, 94, 0.1);
}

.tool-status.error {
  color: #ef4444;
  background: rgba(239, 68, 68, 0.1);
}

.tool-description {
  font-size: 0.8125rem;
  color: var(--text-secondary);
  margin-top: 0.25rem;
  font-family: monospace;
  word-break: break-all;
}

.tool-result {
  margin-top: 0.5rem;
  padding: 0.5rem;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 6px;
  overflow-x: auto;
}

.tool-result pre {
  font-family: monospace;
  font-size: 0.75rem;
  color: var(--text-secondary);
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

/* OpenClaw 消息样式 */
.openclaw-message {
  background: linear-gradient(135deg, #f0f9ff 0%, #e0f2fe 100%);
  border: 1px solid #bae6fd;
}

.openclaw-message .tool-call-content {
  border-top-color: #bae6fd;
}

.openclaw-message .tool-item {
  background: rgba(255, 255, 255, 0.8);
}

/* 时间分隔线 */
.date-separator {
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 1rem 0;
  position: relative;
}

.date-separator::before {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  height: 1px;
  background: var(--border-color);
}

.date-separator span {
  position: relative;
  background: var(--surface-color);
  padding: 0 1rem;
  font-size: 0.75rem;
  color: var(--text-secondary);
  z-index: 1;
}

/* 正在输入提示 */
.typing-indicator {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  margin-top: 0.5rem;
  color: var(--text-secondary);
  font-size: 0.875rem;
}

.typing-dots {
  display: flex;
  gap: 3px;
}

.typing-dots span {
  width: 6px;
  height: 6px;
  background: var(--text-secondary);
  border-radius: 50%;
  animation: typing-bounce 1.4s infinite ease-in-out both;
}

.typing-dots span:nth-child(1) {
  animation-delay: -0.32s;
}

.typing-dots span:nth-child(2) {
  animation-delay: -0.16s;
}

@keyframes typing-bounce {
  0%, 80%, 100% {
    transform: scale(0);
  }
  40% {
    transform: scale(1);
  }
}

.typing-text {
  font-style: italic;
}

.input-area {
  border-top: 1px solid var(--border-color);
  padding: 1rem;
  flex-shrink: 0;
  position: relative;
}

/* 附件预览 */
.attachments-preview {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
  padding: 0.5rem;
  background: var(--bg-color);
  border-radius: 8px;
}

.attachment-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.25rem 0.5rem;
  background: var(--surface-color);
  border-radius: 6px;
  border: 1px solid var(--border-color);
}

.attachment-preview-img {
  width: 48px;
  height: 48px;
  object-fit: cover;
  border-radius: 4px;
}

.attachment-file {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.file-icon {
  font-size: 1.25rem;
}

.file-name {
  font-size: 0.75rem;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-secondary);
}

.remove-attachment {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: none;
  background: var(--border-color);
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 0.875rem;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-left: 0.25rem;
}

.remove-attachment:hover {
  background: #ef4444;
  color: white;
}

.attach-btn {
  width: 44px;
  height: 44px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--surface-color);
  cursor: pointer;
  font-size: 1.25rem;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.attach-btn:hover:not(:disabled) {
  background: var(--bg-color);
  border-color: var(--primary-color);
}

.attach-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.input-wrapper {
  display: flex;
  gap: 0.5rem;
  align-items: flex-end;
}

textarea {
  flex: 1;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  resize: none;
  font-size: 1rem;
  font-family: inherit;
  min-height: 44px;
  max-height: 120px;
}

textarea:disabled {
  background: var(--bg-color);
  cursor: not-allowed;
}

textarea:focus {
  outline: none;
  border-color: var(--primary-color);
}

.input-wrapper button {
  padding: 0.75rem 1.5rem;
  background: var(--primary-color);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 0.875rem;
  cursor: pointer;
  transition: background 0.2s;
  height: 44px;
}

.input-wrapper button:hover:not(:disabled) {
  background: var(--primary-hover);
}

.input-wrapper button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 发送区域 */
.send-section {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 0.25rem;
}

.send-disabled-hint {
  font-size: 0.75rem;
  color: var(--text-secondary);
  max-width: 120px;
  text-align: right;
  line-height: 1.3;
}

/* @提及下拉列表 */
.mention-list {
  position: absolute;
  bottom: 100%;
  left: 1rem;
  right: 1rem;
  max-height: 300px;
  background: var(--surface-color);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  box-shadow: 0 -4px 20px rgba(0,0,0,0.15);
  margin-bottom: 0.5rem;
  overflow-y: auto;
  z-index: 100;
}

.mention-list-header {
  padding: 0.75rem 1rem;
  font-size: 0.75rem;
  color: var(--text-secondary);
  border-bottom: 1px solid var(--border-color);
  background: rgba(0,0,0,0.02);
}

.mention-shortcuts {
  background: rgba(59, 130, 246, 0.05);
}

.mention-divider {
  height: 1px;
  background: var(--border-color);
  margin: 0;
}

.mention-item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  cursor: pointer;
  transition: background 0.15s;
  border-bottom: 1px solid var(--border-color);
}

.mention-item:last-child {
  border-bottom: none;
}

.mention-item:hover,
.mention-item.active {
  background: var(--bg-color);
}

.mention-item.shortcut {
  padding: 0.5rem 1rem;
}

.shortcut-icon {
  font-size: 1rem;
}

.mention-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  object-fit: cover;
}

.mention-avatar-placeholder {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--primary-color);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.75rem;
  font-weight: 600;
}

.mention-info {
  flex: 1;
}

.mention-name {
  font-weight: 500;
  font-size: 0.875rem;
}

.mention-username {
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.mention-self {
  font-size: 0.625rem;
  color: var(--text-secondary);
  background: var(--bg-color);
  padding: 2px 6px;
  border-radius: 4px;
}

.mention-empty {
  padding: 1rem;
  text-align: center;
  color: var(--text-secondary);
  font-size: 0.875rem;
}

.mention-loading {
  padding: 1rem;
  text-align: center;
  color: var(--text-secondary);
  font-size: 0.875rem;
  font-style: italic;
}

/* 弹窗 */
.modal {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.modal-content {
  background: var(--surface-color);
  padding: 1.5rem;
  border-radius: 12px;
  width: 90%;
  max-width: 400px;
}

.modal-content h3 {
  margin-bottom: 1rem;
  color: var(--text-primary);
}

.form-group {
  margin-bottom: 1rem;
}

.form-group label {
  display: block;
  margin-bottom: 0.5rem;
  font-size: 0.875rem;
  color: var(--text-primary);
}

.form-group input {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 1rem;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
  margin-top: 1.5rem;
}

.modal-actions button {
  padding: 0.5rem 1rem;
  border-radius: 6px;
  cursor: pointer;
  font-size: 0.875rem;
}

.modal-actions button:first-child {
  background: transparent;
  border: 1px solid var(--border-color);
}

.modal-actions button:last-child {
  background: var(--primary-color);
  color: white;
  border: none;
}

.modal-actions button.btn-danger {
  background: #ef4444 !important;
}

.modal-actions button.btn-danger:hover {
  background: #dc2626 !important;
}

.warning-text {
  color: var(--text-secondary);
  line-height: 1.6;
  margin: 1rem 0;
}

.warning-text strong {
  color: #ef4444;
}

/* ============================================
   移动端适配 - Mobile Responsive Styles
   ============================================ */

@media (max-width: 768px) {
  /* 整体布局调整 */
  .home-view {
    height: 100dvh; /* 使用动态视口高度 */
  }

  .header {
    height: 56px;
    padding: 0 1rem;
  }

  .logo {
    font-size: 1.25rem;
  }

  .user-info {
    gap: 0.5rem;
    font-size: 0.875rem;
  }

  .user-info button {
    padding: 0.375rem 0.75rem;
    font-size: 0.8125rem;
  }

  .admin-link {
    padding: 0.375rem 0.75rem;
    font-size: 0.8125rem;
  }

  /* 容器布局 - 移动端侧边栏变为顶部导航 */
  .container {
    flex-direction: column;
  }

  /* 侧边栏变为横向滚动 */
  .sidebar {
    width: 100%;
    height: auto;
    max-height: 160px;
    border-right: none;
    border-bottom: 1px solid var(--border-color);
    flex-shrink: 0;
  }

  .section-header {
    padding: 0.75rem 1rem;
  }

  .section-header h2 {
    font-size: 0.9375rem;
  }

  .btn-add {
    width: 24px;
    height: 24px;
    font-size: 1rem;
  }

  /* 聊天室列表横向滚动 */
  .room-list {
    display: flex;
    flex-direction: row;
    overflow-x: auto;
    overflow-y: hidden;
    padding: 0.5rem;
    gap: 0.5rem;
    flex-wrap: nowrap;
    -webkit-overflow-scrolling: touch;
    scrollbar-width: none; /* Firefox */
  }

  .room-list::-webkit-scrollbar {
    display: none; /* Chrome/Safari */
  }

  .room-item {
    flex: 0 0 auto;
    min-width: 120px;
    max-width: 160px;
    padding: 0.625rem 0.875rem;
    border-bottom: none;
    border-radius: 10px;
    border: 1px solid var(--border-color);
    margin-right: 0;
  }

  .room-item.active {
    border-left: none;
    border: 2px solid var(--primary-color);
    background: rgba(59, 130, 246, 0.08);
  }

  .room-name {
    font-size: 0.875rem;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .room-meta {
    font-size: 0.6875rem;
  }

  .loading, .empty {
    padding: 1rem;
    font-size: 0.875rem;
  }

  /* 主内容区 */
  .main-content {
    flex: 1;
    min-height: 0;
  }

  /* 欢迎页面 */
  .welcome {
    padding: 1rem;
  }

  .welcome h2 {
    font-size: 1.25rem;
  }

  .welcome p {
    font-size: 0.9375rem;
  }

  .hint {
    font-size: 0.8125rem;
    padding: 0.625rem;
  }

  /* 聊天容器 */
  .chat-container {
    height: 100%;
  }

  .chat-header {
    height: 52px;
    padding: 0 0.75rem;
  }

  .room-info h3 {
    font-size: 0.9375rem;
  }

  .chat-actions {
    gap: 0.375rem;
  }

  .chat-actions button {
    padding: 0.375rem 0.625rem;
    font-size: 0.8125rem;
  }

  /* 消息容器 */
  .message-container {
    padding: 0.75rem;
    gap: 0.75rem;
    padding-bottom: calc(0.75rem + env(safe-area-inset-bottom, 0px));
  }

  .message {
    max-width: 90%;
    gap: 0.5rem;
  }

  .message-avatar {
    width: 36px;
    height: 36px;
  }

  .avatar-placeholder {
    font-size: 0.8125rem;
  }

  .message-body {
    padding: 0.625rem 0.875rem;
    border-radius: 10px;
  }

  .message-header {
    font-size: 0.6875rem;
    gap: 0.375rem;
  }

  .mention-tag {
    font-size: 0.5625rem;
    padding: 1px 4px;
  }

  .message-content {
    font-size: 0.9375rem;
    line-height: 1.5;
  }

  /* 系统消息 */
  .system-message {
    padding: 0.375rem 0;
  }

  .system-text {
    font-size: 0.6875rem;
    padding: 0.25rem 0.625rem;
  }

  /* 工具调用消息 */
  .tool-call-message {
    max-width: 95%;
    padding: 0.75rem;
    margin: 0.375rem 0.5rem;
  }

  .tool-icon {
    font-size: 0.875rem;
  }

  .tool-title {
    font-size: 0.8125rem;
  }

  .tool-name {
    font-size: 0.8125rem;
  }

  .tool-name code {
    font-size: 0.75rem;
  }

  .tool-status {
    font-size: 0.6875rem;
  }

  .tool-description {
    font-size: 0.75rem;
  }

  .tool-result pre {
    font-size: 0.6875rem;
  }

  /* 日期分隔线 */
  .date-separator {
    margin: 0.75rem 0;
  }

  .date-separator span {
    font-size: 0.6875rem;
    padding: 0 0.75rem;
  }

  /* 正在输入提示 */
  .typing-indicator {
    padding: 0.375rem 0.75rem;
    font-size: 0.8125rem;
  }

  /* 输入区域 */
  .input-area {
    padding: 0.625rem 0.75rem;
    padding-bottom: calc(0.625rem + env(safe-area-inset-bottom, 8px));
    border-top: 1px solid var(--border-color);
    background: var(--surface-color);
  }

  /* 附件预览 */
  .attachments-preview {
    padding: 0.375rem;
    margin-bottom: 0.5rem;
    max-height: 80px;
  }

  .attachment-item {
    padding: 0.25rem 0.375rem;
  }

  .attachment-preview-img {
    width: 40px;
    height: 40px;
  }

  .file-name {
    max-width: 100px;
    font-size: 0.6875rem;
  }

  .remove-attachment {
    width: 18px;
    height: 18px;
    font-size: 0.75rem;
  }

  /* 输入框区域 */
  .input-wrapper {
    gap: 0.375rem;
  }

  .attach-btn {
    width: 40px;
    height: 40px;
    font-size: 1.125rem;
  }

  textarea {
    padding: 0.625rem 0.75rem;
    font-size: 16px; /* 防止 iOS 自动缩放 */
    min-height: 40px;
    max-height: 100px;
    border-radius: 10px;
  }

  .input-wrapper button {
    padding: 0.625rem 1rem;
    font-size: 0.8125rem;
    height: 40px;
    border-radius: 10px;
  }

  .send-disabled-hint {
    font-size: 0.6875rem;
    max-width: 100px;
  }

  /* @提及列表 */
  .mention-list {
    left: 0.5rem;
    right: 0.5rem;
    max-height: 240px;
    border-radius: 10px;
  }

  .mention-list-header {
    padding: 0.625rem 0.875rem;
    font-size: 0.6875rem;
  }

  .mention-item {
    padding: 0.625rem 0.875rem;
  }

  .mention-avatar,
  .mention-avatar-placeholder {
    width: 28px;
    height: 28px;
  }

  .mention-avatar-placeholder {
    font-size: 0.6875rem;
  }

  .mention-name {
    font-size: 0.8125rem;
  }

  .mention-username {
    font-size: 0.6875rem;
  }

  .mention-self {
    font-size: 0.5625rem;
    padding: 1px 4px;
  }

  .shortcut-icon {
    font-size: 0.875rem;
  }

  /* 弹窗 */
  .modal-content {
    padding: 1.25rem;
    width: 92%;
    border-radius: 14px;
    margin: 1rem;
  }

  .modal-content h3 {
    font-size: 1.125rem;
  }

  .form-group label {
    font-size: 0.8125rem;
  }

  .form-group input {
    padding: 0.625rem;
    font-size: 16px; /* 防止 iOS 缩放 */
  }

  .modal-actions {
    margin-top: 1.25rem;
  }

  .modal-actions button {
    padding: 0.5rem 0.875rem;
    font-size: 0.8125rem;
  }
}

/* 小屏手机额外优化 */
@media (max-width: 380px) {
  .header {
    height: 52px;
  }

  .logo {
    font-size: 1.125rem;
  }

  .user-info span {
    display: none; /* 超小屏隐藏用户名 */
  }

  .sidebar {
    max-height: 140px;
  }

  .room-item {
    min-width: 100px;
    padding: 0.5rem 0.75rem;
  }

  .chat-header {
    height: 48px;
  }

  .chat-actions button {
    padding: 0.375rem 0.5rem;
    font-size: 0.75rem;
  }

  .message {
    max-width: 92%;
  }

  .message-avatar {
    width: 32px;
    height: 32px;
  }

  .message-body {
    padding: 0.5rem 0.75rem;
  }

  .message-content {
    font-size: 0.875rem;
  }

  .attach-btn {
    width: 36px;
    height: 36px;
    font-size: 1rem;
  }

  textarea {
    padding: 0.5rem 0.625rem;
  }

  .input-wrapper button {
    padding: 0.5rem 0.875rem;
    height: 36px;
  }

  .send-disabled-hint {
    display: none;
  }
}

/* 横屏模式优化 */
@media (max-height: 500px) and (orientation: landscape) {
  .header {
    height: 48px;
  }

  .sidebar {
    max-height: 100px;
  }

  .chat-header {
    height: 44px;
  }

  .input-area {
    padding: 0.5rem 0.75rem;
  }

  .message-container {
    padding: 0.5rem;
  }
}
</style>
