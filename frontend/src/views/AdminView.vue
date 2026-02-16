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
          placeholder="搜索用户名或邮箱..."
          class="search-input"
        />
        <button @click="showCreateDialog = true" class="btn-primary">+ 新建用户</button>
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
              <button @click="editUser(user)" class="btn-edit" :disabled="user.username === 'admin'">编辑</button>
              <button @click="deleteUser(user)" class="btn-delete" :disabled="user.username === 'admin'">删除</button>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-if="filteredUsers.length === 0 && !loading" class="empty">
        没有找到用户
      </div>
    </div>

    <!-- 创建用户弹窗 -->
    <div v-if="showCreateDialog" class="modal" @click="showCreateDialog = false">
      <div class="modal-content" @click.stop>
        <h3>新建用户</h3>
        
        <div class="form-group">
          <label>用户名 *</label>
          <input v-model="newUser.username" type="text" placeholder="用户名" />
        </div>
        
        <div class="form-group">
          <label>邮箱 *</label>
          <input v-model="newUser.email" placeholder="邮箱" type="email" />
        </div>
        
        <div class="form-group">
          <label>密码 *</label>
          <input v-model="newUser.password" placeholder="密码" type="password" />
        </div>
        
        <div class="form-group">
          <label><input type="checkbox" v-model="newUser.isAdmin" /> 设为管理员</label>
        </div>
        
        <div class="form-group">
          <label><input type="checkbox" v-model="newUser.enabled" /> 启用账号</label>
        </div>
        
        <div class="modal-actions">
          <button @click="showCreateDialog = false">取消</button>
          <button @click="createUser" class="btn-primary">创建</button>
        </div>
      </div>
    </div>

    <!-- 编辑用户弹窗 -->
    <div v-if="showEditDialog" class="modal" @click="showEditDialog = false">
      <div class="modal-content" @click.stop>
        <h3>编辑用户: {{ editingUser?.username }}</h3>
        
        <div class="form-group">
          <label>邮箱</label>
          <input v-model="editForm.email" placeholder="邮箱" type="email" />
        </div>
        
        <div class="form-group">
          <label>新密码（留空不修改）</label>
          <input v-model="editForm.password" placeholder="新密码" type="password" />
        </div>
        
        <div class="form-group">
          <label><input type="checkbox" v-model="editForm.isAdmin" /> 管理员权限</label>
        </div>
        
        <div class="form-group">
          <label><input type="checkbox" v-model="editForm.enabled" /> 账号启用</label>
        </div>
        
        <div class="modal-actions">
          <button @click="showEditDialog = false">取消</button>
          <button @click="updateUser" class="btn-primary">保存</button>
        </div>
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

// 创建用户
const showCreateDialog = ref(false)
const newUser = ref({
  username: '',
  email: '',
  password: '',
  isAdmin: false,
  enabled: true
})

// 编辑用户
const showEditDialog = ref(false)
const editingUser = ref<User | null>(null)
const editForm = ref({
  email: '',
  password: '',
  isAdmin: false,
  enabled: true
})

const isValidNewUser = computed(() => {
  const u = newUser.value.username?.trim() || ''
  const e = newUser.value.email?.trim() || ''
  const p = newUser.value.password || ''
  return u.length >= 3 && e.includes('@') && p.length >= 6
})

const filteredUsers = computed(() => {
  if (!searchQuery.value) return users.value
  const query = searchQuery.value.toLowerCase()
  return users.value.filter(u => 
    u.username.toLowerCase().includes(query) ||
    u.email.toLowerCase().includes(query)
  )
})

onMounted(() => {
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
  } catch (err: any) {
    alert('加载用户列表失败: ' + (err.response?.data?.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

async function createUser() {
  console.log('createUser called', newUser.value)
  console.log('isValidNewUser:', isValidNewUser.value)
  try {
    await apiClient.post('/admin/users', {
      username: newUser.value.username,
      email: newUser.value.email,
      password: newUser.value.password,
      enabled: newUser.value.enabled,
      isAdmin: newUser.value.isAdmin
    })
    showCreateDialog.value = false
    newUser.value = { username: '', email: '', password: '', isAdmin: false, enabled: true }
    loadUsers()
  } catch (err: any) {
    console.error('createUser error:', err)
    alert('创建失败: ' + (err.response?.data?.message || '未知错误'))
  }
}

function editUser(user: User) {
  editingUser.value = user
  editForm.value = {
    email: user.email,
    password: '',
    isAdmin: user.roles.includes('ROLE_ADMIN'),
    enabled: user.enabled
  }
  showEditDialog.value = true
}

async function updateUser() {
  if (!editingUser.value) return
  
  try {
    const roles = editForm.value.isAdmin 
      ? ['ROLE_ADMIN', 'ROLE_USER'] 
      : ['ROLE_USER']
    
    await apiClient.put(`/admin/users/${editingUser.value.id}`, {
      email: editForm.value.email,
      password: editForm.value.password || undefined,
      enabled: editForm.value.enabled,
      roles: roles
    })
    showEditDialog.value = false
    loadUsers()
  } catch (err: any) {
    alert('更新失败: ' + (err.response?.data?.message || '未知错误'))
  }
}

async function deleteUser(user: User) {
  if (!confirm(`确定要删除用户 "${user.username}" 吗？此操作不可恢复。`)) return
  
  try {
    await apiClient.delete(`/admin/users/${user.id}`)
    loadUsers()
  } catch (err: any) {
    alert('删除失败: ' + (err.response?.data?.message || '未知错误'))
  }
}

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleString('zh-CN')
}
</script>

<style scoped>
.admin-view {
  height: 100vh;
  height: 100dvh;
  display: flex;
  flex-direction: column;
  background: var(--bg-color);
  /* 移动端安全区域适配 */
  padding-top: env(safe-area-inset-top);
  padding-bottom: env(safe-area-inset-bottom);
  padding-left: env(safe-area-inset-left);
  padding-right: env(safe-area-inset-right);
  box-sizing: border-box;
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

.btn-primary {
  padding: 0.5rem 1rem;
  background: var(--primary-color);
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

.btn-refresh {
  padding: 0.5rem 1rem;
  background: var(--surface-color);
  border: 1px solid var(--border-color);
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

.role-badge.ROLE_ADMIN {
  background: #dc2626;
  color: white;
}

.role-badge.ROLE_USER {
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

.btn-edit, .btn-delete {
  padding: 0.375rem 0.75rem;
  border: none;
  border-radius: 4px;
  font-size: 0.75rem;
  cursor: pointer;
  margin-right: 0.5rem;
}

.btn-edit {
  background: #3b82f6;
  color: white;
}

.btn-delete {
  background: #ef4444;
  color: white;
}

.btn-edit:disabled, .btn-delete:disabled {
  background: #9ca3af;
  cursor: not-allowed;
}

.loading, .empty {
  text-align: center;
  padding: 3rem;
  color: var(--text-secondary);
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
  display: flex;
  align-items: center;
  gap: 0.5rem;
  color: var(--text-primary);
}

.form-group input[type="text"],
.form-group input[type="email"],
.form-group input[type="password"] {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 1rem;
  margin-top: 0.25rem;
}

.form-group input[type="checkbox"] {
  width: 16px;
  height: 16px;
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

.modal-actions button:not(.btn-primary) {
  background: transparent;
  border: 1px solid var(--border-color);
}

/* 移动端适配 */
@media (max-width: 768px) {
  .admin-view {
    height: 100dvh;
  }

  .header {
    height: 56px;
    padding: 0 1rem;
  }

  .back {
    font-size: 1.25rem;
    margin-right: 0.75rem;
  }

  h1 {
    font-size: 1.125rem;
  }

  .subtitle {
    display: none; /* 移动端隐藏副标题 */
  }

  .container {
    padding: 1rem;
  }

  .toolbar {
    flex-wrap: wrap;
    gap: 0.75rem;
    margin-bottom: 1rem;
  }

  .search-input {
    flex: 1 1 100%;
    max-width: none;
    padding: 0.625rem 0.875rem;
    font-size: 16px;
  }

  .btn-primary,
  .btn-refresh {
    flex: 1;
    padding: 0.625rem 1rem;
    font-size: 0.875rem;
    min-height: 44px;
  }

  /* 表格改为卡片布局 */
  .user-table {
    display: block;
    background: transparent;
  }

  .user-table thead {
    display: none;
  }

  .user-table tbody {
    display: block;
  }

  .user-table tr {
    display: block;
    background: var(--surface-color);
    border-radius: 10px;
    margin-bottom: 0.75rem;
    padding: 1rem;
    box-shadow: 0 2px 8px rgba(0,0,0,0.05);
  }

  .user-table td {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 0.5rem 0;
    border-bottom: 1px solid var(--border-color);
    font-size: 0.875rem;
  }

  .user-table td:last-child {
    border-bottom: none;
    padding-bottom: 0;
    margin-top: 0.5rem;
    justify-content: flex-start;
  }

  .user-table td::before {
    content: attr(data-label);
    font-weight: 600;
    color: var(--text-secondary);
    font-size: 0.75rem;
  }

  .role-badge,
  .status-badge {
    font-size: 0.6875rem;
    padding: 0.25rem 0.5rem;
  }

  .btn-edit,
  .btn-delete {
    flex: 1;
    padding: 0.5rem 0.75rem;
    font-size: 0.8125rem;
    margin-right: 0;
  }

  .btn-edit {
    margin-right: 0.5rem;
  }

  .loading,
  .empty {
    padding: 2rem;
    font-size: 0.875rem;
  }

  .modal {
    align-items: flex-end;
  }

  .modal-content {
    width: 100%;
    max-width: none;
    border-radius: 16px 16px 0 0;
    padding: 1.25rem;
    max-height: 85vh;
    overflow-y: auto;
  }

  .modal-content h3 {
    font-size: 1.125rem;
  }

  .form-group input[type="text"],
  .form-group input[type="email"],
  .form-group input[type="password"] {
    padding: 0.75rem;
    font-size: 16px;
  }

  .modal-actions {
    margin-top: 1.25rem;
  }

  .modal-actions button {
    flex: 1;
    padding: 0.625rem 1rem;
    min-height: 44px;
  }
}

/* 小屏手机 */
@media (max-width: 380px) {
  .header {
    height: 52px;
    padding: 0 0.75rem;
  }

  h1 {
    font-size: 1rem;
  }

  .container {
    padding: 0.75rem;
  }

  .user-table tr {
    padding: 0.875rem;
  }

  .user-table td {
    font-size: 0.8125rem;
  }
}
</style>
