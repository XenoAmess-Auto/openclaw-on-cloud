<template>
  <div class="flowchart-templates-view">
    <!-- 页面头部 -->
    <header class="page-header">
      <h1>流程图模板</h1>
      <button class="btn btn-primary" @click="showCreateDialog = true">
        + 新建模板
      </button>
    </header>

    <!-- 加载状态 -->
    <div v-if="store.loading" class="loading-state">
      <p>加载中...</p>
    </div>

    <!-- 错误状态 -->
    <div v-else-if="store.error" class="error-state">
      <p>加载失败: {{ store.error }}</p>
      <button class="btn" @click="store.fetchTemplates()">重试</button>
    </div>

    <!-- 分类筛选 -->
    <div class="filter-bar">
      <button
        v-for="cat in categories"
        :key="cat"
        :class="['filter-btn', { active: selectedCategory === cat }]"
        @click="selectedCategory = cat"
      >
        {{ cat === 'all' ? '全部' : cat }}
      </button>
    </div>

    <!-- 模板列表 -->
    <div class="templates-grid">
      <div
        v-for="template in filteredTemplates"
        :key="template.id"
        class="template-card"
      >
        <div class="card-icon">{{ template.icon || '=' }}</div>
        <div class="card-content">
          <h3>{{ template.name }}</h3>
          <p class="description">{{ template.description || '暂无描述' }}</p>
          <div class="card-meta">
            <span class="category">{{ template.category || '未分类' }}</span>
            <span class="version">v{{ template.version }}</span>
          </div>
        </div>
        <div class="card-actions" @click.stop>
          <button class="btn-icon" @click="runTemplate(template)" title="运行">▶️</button>
          <button class="btn-icon" @click="openEditor(template)" title="编辑">✏️</button>
          <button class="btn-icon" @click="deleteTemplate(template)" title="删除">🗑️</button>
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-if="filteredTemplates.length === 0" class="empty-state">
      <p>暂无模板</p>
      <button class="btn btn-primary" @click="showCreateDialog = true">
        创建第一个模板
      </button>
    </div>

    <!-- 创建模板对话框 -->
    <div v-if="showCreateDialog" class="dialog-overlay" @click.self="showCreateDialog = false">
      <div class="dialog">
        <h2>创建新模板</h2>
        
        <div class="form-group">
          <label>模板名称 *</label>
          <input v-model="newTemplate.name" type="text" placeholder="输入名称" />
        </div>

        <div class="form-group">
          <label>描述</label>
          <textarea v-model="newTemplate.description" rows="3" placeholder="输入描述"></textarea>
        </div>

        <div class="form-group">
          <label>分类</label>
          <input v-model="newTemplate.category" type="text" placeholder="如: automation, report" />
        </div>

        <div class="form-group">
          <label>图标</label>
          <input v-model="newTemplate.icon" type="text" placeholder="emoji 或图标" maxlength="2" />
        </div>

        <div class="dialog-actions">
          <button class="btn" @click="showCreateDialog = false">取消</button>
          <button class="btn btn-primary" @click="createTemplate" :disabled="!newTemplate.name">
            创建并编辑
          </button>
        </div>
      </div>
    </div>

    <!-- 运行模板对话框 -->
    <div v-if="showRunDialog" class="dialog-overlay" @click.self="showRunDialog = false">
      <div class="dialog">
        <h2>运行: {{ selectedTemplate?.name }}</h2>
        
        <!-- 群选择 -->
        <div class="form-group">
          <label>选择群 *</label>
          <select v-model="selectedRoomId" class="room-select">
            <option v-for="room in chatStore.rooms" :key="room.id" :value="room.id">
              {{ room.name }}
            </option>
          </select>
          <p v-if="chatStore.rooms.length === 0" class="hint-text">
            暂无可用群，请先创建或加入群
          </p>
        </div>
        
        <div v-if="selectedTemplate?.variables?.length" class="variables-form">
          <h4>预定义变量</h4>
          <div
            v-for="variable in selectedTemplate.variables"
            :key="variable.name"
            class="form-group"
          >
            <label>
              {{ variable.name }}
              <span v-if="variable.required" class="required">*</span>
            </label>
            <input
              v-model="runVariables[variable.name]"
              :type="variable.type === 'number' ? 'number' : 'text'"
              :placeholder="variable.description || variable.defaultValue"
            />
          </div>
        </div>

        <!-- 动态变量 -->
        <div class="dynamic-variables-section">
          <div class="section-header">
            <h4>动态变量</h4>
            <button class="btn-add-var" @click="addDynamicVariable">+ 添加</button>
          </div>
          
          <div v-if="dynamicVariables.length === 0" class="no-dynamic-vars">
            <p>点击"添加"按钮添加自定义变量</p>
          </div>
          
          <div
            v-for="(variable, index) in dynamicVariables"
            :key="index"
            class="dynamic-variable-item"
          >
            <input
              v-model="variable.name"
              placeholder="变量名"
              class="var-name-input"
            />
            <input
              v-model="variable.value"
              placeholder="值"
              class="var-value-input"
            />
            
            <button class="btn-remove-var" @click="removeDynamicVariable(index)" title="删除">×</button>
          </div>
        </div>

        <div v-if="!selectedTemplate?.variables?.length && dynamicVariables.length === 0" class="no-variables">
          <p>此模板无预定义变量，可添加动态变量</p>
        </div>

        <div class="dialog-actions">
          <button class="btn" @click="showRunDialog = false" :disabled="isRunning">取消</button>
          <button class="btn btn-primary" @click="confirmRun" :disabled="!selectedRoomId || isRunning">
            {{ isRunning ? '运行中...' : '运行' }}
          </button>
        </div>
        <div v-if="runError" class="error-message">
          {{ runError }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useFlowchartStore } from '@/stores/flowchart'
import { useChatStore } from '@/stores/chat'
import { useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()
const store = useFlowchartStore()
const chatStore = useChatStore()

const showCreateDialog = ref(false)
const showRunDialog = ref(false)
const selectedTemplate = ref<any>(null)
const selectedCategory = ref('all')
const runVariables = ref<Record<string, any>>({})
const dynamicVariables = ref<Array<{ name: string; value: string }>>([])
const selectedRoomId = ref<string>('')
const isRunning = ref(false)
const runError = ref<string | null>(null)

const newTemplate = ref({
  name: '',
  description: '',
  category: 'automation',
  icon: ''
})

const categories = computed(() => {
  const cats = ['all', ...store.templateCategories]
  return [...new Set(cats)]
})

const filteredTemplates = computed(() => {
  if (selectedCategory.value === 'all') {
    return store.templates
  }
  return store.templates.filter(t => t.category === selectedCategory.value)
})

onMounted(() => {
  store.fetchTemplates()
  chatStore.fetchRooms()
})

function createTemplate() {
  const template = {
    ...newTemplate.value,
    definition: {
      nodes: [{
        id: 'start',
        type: 'start',
        position: { x: 250, y: 50 },
        data: { label: '开始' }
      },
      {
        id: 'end',
        type: 'end',
        position: { x: 250, y: 300 },
        data: { label: '结束' }
      }],
      edges: []
    },
    variables: []
  }
  
  store.createTemplate(template).then((created) => {
    showCreateDialog.value = false
    router.push(`/flowchart/editor/${created.templateId}`)
  })
}

function openEditor(template: any) {
  router.push(`/flowchart/editor/${template.templateId}`)
}

function runTemplate(template: any) {
  selectedTemplate.value = template
  runVariables.value = {}
  dynamicVariables.value = [] // 重置动态变量
  
  // 默认选中当前群或第一个群
  const currentRoomId = route.query.roomId as string
  if (currentRoomId && chatStore.rooms.some(r => r.id === currentRoomId)) {
    selectedRoomId.value = currentRoomId
  } else if (chatStore.rooms.length > 0) {
    selectedRoomId.value = chatStore.rooms[0].id
  } else {
    selectedRoomId.value = ''
  }
  
  // 填充默认值
  if (template.variables) {
    for (const v of template.variables) {
      if (v.defaultValue) {
        runVariables.value[v.name] = v.defaultValue
      }
    }
  }
  
  showRunDialog.value = true
}

function addDynamicVariable() {
  dynamicVariables.value.push({ name: '', value: '' })
}

function removeDynamicVariable(index: number) {
  dynamicVariables.value.splice(index, 1)
}

function confirmRun() {
  if (!selectedTemplate.value || !selectedRoomId.value) return
  
  isRunning.value = true
  runError.value = null
  
  const roomId = selectedRoomId.value
  
  // 合并预定义变量和动态变量
  const allVariables: Record<string, any> = { ...runVariables.value }
  
  // 添加动态变量（过滤掉名称为空的）
  for (const variable of dynamicVariables.value) {
    if (variable.name.trim()) {
      allVariables[variable.name.trim()] = variable.value
    }
  }
  
  store.createInstance(
    selectedTemplate.value.templateId,
    roomId,
    allVariables
  ).then(() => {
    showRunDialog.value = false
    isRunning.value = false
    // 跳转到任务队列或实例列表
    router.push('/')
  }).catch((err: any) => {
    isRunning.value = false
    runError.value = err.response?.data?.error || err.message || '运行失败，请重试'
  })
}

function deleteTemplate(template: any) {
  if (confirm(`确定要删除模板 "${template.name}" 吗？`)) {
    store.deleteTemplate(template.templateId)
  }
}
</script>

<style scoped>
.flowchart-templates-view {
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.page-header h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
}

.filter-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 24px;
  flex-wrap: wrap;
}

.filter-btn {
  padding: 8px 16px;
  border: 1px solid #e0e0e0;
  border-radius: 20px;
  background: white;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
}

.filter-btn:hover {
  background: #f5f5f5;
}

.filter-btn.active {
  background: #4f46e5;
  color: white;
  border-color: #4f46e5;
}

.templates-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.template-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px;
  background: white;
  border: 1px solid #e0e0e0;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.template-card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  transform: translateY(-2px);
}

.card-icon {
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f3f4f6;
  border-radius: 10px;
  font-size: 24px;
}

.card-content {
  flex: 1;
  min-width: 0;
}

.card-content h3 {
  margin: 0 0 4px 0;
  font-size: 16px;
  font-weight: 600;
}

.card-content .description {
  margin: 0 0 8px 0;
  font-size: 13px;
  color: #6b7280;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-meta {
  display: flex;
  gap: 8px;
  font-size: 12px;
}

.category {
  padding: 2px 8px;
  background: #e5e7eb;
  border-radius: 4px;
  color: #374151;
}

.version {
  padding: 2px 8px;
  background: #dbeafe;
  border-radius: 4px;
  color: #1d4ed8;
}

.card-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  opacity: 0;
  transition: opacity 0.2s;
}

.template-card:hover .card-actions {
  opacity: 1;
}

.btn-icon {
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  transition: background 0.2s;
}

.btn-icon:hover {
  background: #f3f4f6;
}

.empty-state {
  text-align: center;
  padding: 48px;
  color: #6b7280;
}

.empty-state p {
  margin-bottom: 16px;
}

/* 对话框样式 */
.dialog-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.dialog {
  background: white;
  padding: 24px;
  border-radius: 12px;
  width: 100%;
  max-width: 480px;
  max-height: 90vh;
  overflow-y: auto;
}

.dialog h2 {
  margin: 0 0 20px 0;
  font-size: 20px;
}

.dialog h4 {
  margin: 16px 0 12px 0;
  font-size: 14px;
  color: #374151;
}

.form-group {
  margin-bottom: 16px;
}

.form-group label {
  display: block;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 500;
}

.required {
  color: #ef4444;
}

.form-group input,
.form-group textarea {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #d0d0d0;
  border-radius: 8px;
  font-size: 14px;
  box-sizing: border-box;
}

.form-group input:focus,
.form-group textarea:focus {
  outline: none;
  border-color: #4f46e5;
}

.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
}

.btn {
  padding: 10px 16px;
  border: 1px solid #d0d0d0;
  border-radius: 8px;
  background: white;
  cursor: pointer;
  font-size: 14px;
}

.btn-primary {
  background: #4f46e5;
  color: white;
  border-color: #4f46e5;
}

.btn-primary:hover {
  background: #4338ca;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.no-variables {
  padding: 24px;
  text-align: center;
  color: #6b7280;
  background: #f9fafb;
  border-radius: 8px;
}

.room-select {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #d0d0d0;
  border-radius: 8px;
  font-size: 14px;
  background: white;
  cursor: pointer;
}

.room-select:focus {
  outline: none;
  border-color: #4f46e5;
}

.hint-text {
  font-size: 12px;
  color: #ef4444;
  margin-top: 6px;
}

.error-message {
  padding: 12px;
  margin-top: 12px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 6px;
  color: #dc2626;
  font-size: 14px;
}

/* 动态变量样式 */
.dynamic-variables-section {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid #e5e7eb;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.section-header h4 {
  margin: 0;
  font-size: 14px;
  color: #374151;
}

.btn-add-var {
  padding: 4px 12px;
  background: #10b981;
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.2s;
}

.btn-add-var:hover {
  background: #059669;
}

.no-dynamic-vars {
  padding: 16px;
  text-align: center;
  color: #9ca3af;
  font-size: 13px;
  background: #f9fafb;
  border-radius: 8px;
}

.dynamic-variable-item {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}

.var-name-input {
  flex: 1;
  padding: 8px 10px;
  border: 1px solid #d0d0d0;
  border-radius: 6px;
  font-size: 14px;
}

.var-value-input {
  flex: 1.5;
  padding: 8px 10px;
  border: 1px solid #d0d0d0;
  border-radius: 6px;
  font-size: 14px;
}

.var-name-input:focus,
.var-value-input:focus {
  outline: none;
  border-color: #4f46e5;
}

.btn-remove-var {
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  background: #fee2e2;
  color: #dc2626;
  border-radius: 6px;
  font-size: 18px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
}

.btn-remove-var:hover {
  background: #fecaca;
}

.loading-state,
.error-state {
  padding: 48px;
  text-align: center;
  color: #6b7280;
}

.error-state {
  color: #ef4444;
}

.error-state .btn {
  margin-top: 16px;
}
</style>
