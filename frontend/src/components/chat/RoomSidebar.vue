<template>
  <aside class="sidebar">
    <div class="section-header">
      <h2>聊天室</h2>
      <button class="btn-add" @click="$emit('create-room')">+</button>
    </div>
    
    <div v-if="loading" class="loading">加载中...</div>
    
    <ul v-else class="room-list">
      <li
        v-for="room in rooms"
        :key="room.id"
        @click="$emit('enter-room', room.id)"
        :class="['room-item', { active: currentRoomId === room.id }]"
      >
        <div class="room-name">{{ room.name }}</div>
        <div class="room-meta">{{ room.memberIds.length }} 成员</div>
      </li>
    </ul>
    
    <div v-if="rooms.length === 0 && !loading" class="empty">
      暂无聊天室，创建一个吧
    </div>
  </aside>
</template>

<script setup lang="ts">
import type { ChatRoom } from '@/types'

defineProps<{
  rooms: ChatRoom[]
  currentRoomId?: string
  loading: boolean
}>()

defineEmits<{
  'create-room': []
  'enter-room': [roomId: string]
}>()
</script>

<style scoped>
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

/* ============================================
   移动端适配 - Mobile Responsive Styles
   ============================================ */

@media (max-width: 768px) {
  .sidebar {
    width: 100%;
    height: auto;
    max-height: 160px;
    border-right: none;
    border-bottom: 1px solid var(--border-color);
    flex-shrink: 0;
  }

  .section-header {
    padding: 0.75rem 1rem;
  }

  .section-header h2 {
    font-size: 0.9375rem;
  }

  .btn-add {
    width: 24px;
    height: 24px;
    font-size: 1rem;
  }

  .room-list {
    display: flex;
    flex-direction: row;
    overflow-x: auto;
    overflow-y: hidden;
    padding: 0.5rem;
    gap: 0.5rem;
    flex-wrap: nowrap;
    -webkit-overflow-scrolling: touch;
    scrollbar-width: none;
  }

  .room-list::-webkit-scrollbar {
    display: none;
  }

  .room-item {
    flex: 0 0 auto;
    min-width: 120px;
    max-width: 160px;
    padding: 0.625rem 0.875rem;
    border-bottom: none;
    border-radius: 10px;
    border: 1px solid var(--border-color);
    margin-right: 0;
  }

  .room-item.active {
    border-left: none;
    border: 2px solid var(--primary-color);
    background: rgba(59, 130, 246, 0.08);
  }

  .room-name {
    font-size: 0.875rem;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .room-meta {
    font-size: 0.6875rem;
  }

  .loading, .empty {
    padding: 1rem;
    font-size: 0.875rem;
  }
}

/* 小屏手机额外优化 */
@media (max-width: 380px) {
  .sidebar {
    max-height: 140px;
  }

  .room-item {
    min-width: 100px;
    padding: 0.5rem 0.75rem;
  }
}

/* 横屏模式优化 */
@media (max-height: 500px) and (orientation: landscape) {
  .sidebar {
    max-height: 100px;
  }
}
</style>
