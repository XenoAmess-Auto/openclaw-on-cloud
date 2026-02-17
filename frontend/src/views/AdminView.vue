<template>
  <div class="admin-view">
    <header class="header">
      <router-link to="/" class="back">←</router-link>
      <h1>管理后台</h1>
      <span class="subtitle">管理员: {{ authStore.user?.username }}</span>
    </header>

    <!-- 标签页切换 -->
    <div class="tabs">
      <button 
        class="tab-btn" 
        :class="{ active: activeTab === 'users' }"
        @click="activeTab = 'users'"
      >
        用户管理
      </button>
      <button 
        class="tab-btn" 
        :class="{ active: activeTab === 'bots' }"
        @click="activeTab = 'bots'"
      >
        机器人管理
      </button>
    </div>

    <div class="container">
      <!-- 用户管理标签页 -->
      <div v-if="activeTab === 'users'">
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

      <!-- 机器人管理标签页 -->
      <div v-else-if="activeTab === 'bots'" class="bot-management">
        <div class="toolbar">
          <input 
            v-model="botSearchQuery" 
            type="text" 
            placeholder="搜索机器人用户名..."
            class="search-input"
          />
          <button @click="showCreateBotDialog = true" class="btn-primary">+ 新建机器人</button>
          <button @click="loadBots" class="btn-refresh">刷新</button>
        </div>

        <div v-if="botsLoading" class="loading">加载中...</div>

        <table v-else class="user-table">
          <thead>
            <tr>
              <th>用户名</th>
              <th>类型</th>
              <th>Gateway</th>
              <th>状态</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="bot in filteredBots" :key="bot.id">
              <td>
                <div class="bot-info">
                  <img v-if="bot.avatarUrl" :src="bot.avatarUrl" class="bot-avatar" alt="avatar" />
                  <span class="bot-avatar-placeholder" v-else>🤖</span>
                  <span>{{ bot.username }}</span>
                </div>
              </td>
              <td>
                <span class="type-badge">{{ bot.botType || 'openclaw' }}</span>
              </td>
              <td class="gateway-cell" :title="bot.gatewayUrl">{{ bot.gatewayUrl }}</td>
              <td>
                <span class="status-badge" :class="bot.enabled ? 'enabled' : 'disabled'">
                  {{ bot.enabled ? '启用' : '禁用' }}
                </span>
              </td>
              <td>{{ formatDate(bot.createdAt) }}</td>
              <td>
                <button @click="editBot(bot)" class="btn-edit">编辑</button>
                <button @click="testBotConnection(bot)" class="btn-test-small" :disabled="testingBotId === bot.id">
                  {{ testingBotId === bot.id ? '测试中...' : '测试' }}
                </button>
                <button @click="deleteBot(bot)" class="btn-delete">删除</button>
              </td>
            </tr>
          </tbody>
        </table>

        <div v-if="filteredBots.length === 0 && !botsLoading" class="empty">
          没有找到机器人账户
        </div>
      </div>
    </div>

    <!-- 创建机器人弹窗 -->
    <div v-if="showCreateBotDialog" class="modal" @click="showCreateBotDialog = false">
      <div class="modal-content modal-lg" @click.stop>
        <h3>新建机器人</h3>
        
        <div class="form-row">
          <div class="form-group">
            <label>机器人用户名 *</label>
            <input v-model="newBot.username" type="text" placeholder="例如: openclaw" />
            <span class="hint">用户在聊天中 @ 此用户名来触发机器人</span>
          </div>
          
          <div class="form-group">
            <label>机器人类型</label>
            <select v-model="newBot.botType">
              <option value="openclaw">OpenClaw</option>
              <option value="custom">自定义</option>
            </select>
          </div>
        </div>
        
        <div class="form-row">
          <div class="form-group">
            <label>密码 *</label>
            <input v-model="newBot.password" type="password" placeholder="设置登录密码" />
          </div>
          
          <div class="form-group">
            <label>头像 URL</label>
            <input v-model="newBot.avatarUrl" type="text" placeholder="https://example.com/avatar.png" />
          </div>
        </div>
        
        <div class="form-group">
          <label>Gateway URL *</label>
          <input v-model="newBot.gatewayUrl" type="text" placeholder="http://localhost:18789" />
          <span class="hint">OpenClaw Gateway 服务地址</span>
        </div>
        
        <div class="form-group">
          <label>API Key</label>
          <input v-model="newBot.apiKey" type="password" placeholder="输入 API Key（可选）" />
        </div>
        
        <div class="form-group">
          <label>系统提示词</label>
          <textarea v-model="newBot.systemPrompt" rows="3" placeholder="You are a helpful assistant."></textarea>
          <span class="hint">定义机器人的行为方式和角色</span>
        </div>
        
        <div class="form-group checkbox-group">
          <label>
            <input type="checkbox" v-model="newBot.enabled" />
            启用机器人
          </label>
        </div>
        
        <div class="modal-actions">
          <button @click="showCreateBotDialog = false">取消</button>
          <button @click="createBot" class="btn-primary" :disabled="!isValidNewBot">创建</button>
        </div>
      </div>
    </div>

    <!-- 编辑机器人弹窗 -->
    <div v-if="showEditBotDialog" class="modal" @click="showEditBotDialog = false">
      <div class="modal-content modal-lg" @click.stop>
        <h3>编辑机器人: {{ editingBot?.username }}</h3>
        
        <div class="form-row">
          <div class="form-group">
            <label>机器人用户名</label>
            <input v-model="editBotForm.username" type="text" placeholder="例如: openclaw" />
          </div>
          
          <div class="form-group">
            <label>头像 URL</label>
            <input v-model="editBotForm.avatarUrl" type="text" placeholder="https://example.com/avatar.png" />
          </div>
        </div>
        
        <div class="form-group">
          <label>新密码（留空不修改）</label>
          <input v-model="editBotForm.password" type="password" placeholder="输入新密码" />
        </div>
        
        <div class="form-group">
          <label>Gateway URL</label>
          <input v-model="editBotForm.gatewayUrl" type="text" placeholder="http://localhost:18789" />
        </div>
        
        <div class="form-group">
          <label>API Key（留空保持原值）</label>
          <input v-model="editBotForm.apiKey" type="password" placeholder="输入新的 API Key" />
          <span v-if="editingBot?.apiKey" class="hint">当前: {{ editingBot.apiKey }}</span>
        </div>
        
        <div class="form-group">
          <label>系统提示词</label>
          <textarea v-model="editBotForm.systemPrompt" rows="3" placeholder="You are a helpful assistant."></textarea>
        </div>
        
        <div class="form-group checkbox-group">
          <label>
            <input type="checkbox" v-model="editBotForm.enabled" />
            启用机器人
          </label>
        </div>
        
        <div class="modal-actions">
          <button @click="showEditBotDialog = false">取消</button>
          <button @click="updateBot" class="btn-primary">保存</button>
        </div>
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

interface BotUser {
  id: string
  username: string
  avatarUrl?: string
  gatewayUrl: string
  apiKey?: string
  systemPrompt?: string
  enabled: boolean
  botType?: string
  createdAt?: string
  updatedAt?: string
}

const router = useRouter()
const authStore = useAuthStore()

// 标签页
const activeTab = ref('users')

// 用户管理
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

// 机器人管理
const bots = ref<BotUser[]>([])
const botsLoading = ref(false)
const botSearchQuery = ref('')
const testingBotId = ref<string | null>(null)
const testResult = ref<{ success: boolean; message: string } | null>(null)

// 创建机器人
const showCreateBotDialog = ref(false)
const newBot = ref({
  username: '',
  password: '',
  avatarUrl: '',
  gatewayUrl: 'http://localhost:18789',
  apiKey: '',
  systemPrompt: 'You are a helpful assistant.',
  enabled: true,
  botType: 'openclaw'
})

// 编辑机器人
const showEditBotDialog = ref(false)
const editingBot = ref<BotUser | null>(null)
const editBotForm = ref({
  username: '',
  avatarUrl: '',
  password: '',
  gatewayUrl: '',
  apiKey: '',
  systemPrompt: '',
  enabled: true
})

const isValidNewUser = computed(() => {
  const u = newUser.value.username?.trim() || ''
  const e = newUser.value.email?.trim() || ''
  const p = newUser.value.password || ''
  return u.length >= 3 && e.includes('@') && p.length >= 6
})

const isValidNewBot = computed(() => {
  const u = newBot.value.username?.trim() || ''
  const p = newBot.value.password || ''
  const g = newBot.value.gatewayUrl?.trim() || ''
  return u.length >= 3 && p.length >= 6 && g.length > 0
})

const filteredUsers = computed(() => {
  if (!searchQuery.value) return users.value
  const query = searchQuery.value.toLowerCase()
  return users.value.filter(u => 
    u.username.toLowerCase().includes(query) ||
    u.email.toLowerCase().includes(query)
  )
})

const filteredBots = computed(() => {
  if (!botSearchQuery.value) return bots.value
  const query = botSearchQuery.value.toLowerCase()
  return bots.value.filter(b => 
    b.username.toLowerCase().includes(query) ||
    (b.botType || '').toLowerCase().includes(query)
  )
})

onMounted(() => {
  if (!authStore.user?.roles?.includes('ROLE_ADMIN')) {
    router.push('/')
    return
  }
  loadUsers()
  loadBots()
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

// ============ 机器人管理 ============

async function loadBots() {
  botsLoading.value = true
  try {
    const response = await apiClient.get('/admin/bots')
    console.log('loadBots response:', response.data)
    bots.value = response.data.map((bot: any) => ({
      ...bot,
      enabled: Boolean(bot.enabled)
    }))
    console.log('processed bots:', bots.value)
  } catch (err: any) {
    alert('加载机器人列表失败: ' + (err.response?.data?.message || '未知错误'))
  } finally {
    botsLoading.value = false
  }
}

async function createBot() {
  try {
    await apiClient.post('/admin/bots', {
      username: newBot.value.username,
      password: newBot.value.password,
      avatarUrl: newBot.value.avatarUrl,
      gatewayUrl: newBot.value.gatewayUrl,
      apiKey: newBot.value.apiKey,
      systemPrompt: newBot.value.systemPrompt,
      enabled: newBot.value.enabled,
      botType: newBot.value.botType
    })
    showCreateBotDialog.value = false
    newBot.value = {
      username: '',
      password: '',
      avatarUrl: '',
      gatewayUrl: 'http://localhost:18789',
      apiKey: '',
      systemPrompt: 'You are a helpful assistant.',
      enabled: true,
      botType: 'openclaw'
    }
    loadBots()
  } catch (err: any) {
    alert('创建失败: ' + (err.response?.data?.message || '未知错误'))
  }
}

function editBot(bot: BotUser) {
  editingBot.value = bot
  // 逐个赋值保持响应式
  editBotForm.value.username = bot.username
  editBotForm.value.avatarUrl = bot.avatarUrl || ''
  editBotForm.value.password = ''
  editBotForm.value.gatewayUrl = bot.gatewayUrl
  editBotForm.value.apiKey = ''
  editBotForm.value.systemPrompt = bot.systemPrompt || ''
  editBotForm.value.enabled = Boolean(bot.enabled)
  console.log('editBot - bot.enabled:', bot.enabled, typeof bot.enabled)
  console.log('editBot - form.enabled after set:', editBotForm.value.enabled)
  showEditBotDialog.value = true
}

async function updateBot() {
  if (!editingBot.value) return
  
  console.log('updateBot - form data:', editBotForm.value)
  console.log('updateBot - enabled value:', editBotForm.value.enabled, typeof editBotForm.value.enabled)
  
  try {
    const payload: any = {
      username: editBotForm.value.username,
      avatarUrl: editBotForm.value.avatarUrl,
      gatewayUrl: editBotForm.value.gatewayUrl,
      systemPrompt: editBotForm.value.systemPrompt,
      enabled: Boolean(editBotForm.value.enabled)
    }
    
    console.log('updateBot - sending payload:', payload)
    
    if (editBotForm.value.password) {
      payload.password = editBotForm.value.password
    }
    
    if (editBotForm.value.apiKey) {
      payload.apiKey = editBotForm.value.apiKey
    }
    
    const response = await apiClient.put(`/admin/bots/${editingBot.value.id}`, payload)
    console.log('updateBot - response:', response.data)
    
    showEditBotDialog.value = false
    loadBots()
  } catch (err: any) {
    console.error('updateBot - error:', err)
    alert('更新失败: ' + (err.response?.data?.message || '未知错误'))
  }
}

async function deleteBot(bot: BotUser) {
  if (!confirm(`确定要删除机器人 "${bot.username}" 吗？此操作不可恢复。`)) return
  
  try {
    await apiClient.delete(`/admin/bots/${bot.id}`)
    loadBots()
  } catch (err: any) {
    alert('删除失败: ' + (err.response?.data?.message || '未知错误'))
  }
}

async function testBotConnection(bot: BotUser) {
  testingBotId.value = bot.id
  testResult.value = null
  
  try {
    const gatewayUrl = bot.gatewayUrl
    const response = await fetch(`${gatewayUrl}/health`, {
      method: 'GET',
      signal: AbortSignal.timeout(5000)
    })
    
    if (response.ok) {
      alert(`连接成功！Gateway 服务正常运行。`)
    } else {
      alert(`连接失败: HTTP ${response.status}`)
    }
  } catch (err: any) {
    alert(`连接失败: ${err.message || '无法连接到 Gateway'}`)
  } finally {
    testingBotId.value = null
  }
}

function formatDate(dateStr: string | undefined) {
  if (!dateStr) return '-'
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

/* 标签页 */
.tabs {
  display: flex;
  background: var(--surface-color);
  border-bottom: 1px solid var(--border-color);
  padding: 0 1.5rem;
}

.tab-btn {
  padding: 0.875rem 1.5rem;
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  color: var(--text-secondary);
  font-size: 0.9375rem;
  cursor: pointer;
  transition: all 0.2s;
}

.tab-btn:hover {
  color: var(--text-primary);
}

.tab-btn.active {
  color: var(--primary-color);
  border-bottom-color: var(--primary-color);
  font-weight: 500;
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

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-refresh {
  padding: 0.5rem 1rem;
  background: var(--surface-color);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  cursor: pointer;
}

/* 机器人配置样式 */
.bot-config {
  max-width: 640px;
}

/* 机器人管理样式 */
.bot-management {
  max-width: 100%;
}

.bot-info {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.bot-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  object-fit: cover;
}

.bot-avatar-placeholder {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--border-color);
  font-size: 1rem;
}

.type-badge {
  display: inline-block;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  font-size: 0.75rem;
  background: var(--primary-color);
  color: white;
}

.gateway-cell {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.btn-test-small {
  padding: 0.375rem 0.75rem;
  background: #22c55e;
  color: white;
  border: none;
  border-radius: 4px;
  font-size: 0.75rem;
  cursor: pointer;
  margin-right: 0.5rem;
}

.btn-test-small:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 弹窗表单行 */
.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
}

.form-row .form-group {
  margin-bottom: 1rem;
}

.modal-lg {
  max-width: 600px;
}

select {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 0.9375rem;
  background: var(--bg-color);
  color: var(--text-primary);
  cursor: pointer;
}

.config-card {
  background: var(--surface-color);
  border-radius: 12px;
  padding: 1.5rem;
  margin-bottom: 1rem;
  border: 1px solid var(--border-color);
}

.config-card h3 {
  margin: 0 0 0.5rem;
  font-size: 1.125rem;
  color: var(--text-primary);
}

.config-desc {
  margin: 0 0 1.5rem;
  color: var(--text-secondary);
  font-size: 0.875rem;
}

.config-card .form-group {
  margin-bottom: 1.25rem;
}

.config-card .form-group label {
  display: block;
  margin-bottom: 0.5rem;
  color: var(--text-primary);
  font-size: 0.875rem;
  font-weight: 500;
}

.config-card .form-group input[type="text"],
.config-card .form-group input[type="password"],
.config-card .form-group textarea {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 0.9375rem;
  background: var(--bg-color);
  color: var(--text-primary);
  box-sizing: border-box;
}

.config-card .form-group textarea {
  resize: vertical;
  min-height: 100px;
  font-family: inherit;
}

.config-card .form-group input:focus,
.config-card .form-group textarea:focus {
  outline: none;
  border-color: var(--primary-color);
}

.config-card .hint {
  display: block;
  margin-top: 0.375rem;
  font-size: 0.8125rem;
  color: var(--text-secondary);
}

.config-card .checkbox-group label {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  cursor: pointer;
}

.config-card .checkbox-group input[type="checkbox"] {
  width: 18px;
  height: 18px;
  cursor: pointer;
}

.config-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1.5rem;
  padding-top: 1.5rem;
  border-top: 1px solid var(--border-color);
}

.config-actions button {
  padding: 0.625rem 1.25rem;
  border-radius: 6px;
  font-size: 0.9375rem;
  cursor: pointer;
}

.test-card {
  margin-top: 1rem;
}

.btn-test {
  padding: 0.625rem 1.25rem;
  background: #22c55e;
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 0.9375rem;
  cursor: pointer;
}

.btn-test:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.test-result {
  margin-top: 1rem;
  padding: 0.75rem 1rem;
  border-radius: 6px;
  font-size: 0.875rem;
}

.test-result.success {
  background: #dcfce7;
  color: #166534;
  border: 1px solid #86efac;
}

.test-result.error {
  background: #fee2e2;
  color: #991b1b;
  border: 1px solid #fca5a5;
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

/* 通用 checkbox-group 样式（用于弹窗） */
.checkbox-group label {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  cursor: pointer;
}

.checkbox-group input[type="checkbox"] {
  width: 18px;
  height: 18px;
  cursor: pointer;
  accent-color: var(--primary-color);
  background: var(--bg-color);
  border: 2px solid var(--border-color);
  border-radius: 4px;
  appearance: auto;
  -webkit-appearance: checkbox;
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

  .tabs {
    padding: 0;
    overflow-x: auto;
  }

  .tab-btn {
    padding: 0.75rem 1rem;
    font-size: 0.875rem;
    white-space: nowrap;
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

  /* 机器人配置移动端适配 */
  .bot-config {
    max-width: 100%;
  }

  .config-card {
    padding: 1rem;
  }

  .config-card h3 {
    font-size: 1rem;
  }

  .config-desc {
    font-size: 0.8125rem;
  }

  .config-card .form-group {
    margin-bottom: 1rem;
  }

  .config-actions {
    flex-direction: column;
  }

  .config-actions button {
    width: 100%;
    padding: 0.75rem;
  }

  .btn-test {
    width: 100%;
    padding: 0.75rem;
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
