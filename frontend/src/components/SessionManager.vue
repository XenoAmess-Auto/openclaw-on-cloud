<template>
  <div class="modal" @click="$emit('close')">
    <div class="modal-content session-manager" @click.stop>
      <div class="modal-header">
        <h3>会话管理</h3>
        <button class="btn-close" @click="$emit('close')">×</button>
      </div>
      
      <div class="session-list" v-if="sessions.length > 0">
        <div 
          v-for="session in sessions" 
          :key="session.id" 
          class="session-item"
          :class="{ archived: session.archived, active: isActive(session) }"
        >
          <div class="session-info">
            <div class="session-header">
              <span class="session-name">{{ session.chatRoomName }}</span>
              <span class="session-badge" :class="{ archived: session.archived }">
                {{ session.archived ? '已归档' : '活跃' }}
              </span>
            </div>
            <div class="session-meta">
              <span>消息数: {{ session.messageCount }}</span>
              <span>创建于: {{ formatDate(session.createdAt) }}</span>
            </div>
            <div v-if="session.summary" class="session-summary">
              {{ truncate(session.summary, 100) }}
            </div>
          </div>
          
          <div class="session-actions">
            <button 
              v-if="!session.archived && isActive(session)" 
              @click="archiveSession(session.id)"
              class="btn-archive"
              title="归档会话"
            >
              归档
            </button>
            <button 
              @click="copySession(session.id)" 
              class="btn-copy"
              title="复制会话到新聊天室"
            >
              复制
            </button>
          </div>
        </div>
      </div>
      
      <div v-else class="empty-state">
        <p>暂无会话记录</p>
        <p class="hint">在此聊天室中发送消息将自动创建会话</p>
      </div>
      
      <div v-if="loading" class="loading-overlay">
        <span>加载中...</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import apiClient from '@/api/client'

interface Session {
  id: string
  chatRoomId: string
  chatRoomName: string
  messages: any[]
  messageCount: number
  summary: string | null
  archived: boolean
  createdAt: string
  updatedAt: string
}

const props = defineProps<{
  roomId: string
}>()

const emit = defineEmits<{
  close: []
  copy: [sessionId: string]
}>()

const sessions = ref<Session[]>([])
const loading = ref(false)

onMounted(() => {
  loadSessions()
})

function isActive(session: Session): boolean {
  return !session.archived && session.chatRoomId === props.roomId
}

async function loadSessions() {
  loading.value = true
  try {
    const response = await apiClient.get(`/api/sessions/chat-room/${props.roomId}`)
    sessions.value = response.data
  } catch (err: any) {
    console.error('Failed to load sessions:', err)
    alert('加载会话失败: ' + (err.response?.data?.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

async function archiveSession(sessionId: string) {
  if (!confirm('确定要归档此会话吗？归档后将无法继续在此会话中对话。')) return
  
  try {
    await apiClient.post(`/api/sessions/${sessionId}/archive`)
    await loadSessions()
  } catch (err: any) {
    alert('归档失败: ' + (err.response?.data?.message || '未知错误'))
  }
}

async function copySession(sessionId: string) {
  try {
    await apiClient.post(`/api/sessions/${sessionId}/copy?newChatRoomId=${props.roomId}`)
    emit('copy', sessionId)
    await loadSessions()
    alert('会话复制成功')
  } catch (err: any) {
    alert('复制失败: ' + (err.response?.data?.message || '未知错误'))
  }
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function truncate(text: string, maxLength: number): string {
  if (!text || text.length <= maxLength) return text
  return text.substring(0, maxLength) + '...'
}
</script>

<style scoped>
.modal {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.modal-content {
  background: var(--surface-color);
  border-radius: 12px;
  width: 90%;
  max-width: 600px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem 1.5rem;
  border-bottom: 1px solid var(--border-color);
}

.modal-header h3 {
  font-size: 1.125rem;
  color: var(--text-primary);
}

.btn-close {
  width: 32px;
  height: 32px;
  background: transparent;
  border: none;
  border-radius: 6px;
  font-size: 1.5rem;
  color: var(--text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn-close:hover {
  background: var(--bg-color);
}

.session-list {
  overflow-y: auto;
  padding: 1rem;
  flex: 1;
}

.session-item {
  background: var(--bg-color);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 1rem;
  margin-bottom: 0.75rem;
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
}

.session-item.active {
  border-color: var(--primary-color);
  background: rgba(var(--primary-rgb), 0.05);
}

.session-item.archived {
  opacity: 0.7;
}

.session-info {
  flex: 1;
  min-width: 0;
}

.session-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
}

.session-name {
  font-weight: 600;
  color: var(--text-primary);
}

.session-badge {
  font-size: 0.75rem;
  padding: 0.125rem 0.5rem;
  border-radius: 4px;
  background: #22c55e;
  color: white;
}

.session-badge.archived {
  background: #6b7280;
}

.session-meta {
  font-size: 0.75rem;
  color: var(--text-secondary);
  display: flex;
  gap: 1rem;
  margin-bottom: 0.5rem;
}

.session-summary {
  font-size: 0.875rem;
  color: var(--text-secondary);
  line-height: 1.5;
  padding: 0.5rem;
  background: var(--surface-color);
  border-radius: 4px;
}

.session-actions {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.session-actions button {
  padding: 0.375rem 0.75rem;
  border: none;
  border-radius: 4px;
  font-size: 0.75rem;
  cursor: pointer;
  white-space: nowrap;
}

.btn-archive {
  background: #f59e0b;
  color: white;
}

.btn-copy {
  background: var(--primary-color);
  color: white;
}

.empty-state {
  text-align: center;
  padding: 3rem 1rem;
  color: var(--text-secondary);
}

.empty-state .hint {
  font-size: 0.875rem;
  margin-top: 0.5rem;
  opacity: 0.7;
}

.loading-overlay {
  position: absolute;
  inset: 0;
  background: rgba(var(--surface-color), 0.8);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
}
</style>
