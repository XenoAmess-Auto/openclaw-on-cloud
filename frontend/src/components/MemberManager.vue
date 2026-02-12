<template>
  <div class="modal" @click="$emit('close')">
    <div class="modal-content member-manager" @click.stop>
      <div class="modal-header">
        <h3>成员管理</h3>
        <button class="btn-close" @click="$emit('close')">×</button>
      </div>

      <div class="member-list" v-if="members.length > 0">
        <div
          v-for="member in members"
          :key="member.id"
          class="member-item"
          :class="{ creator: member.creator }"
        >
          <div class="member-avatar">
            <img v-if="member.avatar" :src="member.avatar" :alt="member.username" />
            <span v-else>{{ member.username[0].toUpperCase() }}</span>
          </div>

          <div class="member-info">
            <div class="member-header">
              <span class="member-name">{{ member.username }}</span>
              <span v-if="member.creator" class="creator-badge">群主</span>
            </div>
            <div class="member-email">{{ member.email }}</div>
          </div>

          <div class="member-actions" v-if="isCreator && !member.creator">
            <button
              @click="removeMember(member.id)"
              class="btn-remove"
              title="移除成员"
            >
              移除
            </button>
          </div>
        </div>
      </div>

      <div v-else class="empty-state">
        <p>暂无成员</p>
      </div>

      <!-- 添加成员 (仅群主可见) -->
      <div v-if="isCreator" class="add-member-section">
        <h4>添加成员</h4>
        <div class="add-member-form">
          <input
            v-model="newMemberUsername"
            type="text"
            placeholder="输入用户名"
            @keyup.enter="addMember"
          />
          <button
            @click="addMember"
            class="btn-add"
            :disabled="!newMemberUsername.trim() || adding"
          >
            {{ adding ? '添加中...' : '添加' }}
          </button>
        </div>

        <div v-if="addError" class="error-message">{{ addError }}</div>
      </div>

      <div v-if="loading" class="loading-overlay">
        <span>加载中...</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import apiClient from '@/api/client'

interface Member {
  id: string
  username: string
  email: string
  avatar: string | null
  creator: boolean
}

const props = defineProps<{
  roomId: string
  currentUserId: string
  creatorId: string
}>()

const emit = defineEmits<{
  close: []
  update: []
}>()

const members = ref<Member[]>([])
const loading = ref(false)
const newMemberUsername = ref('')
const adding = ref(false)
const addError = ref('')

const isCreator = computed(() => props.currentUserId === props.creatorId)

onMounted(() => {
  loadMembers()
})

async function loadMembers() {
  loading.value = true
  try {
    const response = await apiClient.get(`/chat-rooms/${props.roomId}/members`)
    members.value = response.data
  } catch (err: any) {
    console.error('Failed to load members:', err)
    alert('加载成员列表失败: ' + (err.response?.data?.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

async function addMember() {
  const username = newMemberUsername.value.trim()
  if (!username) return

  adding.value = true
  addError.value = ''

  try {
    // First find user by username
    const userResponse = await apiClient.get(`/users/by-username/${username}`)
    const userId = userResponse.data.id

    // Then add to room
    await apiClient.post(`/chat-rooms/${props.roomId}/members?userId=${userId}`)

    newMemberUsername.value = ''
    await loadMembers()
    emit('update')
  } catch (err: any) {
    addError.value = err.response?.data?.message || '添加失败，请检查用户名是否正确'
  } finally {
    adding.value = false
  }
}

async function removeMember(userId: string) {
  if (!confirm('确定要移除该成员吗？')) return

  try {
    await apiClient.delete(`/chat-rooms/${props.roomId}/members/${userId}`)
    await loadMembers()
    emit('update')
  } catch (err: any) {
    alert('移除失败: ' + (err.response?.data?.message || '未知错误'))
  }
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
  max-width: 500px;
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

.member-list {
  overflow-y: auto;
  padding: 1rem;
  flex: 1;
}

.member-item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem;
  border-radius: 8px;
  background: var(--bg-color);
  margin-bottom: 0.5rem;
}

.member-item.creator {
  background: rgba(34, 197, 94, 0.1);
  border: 1px solid #22c55e;
}

.member-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--primary-color);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-weight: 600;
  overflow: hidden;
}

.member-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.member-info {
  flex: 1;
  min-width: 0;
}

.member-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.25rem;
}

.member-name {
  font-weight: 500;
  color: var(--text-primary);
}

.creator-badge {
  font-size: 0.75rem;
  padding: 0.125rem 0.375rem;
  background: #22c55e;
  color: white;
  border-radius: 4px;
}

.member-email {
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.member-actions {
  display: flex;
  gap: 0.5rem;
}

.btn-remove {
  padding: 0.375rem 0.75rem;
  background: #ef4444;
  color: white;
  border: none;
  border-radius: 4px;
  font-size: 0.75rem;
  cursor: pointer;
}

.btn-remove:hover {
  background: #dc2626;
}

.add-member-section {
  padding: 1rem 1.5rem;
  border-top: 1px solid var(--border-color);
}

.add-member-section h4 {
  font-size: 0.875rem;
  color: var(--text-primary);
  margin-bottom: 0.75rem;
}

.add-member-form {
  display: flex;
  gap: 0.5rem;
}

.add-member-form input {
  flex: 1;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 0.875rem;
}

.btn-add {
  padding: 0.5rem 1rem;
  background: var(--primary-color);
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 0.875rem;
  cursor: pointer;
}

.btn-add:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.error-message {
  color: #ef4444;
  font-size: 0.875rem;
  margin-top: 0.5rem;
}

.empty-state {
  text-align: center;
  padding: 2rem;
  color: var(--text-secondary);
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
