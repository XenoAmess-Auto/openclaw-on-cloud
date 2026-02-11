<template>
  <div class="admin-view">
    <header class="header">
      <router-link to="/" class="back">←</router-link>
      <h1>用户管理</h1>
      <span class="subtitle">管理员: {{ authStore.user?.username }}</span>
    </header>

    <div class="container">
      <div class="toolbar">
        <input 
          v-model="searchQuery" 
          type="text" 
          placeholder="搜索用户名..."
          class="search-input"
        />
        <button @click="loadUsers" class="btn-refresh">刷新</button>
      </div>

      <div v-if="loading" class="loading">加载中...</div>

      <table v-else class="user-table">
        <thead>
          <tr>
            <th>用户名</th>
            <th>邮箱</th>
            <th>角色</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in filteredUsers" :key="user.id">
            <td>{{ user.username }}</td>
            <td>{{ user.email }}</td>
            <td>
              <span v-for="role in user.roles" :key="role" class="role-badge" :class="role">
                {{ role.replace('ROLE_', '') }}
              </span>
            </td>
            <td>
              <span class="status-badge" :class="user.enabled ? 'enabled' : 'disabled'">
                {{ user.enabled ? '启用' : '禁用' }}
              </span>
            </td>
            <td>{{ formatDate(user.createdAt) }}</td>
            <td>
              <button 
                v-if="user.username !== 'admin'"
                @click="toggleUserStatus(user)"
                class="btn-action"
                :class="user.enabled ? 'btn-disable' : 'btn-enable'"
              >
                {{ user.enabled ? '禁用' : '启用' }}
              </button>
              <span v-else class="text-muted">-</span>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-if="filteredUsers.length === 0 && !loading" class="empty">
        没有找到用户
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import apiClient from '@/api/client'

interface User {
  id: string
  username: string
  email: string
  roles: string[]
  enabled: boolean
  createdAt: string
}

const router = useRouter()
const authStore = useAuthStore()
const users = ref<User[]>([])
const loading = ref(false)
const searchQuery = ref('')

const filteredUsers = computed(() => {
  if (!searchQuery.value) return users.value
  const query = searchQuery.value.toLowerCase()
  return users.value.filter(u => 
    u.username.toLowerCase().includes(query) ||
    u.email.toLowerCase().includes(query)
  )
})

onMounted(() => {
  // 检查是否是管理员
  if (!authStore.user?.roles?.includes('ROLE_ADMIN')) {
    router.push('/')
    return
  }
  loadUsers()
})

async function loadUsers() {
  loading.value = true
  try {
    const response = await apiClient.get('/admin/users')
    users.value = response.data
  } catch (err) {
    alert('加载用户列表失败')
  } finally {
    loading.value = false
  }
}

async function toggleUserStatus(user: User) {
  const action = user.enabled ? '禁用' : '启用'
  if (!confirm(`确定要${action}用户 "${user.username}" 吗？`)) return
  
  try {
    await apiClient.patch(`/admin/users/${user.id}/status`, { enabled: !user.enabled })
    user.enabled = !user.enabled
  } catch (err) {
    alert('操作失败')
  }
}

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleString('zh-CN')
}
</script>

<style scoped>
.admin-view {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--bg-color);
}

.header {
  height: 60px;
  background: var(--surface-color);
  border-bottom: 1px solid var(--border-color);
  display: flex;
  align-items: center;
  padding: 0 1.5rem;
  gap: 1rem;
}

.back {
  font-size: 1.25rem;
  color: var(--text-secondary);
  text-decoration: none;
}

h1 {
  font-size: 1.25rem;
  color: var(--text-primary);
}

.subtitle {
  margin-left: auto;
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.container {
  flex: 1;
  padding: 1.5rem;
  overflow: auto;
}

.toolbar {
  display: flex;
  gap: 1rem;
  margin-bottom: 1.5rem;
}

.search-input {
  flex: 1;
  max-width: 300px;
  padding: 0.5rem 1rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 0.875rem;
}

.btn-refresh {
  padding: 0.5rem 1rem;
  background: var(--primary-color);
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

.user-table {
  width: 100%;
  background: var(--surface-color);
  border-radius: 8px;
  overflow: hidden;
  border-collapse: collapse;
}

.user-table th,
.user-table td {
  padding: 1rem;
  text-align: left;
  border-bottom: 1px solid var(--border-color);
}

.user-table th {
  background: var(--bg-color);
  font-weight: 600;
  color: var(--text-secondary);
  font-size: 0.875rem;
}

.user-table tr:hover {
  background: var(--bg-color);
}

.role-badge {
  display: inline-block;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  font-size: 0.75rem;
  margin-right: 0.5rem;
}

.role-badge.ADMIN {
  background: #dc2626;
  color: white;
}

.role-badge.USER {
  background: var(--primary-color);
  color: white;
}

.status-badge {
  display: inline-block;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  font-size: 0.75rem;
}

.status-badge.enabled {
  background: #22c55e;
  color: white;
}

.status-badge.disabled {
  background: #6b7280;
  color: white;
}

.btn-action {
  padding: 0.375rem 0.75rem;
  border: none;
  border-radius: 4px;
  font-size: 0.75rem;
  cursor: pointer;
}

.btn-disable {
  background: #ef4444;
  color: white;
}

.btn-enable {
  background: #22c55e;
  color: white;
}

.text-muted {
  color: var(--text-secondary);
}

.loading, .empty {
  text-align: center;
  padding: 3rem;
  color: var(--text-secondary);
}
</style>
