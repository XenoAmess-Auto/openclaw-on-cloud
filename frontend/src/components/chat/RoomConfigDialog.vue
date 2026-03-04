<template>
  <div class="modal" @click="$emit('close')">
    <div class="modal-content" @click.stop>
      <h3>聊天室配置</h3>
      
      <div class="form-group">
        <label>关联项目</label>
        <div class="multi-select">
          <div class="selected-items">
            <span 
              v-for="project in selectedProjects" 
              :key="project"
              class="tag"
            >
              {{ project }}
              <button @click="removeProject(project)" class="remove-btn">×</button>
            </span>
          </div>
          <div class="input-wrapper">
            <input
              v-model="projectInput"
              @keydown.enter.prevent="addProject"
              @keydown.backspace="handleBackspace"
              placeholder="输入项目名后按回车添加"
            />
            <button @click="addProject" class="add-btn">添加</button>
          </div>
        </div>
        <p class="hint">
          未配置时默认使用群名「{{ defaultProjectName }}」作为项目名
        </p>
      </div>

      <div class="form-group">
        <label>可用项目（点击添加）</label>
        <div class="available-projects">
          <span
            v-for="project in availableProjects"
            :key="project"
            class="project-chip"
            @click="addProjectFromList(project)"
          >
            {{ project }}
          </span>
          <span v-if="availableProjects.length === 0" class="empty-hint">
            暂无预定义项目
          </span>
        </div>
      </div>
      
      <div class="modal-actions">
        <button @click="$emit('close')">取消</button>
        <button @click="save" :disabled="saving">
          {{ saving ? '保存中...' : '保存' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { chatRoomApi } from '@/api/chatRoom'

interface ProjectInfo {
  name: string
  path: string
}

const props = defineProps<{
  roomId: string
  roomName: string
  currentProjects?: string[]
}>()

const emit = defineEmits<{
  close: []
  save: [projects: string[]]
}>()

const projectInput = ref('')
const selectedProjects = ref<string[]>([])
const saving = ref(false)
const loadingProjects = ref(false)
const availableProjectsFromApi = ref<ProjectInfo[]>([])

// 默认项目名（群名去掉"群"后缀）
const defaultProjectName = computed(() => {
  return props.roomName?.replace(/群$/, '') || ''
})

// 从 API 获取的可用项目列表
const availableProjects = computed(() => {
  const apiProjects = availableProjectsFromApi.value.map(p => p.name)
  // 加上默认项目名
  const defaults = [defaultProjectName.value].filter(Boolean)
  // 去重并过滤掉已选择的
  return [...new Set([...defaults, ...apiProjects])].filter(
    p => p && !selectedProjects.value.includes(p)
  )
})

onMounted(async () => {
  // 初始化已选项目
  if (props.currentProjects?.length) {
    selectedProjects.value = [...props.currentProjects]
  }
  // 加载可用项目列表
  await loadAvailableProjects()
})

async function loadAvailableProjects() {
  loadingProjects.value = true
  try {
    const response = await chatRoomApi.getAvailableProjects()
    if (response.data) {
      availableProjectsFromApi.value = response.data
    }
  } catch (error) {
    console.error('Failed to load available projects:', error)
  } finally {
    loadingProjects.value = false
  }
}

function addProject() {
  const name = projectInput.value.trim()
  if (name && !selectedProjects.value.includes(name)) {
    selectedProjects.value.push(name)
    projectInput.value = ''
  }
}

function addProjectFromList(project: string) {
  if (!selectedProjects.value.includes(project)) {
    selectedProjects.value.push(project)
  }
}

function removeProject(project: string) {
  const index = selectedProjects.value.indexOf(project)
  if (index > -1) {
    selectedProjects.value.splice(index, 1)
  }
}

function handleBackspace() {
  if (projectInput.value === '' && selectedProjects.value.length > 0) {
    selectedProjects.value.pop()
  }
}

async function save() {
  saving.value = true
  try {
    await chatRoomApi.updateRoomProjects(props.roomId, selectedProjects.value)
    emit('save', selectedProjects.value)
  } catch (error) {
    console.error('Failed to save projects:', error)
    alert('保存失败，请重试')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.modal {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
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
  max-width: 500px;
  max-height: 80vh;
  overflow-y: auto;
}

.modal-content h3 {
  margin: 0 0 1.5rem 0;
  color: var(--text-primary);
  font-size: 1.25rem;
}

.form-group {
  margin-bottom: 1.5rem;
}

.form-group label {
  display: block;
  margin-bottom: 0.5rem;
  font-size: 0.875rem;
  color: var(--text-primary);
  font-weight: 500;
}

.multi-select {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 0.5rem;
  background: var(--bg-color);
}

.selected-items {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
  min-height: 32px;
}

.tag {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.25rem 0.5rem;
  background: var(--primary-color);
  color: white;
  border-radius: 4px;
  font-size: 0.875rem;
}

.remove-btn {
  background: none;
  border: none;
  color: white;
  cursor: pointer;
  font-size: 1rem;
  line-height: 1;
  padding: 0;
  width: 16px;
  height: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.remove-btn:hover {
  opacity: 0.8;
}

.input-wrapper {
  display: flex;
  gap: 0.5rem;
}

.input-wrapper input {
  flex: 1;
  padding: 0.5rem;
  border: 1px solid var(--border-color);
  border-radius: 4px;
  font-size: 0.875rem;
  background: var(--surface-color);
  color: var(--text-primary);
}

.add-btn {
  padding: 0.5rem 1rem;
  background: var(--primary-color);
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 0.875rem;
}

.add-btn:hover {
  opacity: 0.9;
}

.hint {
  margin: 0.5rem 0 0 0;
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.available-projects {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  padding: 0.75rem;
  background: var(--bg-color);
  border-radius: 8px;
  min-height: 48px;
}

.project-chip {
  padding: 0.375rem 0.75rem;
  background: var(--surface-color);
  border: 1px solid var(--border-color);
  border-radius: 4px;
  font-size: 0.875rem;
  color: var(--text-primary);
  cursor: pointer;
  transition: all 0.2s;
}

.project-chip:hover {
  background: var(--primary-color);
  color: white;
  border-color: var(--primary-color);
}

.empty-hint {
  font-size: 0.875rem;
  color: var(--text-secondary);
  font-style: italic;
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

.modal-actions button:first-child {
  background: transparent;
  border: 1px solid var(--border-color);
  color: var(--text-primary);
}

.modal-actions button:last-child {
  background: var(--primary-color);
  color: white;
  border: none;
}

.modal-actions button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 移动端适配 */
@media (max-width: 768px) {
  .modal-content {
    padding: 1.25rem;
    width: 92%;
    border-radius: 14px;
    margin: 1rem;
  }

  .modal-content h3 {
    font-size: 1.125rem;
  }

  .form-group label {
    font-size: 0.8125rem;
  }

  .input-wrapper input {
    font-size: 16px;
    padding: 0.625rem;
  }

  .tag {
    font-size: 0.8125rem;
  }

  .project-chip {
    font-size: 0.8125rem;
    padding: 0.375rem 0.625rem;
  }

  .modal-actions button {
    padding: 0.5rem 0.875rem;
    font-size: 0.8125rem;
  }
}
</style>
