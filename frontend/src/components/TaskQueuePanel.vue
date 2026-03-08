<template>
  <div 
    v-if="visible" 
    class="task-queue-panel"
    :style="panelStyle"
  >
    <div class="queue-header" @mousedown="startDrag">
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
        <span class="stat-value">{{ pendingCount }}</span>
        <span class="stat-label">待处理</span>
      </div>
      <div class="stat-item">
        <span class="stat-value" :class="{ 'active': processingCount > 0 }">
          {{ processingCount }}
        </span>
        <span class="stat-label">执行中</span>
      </div>
      <div class="stat-item">
        <span class="stat-value">{{ totalCount }}</span>
        <span class="stat-label">总计</span>
      </div>
    </div>

    <div class="queue-list" v-if="queueInfo.tasks.length > 0">
      <div 
        v-for="(task, index) in sortedTasks" 
        :key="task.taskId"
        class="queue-item"
        :class="{ 
          'processing': task.status === 'PROCESSING',
          'pending': task.status === 'PENDING',
          'dragging': draggedTaskId === task.taskId,
          'drag-over': dragOverTaskId === task.taskId
        }"
        :draggable="task.status === 'PENDING'"
        @dragstart="handleDragStart($event, task, index)"
        @dragend="handleDragEnd"
        @dragover="handleDragOver($event, task, index)"
        @dragleave="handleDragLeave"
        @drop="handleDrop($event, index)"
      >
        <div class="drag-handle" v-if="task.status === 'PENDING'" title="拖拽排序">
          ⋮⋮
        </div>
        <div class="task-number" :class="{ 'processing': task.status === 'PROCESSING' }">
          {{ task.status === 'PROCESSING' ? '▶' : index + 1 }}
        </div>
        <div class="task-info">
          <div class="task-sender">
            <span class="bot-type-badge" :class="task.botType?.toLowerCase()">
              {{ getBotDisplayName(task.botType) }}
            </span>
            {{ task.senderName }}
          </div>
          <div class="task-content">{{ task.content }}</div>
          <div class="task-meta">
            <span class="task-status" :class="task.status.toLowerCase()">
              {{ getStatusText(task.status) }}
            </span>
            <span class="task-time">{{ formatTime(task.createdAt) }}</span>
          </div>
        </div>
        <button 
          v-if="task.status === 'PENDING' || task.status === 'PROCESSING'"
          class="cancel-btn"
          :class="{ 'stop-btn': task.status === 'PROCESSING' }"
          @click="cancelTask(task.taskId)"
          :title="task.status === 'PROCESSING' ? '停止当前任务' : '取消任务'"
          :disabled="cancellingTaskId === task.taskId"
        >
          {{ cancellingTaskId === task.taskId ? '...' : (task.status === 'PROCESSING' ? '■' : '✕') }}
        </button>
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
import { ref, onMounted, onUnmounted, watch, computed } from 'vue'
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
const cancellingTaskId = ref<string | null>(null)
const refreshInterval = ref<number | null>(null)

// 任务列表拖拽相关状态
const draggedTaskId = ref<string | null>(null)
const draggedIndex = ref<number>(-1)
const dragOverTaskId = ref<string | null>(null)

// 面板拖拽相关状态
const isDragging = ref(false)
const panelPosition = ref({ x: 0, y: 0 })
const dragOffset = ref({ x: 0, y: 0 })

// 默认位置（相对于 right/top）
const DEFAULT_RIGHT = 20
const DEFAULT_TOP = 140

// 面板样式计算属性
const panelStyle = computed(() => {
  if (panelPosition.value.x === 0 && panelPosition.value.y === 0) {
    // 使用默认位置
    return {
      right: `${DEFAULT_RIGHT}px`,
      top: `${DEFAULT_TOP}px`,
      left: 'auto'
    }
  }
  // 使用拖拽后的位置
  return {
    left: `${panelPosition.value.x}px`,
    top: `${panelPosition.value.y}px`,
    right: 'auto'
  }
})

// 拖拽处理函数
const startDrag = (event: MouseEvent) => {
  // 只有左键可以拖拽
  if (event.button !== 0) return
  
  isDragging.value = true
  
  // 获取面板元素
  const panel = (event.target as HTMLElement).closest('.task-queue-panel') as HTMLElement
  if (!panel) return
  
  const rect = panel.getBoundingClientRect()
  
  // 如果是第一次拖拽，从默认位置计算
  if (panelPosition.value.x === 0 && panelPosition.value.y === 0) {
    panelPosition.value = {
      x: rect.left,
      y: rect.top
    }
  }
  
  // 计算鼠标相对于面板左上角的偏移
  dragOffset.value = {
    x: event.clientX - rect.left,
    y: event.clientY - rect.top
  }
  
  // 添加全局事件监听
  document.addEventListener('mousemove', onDrag)
  document.addEventListener('mouseup', stopDrag)
  
  // 防止选中文本
  event.preventDefault()
}

const onDrag = (event: MouseEvent) => {
  if (!isDragging.value) return
  
  // 计算新位置
  let newX = event.clientX - dragOffset.value.x
  let newY = event.clientY - dragOffset.value.y
  
  // 边界限制：确保面板不会完全移出视口
  const panelWidth = 380
  const minVisible = 50 // 至少保留可见的像素
  
  newX = Math.max(minVisible - panelWidth, Math.min(newX, window.innerWidth - minVisible))
  newY = Math.max(0, Math.min(newY, window.innerHeight - minVisible))
  
  panelPosition.value = { x: newX, y: newY }
}

const stopDrag = () => {
  isDragging.value = false
  document.removeEventListener('mousemove', onDrag)
  document.removeEventListener('mouseup', stopDrag)
}

// 计算属性
const pendingCount = computed(() => queueInfo.value.tasks.filter(t => t.status === 'PENDING').length)
const processingCount = computed(() => queueInfo.value.tasks.filter(t => t.status === 'PROCESSING').length)
const totalCount = computed(() => queueInfo.value.tasks.length)

// 排序后的任务列表：PROCESSING 在前，PENDING 在后
const sortedTasks = computed(() => {
  return [...queueInfo.value.tasks].sort((a, b) => {
    if (a.status === 'PROCESSING' && b.status !== 'PROCESSING') return -1
    if (a.status !== 'PROCESSING' && b.status === 'PROCESSING') return 1
    return 0
  })
})

const getBotDisplayName = (botType: string): string => {
  const botNames: Record<string, string> = {
    'OPENCLAW': 'OpenClaw',
    'KIMI': 'Kimi',
    'CLAUDE': 'Claude'
  }
  return botNames[botType?.toUpperCase()] || botType || 'Bot'
}

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

const cancelTask = async (taskId: string) => {
  if (!props.roomId) return
  
  cancellingTaskId.value = taskId
  try {
    await chatRoomApi.cancelTask(props.roomId, taskId)
    // 刷新队列
    await fetchQueue()
  } catch (error) {
    console.error('Failed to cancel task:', error)
    alert('取消任务失败，请重试')
  } finally {
    cancellingTaskId.value = null
  }
}

// 拖拽处理函数
const handleDragStart = (event: DragEvent, task: any, index: number) => {
  if (task.status !== 'PENDING') {
    event.preventDefault()
    return
  }
  
  draggedTaskId.value = task.taskId
  draggedIndex.value = index
  
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', task.taskId)
    // 设置拖拽时的自定义图像（可选）
    const dragImage = event.target as HTMLElement
    if (dragImage) {
      event.dataTransfer.setDragImage(dragImage, 0, 0)
    }
  }
}

const handleDragEnd = () => {
  draggedTaskId.value = null
  draggedIndex.value = -1
  dragOverTaskId.value = null
}

const handleDragOver = (event: DragEvent, task: any, _index: number) => {
  event.preventDefault()
  if (task.status !== 'PENDING' || draggedTaskId.value === task.taskId) {
    return
  }
  dragOverTaskId.value = task.taskId
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'move'
  }
}

const handleDragLeave = () => {
  dragOverTaskId.value = null
}

const handleDrop = async (event: DragEvent, dropIndex: number) => {
  event.preventDefault()
  dragOverTaskId.value = null
  
  if (draggedIndex.value === -1 || draggedIndex.value === dropIndex) {
    return
  }
  
  // 获取所有待处理任务的ID列表
  const pendingTasks = queueInfo.value.tasks.filter(t => t.status === 'PENDING')
  const pendingTaskIds = pendingTasks.map(t => t.taskId)
  
  // 移动任务ID到新位置
  const [movedTaskId] = pendingTaskIds.splice(draggedIndex.value, 1)
  pendingTaskIds.splice(dropIndex, 1, movedTaskId)
  
  // 更新本地显示
  const reorderedTasks = [...queueInfo.value.tasks]
  const movedTask = reorderedTasks.find(t => t.taskId === movedTaskId)
  if (movedTask) {
    const oldIndex = reorderedTasks.indexOf(movedTask)
    reorderedTasks.splice(oldIndex, 1)
    reorderedTasks.splice(dropIndex, 0, movedTask)
    queueInfo.value.tasks = reorderedTasks
  }
  
  // 调用API保存新顺序
  try {
    await chatRoomApi.reorderTaskQueue(props.roomId, pendingTaskIds)
    // 刷新队列以确保状态同步
    await fetchQueue()
  } catch (error) {
    console.error('Failed to reorder tasks:', error)
    // 如果失败，恢复原顺序
    await fetchQueue()
    alert('排序失败，请重试')
  }
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
  // 如果队列为空，立即查询一次，然后开始定时刷新
  if (queueInfo.value.tasks.length === 0) {
    fetchQueue()
  }
  refreshInterval.value = window.setInterval(fetchQueue, 3000) // 每3秒刷新
})

onUnmounted(() => {
  if (refreshInterval.value) {
    clearInterval(refreshInterval.value)
  }
  // 清理拖拽事件监听
  if (isDragging.value) {
    document.removeEventListener('mousemove', onDrag)
    document.removeEventListener('mouseup', stopDrag)
  }
})

// 当 visible 变化时刷新
watch(() => props.visible, (newVal) => {
  if (newVal) {
    fetchQueue()
  } else {
    // 关闭时重置面板位置，下次打开使用默认位置
    panelPosition.value = { x: 0, y: 0 }
  }
})
</script>

<style scoped>
.task-queue-panel {
  position: fixed;
  width: 380px;
  max-height: 500px;
  background: white;
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
  z-index: 1000;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  user-select: none;
}

.queue-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-bottom: 1px solid #eee;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  cursor: grab;
  user-select: none;
}

.queue-header:active {
  cursor: grabbing;
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
  gap: 8px;
  padding: 12px;
  border-radius: 8px;
  margin-bottom: 8px;
  background: #f8f9fa;
  transition: all 0.2s;
  align-items: flex-start;
}

.queue-item.processing {
  background: linear-gradient(90deg, #dbeafe 0%, #bfdbfe 100%);
  border-left: 4px solid #3b82f6;
  animation: pulse-processing 2s infinite;
}

@keyframes pulse-processing {
  0%, 100% { box-shadow: 0 0 0 0 rgba(59, 130, 246, 0.4); }
  50% { box-shadow: 0 0 0 4px rgba(59, 130, 246, 0.2); }
}

.queue-item.pending {
  background: #f8f9fa;
}

.bot-type-badge {
  display: inline-block;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  margin-right: 6px;
  font-weight: 600;
  text-transform: uppercase;
}

.bot-type-badge.openclaw {
  background: #dbeafe;
  color: #1e40af;
}

.bot-type-badge.kimi {
  background: #fce7f3;
  color: #be185d;
}

.bot-type-badge.claude {
  background: #fef3c7;
  color: #92400e;
}

.task-number.processing {
  background: #3b82f6;
  color: white;
  animation: spin 2s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.cancel-btn.stop-btn {
  background: #fecaca;
  color: #dc2626;
  font-size: 12px;
}

.cancel-btn.stop-btn:hover:not(:disabled) {
  background: #f87171;
  color: white;
}

.queue-item.dragging {
  opacity: 0.5;
  transform: scale(1.02);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.queue-item.drag-over {
  border-top: 3px solid #667eea;
  transform: translateY(2px);
}

.drag-handle {
  cursor: grab;
  color: #999;
  font-size: 12px;
  padding: 4px 2px;
  user-select: none;
  line-height: 1;
}

.drag-handle:active {
  cursor: grabbing;
}

.queue-item.processing .drag-handle {
  display: none;
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

.cancel-btn {
  background: #fee2e2;
  color: #dc2626;
  border: none;
  border-radius: 4px;
  width: 28px;
  height: 28px;
  cursor: pointer;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
  flex-shrink: 0;
}

.cancel-btn:hover:not(:disabled) {
  background: #fecaca;
  color: #991b1b;
}

.cancel-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
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
    left: 10px !important;
    right: 10px !important;
    width: auto !important;
    top: 100px !important;
  }
}
</style>
