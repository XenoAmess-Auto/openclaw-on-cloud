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
      <RoomSidebar
        :rooms="chatStore.rooms"
        :current-room-id="currentRoomId"
        :loading="chatStore.loading"
        @create-room="showCreateDialog = true"
        @enter-room="enterRoom"
      />
      
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
          <ChatHeader
            :room-name="chatStore.currentRoom?.name"
            :is-connected="chatStore.isConnected"
            :is-creator="isCreator"
            @dismiss="confirmDismiss"
            @show-members="showMembers = true"
            @show-sessions="showSessions = true"
            @show-task-queue="showTaskQueue = true"
          />
          
          <MessageList
            ref="messageListRef"
            :messages="chatStore.messages"
            :current-user-id="authStore.user?.id"
            :current-user-avatar="authStore.user?.avatar"
            :typing-users="chatStore.typingUserList"
            :has-more-messages="chatStore.hasMoreMessages"
            :loading-more="chatStore.loadingMore"
            :room-members="roomMembers"
            @load-more="loadMoreMessages"
            @scroll-to-message="scrollToMessage"
          />
          
          <MessageInput
            ref="messageInputRef"
            :room-members="roomMembers"
            :current-user-id="authStore.user?.id"
            :is-connected="chatStore.isConnected"
            @send-message="handleSendMessage"
            @typing="chatStore.sendTyping()"
          />
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
import RoomSidebar from '@/components/chat/RoomSidebar.vue'
import ChatHeader from '@/components/chat/ChatHeader.vue'
import MessageList from '@/components/chat/MessageList.vue'
import MessageInput from '@/components/chat/MessageInput.vue'
import SessionManager from '@/components/SessionManager.vue'
import MemberManager from '@/components/MemberManager.vue'
import TaskQueuePanel from '@/components/TaskQueuePanel.vue'
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

// 组件引用
const messageListRef = ref<InstanceType<typeof MessageList>>()
const messageInputRef = ref<InstanceType<typeof MessageInput>>()

// 聊天相关
const currentRoomId = computed(() => route.params.roomId as string | undefined)
const showMembers = ref(false)
const showSessions = ref(false)
const showTaskQueue = ref(false)
const showDismissDialog = ref(false)

// 成员列表
const roomMembers = ref<MemberDto[]>([])

// 是否为当前聊天室群主
const isCreator = computed(() => {
  return chatStore.currentRoom?.creatorId === authStore.user?.username
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
  const container = messageListRef.value?.containerRef
  if (container) {
    container.scrollTop = container.scrollHeight
  }
}

// 滚动到指定消息
function scrollToMessage(messageId: string) {
  const container = messageListRef.value?.containerRef
  if (!container) return
  
  const targetElement = document.getElementById('msg-' + messageId)
  if (!targetElement) {
    console.warn('Message not found:', messageId)
    return
  }
  
  targetElement.classList.add('highlight-message')
  targetElement.scrollIntoView({ behavior: 'smooth', block: 'center' })
  
  setTimeout(() => {
    targetElement.classList.remove('highlight-message')
  }, 3000)
}

// 滚动位置记录
let scrollHeightBeforeLoad = 0
let scrollTopBeforeLoad = 0

// 加载更多历史消息
async function loadMoreMessages() {
  if (!currentRoomId.value) return
  
  const container = messageListRef.value?.containerRef
  if (!container) return
  
  scrollHeightBeforeLoad = container.scrollHeight
  scrollTopBeforeLoad = container.scrollTop
  
  const success = await chatStore.loadMoreMessages(currentRoomId.value)
  
  if (success) {
    nextTick(() => {
      const newContainer = messageListRef.value?.containerRef
      if (!newContainer) return
      
      const newScrollHeight = newContainer.scrollHeight
      const heightDiff = newScrollHeight - scrollHeightBeforeLoad
      newContainer.scrollTop = heightDiff + scrollTopBeforeLoad
    })
  }
}

// 发送消息
function handleSendMessage(content: string, attachments: Array<{ id: string; dataUrl: string; mimeType: string }>) {
  if ((!content.trim() && attachments.length === 0) || !chatStore.isConnected || !currentRoomId.value) return

  console.log('[handleSendMessage] content:', content, 'attachments:', attachments)
  chatStore.sendMessage(content, attachments)
}
</script>

<style scoped>
.home-view {
  height: 100vh;
  height: 100dvh;
  display: flex;
  flex-direction: column;
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

.flowchart-link, .settings-link {
  padding: 0.5rem 1rem;
  background: var(--bg-color);
  color: var(--text-primary);
  text-decoration: none;
  border-radius: 6px;
  font-size: 0.875rem;
  border: 1px solid var(--border-color);
  transition: background 0.2s;
}

.flowchart-link:hover, .settings-link:hover {
  background: var(--border-color);
}

.container {
  flex: 1;
  display: flex;
  overflow: hidden;
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
  .home-view {
    height: 100dvh;
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

  .user-info button,
  .admin-link,
  .flowchart-link,
  .settings-link {
    padding: 0.375rem 0.75rem;
    font-size: 0.8125rem;
  }

  .container {
    flex-direction: column;
  }

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
    font-size: 16px;
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
    display: none;
  }
}

/* 横屏模式优化 */
@media (max-height: 500px) and (orientation: landscape) {
  .header {
    height: 48px;
  }
}
</style>
