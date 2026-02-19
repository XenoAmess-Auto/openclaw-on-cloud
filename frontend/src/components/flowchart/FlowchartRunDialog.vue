<template>
  <div v-if="visible" class="dialog-overlay" @click.self="onCancel">
    <div class="dialog">
      <h2>{{ title }}</h2>
      
      <!-- 群选择 -->
      <div class="form-group">
        <label>选择群 *</label>
        <select v-model="localRoomId" class="room-select" :disabled="isLoadingRooms">
          <option v-for="room in chatStore.rooms" :key="room.id" :value="room.id">
            {{ room.name }}
          </option>
        </select>
        <p v-if="chatStore.rooms.length === 0 && !isLoadingRooms" class="hint-text">
          暂无可用群，请先创建或加入群
        </p>
      </div>
      
      <!-- 预定义变量 -->
      <div v-if="predefinedVariables.length" class="variables-section">
        <h4>预定义变量</h4>
        <div
          v-for="variable in predefinedVariables"
          :key="variable.name"
          class="form-group"
        >
          <label>
            {{ variable.name }}
            <span v-if="variable.required" class="required">*</span>
            <small v-if="variable.description" class="var-desc">({{ variable.description }})</small>
          </label>
          <input
            v-model="runVariables[variable.name]"
            :type="variable.type === 'number' ? 'number' : 'text'"
            :placeholder="variable.defaultValue || variable.description"
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

      <div v-if="!predefinedVariables.length && dynamicVariables.length === 0" class="no-variables">
        <p>此模板无预定义变量，可添加动态变量</p>
      </div>

      <div class="dialog-actions">
        <button class="btn" @click="onCancel" :disabled="isRunning">取消</button>
        <button 
          class="btn btn-primary" 
          @click="onConfirm" 
          :disabled="!canRun || isRunning"
        >
          {{ isRunning ? '运行中...' : '运行' }}
        </button>
      </div>
      
      <div v-if="error" class="error-message">
        {{ error }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useChatStore } from '@/stores/chat'

export interface VariableDefinition {
  name: string
  type: 'string' | 'number' | 'boolean'
  description?: string
  required?: boolean
  defaultValue?: string
}

export interface DynamicVariable {
  name: string
  value: string
}

interface Props {
  visible: boolean
  title: string
  predefinedVariables?: VariableDefinition[]
  initialRoomId?: string
  isRunning?: boolean
  error?: string | null
  isLoadingRooms?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  predefinedVariables: () => [],
  initialRoomId: '',
  isRunning: false,
  error: null,
  isLoadingRooms: false
})

const emit = defineEmits<{
  cancel: []
  confirm: [roomId: string, variables: Record<string, any>]
}>()

const chatStore = useChatStore()

const localRoomId = ref('')
const runVariables = ref<Record<string, any>>({})
const dynamicVariables = ref<DynamicVariable[]>([])

const canRun = computed(() => {
  if (!localRoomId.value) return false
  
  // 检查必填的预定义变量
  for (const v of props.predefinedVariables) {
    if (v.required && !runVariables.value[v.name]) {
      return false
    }
  }
  
  return true
})

// 监听 visible 变化，初始化数据
watch(() => props.visible, (visible) => {
  if (visible) {
    initialize()
  }
})

// 监听 initialRoomId 变化
watch(() => props.initialRoomId, (roomId) => {
  if (roomId && !localRoomId.value) {
    localRoomId.value = roomId
  }
})

function initialize() {
  // 初始化预定义变量值
  runVariables.value = {}
  for (const v of props.predefinedVariables) {
    if (v.defaultValue) {
      runVariables.value[v.name] = v.defaultValue
    }
  }
  
  // 初始化动态变量
  dynamicVariables.value = []
  
  // 初始化房间选择
  if (props.initialRoomId && chatStore.rooms.some(r => r.id === props.initialRoomId)) {
    localRoomId.value = props.initialRoomId
  } else if (chatStore.rooms.length > 0 && !localRoomId.value) {
    localRoomId.value = chatStore.rooms[0].id
  }
}

function addDynamicVariable() {
  dynamicVariables.value.push({ name: '', value: '' })
}

function removeDynamicVariable(index: number) {
  dynamicVariables.value.splice(index, 1)
}

function onCancel() {
  emit('cancel')
}

function onConfirm() {
  // 合并预定义变量和动态变量
  const allVariables: Record<string, any> = { ...runVariables.value }
  
  // 添加动态变量（过滤掉名称为空的）
  for (const variable of dynamicVariables.value) {
    if (variable.name.trim()) {
      allVariables[variable.name.trim()] = variable.value
    }
  }
  
  emit('confirm', localRoomId.value, allVariables)
}

onMounted(() => {
  if (props.visible) {
    initialize()
  }
})
</script>

<style scoped>
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

.var-desc {
  color: #6b7280;
  font-weight: normal;
  margin-left: 4px;
}

.form-group input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #d0d0d0;
  border-radius: 8px;
  font-size: 14px;
  box-sizing: border-box;
}

.form-group input:focus {
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

.room-select:disabled {
  opacity: 0.5;
  cursor: not-allowed;
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
.variables-section {
  margin-top: 16px;
}

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
</style>
