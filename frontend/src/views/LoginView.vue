<template>
  <div class="login-view">
    <div class="login-box">
      <h1>OOC</h1>
      <p class="subtitle">OpenClaw on Cloud</p>
      
      <form @submit.prevent="handleLogin">
        <div class="form-group">
          <label>用户名</label>
          <input
            v-model="form.username"
            type="text"
            required
            placeholder="输入用户名"
          />
        </div>
        
        <div class="form-group">
          <label>密码</label>
          <input
            v-model="form.password"
            type="password"
            required
            placeholder="输入密码"
          />
        </div>
        
        <div v-if="error" class="error">{{ error }}</div>
        
        <button type="submit" :disabled="loading">
          {{ loading ? '登录中...' : '登录' }}
        </button>
      </form>

      <!-- 服务器地址配置 -->
      <div class="server-config">
        <div class="config-header" @click="showConfig = !showConfig">
          <span>⚙️ 服务器地址配置</span>
          <span class="toggle-icon">{{ showConfig ? '▼' : '▶' }}</span>
        </div>
        
        <div v-if="showConfig" class="config-content">
          <div class="form-group">
            <label>后端服务器地址</label>
            <input
              v-model="backendUrl"
              type="text"
              placeholder="http://localhost:8081"
            />
            <p class="help-text">
              留空则使用：{{ defaultBackendUrl }}
            </p>
          </div>

          <div v-if="configError" class="error-text">{{ configError }}</div>
          <div v-if="configSuccess" class="success-text">{{ configSuccess }}</div>

          <div class="config-actions">
            <button 
              type="button"
              @click="saveBackendConfig"
              class="btn-secondary"
              :disabled="configSaving"
            >
              {{ configSaving ? '保存中...' : '保存配置' }}
            </button>
            <button 
              v-if="hasCustomConfig"
              type="button"
              @click="resetConfig"
              class="btn-text"
              :disabled="configSaving"
            >
              恢复默认
            </button>
          </div>
        </div>
      </div>
      
      <p class="link">
        还没有账号？<router-link to="/register">立即注册</router-link>
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { reinitApiClient } from '@/api/client'
import {
  loadConfig,
  saveConfig,
  resetConfig as resetConfigUtil,
  getDefaultBaseUrl
} from '@/utils/config'

const router = useRouter()
const authStore = useAuthStore()
const loading = ref(false)
const error = ref('')

const form = reactive({
  username: '',
  password: ''
})

// 服务器配置
const showConfig = ref(false)
const backendUrl = ref('')
const configSaving = ref(false)
const configError = ref('')
const configSuccess = ref('')

const defaultBackendUrl = computed(() => getDefaultBaseUrl())
const hasCustomConfig = computed(() => !!loadConfig().baseUrl)

onMounted(() => {
  // 加载已保存的配置
  const config = loadConfig()
  backendUrl.value = config.baseUrl || ''
})

async function handleLogin() {
  error.value = ''
  loading.value = true
  
  try {
    await authStore.login(form.username, form.password)
    router.push('/')
  } catch (err: any) {
    error.value = err.response?.data?.message || '登录失败'
  } finally {
    loading.value = false
  }
}

function saveBackendConfig() {
  configError.value = ''
  configSuccess.value = ''
  configSaving.value = true

  try {
    const url = backendUrl.value.trim()
    
    // 如果为空，则重置为默认
    if (!url) {
      resetConfigUtil()
      reinitApiClient() // 立即生效
      configSuccess.value = '已恢复默认后端地址并生效'
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

    saveConfig({ baseUrl: validatedUrl })
    reinitApiClient() // 立即生效
    configSuccess.value = '后端地址已保存并生效'
  } catch (err: any) {
    configError.value = err.message || '保存失败，请重试'
  } finally {
    configSaving.value = false
  }
}

function resetConfig() {
  configError.value = ''
  configSuccess.value = ''
  backendUrl.value = ''
  resetConfigUtil()
  reinitApiClient() // 立即生效
  configSuccess.value = '已恢复默认后端地址并生效'
}
</script>

<style scoped>
.login-view {
  height: 100vh;
  height: 100dvh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-color);
  /* 移动端安全区域适配 */
  padding-top: env(safe-area-inset-top);
  padding-bottom: env(safe-area-inset-bottom);
  padding-left: env(safe-area-inset-left);
  padding-right: env(safe-area-inset-right);
  box-sizing: border-box;
}

.login-box {
  background: var(--surface-color);
  padding: 2.5rem;
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0,0,0,0.1);
  width: 100%;
  max-width: 400px;
}

h1 {
  text-align: center;
  color: var(--primary-color);
  font-size: 2rem;
  margin-bottom: 0.5rem;
}

.subtitle {
  text-align: center;
  color: var(--text-secondary);
  margin-bottom: 2rem;
}

.form-group {
  margin-bottom: 1rem;
}

label {
  display: block;
  margin-bottom: 0.5rem;
  font-size: 0.875rem;
  color: var(--text-primary);
}

input {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 1rem;
  transition: border-color 0.2s;
  box-sizing: border-box;
  background: var(--bg-color);
  color: var(--text-primary);
}

input:focus {
  outline: none;
  border-color: var(--primary-color);
}

button {
  width: 100%;
  padding: 0.75rem;
  background: var(--primary-color);
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 1rem;
  cursor: pointer;
  transition: background 0.2s;
}

button:hover:not(:disabled) {
  background: var(--primary-hover);
}

button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.error {
  color: var(--error-color);
  font-size: 0.875rem;
  margin-bottom: 1rem;
  text-align: center;
}

.link {
  text-align: center;
  margin-top: 1.5rem;
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.link a {
  color: var(--primary-color);
  text-decoration: none;
}

/* 服务器配置 */
.server-config {
  margin-top: 1.5rem;
  border-top: 1px solid var(--border-color);
  padding-top: 1rem;
}

.config-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: pointer;
  font-size: 0.875rem;
  color: var(--text-secondary);
  user-select: none;
}

.config-header:hover {
  color: var(--text-primary);
}

.toggle-icon {
  font-size: 0.75rem;
}

.config-content {
  margin-top: 1rem;
  padding: 1rem;
  background: var(--bg-color);
  border-radius: 8px;
}

.help-text {
  margin: 0.25rem 0 0 0;
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.error-text {
  color: var(--error-color);
  font-size: 0.75rem;
  margin: 0.5rem 0;
}

.success-text {
  color: var(--success-color);
  font-size: 0.75rem;
  margin: 0.5rem 0;
}

.config-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 0.75rem;
}

.btn-secondary {
  flex: 1;
  padding: 0.5rem;
  background: var(--primary-color);
  color: white;
  border: none;
  border-radius: 4px;
  font-size: 0.875rem;
  cursor: pointer;
  transition: background 0.2s;
}

.btn-secondary:hover:not(:disabled) {
  background: var(--primary-hover);
}

.btn-text {
  padding: 0.5rem 1rem;
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border-color);
  border-radius: 4px;
  font-size: 0.875rem;
  cursor: pointer;
  transition: all 0.2s;
  width: auto;
}

.btn-text:hover:not(:disabled) {
  color: var(--text-primary);
  border-color: var(--text-secondary);
}

/* 移动端适配 */
@media (max-width: 768px) {
  .login-view {
    height: 100dvh;
    padding: 1rem;
    align-items: flex-start;
    /* 使用安全区域 + 额外间距，避免与状态栏/灵动岛重叠 */
    padding-top: calc(env(safe-area-inset-top) + 2rem);
  }

  .login-box {
    padding: 1.5rem;
    border-radius: 12px;
    max-width: 100%;
  }

  h1 {
    font-size: 1.75rem;
  }

  .subtitle {
    font-size: 0.9375rem;
    margin-bottom: 1.5rem;
  }

  .form-group {
    margin-bottom: 1rem;
  }

  label {
    font-size: 0.8125rem;
  }

  input {
    padding: 0.75rem;
    font-size: 16px; /* 防止 iOS 缩放 */
  }

  button {
    padding: 0.875rem;
    font-size: 1rem;
    min-height: 48px; /* 更大的触摸目标 */
  }

  .error {
    font-size: 0.8125rem;
  }

  .link {
    font-size: 0.8125rem;
    margin-top: 1.25rem;
  }

  .server-config {
    margin-top: 1.25rem;
    padding-top: 1rem;
  }

  .config-content {
    padding: 0.875rem;
  }

  .config-actions {
    flex-direction: column;
  }

  .btn-text {
    width: 100%;
  }
}

/* 小屏手机 */
@media (max-width: 380px) {
  .login-view {
    padding-top: 10vh;
  }

  .login-box {
    padding: 1.25rem;
  }

  h1 {
    font-size: 1.5rem;
  }

  .subtitle {
    font-size: 0.875rem;
  }
}

/* 横屏模式 */
@media (max-height: 500px) and (orientation: landscape) {
  .login-view {
    padding-top: 5vh;
    align-items: center;
  }

  .login-box {
    padding: 1.25rem;
  }

  h1 {
    font-size: 1.5rem;
    margin-bottom: 0.25rem;
  }

  .subtitle {
    margin-bottom: 1rem;
  }

  .form-group {
    margin-bottom: 0.75rem;
  }

  .server-config {
    margin-top: 1rem;
    padding-top: 0.75rem;
  }
}
</style>
