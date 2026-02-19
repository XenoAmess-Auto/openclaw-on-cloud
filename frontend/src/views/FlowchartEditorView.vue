<template>
  <div class="flowchart-editor-view">
    <!-- 头部 -->
    <header class="editor-header">
      <div class="header-left">
        <router-link to="/flowchart/templates" class="back-link"></router-link>
        <div v-if="loading" class="loading">加载中...</div>
        <template v-else-if="template">
          <input
            v-model="templateName"
            class="title-input"
            placeholder="模板名称"
            @blur="saveTemplate"
          />
          <span class="version-badge">v{{ template.version }}</span>
        </template>
      </div>
      
      <div class="header-right">
        <button class="btn" @click="showVariablesPanel = !showVariablesPanel">变量</button>
        <button class="btn btn-primary" @click="runTemplate">运行</button>
      </div>
    </header>

    <!-- 编辑器主体 -->
    <div class="editor-body">
      <FlowchartEditor
        ref="editorRef"
        v-model="definition"
        @save="onSave"
      />
    </div>

    <!-- 变量配置面板 -->
    <div v-if="showVariablesPanel" class="variables-panel">
      <div class="panel-header">
        <h3>模板变量</h3>
        <button class="btn-icon" @click="showVariablesPanel = false"></button>
      </div>
      
      <div class="panel-content">
        <div v-for="(variable, index) in variables" :key="index" class="variable-item">
          <div class="variable-header">
            <input v-model="variable.name" placeholder="变量名" class="var-name" />
            <select v-model="variable.type" class="var-type">
              <option value="string">字符串</option>
              <option value="number">数字</option>
              <option value="boolean">布尔</option>
            </select>
            <button class="btn-icon-delete" @click="removeVariable(index)"></button>
          </div>
          
          <input v-model="variable.description" placeholder="描述" class="var-desc" />
          
          <div class="variable-options">
            <label class="checkbox">
              <input v-model="variable.required" type="checkbox" />
              必填
            </label>
            <input
              v-model="variable.defaultValue"
              placeholder="默认值"
              class="var-default"
            />
          </div>
        </div>
        
        <button class="btn-add" @click="addVariable">+ 添加变量</button>
      </div>
    </div>

    <!-- 运行对话框 -->
    <FlowchartRunDialog
      :visible="showRunDialog"
      :title="'运行模板'"
      :predefined-variables="variables"
      :initial-room-id="(route.query.roomId as string) || ''"
      :is-running="running"
      :is-loading-rooms="isLoadingRooms"
      @cancel="showRunDialog = false"
      @confirm="handleConfirmRun"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import FlowchartEditor from '@/components/flowchart/FlowchartEditor.vue'
import FlowchartRunDialog from '@/components/flowchart/FlowchartRunDialog.vue'
import { useFlowchartStore } from '@/stores/flowchart'
import { useChatStore } from '@/stores/chat'
import { showToast } from '@/utils/toast'

const route = useRoute()
const store = useFlowchartStore()
const chatStore = useChatStore()

const editorRef = ref<InstanceType<typeof FlowchartEditor> | null>(null)
const templateId = computed(() => route.params.id as string)

const template = ref<any>(null)
const templateName = ref('')
const definition = ref({ nodes: [], edges: [] })
const variables = ref<any[]>([])
const loading = ref(false)
const saving = ref(false)

const showVariablesPanel = ref(false)
const showRunDialog = ref(false)
const running = ref(false)
const isLoadingRooms = ref(false)

onMounted(async () => {
  if (templateId.value) {
    await loadTemplate()
  }
})

async function loadTemplate() {
  loading.value = true
  try {
    const data = await store.fetchTemplate(templateId.value)
    template.value = data
    templateName.value = data.name
    definition.value = data.definition || { nodes: [], edges: [] }
    variables.value = data.variables || []
  } catch (e) {
    console.error('Failed to load template:', e)
  } finally {
    loading.value = false
  }
}

function onSave(_newDefinition: any) {
  saveTemplate()
}

async function saveTemplate() {
  if (!template.value || saving.value) return

  saving.value = true
  try {
    const currentDef = editorRef.value?.getDefinition()

    const updated = await store.updateTemplate(templateId.value, {
      name: templateName.value,
      definition: currentDef || definition.value,
      variables: variables.value
    })

    // 更新模板数据，包括新版本号
    template.value = updated
  } finally {
    saving.value = false
  }
}

function addVariable() {
  variables.value.push({
    name: '',
    type: 'string',
    description: '',
    required: false,
    defaultValue: ''
  })
}

function removeVariable(index: number) {
  variables.value.splice(index, 1)
}

function runTemplate() {
  // 加载群列表
  isLoadingRooms.value = true
  chatStore.fetchRooms().then(() => {
    isLoadingRooms.value = false
  })
  
  showRunDialog.value = true
}

async function handleConfirmRun(roomId: string, runVariables: Record<string, any>) {
  running.value = true
  
  try {
    await store.createInstance(
      templateId.value,
      roomId,
      runVariables
    )
    
    showRunDialog.value = false
    
    // 获取群名称
    const room = chatStore.rooms.find(r => r.id === roomId)
    const roomName = room?.name || '相应群'
    
    showToast(`成功启动流程，请到「${roomName}」查看`, 3000, 'success')
  } finally {
    running.value = false
  }
}
</script>

<style scoped>
.flowchart-editor-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f5f5f5;
}

.editor-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  background: white;
  border-bottom: 1px solid #e0e0e0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.back-link {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  color: #6b7280;
  text-decoration: none;
}

.back-link:hover {
  background: #f3f4f6;
}

.back-link::before {
  content: '←';
  font-size: 18px;
}

.loading {
  color: #6b7280;
}

.title-input {
  font-size: 18px;
  font-weight: 600;
  border: none;
  background: transparent;
  padding: 4px 8px;
  border-radius: 4px;
  min-width: 200px;
}

.title-input:hover,
.title-input:focus {
  background: #f3f4f6;
  outline: none;
}

.version-badge {
  padding: 2px 8px;
  background: #e0e7ff;
  color: #4338ca;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}

.header-right {
  display: flex;
  gap: 8px;
}

.btn {
  padding: 8px 16px;
  border: 1px solid #d0d0d0;
  border-radius: 6px;
  background: white;
  cursor: pointer;
  font-size: 14px;
}

.btn:hover {
  background: #f3f4f6;
}

.btn-primary {
  background: #4f46e5;
  color: white;
  border-color: #4f46e5;
}

.btn-primary:hover {
  background: #4338ca;
}

.editor-body {
  flex: 1;
  overflow: hidden;
}

/* 变量面板 */
.variables-panel {
  position: absolute;
  right: 0;
  top: 57px;
  bottom: 0;
  width: 320px;
  background: white;
  border-left: 1px solid #e0e0e0;
  display: flex;
  flex-direction: column;
  z-index: 20;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-bottom: 1px solid #e0e0e0;
}

.panel-header h3 {
  margin: 0;
  font-size: 16px;
}

.panel-content {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}

.variable-item {
  padding: 12px;
  background: #f9fafb;
  border-radius: 8px;
  margin-bottom: 12px;
}

.variable-header {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.var-name {
  flex: 1;
  padding: 6px 10px;
  border: 1px solid #d0d0d0;
  border-radius: 6px;
  font-size: 14px;
}

.var-type {
  width: 90px;
  padding: 6px;
  border: 1px solid #d0d0d0;
  border-radius: 6px;
}

.var-desc {
  width: 100%;
  padding: 6px 10px;
  border: 1px solid #d0d0d0;
  border-radius: 6px;
  font-size: 14px;
  margin-bottom: 8px;
  box-sizing: border-box;
}

.variable-options {
  display: flex;
  gap: 8px;
  align-items: center;
}

.checkbox {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #374151;
}

.var-default {
  flex: 1;
  padding: 6px 10px;
  border: 1px solid #d0d0d0;
  border-radius: 6px;
  font-size: 14px;
}

.btn-add {
  width: 100%;
  padding: 10px;
  border: 1px dashed #d0d0d0;
  border-radius: 6px;
  background: transparent;
  color: #6b7280;
  cursor: pointer;
}

.btn-add:hover {
  border-color: #4f46e5;
  color: #4f46e5;
}
</style>
