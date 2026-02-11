<template>
  <div class="chat-view">
    <header class="header">
      <router-link to="/" class="back">←</router-link>
      <div class="room-info">
        <h1>{{ chatStore.currentRoom?.name || '聊天室' }}</h1>
        <span :class="['status', { connected: chatStore.isConnected }]">
          {{ chatStore.isConnected ? '已连接' : '连接中...' }}
        </span>
      </div>
      <div class="actions">
        <button @click="showMembers = true">成员</button>
        <button @click="showSessions = true">会话</button>
      </div>
    </header>
    
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
      
      <div v-if="chatStore.messages.length === 0" class="empty">
        发送消息开始对话
        <br/>
        <span v-if="isPrivateChat">这是私聊，每条消息都会触发 OpenClaw</span>
        <span v-else>在群聊中使用 @openclaw 召唤 AI</span>
      </div>
    </div>
    
    <div class="input-area">
      <div class="input-wrapper">
        <textarea
          v-model="inputMessage"
          @keydown.enter.prevent="sendMessage"
          @input="handleInput"
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
      
      <div v-if="showMentionList" class="mention-list">
        <div
          v-for="user in mentionableUsers"
          :key="user.id"
          class="mention-item"
          @click="insertMention(user)"
        >
          @{{ user.name }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'

const route = useRoute()
const authStore = useAuthStore()
const chatStore = useChatStore()

const roomId = computed(() => route.params.roomId as string)
const inputMessage = ref('')
const messageContainer = ref<HTMLDivElement>()
const inputRef = ref<HTMLTextAreaElement>()
const showMembers = ref(false)
const showSessions = ref(false)
const showMentionList = ref(false)

const isPrivateChat = computed(() => {
  // 简化的判断：只有自己和 OpenClaw 时为私聊
  return false // 实际应根据成员数量判断
})

const mentionableUsers = [
  { id: 'openclaw', name: 'openclaw' }
]

onMounted(() => {
  chatStore.connect(roomId.value)
})

onUnmounted(() => {
  chatStore.disconnect()
})

watch(() => chatStore.messages.length, () => {
  nextTick(() => {
    scrollToBottom()
  })
})

function scrollToBottom() {
  if (messageContainer.value) {
    messageContainer.value.scrollTop = messageContainer.value.scrollHeight
  }
}

function sendMessage() {
  const content = inputMessage.value.trim()
  if (!content || !chatStore.isConnected) return
  
  chatStore.sendMessage(content)
  inputMessage.value = ''
  adjustTextareaHeight()
}

function handleInput() {
  adjustTextareaHeight()
  
  // 检查是否输入了 @
  const text = inputMessage.value
  const lastAtIndex = text.lastIndexOf('@')
  if (lastAtIndex >= 0 && lastAtIndex === text.length - 1) {
    showMentionList.value = true
  } else {
    showMentionList.value = false
  }
}

function insertMention(user: { id: string, name: string }) {
  const text = inputMessage.value
  const lastAtIndex = text.lastIndexOf('@')
  inputMessage.value = text.slice(0, lastAtIndex) + '@' + user.name + ' '
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
  // 高亮 @openclaw
  return content
    .replace(/@openclaw/g, '<span class="mention">@openclaw</span>')
    .replace(/\n/g, '<br>')
}
</script>

<style scoped>
.chat-view {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--surface-color);
}

.header {
  height: 60px;
  background: var(--surface-color);
  border-bottom: 1px solid var(--border-color);
  display: flex;
  align-items: center;
  padding: 0 1rem;
  gap: 1rem;
}

.back {
  font-size: 1.25rem;
  color: var(--text-secondary);
  text-decoration: none;
}

.room-info {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.room-info h1 {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
}

.status {
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.status.connected {
  color: var(--success-color);
}

.actions {
  display: flex;
  gap: 0.5rem;
}

.actions button {
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

.empty {
  text-align: center;
  color: var(--text-secondary);
  padding: 3rem 1rem;
}

.empty span {
  display: block;
  margin-top: 0.5rem;
  font-size: 0.875rem;
}

.input-area {
  border-top: 1px solid var(--border-color);
  padding: 1rem;
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

.mention-list {
  position: absolute;
  bottom: 100%;
  left: 1rem;
  background: var(--surface-color);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
  margin-bottom: 0.5rem;
  overflow: hidden;
}

.mention-item {
  padding: 0.5rem 1rem;
  cursor: pointer;
  font-size: 0.875rem;
}

.mention-item:hover {
  background: var(--bg-color);
}

@media (max-width: 768px) {
  .message {
    max-width: 85%;
  }
  
  .input-wrapper button {
    padding: 0.75rem 1rem;
  }
}
</style>
