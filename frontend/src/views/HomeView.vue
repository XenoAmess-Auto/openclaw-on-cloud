<template>
  <div class="home-view">
    <header class="header">
      <div class="logo">OOC</div>
      <div class="user-info">
        <router-link v-if="authStore.user?.roles?.includes('ROLE_ADMIN')" to="/admin" class="admin-link">管理</router-link>
        <router-link to="/flowchart/templates" class="flowchart-link">流程图</router-link>
        <router-link to="/settings" class="settings-link">设置</router-link>
        <span>{{ authStore.user?.nickname || authStore.user?.username }}</span>
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
              <button @click="showTaskQueue = true">队列</button>
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
              
              <!-- 系统消息（排除 Flowbot 消息） -->
              <div v-if="!!msg.isSystem && msg.senderName !== 'Flowbot'" class="system-message">
                <span class="system-text">{{ msg.content }}</span>
              </div>
              
              <!-- Flowbot 结果消息（带变量展开/折叠） -->
              <div v-else-if="isFlowbotResultMessage(msg)" :id="'msg-' + msg.id" class="message flowbot-message">
                <div class="message-avatar">
                  <div class="avatar-placeholder">{{ msg.senderAvatar || '🤖' }}</div>
                </div>
                <div class="message-body flowbot-body">
                  <div class="message-header">
                    <span class="sender flowbot-sender">{{ msg.senderName }}</span>
                    <span class="time">{{ formatTime(msg.timestamp) }}</span>
                  </div>
                  <div class="message-content flowbot-content" v-html="renderContent(msg)"></div>
                  
                  <!-- 展开/折叠按钮 -->
                  <button class="flowbot-toggle-btn" @click="toggleFlowbotVariables(msg.id)">
                    {{ expandedFlowbotMessages.has(msg.id) ? '🔽 隐藏变量' : '🔼 查看变量' }}
                  </button>
                  
                  <!-- 变量列表（展开时显示） -->
                  <div v-if="expandedFlowbotMessages.has(msg.id)" class="flowbot-variables">
                    <div v-for="(value, key) in decodeFlowbotVariables(msg)" :key="key" class="flowbot-variable">
                      <span class="var-name">{{ key }}:</span>
                      <pre class="var-value">{{ formatVariableValue(value) }}</pre>
                    </div>
                  </div>
                </div>
              </div>
              
              <!-- Flowbot 普通消息 -->
              <div v-else-if="msg.senderName === 'Flowbot'" :id="'msg-' + msg.id" class="message flowbot-message">
                <div class="message-avatar">
                  <div class="avatar-placeholder">{{ msg.senderAvatar || '🤖' }}</div>
                </div>
                <div class="message-body flowbot-body">
                  <div class="message-header">
                    <span class="sender flowbot-sender">{{ msg.senderName }}</span>
                    <span class="time">{{ formatTime(msg.timestamp) }}</span>
                  </div>
                  <div class="message-content flowbot-content">{{ msg.content }}</div>
                </div>
              </div>
              
              <!-- OpenClaw 消息（包含工具调用） -->
              <template v-else-if="msg.fromOpenClaw">
                <!-- 使用段落式渲染 - 按位置顺序显示文本和工具调用 -->
                <template v-for="(segment, _segIndex) in renderSegments(msg)" :key="segment.type + _segIndex">
                  <div :id="'msg-' + msg.id" class="message openclaw-message-container">
                    <div class="message-avatar">
                      <img v-if="msg.senderAvatar" :src="msg.senderAvatar" :alt="msg.senderName" />
                      <div v-else class="avatar-placeholder">🤖</div>
                    </div>
                    <div class="message-body openclaw-body">
                      <div class="message-header">
                        <span class="sender">{{ msg.senderName }}</span>
                        <span class="time">{{ formatTime(msg.timestamp) }}</span>
                        <span v-if="msg.id" class="message-id" title="Message ID">{{ msg.id.slice(-6) }}</span>
                        <span v-if="msg.replyToMessageId" class="reply-to-id clickable" :title="'点击跳转到消息: ' + msg.replyToMessageId" @click="scrollToMessage(msg.replyToMessageId!)">↩ {{ msg.replyToMessageId.slice(-6) }}</span>
                      </div>
                      <div class="message-content" v-html="segment.html"></div>
                    </div>
                  </div>
                </template>
              </template>
              
              <!-- 纯工具调用消息（不含 fromOpenClaw） -->
              <div v-else-if="msg.isToolCall || msg.toolCalls?.length" :id="'msg-' + msg.id" class="tool-call-message">
                <div v-if="msg.id || msg.replyToMessageId" class="tool-call-header">
                  <span v-if="msg.id" class="message-id" title="Message ID">{{ msg.id.slice(-6) }}</span>
                  <span v-if="msg.replyToMessageId" class="reply-to-id clickable" :title="'点击跳转到消息: ' + msg.replyToMessageId" @click="scrollToMessage(msg.replyToMessageId!)">↩ {{ msg.replyToMessageId.slice(-6) }}</span>
                </div>
                <div class="message-content tool-call-content" v-html="renderContent(msg)"></div>
              </div>
              
              <!-- 普通消息 -->
              <div
                v-else
                :id="'msg-' + msg.id"
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
                  <img v-if="getMessageAvatar(msg)" :src="getMessageAvatar(msg)" :alt="msg.senderName" />
                  <div v-else class="avatar-placeholder">{{ getInitials(msg.senderName) }}</div>
                </div>
                
                <div class="message-body">
                  <div class="message-header">
                    <span class="sender">{{ msg.senderName }}</span>
                    <span v-if="msg.mentionAll" class="mention-tag mention-all">@所有人</span>
                    <span v-else-if="msg.mentionHere" class="mention-tag mention-here">@在线</span>
                    <span class="time">{{ formatTime(msg.timestamp) }}</span>
                    <span v-if="msg.id" class="message-id" title="Message ID">{{ msg.id.slice(-6) }}</span>
                    <span v-if="msg.replyToMessageId" class="reply-to-id clickable" :title="'点击跳转到消息: ' + msg.replyToMessageId" @click="scrollToMessage(msg.replyToMessageId!)">↩ {{ msg.replyToMessageId.slice(-6) }}</span>
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
              <VoiceInput @send="handleVoiceSend" />
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
    
    <TaskQueuePanel
      v-if="showTaskQueue && currentRoomId"
      :room-id="currentRoomId"
      :visible="showTaskQueue"
      @close="showTaskQueue = false"
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
import VoiceInput from '@/components/VoiceInput.vue'
import TaskQueuePanel from '@/components/TaskQueuePanel.vue'
import { fileApi } from '@/api/file'
import { getBaseUrl } from '@/utils/config'
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
const showTaskQueue = ref(false)
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

// Flowbot 消息展开状态管理
const expandedFlowbotMessages = ref<Set<string>>(new Set())

// 检查消息是否是 Flowbot 结果消息
function isFlowbotResultMessage(msg: Message): boolean {
  return msg.senderName === 'Flowbot' && 
         !!(msg.attachments?.some(att => att.type === 'FLOWCHART_VARIABLES'))
}

// 切换 Flowbot 消息展开状态
function toggleFlowbotVariables(messageId: string) {
  if (expandedFlowbotMessages.value.has(messageId)) {
    expandedFlowbotMessages.value.delete(messageId)
  } else {
    expandedFlowbotMessages.value.add(messageId)
  }
}

// 解码 Flowbot 变量数据
function decodeFlowbotVariables(msg: Message): Record<string, any> | null {
  const varsAttachment = msg.attachments?.find(att => att.type === 'FLOWCHART_VARIABLES')
  if (!varsAttachment?.url) return null
  
  try {
    // 从 data:application/json;base64,xxx 格式中提取 base64 数据
    const base64Match = varsAttachment.url.match(/base64,(.+)/)
    if (!base64Match) return null
    
    const jsonStr = atob(base64Match[1])
    return JSON.parse(jsonStr)
  } catch (e) {
    console.error('Failed to decode flowbot variables:', e)
    return null
  }
}

// 格式化变量值为字符串
function formatVariableValue(value: any): string {
  if (value === null) return 'null'
  if (value === undefined) return 'undefined'
  if (typeof value === 'object') return JSON.stringify(value, null, 2)
  return String(value)
}

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

// 滚动到指定消息
function scrollToMessage(messageId: string) {
  if (!messageContainer.value) return
  
  const targetElement = document.getElementById('msg-' + messageId)
  if (!targetElement) {
    console.warn('Message not found:', messageId)
    // 可以在这里添加提示：消息不存在或已被删除
    return
  }
  
  // 高亮目标消息
  targetElement.classList.add('highlight-message')
  
  // 滚动到目标消息
  targetElement.scrollIntoView({ behavior: 'smooth', block: 'center' })
  
  // 3秒后移除高亮效果
  setTimeout(() => {
    targetElement.classList.remove('highlight-message')
  }, 3000)
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
  console.log('[sendMessage] attachments count:', attachments.value.length)
  console.log('[sendMessage] attachments:', attachments.value.map(a => ({ filename: a.filename, url: a.url, previewUrl: a.previewUrl })))
  if ((!content && attachments.value.length === 0) || !chatStore.isConnected || !currentRoomId.value) return

  // 转换附件格式以匹配 chatStore.sendMessage 期望的格式
  const chatAttachments = attachments.value.map(att => ({
    id: att.filename, // 使用文件名作为唯一标识
    dataUrl: att.previewUrl || att.url, // 使用预览URL或上传后的URL
    mimeType: att.contentType || 'image/png'
  }))
  console.log('[sendMessage] chatAttachments:', chatAttachments)

  // 发送消息（内容 + 附件）
  chatStore.sendMessage(content, chatAttachments)
  inputMessage.value = ''
  attachments.value = []
  showMentionList.value = false
  adjustTextareaHeight()
}

// 处理语音输入发送
function handleVoiceSend(text: string) {
  if (!text.trim() || !chatStore.isConnected || !currentRoomId.value) return
  
  // 发送语音识别的文本
  chatStore.sendMessage(text.trim(), [])
  showMentionList.value = false
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
      // 使用上传后返回的 URL（可能是相对路径），转换为完整 URL
      const previewUrl = resolveFileUrl(response.data.url)
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

// 文件处理 - 将相对 URL 转换为完整 URL
function resolveFileUrl(url: string): string {
  if (url.startsWith('http://') || url.startsWith('https://')) {
    return url
  }
  // 相对路径，拼接 baseUrl
  const baseUrl = getBaseUrl()
  return baseUrl + url
}

async function handleFileSelect(event: Event) {
  const target = event.target as HTMLInputElement
  const files = target.files
  if (!files || files.length === 0) return

  isUploading.value = true

  try {
    for (const file of Array.from(files)) {
      // 先上传文件到服务器
      const response = await fileApi.upload(file)
      // 使用上传后返回的 URL（可能是相对路径），转换为完整 URL
      const previewUrl = resolveFileUrl(response.data.url)

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

  // 检查是否有工具调用部分，如果有，先提取并转换
  let toolCallsHtml = ''
  
  // 优先使用 msg.toolCalls 数据（来自实时 tool_start 事件或后端解析）
  if (msg.toolCalls && msg.toolCalls.length > 0) {
    // 生成工具调用卡片 HTML - 新卡片样式
    toolCallsHtml = generateToolCallsHtml(msg.toolCalls)
    
    // 从 content 中移除 Tools used 部分，避免重复显示
    const toolsMatch = content.match(/(\*\*Tools used:\*\*.*?)(?=\n\n|$)/s)
    if (toolsMatch) {
      content = content.replace(toolsMatch[0], '\n<!--TOOL_CALLS_PLACEHOLDER-->\n')
    } else {
      // 如果没有找到 Tools used 部分，在内容前插入占位符
      content = '<!--TOOL_CALLS_PLACEHOLDER-->\n\n' + content
    }
  } else {
    // 回退：从内容中解析 **Tools used:** 部分
    const toolsMatch = content.match(/(\*\*Tools used:\*\*.*?)(?=\n\n|$)/s)
    if (toolsMatch) {
      const toolsSection = toolsMatch[1]
      // 解析工具列表
      const toolLines = toolsSection.split('\n').slice(1) // 跳过标题行
      const tools: Array<{name: string, desc: string}> = []
      
      for (const line of toolLines) {
        const match = line.match(/^[-*]\s*`?(\w+)`?\s*:?\s*(.*)/)
        if (match) {
          tools.push({ name: match[1], desc: match[2] || '' })
        }
      }
      
      if (tools.length > 0) {
        // 生成工具调用卡片 HTML - 新卡片样式
        toolCallsHtml = generateToolCallsHtmlFromArray(tools)
        
        // 从 content 中移除 Tools used 部分，后面会插入卡片
        content = content.replace(toolsMatch[0], '\n<!--TOOL_CALLS_PLACEHOLDER-->\n')
      }
    }
  }

  // Step 1: 渲染 Markdown（不进行 @提及替换，DOMPurify 会清理特殊标记）
  let htmlContent = renderMarkdown(content)

  // XSS 清理
  htmlContent = DOMPurify.sanitize(htmlContent, {
    ALLOWED_TAGS: [
      'p', 'br', 'hr',
      'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
      'ul', 'ol', 'li',
      'strong', 'em', 'code', 'pre', 'blockquote',
      'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td',
      'del', 'ins', 'sup', 'sub',
      // 工具卡片相关标签
      'div', 'span'
    ],
    ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'target', 'class']
  })

  // 插入工具调用卡片（替换占位符）
  if (toolCallsHtml) {
    // 尝试替换占位符，如果不存在则直接插入到开头
    if (htmlContent.includes('TOOL_CALLS_PLACEHOLDER')) {
      htmlContent = htmlContent.replace(/&lt;!--TOOL_CALLS_PLACEHOLDER--&gt;/g, toolCallsHtml)
      htmlContent = htmlContent.replace(/<!--TOOL_CALLS_PLACEHOLDER-->/g, toolCallsHtml)
    } else {
      // 占位符被清理了，直接插入到开头
      htmlContent = toolCallsHtml + '\n' + htmlContent
    }
  }

  // Step 2: 在 HTML 中查找并高亮 @提及（在 sanitization 之后进行）
  htmlContent = highlightMentions(htmlContent, msg)

  // Step 4: 渲染附件图片
  let attachmentsHtml = renderAttachments(msg)

  return htmlContent + attachmentsHtml
}



// 生成工具调用卡片 HTML（从 toolCalls 数组）
function generateToolCallsHtml(toolCalls: Message['toolCalls']): string {
  if (!toolCalls || toolCalls.length === 0) return ''

  return `<div class="tool-call-section">
    <div class="tool-call-list">
      ${toolCalls.map(tool => `
        <div class="tool-item ${tool.status || 'completed'}">
          <div class="tool-item-header">
            <span class="tool-icon-small">${getToolIcon(tool.name)}</span>
            <span class="tool-name"><code>${tool.name}</code></span>
          </div>
          ${tool.description ? `<div class="tool-item-body">
            <div class="tool-description">${formatToolDescription(tool.name, tool.description)}</div>
          </div>` : ''}
          ${tool.result ? `<div class="tool-item-body">
            <div class="tool-result"><pre>${escapeHtml(tool.result)}</pre></div>
          </div>` : ''}
        </div>
      `).join('')}
    </div>
  </div>`
}

// 生成工具调用卡片 HTML（从解析的工具数组）
function generateToolCallsHtmlFromArray(tools: Array<{name: string, desc: string}>): string {
  if (!tools || tools.length === 0) return ''

  return `<div class="tool-call-section">
    <div class="tool-call-list">
      ${tools.map(tool => `
        <div class="tool-item completed">
          <div class="tool-item-header">
            <span class="tool-icon-small">${getToolIcon(tool.name)}</span>
            <span class="tool-name"><code>${tool.name}</code></span>
          </div>
          ${tool.desc ? `<div class="tool-item-body"><div class="tool-description">${escapeHtml(tool.desc)}</div></div>` : ''}
        </div>
      `).join('')}
    </div>
  </div>`
}

// 渲染 Markdown
function renderMarkdown(content: string): string {
  try {
    // 使用 marked.marked 进行同步解析（marked v17+）
    const parsed = (marked as any).marked?.(content) || marked.parse(content, { async: false })
    const htmlContent = String(parsed)

    // 安全检查：如果解析结果看起来像 Promise 或没有 HTML 标签，使用 fallback
    if (htmlContent === '[object Promise]' || !htmlContent.includes('<')) {
      throw new Error('Invalid parsed content')
    }
    return htmlContent
  } catch (e) {
    // 解析失败时的 fallback
    return content
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
}

// 高亮 @提及
function highlightMentions(htmlContent: string, msg: Message): string {
  // 使用正则匹配文本节点中的 @提及
  htmlContent = htmlContent.replace(/(@所有人|@everyone|@all)/gi, '<span class="mention mention-all">$1</span>')
  htmlContent = htmlContent.replace(/(@在线|@here)/gi, '<span class="mention mention-here">$1</span>')
  htmlContent = htmlContent.replace(/(@openclaw)/gi, '<span class="mention">$1</span>')

  // 处理其他用户提及（来自后端解析的 mentions 数组）
  if (msg.mentions) {
    msg.mentions.forEach(mention => {
      const regex = new RegExp(`@${mention.userName}`, 'g')
      htmlContent = htmlContent.replace(regex, `<span class="mention">@${mention.userName}</span>`)
    })
  }

  // 对房间中所有成员和机器人的 @提及添加特效
  // 这样可以覆盖手动输入的 @提及（即使后端没有正确解析到 mentions 数组）
  roomMembers.value.forEach(member => {
    const displayName = member.nickname || member.username
    if (displayName && displayName !== 'openclaw') {
      // 使用否定前瞻确保不会重复包裹已经处理过的提及
      const regex = new RegExp(`(?<!<span class="mention">)@${escapeRegExp(displayName)}`, 'g')
      htmlContent = htmlContent.replace(regex, `<span class="mention">@${displayName}</span>`)
    }
  })

  // 也对当前用户（如果不在 roomMembers 中）添加特效
  const currentUserName = authStore.user?.nickname || authStore.user?.username
  if (currentUserName && currentUserName !== 'openclaw') {
    const regex = new RegExp(`(?<!<span class="mention">)@${escapeRegExp(currentUserName)}`, 'g')
    htmlContent = htmlContent.replace(regex, `<span class="mention">@${currentUserName}</span>`)
  }

  return htmlContent
}

// 转义正则特殊字符
function escapeRegExp(string: string): string {
  return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

// 渲染附件图片
function renderAttachments(msg: Message): string {
  if (!msg.attachments || msg.attachments.length === 0) {
    return ''
  }

  return '<div class="message-attachments">' +
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

      // 将相对 URL 转换为完整 URL
      const fullUrl = resolveFileUrl(att.url || '')

      if (isImage) {
        return `<img src="${fullUrl}" alt="${att.name || '图片'}" class="message-image" loading="lazy" />`
      }
      return `<a href="${fullUrl}" target="_blank" class="message-file">${att.name || '附件'}</a>`
    }).join('') +
    '</div>'
}

// 按位置顺序渲染段落（工具调用和文本交替显示）
function renderSegments(msg: Message): Array<{ type: 'text' | 'tools', html: string }> {
  const segments: Array<{ type: 'text' | 'tools', html: string }> = []
  
  if (!msg.content && (!msg.toolCalls || msg.toolCalls.length === 0)) {
    return segments
  }
  
  // 按位置排序工具调用
  const sortedToolCalls = [...(msg.toolCalls || [])].sort((a, b) => {
    const posA = a.position ?? Infinity
    const posB = b.position ?? Infinity
    return posA - posB
  })
  
  // 获取纯文本内容（移除 Tools used 部分）
  let content = msg.content || ''
  content = content.replace(/\\n/g, '\n').replace(/\\t/g, '\t')
  const toolsMatch = content.match(/(\*\*Tools used:\*\*.*?)(?=\n\n|$)/s)
  if (toolsMatch) {
    content = content.replace(toolsMatch[0], '')
  }
  
  // 如果没有工具调用或没有位置信息，按原来的方式渲染
  if (sortedToolCalls.length === 0 || sortedToolCalls[0].position === undefined) {
    // 先渲染工具调用（如果有）
    if (msg.toolCalls?.length) {
      segments.push({
        type: 'tools',
        html: generateToolCallsHtml(msg.toolCalls)
      })
    }
    // 再渲染文本（如果有）
    if (content.trim()) {
      let htmlContent = renderMarkdown(content)
      htmlContent = DOMPurify.sanitize(htmlContent, {
        ALLOWED_TAGS: ['p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'strong', 'em', 'code', 'pre', 'blockquote', 'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'del', 'ins', 'sup', 'sub', 'div', 'span'],
        ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'target', 'class']
      })
      htmlContent = highlightMentions(htmlContent, msg)
      htmlContent += renderAttachments(msg)
      segments.push({ type: 'text', html: htmlContent })
    }
    return segments
  }
  
  // 按位置分段渲染
  let lastPosition = 0
  
  for (const toolCall of sortedToolCalls) {
    const position = toolCall.position ?? 0
    
    // 渲染此工具调用之前的文本
    if (position > lastPosition) {
      const textSegment = content.substring(lastPosition, position)
      if (textSegment.trim()) {
        let htmlContent = renderMarkdown(textSegment)
        htmlContent = DOMPurify.sanitize(htmlContent, {
          ALLOWED_TAGS: ['p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'strong', 'em', 'code', 'pre', 'blockquote', 'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'del', 'ins', 'sup', 'sub', 'div', 'span'],
          ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'target', 'class']
        })
        htmlContent = highlightMentions(htmlContent, msg)
        segments.push({ type: 'text', html: htmlContent })
      }
    }
    
    // 渲染工具调用
    segments.push({
      type: 'tools',
      html: generateToolCallsHtml([toolCall])
    })
    
    lastPosition = position
  }
  
  // 渲染最后一个工具调用之后的文本
  if (lastPosition < content.length) {
    const textSegment = content.substring(lastPosition)
    if (textSegment.trim()) {
      let htmlContent = renderMarkdown(textSegment)
      htmlContent = DOMPurify.sanitize(htmlContent, {
        ALLOWED_TAGS: ['p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'strong', 'em', 'code', 'pre', 'blockquote', 'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'del', 'ins', 'sup', 'sub', 'div', 'span'],
        ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'target', 'class']
      })
      htmlContent = highlightMentions(htmlContent, msg)
      htmlContent += renderAttachments(msg)
      segments.push({ type: 'text', html: htmlContent })
    }
  }
  
  return segments
}

// HTML 转义
function escapeHtml(text: string): string {
  const div = document.createElement('div')
  div.textContent = text
  return div.innerHTML
}

// 格式化工具描述（美化 exec 等工具的显示）
function formatToolDescription(toolName: string, description: string): string {
  if (!description) return ''
  
  // 对于 exec 工具，提取并格式化命令
  if (toolName === 'exec' || toolName === ' Exec') {
    // 尝试提取 command 参数
    const cmdMatch = description.match(/command=["'](.+?)["']/s)
    if (cmdMatch) {
      const cmd = cmdMatch[1].replace(/\\"/g, '"').replace(/\\n/g, '\n')
      // 截断过长的命令
      const displayCmd = cmd.length > 200 ? cmd.substring(0, 200) + '...' : cmd
      return `<div class="exec-command">
        <div class="exec-label">命令</div>
        <pre class="exec-code">${escapeHtml(displayCmd)}</pre>
      </div>`
    }
  }
  
  // 对于 web_search，高亮搜索词
  if (toolName === 'web_search') {
    const queryMatch = description.match(/query=["'](.+?)["']/)
    if (queryMatch) {
      return `<span class="search-query">🔍 ${escapeHtml(queryMatch[1])}</span>`
    }
  }
  
  // 对于 read/write/edit，显示文件路径
  if (['read', 'write', 'edit'].includes(toolName)) {
    const pathMatch = description.match(/path=["'](.+?)["']/)
    if (pathMatch) {
      return `<span class="file-path">📄 ${escapeHtml(pathMatch[1])}</span>`
    }
  }
  
  // 默认返回转义后的描述
  return escapeHtml(description)
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

// 获取消息头像 - 如果是当前用户，使用当前用户的最新头像
function getMessageAvatar(msg: Message): string | undefined {
  if (msg.senderId === authStore.user?.id) {
    return authStore.user?.avatar || msg.senderAvatar
  }
  return msg.senderAvatar
}

function getInitials(name: string): string {
  return name.slice(0, 2).toUpperCase()
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
  height: 100dvh; /* 动态视口高度，适配移动端 */
  display: flex;
  flex-direction: column;
  /* 移动端安全区域适配 - 避免与状态栏、灵动岛、导航栏重叠 */
  padding-top: env(safe-area-inset-top);
  padding-bottom: env(safe-area-inset-bottom);
  padding-left: env(safe-area-inset-left);
  padding-right: env(safe-area-inset-right);
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

.flowchart-link {
  padding: 0.5rem 1rem;
  background: var(--bg-tertiary);
  color: var(--text-primary);
  text-decoration: none;
  border-radius: 6px;
  font-size: 0.875rem;
  border: 1px solid var(--border-color);
  transition: background 0.2s;
}

.flowchart-link:hover {
  background: var(--border-color);
}

.settings-link {
  padding: 0.5rem 1rem;
  background: var(--bg-tertiary);
  color: var(--text-primary);
  text-decoration: none;
  border-radius: 6px;
  font-size: 0.875rem;
  border: 1px solid var(--border-color);
  transition: background 0.2s;
}

.settings-link:hover {
  background: var(--border-color);
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

.message-id, .reply-to-id {
  font-size: 0.625rem;
  color: var(--text-secondary);
  background: var(--bg-color);
  padding: 1px 4px;
  border-radius: 3px;
  font-family: 'SF Mono', monospace;
  opacity: 0.7;
  cursor: help;
}

.message-id:hover, .reply-to-id:hover {
  opacity: 1;
}

.reply-to-id {
  background: #e0e7ff;
  color: #4f46e5;
}

.reply-to-id.clickable {
  cursor: pointer;
  transition: all 0.2s ease;
}

.reply-to-id.clickable:hover {
  background: #4f46e5;
  color: white;
  opacity: 1;
}

/* 消息高亮动画 */
@keyframes message-highlight {
  0% {
    box-shadow: 0 0 0 0 rgba(79, 70, 229, 0.7);
  }
  50% {
    box-shadow: 0 0 0 8px rgba(79, 70, 229, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(79, 70, 229, 0);
  }
}

.highlight-message {
  animation: message-highlight 1s ease-out;
  border-radius: 12px;
}

.highlight-message .message-body {
  background: linear-gradient(135deg, #e0e7ff 0%, #c7d2fe 100%) !important;
  transition: background 0.3s ease;
}

.highlight-message.from-me .message-body {
  background: linear-gradient(135deg, #6366f1 0%, #4f46e5 100%) !important;
}

.message.from-me .message-id {
  background: rgba(255,255,255,0.2);
  color: rgba(255,255,255,0.9);
}

.message.from-me .reply-to-id {
  background: rgba(255,255,255,0.25);
  color: rgba(255,255,255,0.95);
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

/* Flowbot 消息样式 */
.flowbot-message {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border-radius: 12px;
  padding: 0.75rem 1rem;
  margin: 0.5rem 0;
  max-width: 85%;
  align-self: flex-start;
}

.flowbot-body {
  background: rgba(255, 255, 255, 0.15);
  border-radius: 8px;
  padding: 0.75rem;
}

.flowbot-sender {
  color: #fff !important;
  font-weight: 600;
}

.flowbot-content {
  color: #fff;
  white-space: pre-wrap;
}

.flowbot-content :deep(p) {
  color: #fff;
  margin: 0.5rem 0;
}

.flowbot-content :deep(strong) {
  color: #ffd700;
}

.flowbot-toggle-btn {
  margin-top: 0.75rem;
  padding: 0.375rem 0.75rem;
  background: rgba(255, 255, 255, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.3);
  border-radius: 6px;
  color: #fff;
  font-size: 0.8rem;
  cursor: pointer;
  transition: all 0.2s;
}

.flowbot-toggle-btn:hover {
  background: rgba(255, 255, 255, 0.3);
}

.flowbot-variables {
  margin-top: 0.75rem;
  padding: 0.75rem;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 8px;
  max-height: 300px;
  overflow-y: auto;
}

.flowbot-variable {
  margin: 0.5rem 0;
  padding: 0.5rem;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 4px;
}

.var-name {
  font-weight: 600;
  color: #90caf9;
}

.var-value {
  margin: 0.25rem 0 0 0;
  padding: 0.5rem;
  background: rgba(0, 0, 0, 0.3);
  border-radius: 4px;
  font-size: 0.8rem;
  color: #e0e0e0;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
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

/* 工具调用消息 - 使用 :deep() 确保 v-html 内容也能应用样式 */
.tool-call-message {
  background: var(--bg-color);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 1rem;
  margin: 0.5rem 1rem;
  max-width: 80%;
  align-self: flex-start;
}

:deep(.tool-call-content) {
  margin-top: 0.75rem;
  padding-top: 0.75rem;
  border-top: 1px solid var(--border-color);
}

:deep(.tool-call-header) {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
  padding-bottom: 0.5rem;
  border-bottom: 1px solid var(--border-color);
}

.tool-call-message > .tool-call-header {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
  padding-bottom: 0.375rem;
  border-bottom: 1px solid var(--border-color);
}

.tool-call-message > .tool-call-header .reply-to-id.clickable {
  cursor: pointer;
  transition: all 0.2s ease;
}

.tool-call-message > .tool-call-header .reply-to-id.clickable:hover {
  background: #4f46e5;
  color: white;
  opacity: 1;
}

:deep(.tool-icon) {
  font-size: 1.1rem;
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #3b82f6, #6366f1);
  border-radius: 6px;
  color: white;
}

:deep(.tool-title) {
  font-weight: 600;
  font-size: 0.875rem;
  color: var(--text-primary);
}

:deep(.tool-call-list) {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

/* 工具卡片样式 */
:deep(.tool-item) {
  background: white;
  border-radius: 12px;
  padding: 0;
  border: 1px solid #e5e7eb;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
  transition: all 0.2s ease;
  overflow: hidden;
}

:deep(.tool-item:hover) {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  transform: translateY(-2px);
}

:deep(.tool-item.running) {
  border-color: #3b82f6;
  box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.2);
  animation: tool-pulse 2s infinite;
}

@keyframes tool-pulse {
  0%, 100% {
    box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.2);
  }
  50% {
    box-shadow: 0 0 0 4px rgba(59, 130, 246, 0.3);
  }
}

:deep(.tool-item.completed) {
  border-color: #22c55e;
}

:deep(.tool-item.completed:hover) {
  box-shadow: 0 4px 12px rgba(34, 197, 94, 0.2);
}

:deep(.tool-item.error) {
  border-color: #ef4444;
  background: #fef2f2;
}

:deep(.tool-item.error:hover) {
  box-shadow: 0 4px 12px rgba(239, 68, 68, 0.2);
}

/* 工具卡片头部 */
:deep(.tool-item-header) {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem;
  background: linear-gradient(135deg, #f8fafc, #f1f5f9);
  border-bottom: 1px solid #e5e7eb;
}

:deep(.tool-item.running .tool-item-header) {
  background: linear-gradient(135deg, #eff6ff, #dbeafe);
}

:deep(.tool-item.completed .tool-item-header) {
  background: linear-gradient(135deg, #f0fdf4, #dcfce7);
}

:deep(.tool-item.error .tool-item-header) {
  background: linear-gradient(135deg, #fef2f2, #fee2e2);
}

:deep(.tool-icon-small) {
  font-size: 1rem;
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: white;
  border-radius: 6px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
}

:deep(.tool-name) {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
  font-weight: 600;
  color: #1f2937;
  flex: 1;
}

:deep(.tool-name code) {
  background: rgba(0, 0, 0, 0.08);
  padding: 0.125rem 0.375rem;
  border-radius: 4px;
  font-family: 'SF Mono', monospace;
  font-size: 0.8rem;
  font-weight: 600;
  color: #4b5563;
}

:deep(.tool-status) {
  font-size: 0.75rem;
  padding: 0.25rem 0.5rem;
  border-radius: 20px;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 0.25rem;
}

:deep(.tool-status.running) {
  color: #3b82f6;
  background: rgba(59, 130, 246, 0.15);
}

:deep(.tool-status.completed) {
  color: #22c55e;
  background: rgba(34, 197, 94, 0.15);
}

:deep(.tool-status.error) {
  color: #ef4444;
  background: rgba(239, 68, 68, 0.15);
}

:deep(.tool-spinner) {
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 2px solid rgba(59, 130, 246, 0.3);
  border-top-color: #3b82f6;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

:deep(.tool-item-body) {
  padding: 0.75rem;
}

:deep(.tool-description) {
  font-size: 0.8125rem;
  color: #6b7280;
  line-height: 1.5;
}

:deep(.tool-description .exec-command) {
  background: #f3f4f6;
  border-radius: 8px;
  padding: 0.5rem 0.75rem;
  margin-top: 0.25rem;
}

:deep(.tool-description .exec-label) {
  font-size: 0.7rem;
  color: #9ca3af;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 0.25rem;
  font-weight: 600;
}

:deep(.tool-description .exec-code) {
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Consolas', monospace;
  font-size: 0.8rem;
  color: #374151;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.4;
}

:deep(.tool-result) {
  margin-top: 0.5rem;
  padding: 0.5rem 0.75rem;
  background: #f9fafb;
  border-radius: 8px;
  border-left: 3px solid #d1d5db;
}

:deep(.tool-result pre) {
  font-family: 'SF Mono', monospace;
  font-size: 0.75rem;
  color: #4b5563;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 200px;
  overflow-y: auto;
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

/* OpenClaw 消息容器样式 */
.openclaw-message-container {
  display: flex;
  gap: 0.75rem;
  max-width: 80%;
  min-width: 0;
  align-self: flex-start;
}

.openclaw-message-container .openclaw-body {
  background: linear-gradient(135deg, #f0f9ff 0%, #e0f2fe 100%);
  border: 1px solid #bae6fd;
  border-radius: 12px;
  flex: 1;
  min-width: 0;
}

.openclaw-message-container .openclaw-body .message-content {
  padding: 0;
}

.openclaw-message-container.has-tool-calls .openclaw-body {
  padding: 0.75rem 1rem;
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

  .flowchart-link {
    padding: 0.375rem 0.75rem;
    font-size: 0.8125rem;
  }

  .settings-link {
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
