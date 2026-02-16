<template>
  <div class="settings-view">
    <header class="header">
      <div class="logo">OOC</div>
      <div class="nav-links">
        <router-link to="/" class="nav-link">&larr; 返回聊天</router-link>
      </div>
    </header>

    <div class="container">
      <div class="settings-card">
        <h2>用户设置</h2>

        <!-- 后端地址配置 -->
        <div class="form-section">
          <h3>后端服务器地址</h3>
          
          <div class="form-group">
            <label>自定义后端地址（可选）</label>
            <input 
              v-model="backendUrl"
              type="text"
              placeholder="http://localhost:8081"
            />
            <p class="help-text">
              留空则使用默认值：{{ currentBackendUrl }}
            </p>
            <p class="help-text">
              格式：http://host:port 或 https://host:port
            </p>
          </div>

          <div v-if="configError" class="error-message">{{ configError }}</div>
          <div v-if="configSuccess" class="success-message">{{ configSuccess }}</div>

          <div class="form-actions-inline">
            <button 
              @click="saveBackendConfig" 
              class="btn-primary"
              :disabled="configSaving"
            >
              {{ configSaving ? '保存中...' : '保存后端配置' }}
            </button>
            <button 
              v-if="backendUrl"
              @click="backendUrl = ''; saveBackendConfig()" 
              class="btn-secondary"
              :disabled="configSaving"
            >
              恢复默认
            </button>
          </div>
        </div>

        <!-- 头像上传 -->
        <div class="form-section">
          <div class="avatar-section">
            <div class="avatar-preview" @click="triggerFileInput">
              <img 
                v-if="avatarPreview" 
                :src="avatarPreview" 
                alt="Avatar"
                class="avatar-image"
              />
              <div v-else class="avatar-placeholder">
                {{ authStore.user?.nickname?.charAt(0).toUpperCase() || authStore.user?.username?.charAt(0).toUpperCase() }}
              </div>
              <div class="avatar-overlay">
                <span>点击更换头像</span>
              </div>
            </div>
            <input
              ref="fileInput"
              type="file"
              accept="image/*"
              style="display: none"
              @change="handleFileChange"
            />
            <p v-if="uploadError" class="error-text">{{ uploadError }}</p>
          </div>
        </div>

        <!-- 基本信息表单 -->
        <form @submit.prevent="saveProfile" class="settings-form">
          <div class="form-section">
            <h3>基本信息</h3>
            
            <div class="form-group">
              <label>用户名</label>
              <input 
                type="text" 
                :value="authStore.user?.username" 
                disabled
                class="input-disabled"
              />
              <p class="help-text">用户名不可修改</p>
            </div>

            <div class="form-group">
              <label>昵称</label>
              <input 
                v-model="form.nickname"
                type="text"
                placeholder="输入昵称"
                maxlength="50"
              />
              <p class="help-text">显示在聊天中的名称</p>
            </div>

            <div class="form-group">
              <label>邮箱</label>
              <input 
                v-model="form.email"
                type="email"
                placeholder="输入邮箱"
              />
            </div>
          </div>

          <div class="form-section">
            <h3>修改密码（可选）</h3>
            
            <div class="form-group">
              <label>当前密码</label>
              <input 
                v-model="form.currentPassword"
                type="password"
                placeholder="如需修改密码请输入当前密码"
              />
            </div>

            <div class="form-group">
              <label>新密码</label>
              <input 
                v-model="form.newPassword"
                type="password"
                placeholder="至少6位字符"
                :disabled="!form.currentPassword"
              />
            </div>
          </div>

          <div v-if="errorMessage" class="error-message">{{ errorMessage }}</div>
          <div v-if="successMessage" class="success-message">{{ successMessage }}</div>

          <div class="form-actions">
            <button 
              type="submit" 
              class="btn-primary"
              :disabled="saving"
            >
              {{ saving ? '保存中...' : '保存设置' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { fileApi } from '@/api/file'
import {
  loadConfig,
  saveConfig as saveConfigUtil,
  resetConfig as resetConfigUtil,
  getBaseUrl
} from '@/utils/config'

const authStore = useAuthStore()
const fileInput = ref<HTMLInputElement>()

const avatarPreview = ref('')
const uploadedAvatarUrl = ref('')
const uploadError = ref('')
const saving = ref(false)
const errorMessage = ref('')
const successMessage = ref('')

const form = reactive({
  nickname: '',
  email: '',
  currentPassword: '',
  newPassword: ''
})

// 后端地址配置
const backendUrl = ref('')
const configSaving = ref(false)
const configError = ref('')
const configSuccess = ref('')

// 当前使用的后端地址
const currentBackendUrl = computed(() => getBaseUrl())

onMounted(() => {
  // 初始化表单数据
  if (authStore.user) {
    form.nickname = authStore.user.nickname || authStore.user.username
    form.email = authStore.user.email
    avatarPreview.value = authStore.user.avatar || ''
  }
  // 初始化后端地址配置
  const config = loadConfig()
  backendUrl.value = config.baseUrl || ''
})

function triggerFileInput() {
  fileInput.value?.click()
}

async function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return

  uploadError.value = ''

  // 验证文件类型
  if (!file.type.startsWith('image/')) {
    uploadError.value = '请上传图片文件'
    return
  }

  // 验证文件大小（最大 5MB）
  if (file.size > 5 * 1024 * 1024) {
    uploadError.value = '图片大小不能超过 5MB'
    return
  }

  try {
    // 上传文件
    const response = await fileApi.upload(file)
    uploadedAvatarUrl.value = response.data.url
    avatarPreview.value = response.data.url
  } catch (error) {
    uploadError.value = '上传失败，请重试'
    console.error('Avatar upload failed:', error)
  }
}

async function saveProfile() {
  errorMessage.value = ''
  successMessage.value = ''
  saving.value = true

  try {
    // 构建更新数据
    const updateData: {
      nickname?: string
      email?: string
      avatar?: string
      currentPassword?: string
      newPassword?: string
    } = {}

    // 只提交有变化的字段
    if (form.nickname !== authStore.user?.nickname) {
      updateData.nickname = form.nickname.trim()
    }
    if (form.email !== authStore.user?.email) {
      updateData.email = form.email.trim()
    }
    if (uploadedAvatarUrl.value) {
      updateData.avatar = uploadedAvatarUrl.value
    }

    // 密码修改
    if (form.currentPassword) {
      if (form.newPassword.length < 6) {
        errorMessage.value = '新密码至少需要 6 位字符'
        saving.value = false
        return
      }
      updateData.currentPassword = form.currentPassword
      updateData.newPassword = form.newPassword
    }

    // 如果没有变化，直接返回
    if (Object.keys(updateData).length === 0) {
      successMessage.value = '没有需要保存的更改'
      saving.value = false
      return
    }

    // 发送更新请求
    await authStore.updateUserInfo(updateData)
    
    successMessage.value = '设置已保存'
    
    // 清空密码字段
    form.currentPassword = ''
    form.newPassword = ''
    uploadedAvatarUrl.value = ''
  } catch (error: any) {
    errorMessage.value = error.response?.data?.message || '保存失败，请重试'
  } finally {
    saving.value = false
  }
}

// 保存后端地址配置
async function saveBackendConfig() {
  configError.value = ''
  configSuccess.value = ''
  configSaving.value = true

  try {
    const url = backendUrl.value.trim()
    
    // 如果为空，则重置为默认
    if (!url) {
      resetConfigUtil()
      configSuccess.value = '已恢复默认后端地址，刷新页面后生效'
      configSaving.value = false
      return
    }

    // 验证 URL 格式
    let validatedUrl: string
    try {
      const parsed = new URL(url)
      // 确保协议是 http 或 https
      if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
        throw new Error('协议必须是 http 或 https')
      }
      validatedUrl = `${parsed.protocol}//${parsed.host}`
    } catch (e) {
      configError.value = '无效的 URL 格式，请使用 http://host:port 或 https://host:port'
      configSaving.value = false
      return
    }

    saveConfigUtil({ baseUrl: validatedUrl })
    configSuccess.value = '后端地址已保存，刷新页面后生效'
  } catch (error: any) {
    configError.value = error.message || '保存失败，请重试'
  } finally {
    configSaving.value = false
  }
}
</script>

<style scoped>
.settings-view {
  min-height: 100vh;
  background: var(--bg-color);
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1rem 2rem;
  background: var(--surface-color);
  border-bottom: 1px solid var(--border-color);
}

.logo {
  font-size: 1.5rem;
  font-weight: bold;
  color: var(--primary-color);
}

.nav-link {
  color: var(--text-secondary);
  text-decoration: none;
  transition: color 0.2s;
}

.nav-link:hover {
  color: var(--text-primary);
}

.container {
  max-width: 600px;
  margin: 2rem auto;
  padding: 0 1rem;
}

.settings-card {
  background: var(--surface-color);
  border-radius: 8px;
  padding: 2rem;
  border: 1px solid var(--border-color);
}

.settings-card h2 {
  margin: 0 0 1.5rem 0;
  color: var(--text-primary);
}

.form-section {
  margin-bottom: 2rem;
  padding-bottom: 2rem;
  border-bottom: 1px solid var(--border-color);
}

.form-section:last-of-type {
  border-bottom: none;
}

.form-section h3 {
  margin: 0 0 1rem 0;
  color: var(--text-primary);
  font-size: 1.1rem;
}

.avatar-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
}

.avatar-preview {
  position: relative;
  width: 120px;
  height: 120px;
  border-radius: 50%;
  cursor: pointer;
  overflow: hidden;
  background: var(--bg-color);
  display: flex;
  align-items: center;
  justify-content: center;
}

.avatar-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 3rem;
  color: var(--text-secondary);
  background: linear-gradient(135deg, var(--primary-color), var(--primary-hover));
}

.avatar-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: opacity 0.2s;
}

.avatar-overlay span {
  color: white;
  font-size: 0.9rem;
  text-align: center;
}

.avatar-preview:hover .avatar-overlay {
  opacity: 1;
}

.form-group {
  margin-bottom: 1rem;
}

.form-group label {
  display: block;
  margin-bottom: 0.5rem;
  color: var(--text-primary);
  font-weight: 500;
}

.form-group input {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 4px;
  background: var(--bg-color);
  color: var(--text-primary);
  font-size: 1rem;
  box-sizing: border-box;
}

.form-group input:focus {
  outline: none;
  border-color: var(--primary-color);
}

.form-group input:disabled,
.input-disabled {
  background: var(--bg-color);
  color: var(--text-secondary);
  cursor: not-allowed;
}

.help-text {
  margin: 0.25rem 0 0 0;
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.error-text {
  margin: 0.25rem 0 0 0;
  font-size: 0.85rem;
  color: var(--error-color);
}

.error-message {
  padding: 0.75rem;
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid var(--error-color);
  border-radius: 4px;
  color: var(--error-color);
  margin-bottom: 1rem;
}

.success-message {
  padding: 0.75rem;
  background: rgba(16, 185, 129, 0.1);
  border: 1px solid var(--success-color);
  border-radius: 4px;
  color: var(--success-color);
  margin-bottom: 1rem;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 1.5rem;
}

.btn-primary {
  padding: 0.75rem 2rem;
  background: var(--primary-color);
  color: white;
  border: none;
  border-radius: 4px;
  font-size: 1rem;
  cursor: pointer;
  transition: background 0.2s;
}

.btn-primary:hover:not(:disabled) {
  background: var(--primary-hover);
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.form-actions-inline {
  display: flex;
  gap: 0.75rem;
  margin-top: 1rem;
}

.btn-secondary {
  padding: 0.75rem 1.5rem;
  background: var(--surface-color);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
  border-radius: 4px;
  font-size: 1rem;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-secondary:hover:not(:disabled) {
  background: var(--bg-color);
  border-color: var(--text-secondary);
}

.btn-secondary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

@media (max-width: 768px) {
  .container {
    margin: 1rem auto;
  }
  
  .settings-card {
    padding: 1.5rem;
  }
  
  .form-actions-inline {
    flex-direction: column;
  }
}
</style>
