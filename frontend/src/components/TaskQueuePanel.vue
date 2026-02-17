<template>
  <div v-if="visible" class="task-queue-panel">
    <div class="queue-header">
      <span class="queue-title">
        🤖 OpenClaw 任务队列
        <span v-if="queueInfo.isProcessing" class="processing-badge">
          处理中
        </span>
      </span>
      <button class="close-btn" @click="close">✕</button>
    </div>
    
    <div class="queue-stats">
      <div class="stat-item">
        <span class="stat-value">{{ queueInfo.queueSize }}</span>
        <span class="stat-label">待处理</span>
      </div>
      <div class="stat-item">
        <span class="stat-value" :class="{ 'active': queueInfo.isProcessing }">
          {{ queueInfo.isProcessing ? '是' : '否' }}
        </span>
        <span class="stat-label">处理中</span>
      </div>
    </div>

    <div class="queue-list" v-if="queueInfo.tasks.length > 0">
      <div 
        v-for="(task, index) in queueInfo.tasks" 
        :key="task.taskId"
        class="queue-item"
        :class="{ 'processing': task.status === 'PROCESSING' }"
      >
        <div class="task-number">{{ index + 1 }}</div>
        <div class="task-info">
          <div class="task-sender">{{ task.senderName }}</div>
          <div class="task-content">{{ task.content }}</div>
          <div class="task-meta">
            <span class="task-status" :class="task.status.toLowerCase()">
              {{ getStatusText(task.status) }}
            </span>
            <span class="task-time">{{ formatTime(task.createdAt) }}</span>
          </div>
        </div>
      </div>
    </div>
    
    <div v-else class="queue-empty">
      队列为空
    </div>

    <div class="queue-actions">
      <button class="refresh-btn" @click="refresh" :disabled="loading">
        {{ loading ? '刷新中...' : '刷新' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { chatRoomApi, type TaskQueueInfo } from '@/api/chatRoom'

interface Props {
  roomId: string
  visible: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{ (e: 'close'): void }>()

const queueInfo = ref<TaskQueueInfo>({
  roomId: '',
  isProcessing: false,
  queueSize: 0,
  tasks: []
})

const loading = ref(false)
const refreshInterval = ref<number | null>(null)

const fetchQueue = async () => {
  if (!props.roomId) return
  
  loading.value = true
  try {
    const response = await chatRoomApi.getTaskQueue(props.roomId)
    queueInfo.value = response.data
  } catch (error) {
    console.error('Failed to fetch task queue:', error)
  } finally {
    loading.value = false
  }
}

const refresh = () => {
  fetchQueue()
}

const close = () => {
  emit('close')
}

const getStatusText = (status: string): string => {
  const statusMap: Record<string, string> = {
    'PENDING': '等待中',
    'PROCESSING': '处理中',
    'COMPLETED': '已完成',
    'FAILED': '失败'
  }
  return statusMap[status] || status
}

const formatTime = (timestamp: string): string => {
  const date = new Date(timestamp)
  return date.toLocaleTimeString('zh-CN', { 
    hour: '2-digit', 
    minute: '2-digit',
    second: '2-digit'
  })
}

// 自动刷新
onMounted(() => {
  fetchQueue()
  refreshInterval.value = window.setInterval(fetchQueue, 3000) // 每3秒刷新
})

onUnmounted(() => {
  if (refreshInterval.value) {
    clearInterval(refreshInterval.value)
  }
})

// 当 visible 变化时刷新
watch(() => props.visible, (newVal) => {
  if (newVal) {
    fetchQueue()
  }
})

// 添加 watch import
import { watch } from 'vue'
</script>

<style scoped>
.task-queue-panel {
  position: fixed;
  right: 20px;
  top: 80px;
  width: 350px;
  max-height: 500px;
  background: white;
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
  z-index: 1000;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.queue-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-bottom: 1px solid #eee;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.queue-title {
  font-weight: 600;
  font-size: 16px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.processing-badge {
  font-size: 11px;
  background: rgba(255, 255, 255, 0.3);
  padding: 2px 8px;
  border-radius: 12px;
  animation: pulse 1.5s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.close-btn {
  background: none;
  border: none;
  color: white;
  font-size: 18px;
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  transition: background 0.2s;
}

.close-btn:hover {
  background: rgba(255, 255, 255, 0.2);
}

.queue-stats {
  display: flex;
  padding: 16px;
  gap: 24px;
  border-bottom: 1px solid #eee;
  background: #f8f9fa;
}

.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: #333;
}

.stat-value.active {
  color: #10b981;
}

.stat-label {
  font-size: 12px;
  color: #666;
  margin-top: 4px;
}

.queue-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.queue-item {
  display: flex;
  gap: 12px;
  padding: 12px;
  border-radius: 8px;
  margin-bottom: 8px;
  background: #f8f9fa;
  transition: all 0.2s;
}

.queue-item.processing {
  background: linear-gradient(90deg, #dbeafe 0%, #bfdbfe 100%);
  border-left: 4px solid #3b82f6;
}

.task-number {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #e5e7eb;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  color: #666;
  flex-shrink: 0;
}

.queue-item.processing .task-number {
  background: #3b82f6;
  color: white;
}

.task-info {
  flex: 1;
  min-width: 0;
}

.task-sender {
  font-weight: 600;
  font-size: 13px;
  color: #333;
  margin-bottom: 4px;
}

.task-content {
  font-size: 12px;
  color: #666;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 8px;
}

.task-meta {
  display: flex;
  gap: 8px;
  align-items: center;
}

.task-status {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 12px;
  font-weight: 500;
}

.task-status.pending {
  background: #fef3c7;
  color: #92400e;
}

.task-status.processing {
  background: #dbeafe;
  color: #1e40af;
}

.task-status.completed {
  background: #d1fae5;
  color: #065f46;
}

.task-status.failed {
  background: #fee2e2;
  color: #991b1b;
}

.task-time {
  font-size: 11px;
  color: #999;
}

.queue-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #999;
  font-size: 14px;
  padding: 40px;
}

.queue-actions {
  padding: 12px 16px;
  border-top: 1px solid #eee;
  background: #f8f9fa;
}

.refresh-btn {
  width: 100%;
  padding: 10px;
  background: #3b82f6;
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.2s;
}

.refresh-btn:hover:not(:disabled) {
  background: #2563eb;
}

.refresh-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

@media (max-width: 768px) {
  .task-queue-panel {
    right: 10px;
    left: 10px;
    width: auto;
    top: 60px;
  }
}
</style>