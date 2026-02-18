// stores/flowchart.ts
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { initApiClient } from '@/api/client'

const apiClient = initApiClient()

export interface FlowchartNode {
  id: string
  type: string
  position: { x: number; y: number }
  data: Record<string, any>
  label?: string
}

export interface FlowchartEdge {
  id: string
  source: string
  target: string
  label?: string
  sourceHandle?: string
  animated?: boolean
}

export interface FlowchartTemplate {
  id: string
  templateId: string
  name: string
  description: string
  category: string
  icon: string
  version: number
  definition: {
    nodes: FlowchartNode[]
    edges: FlowchartEdge[]
  }
  variables: VariableDef[]
}

export interface VariableDef {
  name: string
  type: string
  description: string
  defaultValue?: string
  required: boolean
}

export interface FlowchartInstance {
  id: string
  instanceId: string
  templateId: string
  templateName: string
  roomId: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  currentNodeId?: string
  finalOutput?: string
  startedAt?: string
  completedAt?: string
  durationMs?: number
}

export const useFlowchartStore = defineStore('flowchart', () => {
  // State
  const templates = ref<FlowchartTemplate[]>([])
  const currentTemplate = ref<FlowchartTemplate | null>(null)
  const instances = ref<FlowchartInstance[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  // Getters
  const templateCategories = computed(() => {
    const cats = new Set(templates.value.map(t => t.category))
    return Array.from(cats)
  })

  const runningInstances = computed(() => 
    instances.value.filter(i => i.status === 'RUNNING')
  )

  // Actions
  async function fetchTemplates(category?: string) {
    loading.value = true
    try {
      const params = category ? `?category=${category}` : ''
      const response = await apiClient.get(`/flowchart-templates${params}`)
      templates.value = response.data.templates
    } catch (e: any) {
      error.value = e.message
    } finally {
      loading.value = false
    }
  }

  async function fetchTemplate(templateId: string) {
    loading.value = true
    try {
      const response = await apiClient.get(`/flowchart-templates/${templateId}`)
      currentTemplate.value = response.data
      return response.data
    } catch (e: any) {
      error.value = e.message
      throw e
    } finally {
      loading.value = false
    }
  }

  async function createTemplate(template: Partial<FlowchartTemplate>) {
    const response = await apiClient.post('/flowchart-templates', template)
    templates.value.unshift(response.data)
    return response.data
  }

  async function updateTemplate(templateId: string, updates: Partial<FlowchartTemplate>) {
    const response = await apiClient.put(`/flowchart-templates/${templateId}`, updates)
    const index = templates.value.findIndex(t => t.templateId === templateId)
    if (index !== -1) {
      templates.value[index] = response.data
    }
    return response.data
  }

  async function deleteTemplate(templateId: string) {
    await apiClient.delete(`/flowchart-templates/${templateId}`)
    templates.value = templates.value.filter(t => t.templateId !== templateId)
  }

  async function fetchInstances(roomId: string) {
    const response = await apiClient.get(`/flowchart-instances?roomId=${roomId}`)
    instances.value = response.data.instances
    return response.data
  }

  async function createInstance(templateId: string, roomId: string, variables: Record<string, any>) {
    const response = await apiClient.post('/flowchart-instances', {
      templateId,
      roomId,
      variables
    })
    instances.value.unshift(response.data)
    return response.data
  }

  async function stopInstance(instanceId: string) {
    await apiClient.post(`/flowchart-instances/${instanceId}/stop`)
    const instance = instances.value.find(i => i.instanceId === instanceId)
    if (instance) {
      instance.status = 'CANCELLED'
    }
  }

  return {
    templates,
    currentTemplate,
    instances,
    loading,
    error,
    templateCategories,
    runningInstances,
    fetchTemplates,
    fetchTemplate,
    createTemplate,
    updateTemplate,
    deleteTemplate,
    fetchInstances,
    createInstance,
    stopInstance
  }
})
