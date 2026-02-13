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
            <div
              v-for="msg in chatStore.messages"
              :key="msg.id"
              :class="['message', { 'from-me': msg.senderId === authStore.user?.id, 'from-openclaw': msg.fromOpenClaw }]"
            >
              <div class="message-header">
                <span class="sender">{{ msg.senderName }}</span>
                <span class="time">{{ formatTime(msg.timestamp) }}</span>
              </div>
              <div class="message-content" v-html="renderContent(msg.content)"></div>
            </div>
            
            <div v-if="chatStore.messages.length === 0" class="empty-messages">
              发送消息开始对话
              <br/>
              <span>在群聊中使用 @openclaw 召唤 AI</span>
            </div>
          </div>
          
          <div class="input-area">
            <div class="input-wrapper">
              <textarea
                v-model="inputMessage"
                @keydown="handleKeydown"
                @input="handleInput"
                placeholder="输入消息... 使用 @ 提及他人"
                rows="1"
                ref="inputRef"
              />
              <button
                @click="sendMessage"
                :disabled="!inputMessage.trim() || !chatStore.isConnected"
              >
                发送
              </button>
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
import type { MemberDto } from '@/types'

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

// 是否为当前聊天室群主
const isCreator = computed(() => {
  return chatStore.currentRoom?.creatorId === authStore.user?.username
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
})

onUnmounted(() => {
  chatStore.disconnect()
})

// 监听路由变化，切换聊天室
watch(() => route.params.roomId, async (newRoomId) => {
  if (newRoomId) {
    chatStore.disconnect()
    await chatStore.connect(newRoomId as string)
    loadRoomMembers()
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

function sendMessage() {
  const content = inputMessage.value.trim()
  if (!content || !chatStore.isConnected || !currentRoomId.value) return
  
  chatStore.sendMessage(content)
  inputMessage.value = ''
  showMentionList.value = false
  adjustTextareaHeight()
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

function renderContent(content: string) {
  return content
    .replace(/@openclaw/g, '<span class="mention">@openclaw</span>')
    .replace(/@所有人|@everyone|@all/gi, '<span class="mention mention-all">$&</span>')
    .replace(/@在线|@here/gi, '<span class="mention mention-here">$&</span>')
    .replace(/\n/g, '<br>')
}

function getInitials(name: string): string {
  return name.slice(0, 2).toUpperCase()
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

.message {
  max-width: 70%;
  padding: 0.75rem 1rem;
  border-radius: 12px;
  background: var(--bg-color);
  align-self: flex-start;
}

.message.from-me {
  align-self: flex-end;
  background: var(--primary-color);
  color: white;
}

.message.from-openclaw {
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

.message-content {
  line-height: 1.5;
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
  color: rgba(255,255,255,0.9);
  background: rgba(255,255,255,0.2);
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

.input-area {
  border-top: 1px solid var(--border-color);
  padding: 1rem;
  flex-shrink: 0;
  position: relative;
}

.input-wrapper {
  display: flex;
  gap: 0.75rem;
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
</style>
