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
                @keydown.enter.prevent="sendMessage"
                @input="adjustTextareaHeight"
                placeholder="输入消息... 使用 @openclaw 召唤 AI"
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
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted, watch, nextTick, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'

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

onMounted(() => {
  chatStore.fetchRooms()
  if (currentRoomId.value) {
    chatStore.connect(currentRoomId.value)
  }
})

onUnmounted(() => {
  chatStore.disconnect()
})

// 监听路由变化，切换聊天室
watch(() => route.params.roomId, (newRoomId) => {
  if (newRoomId) {
    chatStore.disconnect()
    chatStore.connect(newRoomId as string)
  } else {
    chatStore.disconnect()
  }
})

// 监听消息变化，自动滚动到底部
watch(() => chatStore.messages.length, () => {
  nextTick(() => {
    scrollToBottom()
  })
})

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
  adjustTextareaHeight()
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
    .replace(/\n/g, '<br>')
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
}

.message.from-me .message-content :deep(.mention) {
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

.input-area {
  border-top: 1px solid var(--border-color);
  padding: 1rem;
  flex-shrink: 0;
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
</style>
