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
            class="room-item"
          >
            <div class="room-name">{{ room.name }}</div>
            <div class="room-meta">{{ room.memberIds.length }} 成员</div>
          </li>
        </ul>
        
        <div v-if="chatStore.rooms.length === 0 && !chatStore.loading" class="empty">
          暂无聊天室，创建一个吧
        </div>
      </aside>
      
      <main class="main-content">
        <div class="welcome">
          <h2>欢迎使用 OOC</h2>
          <p>选择一个聊天室开始对话，或创建一个新的聊天室。</p>
          <p class="hint">
            提示：在群聊中使用 @openclaw 来召唤 AI 助手
          </p>
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
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'

const router = useRouter()
const authStore = useAuthStore()
const chatStore = useChatStore()

const showCreateDialog = ref(false)
const newRoom = reactive({
  name: '',
  description: ''
})

onMounted(() => {
  chatStore.fetchRooms()
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

.sidebar {
  width: 280px;
  background: var(--bg-color);
  border-right: 1px solid var(--border-color);
  display: flex;
  flex-direction: column;
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

.main-content {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--surface-color);
}

.welcome {
  text-align: center;
  max-width: 400px;
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

@media (max-width: 768px) {
  .sidebar {
    width: 100%;
    position: absolute;
    z-index: 10;
    transform: translateX(-100%);
  }
  
  .sidebar.open {
    transform: translateX(0);
  }
}
</style>
