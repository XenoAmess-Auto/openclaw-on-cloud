<template>
  <div v-if="visible" class="modal-overlay" @click.self="close">
    <div class="modal-container">
      <div class="modal-header">
        <h3>配置群聊项目</h3>
        <button class="close-btn" @click="close">&times;</button>
      </div>
      
      <div class="modal-body">
        <p class="description">
          选择此群聊关联的项目，OpenClaw 将只回答与这些项目相关的问题。
          <br>
          <small>未配置时默认使用群名作为项目名</small>
        </p>
        
        <div class="projects-list">
          <div
            v-for="project in availableProjects"
            :key="project.name"
            :class="['project-item', { selected: selectedProjects.includes(project.name) }]"
            @click="toggleProject(project.name)"
          >
            <span class="checkbox">{{ selectedProjects.includes(project.name) ? '☑️' : '⬜' }}</span>
            <span class="project-name">{{ project.name }}</span>
          </div>
        </div>
        
        <div class="selected-info">
          <strong>已选择: </strong>
          <span v-if="selectedProjects.length === 0" class="empty">无（使用默认）</span>
          <span v-else>{{ selectedProjects.join(', ') }}</span>
        </div>
      </div>
      
      <div class="modal-footer">
        <button class="btn-secondary" @click="close">取消</button>
        <button class="btn-primary" @click="save" :disabled="saving">
          {{ saving ? '保存中...' : '保存' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'

interface Project {
  name: string
  path: string
}

const props = defineProps<{
  visible: boolean
  roomId: string
  currentProjects: string[]
}>()

const emit = defineEmits<{
  close: []
  saved: [projects: string[]]
}>()

const availableProjects = ref<Project[]>([])
const selectedProjects = ref<string[]>([])
const loading = ref(false)
const saving = ref(false)

// 加载可用项目列表
const loadProjects = async () => {
  loading.value = true
  try {
    const response = await fetch('/api/chat-rooms/projects', {
      headers: {
        'Authorization': `Bearer ${localStorage.getItem('token')}`
      }
    })
    if (response.ok) {
      availableProjects.value = await response.json()
    }
  } catch (error) {
    console.error('Failed to load projects:', error)
  } finally {
    loading.value = false
  }
}

// 切换项目选择
const toggleProject = (projectName: string) => {
  const index = selectedProjects.value.indexOf(projectName)
  if (index > -1) {
    selectedProjects.value.splice(index, 1)
  } else {
    selectedProjects.value.push(projectName)
  }
}

// 保存配置
const save = async () => {
  saving.value = true
  try {
    const response = await fetch(`/api/chat-rooms/${props.roomId}/projects`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${localStorage.getItem('token')}`
      },
      body: JSON.stringify(selectedProjects.value)
    })
    
    if (response.ok) {
      emit('saved', [...selectedProjects.value])
      close()
    } else {
      alert('保存失败，请重试')
    }
  } catch (error) {
    console.error('Failed to save projects:', error)
    alert('保存失败，请重试')
  } finally {
    saving.value = false
  }
}

const close = () => {
  emit('close')
}

// 监听 visible 变化
watch(() => props.visible, (newVisible) => {
  if (newVisible) {
    loadProjects()
    selectedProjects.value = [...props.currentProjects]
  }
})
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-container {
  background: var(--bg-color, #fff);
  border-radius: 8px;
  width: 90%;
  max-width: 500px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
}

.modal-header {
  padding: 1rem;
  border-bottom: 1px solid var(--border-color, #e5e7eb);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.modal-header h3 {
  margin: 0;
  font-size: 1.125rem;
}

.close-btn {
  background: none;
  border: none;
  font-size: 1.5rem;
  cursor: pointer;
  color: var(--text-secondary, #6b7280);
}

.close-btn:hover {
  color: var(--text-primary, #111827);
}

.modal-body {
  padding: 1rem;
  overflow-y: auto;
  flex: 1;
}

.description {
  margin: 0 0 1rem 0;
  color: var(--text-secondary, #6b7280);
  font-size: 0.875rem;
}

.projects-list {
  max-height: 300px;
  overflow-y: auto;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 6px;
}

.project-item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  cursor: pointer;
  transition: background 0.2s;
  border-bottom: 1px solid var(--border-color, #e5e7eb);
}

.project-item:last-child {
  border-bottom: none;
}

.project-item:hover {
  background: var(--surface-color, #f9fafb);
}

.project-item.selected {
  background: rgba(59, 130, 246, 0.1);
}

.checkbox {
  font-size: 1rem;
}

.project-name {
  font-size: 0.875rem;
  color: var(--text-primary, #111827);
}

.selected-info {
  margin-top: 1rem;
  padding: 0.75rem;
  background: var(--surface-color, #f3f4f6);
  border-radius: 6px;
  font-size: 0.875rem;
}

.selected-info .empty {
  color: var(--text-secondary, #6b7280);
}

.modal-footer {
  padding: 1rem;
  border-top: 1px solid var(--border-color, #e5e7eb);
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
}

.btn-secondary {
  padding: 0.5rem 1rem;
  background: var(--surface-color, #f3f4f6);
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 6px;
  cursor: pointer;
  font-size: 0.875rem;
}

.btn-primary {
  padding: 0.5rem 1rem;
  background: #3b82f6;
  border: none;
  border-radius: 6px;
  color: white;
  cursor: pointer;
  font-size: 0.875rem;
}

.btn-primary:hover:not(:disabled) {
  background: #2563eb;
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
