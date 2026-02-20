<template>
  <div class="chat-header">
    <div class="room-info">
      <h3>{{ roomName || '聊天室' }}</h3>
      <span :class="['status', { connected: isConnected }]">
        {{ isConnected ? '已连接' : '连接中...' }}
      </span>
    </div>
    <div class="chat-actions">
      <button v-if="isCreator" class="btn-danger" @click="$emit('dismiss')">解散</button>
      <button @click="$emit('show-members')">成员</button>
      <button @click="$emit('show-sessions')">会话</button>
      <button @click="$emit('show-task-queue')">队列</button>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  roomName?: string
  isConnected: boolean
  isCreator: boolean
}>()

defineEmits<{
  dismiss: []
  'show-members': []
  'show-sessions': []
  'show-task-queue': []
}>()
</script>

<style scoped>
.chat-header {
  padding: 1rem;
  border-bottom: 1px solid var(--border-color);
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;
}

.room-info {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.room-info h3 {
  margin: 0;
  font-size: 1.125rem;
  color: var(--text-primary);
}

.status {
  font-size: 0.75rem;
  color: var(--text-secondary);
  padding: 0.25rem 0.5rem;
  background: var(--surface-color);
  border-radius: 4px;
}

.status.connected {
  color: #10b981;
  background: rgba(16, 185, 129, 0.1);
}

.chat-actions {
  display: flex;
  gap: 0.5rem;
}

.chat-actions button {
  padding: 0.375rem 0.75rem;
  background: var(--surface-color);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  cursor: pointer;
  font-size: 0.875rem;
  color: var(--text-primary);
  transition: all 0.2s;
}

.chat-actions button:hover {
  background: var(--border-color);
}

.chat-actions .btn-danger {
  background: #fee2e2;
  border-color: #fecaca;
  color: #dc2626;
}

.chat-actions .btn-danger:hover {
  background: #fecaca;
}

/* ============================================
   移动端适配 - Mobile Responsive Styles
   ============================================ */

@media (max-width: 768px) {
  .chat-header {
    padding: 0.75rem 1rem;
    flex-wrap: wrap;
    gap: 0.5rem;
  }

  .room-info {
    flex: 1;
    min-width: 0;
  }

  .room-info h3 {
    font-size: 1rem;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 150px;
  }

  .status {
    font-size: 0.6875rem;
    padding: 0.125rem 0.375rem;
    white-space: nowrap;
  }

  .chat-actions {
    gap: 0.375rem;
    flex-wrap: nowrap;
  }

  .chat-actions button {
    padding: 0.25rem 0.5rem;
    font-size: 0.75rem;
    white-space: nowrap;
  }
}

/* 小屏手机 */
@media (max-width: 380px) {
  .chat-header {
    padding: 0.625rem 0.75rem;
  }

  .room-info h3 {
    max-width: 120px;
    font-size: 0.9375rem;
  }

  .chat-actions button {
    padding: 0.25rem 0.4375rem;
    font-size: 0.6875rem;
  }
}
</style>
