<template>
  <div class="session-panel">
    <div class="panel-header">
      <h3>会话列表</h3>
      <span class="session-count">{{ sessions.length }} 个会话</span>
    </div>

    <div class="session-list" v-if="sessions.length > 0">
      <div
        v-for="session in sessions"
        :key="session.id"
        class="session-item"
        :class="{ 
          active: isActive(session), 
          archived: session.archived,
          'has-summary': session.summary 
        }"
        @click="selectSession(session)"
      >
        <div class="session-icon">
          <span v-if="session.archived">📦</span>
          <span v-else-if="isActive(session)">💬</span>
          <span v-else>📝</span>
        </div>
        
        <div class="session-content">
          <div class="session-title">
            <span class="name">{{ session.chatRoomName || '未命名会话' }}</span>
            <span class="badge" :class="getBadgeClass(session)">
              {{ getBadgeText(session) }}
            </span>
          </div>
          
          <div class="session-meta">
            <span class="message-count">{{ session.messageCount }} 条消息</span>
            <span class="date">{{ formatDate(session.updatedAt) }}</span>
          </div>
          
          <div v-if="session.summary" class="session-preview">
            {{ truncate(session.summary, 80) }}
          </div>
        </div>

        <div class="session-actions" @click.stop>
          <button
            v-if="!session.archived && canArchive"
            class="btn-icon btn-archive"
            @click="archiveSession(session.id)"
            title="归档会话"
          >
            📦
          </button>
          <button
            class="btn-icon btn-copy"
            @click="copySession(session.id)"
            title="复制到新聊天室"
          >
            📋
          </button>
        </div>
      </div>
    </div>

    <div v-else class="empty-state">
      <div class="empty-icon">📝</div>
      <p>暂无会话</p>
      <span class="hint">在此聊天室发送消息将自动创建会话</span>
    </div>

    <div v-if="loading" class="loading-state">
      <div class="spinner"></div>
      <span>加载中...</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
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
  canArchive?: boolean
}>()

const emit = defineEmits<{
  select: [session: Session]
  archive: [sessionId: string]
  copy: [sessionId: string]
}>()

const sessions = ref<Session[]>([])
const loading = ref(false)
const activeSessionId = ref<string | null>(null)

onMounted(() => {
  loadSessions()
})

function isActive(session: Session): boolean {
  return !session.archived && session.chatRoomId === props.roomId && activeSessionId.value === session.id
}

function getBadgeClass(session: Session): string {
  if (session.archived) return 'archived'
  if (isActive(session)) return 'active'
  return 'normal'
}

function getBadgeText(session: Session): string {
  if (session.archived) return '已归档'
  if (isActive(session)) return '当前'
  return '历史'
}

async function loadSessions() {
  loading.value = true
  try {
    const response = await apiClient.get(`/sessions/chat-room/${props.roomId}`)
    sessions.value = response.data || []
    
    // 设置当前活跃会话
    const active = sessions.value.find(s => !s.archived && s.chatRoomId === props.roomId)
    if (active) {
      activeSessionId.value = active.id
    }
  } catch (err: any) {
    console.error('Failed to load sessions:', err)
  } finally {
    loading.value = false
  }
}

function selectSession(session: Session) {
  emit('select', session)
}

async function archiveSession(sessionId: string) {
  if (!confirm('确定要归档此会话吗？归档后将无法继续在此会话中对话。')) return

  try {
    await apiClient.post(`/sessions/${sessionId}/archive`)
    emit('archive', sessionId)
    await loadSessions()
  } catch (err: any) {
    alert('归档失败: ' + (err.response?.data?.message || '未知错误'))
  }
}

async function copySession(sessionId: string) {
  try {
    await apiClient.post(`/sessions/${sessionId}/copy?newChatRoomId=${props.roomId}`)
    emit('copy', sessionId)
    await loadSessions()
  } catch (err: any) {
    alert('复制失败: ' + (err.response?.data?.message || '未知错误'))
  }
}

function formatDate(dateStr: string): string {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  const now = new Date()
  const diffDays = Math.floor((now.getTime() - date.getTime()) / (1000 * 60 * 60 * 24))
  
  if (diffDays === 0) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  } else if (diffDays === 1) {
    return '昨天'
  } else if (diffDays < 7) {
    return `${diffDays}天前`
  } else {
    return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
  }
}

function truncate(text: string, maxLength: number): string {
  if (!text || text.length <= maxLength) return text
  return text.substring(0, maxLength) + '...'
}

// 暴露刷新方法给父组件
defineExpose({
  refresh: loadSessions
})
</script>

<style scoped>
.session-panel {
  background: var(--surface-color);
  border-radius: 12px;
  border: 1px solid var(--border-color);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  height: 100%;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem 1.25rem;
  border-bottom: 1px solid var(--border-color);
  background: linear-gradient(135deg, var(--surface-color) 0%, var(--bg-color) 100%);
}

.panel-header h3 {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}

.session-count {
  font-size: 0.75rem;
  color: var(--text-secondary);
  background: var(--bg-color);
  padding: 0.25rem 0.75rem;
  border-radius: 12px;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 0.5rem;
}

.session-item {
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
  padding: 0.875rem;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
  border: 1px solid transparent;
  margin-bottom: 0.5rem;
}

.session-item:hover {
  background: var(--bg-color);
  border-color: var(--border-color);
}

.session-item.active {
  background: rgba(var(--primary-rgb), 0.08);
  border-color: var(--primary-color);
}

.session-item.archived {
  opacity: 0.7;
}

.session-icon {
  font-size: 1.25rem;
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-color);
  border-radius: 8px;
}

.session-content {
  flex: 1;
  min-width: 0;
}

.session-title {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.375rem;
}

.session-title .name {
  font-weight: 500;
  color: var(--text-primary);
  font-size: 0.9375rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.badge {
  font-size: 0.625rem;
  padding: 0.125rem 0.5rem;
  border-radius: 4px;
  font-weight: 500;
  flex-shrink: 0;
}

.badge.active {
  background: var(--primary-color);
  color: white;
}

.badge.archived {
  background: #6b7280;
  color: white;
}

.badge.normal {
  background: var(--bg-color);
  color: var(--text-secondary);
  border: 1px solid var(--border-color);
}

.session-meta {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  font-size: 0.75rem;
  color: var(--text-secondary);
  margin-bottom: 0.25rem;
}

.message-count::before {
  content: '💬';
  margin-right: 0.25rem;
}

.date::before {
  content: '🕐';
  margin-right: 0.25rem;
}

.session-preview {
  font-size: 0.8125rem;
  color: var(--text-secondary);
  line-height: 1.5;
  margin-top: 0.5rem;
  padding: 0.5rem 0.75rem;
  background: var(--bg-color);
  border-radius: 6px;
  border-left: 3px solid var(--primary-color);
}

.session-actions {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  opacity: 0;
  transition: opacity 0.2s ease;
}

.session-item:hover .session-actions {
  opacity: 1;
}

.btn-icon {
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: var(--bg-color);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.875rem;
  transition: all 0.2s ease;
}

.btn-icon:hover {
  background: var(--primary-color);
  transform: scale(1.1);
}

.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 3rem 1.5rem;
  text-align: center;
  color: var(--text-secondary);
}

.empty-icon {
  font-size: 3rem;
  margin-bottom: 1rem;
  opacity: 0.5;
}

.empty-state p {
  font-size: 1rem;
  margin: 0 0 0.5rem;
}

.empty-state .hint {
  font-size: 0.8125rem;
  opacity: 0.7;
}

.loading-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1rem;
  color: var(--text-secondary);
}

.spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--border-color);
  border-top-color: var(--primary-color);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* 滚动条样式 */
.session-list::-webkit-scrollbar {
  width: 6px;
}

.session-list::-webkit-scrollbar-track {
  background: transparent;
}

.session-list::-webkit-scrollbar-thumb {
  background: var(--border-color);
  border-radius: 3px;
}

.session-list::-webkit-scrollbar-thumb:hover {
  background: var(--text-secondary);
}
</style>
