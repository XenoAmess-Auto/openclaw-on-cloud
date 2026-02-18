<template>
  <div class="flowchart-templates-view">
    <!-- 页面头部 -->
    <header class="page-header">
      <h1></h1> 流程图模板
      <button class="btn btn-primary" @click="showCreateDialog = true">
        + 新建模板
      </button>
    </header>

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
        @click="openEditor(template)"
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
          <button class="btn-icon" @click="runTemplate(template)" title="运行"></button>
          <button class="btn-icon" @click="deleteTemplate(template)" title="删除"></button>
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
        
        <div v-if="selectedTemplate?.variables?.length" class="variables-form">
          <h4>变量</h4>
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

        <div v-else class="no-variables">
          <p>此模板无需输入变量</p>
        </div>

        <div class="dialog-actions">
          <button class="btn" @click="showRunDialog = false">取消</button>
          <button class="btn btn-primary" @click="confirmRun">运行</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useFlowchartStore } from '@/stores/flowchart'
import { useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()
const store = useFlowchartStore()

const showCreateDialog = ref(false)
const showRunDialog = ref(false)
const selectedTemplate = ref<any>(null)
const selectedCategory = ref('all')
const runVariables = ref<Record<string, any>>({})

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

function confirmRun() {
  if (!selectedTemplate.value) return
  
  const roomId = route.query.roomId as string || 'default'
  
  store.createInstance(
    selectedTemplate.value.templateId,
    roomId,
    runVariables.value
  ).then(() => {
    showRunDialog.value = false
    // 跳转到任务队列或实例列表
    router.push('/')
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
</style>
