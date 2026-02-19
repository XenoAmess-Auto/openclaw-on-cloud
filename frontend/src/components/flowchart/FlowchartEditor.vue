<template>
  <div class="flowchart-editor">
    <!-- 工具栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <button class="btn" @click="addNode('start')" title="开始节点">
          <span class="icon"></span> 开始
        </button>
        <button class="btn" @click="addNode('llm')" title="AI 节点">
          <span class="icon"></span> AI
        </button>
        <button class="btn" @click="addNode('condition')" title="条件节点">
          <span class="icon"></span> 条件
        </button>
        <button class="btn" @click="addNode('code')" title="代码节点">
          <span class="icon"></span> 代码
        </button>
        <button class="btn" @click="addNode('variable')" title="变量节点">
          <span class="icon"></span> 变量
        </button>
        <button class="btn" @click="addNode('wait')" title="等待节点">
          <span class="icon"></span> 等待
        </button>
        <button class="btn" @click="addNode('end')" title="结束节点">
          <span class="icon"></span> 结束
        </button>
      </div>
      <div class="toolbar-right">
        <button class="btn btn-primary" @click="saveTemplate" :disabled="saving">
          {{ saving ? '保存中...' : '保存' }}
        </button>
      </div>
    </div>

    <!-- 画布区域 -->
    <div class="canvas-container">
      <VueFlow
        v-model="elements"
        :default-viewport="viewport"
        :min-zoom="0.2"
        :max-zoom="4"
        :delete-key-code="null"
        :selection-key-code="null"
        :multi-selection-key-code="null"
        @node-click="onNodeClick"
        @node-context-menu="onNodeContextMenu"
        @edge-context-menu="onEdgeContextMenu"
        @connect="onConnect"
        @pane-click="onPaneClick"
        fit-view-on-init
      >
        <!-- 自定义节点 -->
        <template #node-start="{ data, id }">
          <div
            class="node node-start"
            @touchstart="onTouchStart($event, id)"
            @touchend="onTouchEnd"
            @touchmove="onTouchMove"
          >
            <div class="node-header"></div>
            <div class="node-content">
              <span class="node-icon"></span>
              <span>{{ data?.label || '开始' }}</span>
            </div>
            <Handle type="source" :position="Position.Bottom" />
          </div>
        </template>

        <template #node-llm="{ data, id }">
          <div
            class="node node-llm"
            @touchstart="onTouchStart($event, id)"
            @touchend="onTouchEnd"
            @touchmove="onTouchMove"
          >
            <Handle type="target" :position="Position.Top" />
            <div class="node-header"></div>
            <div class="node-content">
              <span class="node-icon"></span>
              <div class="node-info">
                <div class="node-title">{{ data?.label || 'AI 调用' }}</div>
                <div class="node-subtitle" v-if="data?.model">{{ data.model }}</div>
              </div>
            </div>
            
            <Handle type="source" :position="Position.Bottom" />
          </div>
        </template>

        <template #node-condition="{ data, id }">
          <div
            class="node node-condition"
            @touchstart="onTouchStart($event, id)"
            @touchend="onTouchEnd"
            @touchmove="onTouchMove"
          >
            <Handle type="target" :position="Position.Top" />
            <div class="node-content">
              <span class="node-icon"></span>
              <div class="node-info">
                <div class="node-title">{{ data?.label || '条件' }}</div>
                <div class="node-subtitle" v-if="data?.conditionMode === 'range'">{{ (data?.rangeBranches?.length || 0) }} 个范围</div>
                <div class="node-subtitle" v-else-if="data?.conditionMode === 'switch'">{{ (data?.branches?.length || 0) + 1 }} 个分支</div>
                <div class="node-subtitle" v-else>真 / 假</div>
              </div>
            </div>

            <!-- 范围模式：动态范围输出端口 -->
            <template v-if="data?.conditionMode === 'range' && data?.rangeBranches?.length > 0">
              <Handle
                v-for="(branch, index) in data.rangeBranches"
                :key="index"
                type="source"
                :position="Position.Bottom"
                :id="branch.handleId || ('range_' + index)"
                :style="{ left: getBranchPosition(index, data.rangeBranches.length) }"
              >
                <span class="handle-label">{{ branch.label || (index + 1) }}</span>
              </Handle>
            </template>
            <!-- 分支模式：动态分支输出端口 -->
            <template v-else-if="data?.branches?.length > 0">
              <Handle
                v-for="(branch, index) in data.branches"
                :key="index"
                type="source"
                :position="Position.Bottom"
                :id="'branch_' + index"
                :style="{ left: getBranchPosition(index, data.branches.length) }"
              >
                <span class="handle-label">{{ branch.label || index + 1 }}</span>
              </Handle>
            </template>
            <!-- 默认布尔模式：两分支 -->
            <template v-else>
              <Handle type="source" :position="Position.Bottom" id="true" :style="{ left: '25%' }">
                <span class="handle-label">真</span>
              </Handle>
              <Handle type="source" :position="Position.Bottom" id="false" :style="{ left: '75%' }">
                <span class="handle-label">假</span>
              </Handle>
            </template>
          </div>
        </template>

        <template #node-code="{ data, id }">
          <div
            class="node node-code"
            @touchstart="onTouchStart($event, id)"
            @touchend="onTouchEnd"
            @touchmove="onTouchMove"
          >
            <Handle type="target" :position="Position.Top" />
            <div class="node-content">
              <span class="node-icon">💻</span>
              <div class="node-info">
                <div class="node-title">{{ data?.label || '代码' }}</div>
                <div class="node-subtitle">{{ data?.language || 'groovy' }}</div>
              </div>
            </div>
            
            <Handle type="source" :position="Position.Bottom" />
          </div>
        </template>

        <template #node-variable="{ data, id }">
          <div
            class="node node-variable"
            @touchstart="onTouchStart($event, id)"
            @touchend="onTouchEnd"
            @touchmove="onTouchMove"
          >
            <Handle type="target" :position="Position.Top" />
            <div class="node-content">
              <span class="node-icon">🔧</span>
              <div class="node-info">
                <div class="node-title">{{ data?.label || '变量' }}</div>
                <div class="node-subtitle" v-if="data?.varName">{{ data.varName }} = ...</div>
              </div>
            </div>
            
            <Handle type="source" :position="Position.Bottom" />
          </div>
        </template>

        <template #node-wait="{ data, id }">
          <div
            class="node node-wait"
            @touchstart="onTouchStart($event, id)"
            @touchend="onTouchEnd"
            @touchmove="onTouchMove"
          >
            <Handle type="target" :position="Position.Top" />
            <div class="node-content">
              <span class="node-icon"></span>
              <span>等待 {{ data?.waitSeconds || 0 }}s</span>
            </div>
            
            <Handle type="source" :position="Position.Bottom" />
          </div>
        </template>

        <template #node-end="{ data, id }">
          <div
            class="node node-end"
            @touchstart="onTouchStart($event, id)"
            @touchend="onTouchEnd"
            @touchmove="onTouchMove"
          >
            <Handle type="target" :position="Position.Top" />
            <div class="node-content">
              <span class="node-icon"></span>
              <span>{{ data?.label || '结束' }}</span>
            </div>
          </div>
        </template>

        <Controls />
        <Background pattern-color="#aaa" :gap="16" />
        <MiniMap />
      </VueFlow>

      <!-- 右键/长按菜单 -->
      <div
        v-if="contextMenu.visible"
        class="node-context-menu"
        :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
      >
        <div class="context-menu-item" @click="onContextMenuDetail">
          <span class="menu-icon">📋</span>
          <span>详情</span>
        </div>
        <div class="context-menu-divider"></div>
        <div class="context-menu-item delete" @click="onContextMenuDelete">
          <span class="menu-icon">🗑️</span>
          <span>删除</span>
        </div>
      </div>
    </div>

    <!-- 节点配置面板 -->
    <div v-if="selectedNode" class="config-panel">
      <div class="panel-header">
        <h3>节点配置</h3>
        <button class="btn-delete" @click="deleteNode" title="删除节点">🗑️</button>
      </div>

      <div class="panel-content">
        <!-- 通用配置 -->
        <div class="form-group">
          <label>节点名称</label>
          <input v-model="nodeConfig.label" type="text" placeholder="输入名称" />
        </div>

        <div class="form-group">
          <label>描述</label>
          <textarea v-model="nodeConfig.description" rows="2" placeholder="输入描述"></textarea>
        </div>

        <!-- LLM 节点配置 -->
        <template v-if="selectedNode.type === 'llm'">
          <div class="form-group">
            <label>模型</label>
            <select v-model="nodeConfig.model">
              <option value="openclaw">OpenClaw</option>
              <option value="kimi">Kimi</option>
              <option value="claude">Claude</option>
            </select>
          </div>

          <div class="form-group">
            <label>系统提示词</label>
            <textarea v-model="nodeConfig.systemPrompt" rows="3" placeholder="系统提示词"></textarea>
          </div>

          <div class="form-group">
            <label>用户提示词 (支持变量语法)</label>
            <textarea v-model="nodeConfig.userPrompt" rows="4" placeholder="用户提示词"></textarea>
          </div>

          <div class="form-group">
            <label>输出变量</label>
            <input v-model="nodeConfig.outputVar" type="text" placeholder="result" />
          </div>
        </template>

        <!-- 条件节点配置 -->
        <template v-if="selectedNode.type === 'condition'">
          <div class="form-group">
            <label>判断模式</label>
            <select v-model="nodeConfig.conditionMode">
              <option value="boolean">布尔判断（真/假）</option>
              <option value="switch">分支判断（多条件）</option>
              <option value="range">范围判断（数值区间）</option>
            </select>
          </div>

          <!-- 布尔模式 -->
          <template v-if="nodeConfig.conditionMode === 'boolean' || !nodeConfig.conditionMode">
            <div class="form-group">
              <label>条件表达式 (如: score > 0.5)</label>
              <input v-model="nodeConfig.conditionExpr" type="text" placeholder="输入条件" />
            </div>

            <div class="form-group">
              <label>真分支目标节点ID</label>
              <input v-model="nodeConfig.trueTarget" type="text" placeholder="节点ID" />
            </div>

            <div class="form-group">
              <label>假分支目标节点ID</label>
              <input v-model="nodeConfig.falseTarget" type="text" placeholder="节点ID" />
            </div>
          </template>

          <!-- 分支模式 -->
          <template v-else-if="nodeConfig.conditionMode === 'switch'">
            <div class="form-group">
              <label>判断变量</label>
              <input v-model="nodeConfig.switchVar" type="text" placeholder="如: score, status" />
            </div>

            <div class="branches-section">
              <div class="section-header">
                <label>分支列表</label>
                <button class="btn-sm" @click="addBranch">+ 添加分支</button>
              </div>

              <div v-for="(branch, index) in nodeConfig.branches" :key="index" class="branch-item">
                <div class="branch-header">
                  <span class="branch-index">{{ index + 1 }}</span>
                  <button class="btn-icon-sm" @click="removeBranch(index)">🗑️</button>
                </div>
                <div class="branch-fields">
                  <input v-model="branch.label" type="text" placeholder="分支标签" class="branch-input" />
                  <select v-model="branch.operator" class="branch-select">
                    <option value="eq">等于</option>
                    <option value="ne">不等于</option>
                    <option value="gt">大于</option>
                    <option value="gte">大于等于</option>
                    <option value="lt">小于</option>
                    <option value="lte">小于等于</option>
                    <option value="contains">包含</option>
                    <option value="regex">正则匹配</option>
                  </select>
                  <input v-model="branch.value" type="text" placeholder="比较值" class="branch-input" />
                </div>
              </div>
            </div>
          </template>

          <!-- 范围模式 -->
          <template v-else-if="nodeConfig.conditionMode === 'range'">
            <div class="form-group">
              <label>判断变量（数值）</label>
              <input v-model="nodeConfig.rangeVar" type="text" placeholder="如: score, temperature, age" />
            </div>

            <div class="branches-section">
              <div class="section-header">
                <label>范围分支列表</label>
                <button class="btn-sm" @click="addRangeBranch">+ 添加范围</button>
              </div>

              <div v-for="(branch, index) in nodeConfig.rangeBranches" :key="index" class="branch-item range-branch">
                <div class="branch-header">
                  <span class="branch-index">{{ index + 1 }}</span>
                  <button class="btn-icon-sm" @click="removeRangeBranch(index)">🗑️</button>
                </div>
                <div class="range-fields">
                  <input v-model="branch.label" type="text" placeholder="分支标签（如：优秀）" class="branch-input full-width" />
                  <div class="range-row">
                    <div class="range-bound">
                      <select v-model="branch.minInclusive" class="range-select">
                        <option :value="true">≥</option>
                        <option :value="false">></option>
                      </select>
                      <input v-model.number="branch.min" type="number" placeholder="最小值" class="range-input" />
                    </div>
                    <span class="range-separator">~</span>
                    <div class="range-bound">
                      <input v-model.number="branch.max" type="number" placeholder="最大值" class="range-input" />
                      <select v-model="branch.maxInclusive" class="range-select">
                        <option :value="true">≤</option>
                        <option :value="false"><</option>
                      </select>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div class="form-group">
              <label>默认分支（没有匹配时）</label>
              <input v-model="nodeConfig.rangeDefaultBranch" type="text" placeholder="如：其他、未分类" />
            </div>
          </template>
        </template>

        <!-- 代码节点配置 -->
        <template v-if="selectedNode.type === 'code'">
          <div class="form-group">
            <label>编程语言</label>
            <select v-model="nodeConfig.language">
              <option value="groovy">Groovy</option>
              <option value="java">Java</option>
            </select>
          </div>

          <div class="form-group">
            <label>代码</label>
            <textarea v-model="nodeConfig.code" rows="8" placeholder="// 可直接访问流程变量&#10;// 示例：将 JSON 字符串解析并提取字段&#10;import groovy.json.JsonSlurper&#10;def json = new JsonSlurper().parseText(llmResponse)&#10;return json.completed"></textarea>
          </div>

          <div class="form-group">
            <label>输出变量（可选）</label>
            <input v-model="nodeConfig.outputVar" type="text" placeholder="将代码返回值存入此变量" />
          </div>
        </template>

        <!-- 变量节点配置 -->
        <template v-if="selectedNode.type === 'variable'">
          <div class="form-group">
            <label>变量名</label>
            <input v-model="nodeConfig.varName" type="text" placeholder="varName" />
          </div>

          <div class="form-group">
            <label>变量值 (支持变量语法)</label>
            <input v-model="nodeConfig.varValue" type="text" placeholder="值或表达式" />
          </div>
        </template>

        <!-- 等待节点配置 -->
        <template v-if="selectedNode.type === 'wait'">
          <div class="form-group">
            <label>等待秒数</label>
            <input v-model.number="nodeConfig.waitSeconds" type="number" min="1" max="3600" />
          </div>
        </template>

        <!-- 结束节点配置 -->
        <template v-if="selectedNode.type === 'end'">
          <div class="form-group">
            <label>输出变量</label>
            <input v-model="nodeConfig.outputVar" type="text" placeholder="要输出的变量名" />
          </div>
        </template>

        <div class="form-group">
          <label>错误处理</label>
          <select v-model="nodeConfig.onError">
            <option value="stop">停止</option>
            <option value="continue">继续</option>
          </select>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { VueFlow, useVueFlow, Handle, Position } from '@vue-flow/core'
import { Controls } from '@vue-flow/controls'
import { Background } from '@vue-flow/background'
import { MiniMap } from '@vue-flow/minimap'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/controls/dist/style.css'

const props = defineProps<{
  modelValue?: {
    nodes: any[]
    edges: any[]
  }
  readonly?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: { nodes: any[]; edges: any[] }]
  save: [value: { nodes: any[]; edges: any[] }]
}>()

const { addNodes, addEdges, removeNodes, removeEdges, findNode, getSelectedNodes, getSelectedEdges } = useVueFlow()

const elements = ref<any[]>([])
const selectedNode = ref<any>(null)
const nodeConfig = ref<Record<string, any>>({})
const saving = ref(false)
const isInitialized = ref(false)

// 右键/长按菜单状态
const contextMenu = ref({
  visible: false,
  x: 0,
  y: 0,
  type: null as 'node' | 'edge' | null,
  nodeId: null as string | null,
  edgeId: null as string | null
})

const viewport = ref({ x: 0, y: 0, zoom: 1 })

// 初始化 - 同步初始化（用于非异步场景）
if (props.modelValue?.nodes?.length || props.modelValue?.edges?.length) {
  elements.value = [
    ...props.modelValue.nodes,
    ...props.modelValue.edges
  ]
  isInitialized.value = true
}

// 监听 modelValue 变化（用于异步加载场景）
watch(() => props.modelValue, (newValue) => {
  if (!isInitialized.value && newValue?.nodes?.length || newValue?.edges?.length) {
    elements.value = [
      ...newValue.nodes,
      ...newValue.edges
    ]
    isInitialized.value = true
  }
}, { deep: true })

// 键盘事件处理
function handleKeyDown(event: KeyboardEvent) {
  // Delete 或 Backspace 键删除选中元素
  if (event.key === 'Delete' || event.key === 'Backspace') {
    // 避免在输入框中触发
    if (event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement) {
      return
    }

    const selectedNodes = getSelectedNodes.value
    const selectedEdges = getSelectedEdges.value

    if (selectedNodes.length > 0) {
      removeNodes(selectedNodes.map(n => n.id))
      // 如果删除的是当前选中的节点，清空配置面板
      if (selectedNode.value && selectedNodes.some(n => n.id === selectedNode.value.id)) {
        selectedNode.value = null
      }
    }

    if (selectedEdges.length > 0) {
      removeEdges(selectedEdges.map(e => e.id))
    }
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleKeyDown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleKeyDown)
})

// 触摸长按事件处理
let longPressTimer: ReturnType<typeof setTimeout> | null = null
const LONG_PRESS_DURATION = 500 // 长按触发时间（毫秒）

function onTouchStart(event: TouchEvent, nodeId: string) {
  longPressTimer = setTimeout(() => {
    // 长按触发，显示上下文菜单
    const touch = event.touches[0]
    contextMenu.value = {
      visible: true,
      x: touch.clientX,
      y: touch.clientY,
      type: 'node',
      nodeId: nodeId,
      edgeId: null
    }
    longPressTimer = null
  }, LONG_PRESS_DURATION)
}

function onTouchEnd() {
  if (longPressTimer) {
    clearTimeout(longPressTimer)
    longPressTimer = null
  }
}

function onTouchMove() {
  // 移动时取消长按
  if (longPressTimer) {
    clearTimeout(longPressTimer)
    longPressTimer = null
  }
}

// 生成唯一ID
function generateId() {
  return `node_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
}

// 添加节点
function addNode(type: string) {
  const id = generateId()
  const position = { x: 100 + Math.random() * 200, y: 100 + Math.random() * 200 }
  
  const nodeData: Record<string, any> = {
    label: getDefaultLabel(type),
    onError: 'stop'
  }

  // 根据类型设置默认值
  switch (type) {
    case 'llm':
      nodeData.model = 'openclaw'
      nodeData.temperature = 0.7
      break
    case 'wait':
      nodeData.waitSeconds = 5
      break
    case 'condition':
      nodeData.conditionExpr = ''
      break
    case 'code':
      nodeData.language = 'groovy'
      nodeData.code = '// 访问流程变量\n// 示例：return variables.myVar'
      break
    case 'variable':
      nodeData.varName = ''
      nodeData.varValue = ''
      break
  }

  const newNode = {
    id,
    type,
    position,
    data: nodeData
  }

  addNodes([newNode])
}

function getDefaultLabel(type: string): string {
  const labels: Record<string, string> = {
    start: '开始',
    llm: 'AI 调用',
    condition: '条件判断',
    code: '代码执行',
    variable: '设置变量',
    wait: '等待',
    end: '结束'
  }
  return labels[type] || type
}

// 获取分支位置百分比
function getBranchPosition(index: number, total: number): string {
  if (total === 1) return '50%'
  const step = 100 / (total + 1)
  return `${step * (index + 1)}%`
}

// 添加分支
function addBranch() {
  if (!nodeConfig.value.branches) {
    nodeConfig.value.branches = []
  }
  nodeConfig.value.branches.push({
    label: `分支${nodeConfig.value.branches.length + 1}`,
    operator: 'eq',
    value: ''
  })
}

// 删除分支
function removeBranch(index: number) {
  if (nodeConfig.value.branches) {
    nodeConfig.value.branches.splice(index, 1)
  }
}

// 添加范围分支
function addRangeBranch() {
  if (!nodeConfig.value.rangeBranches) {
    nodeConfig.value.rangeBranches = []
  }
  const index = nodeConfig.value.rangeBranches.length
  nodeConfig.value.rangeBranches.push({
    label: `范围${index + 1}`,
    min: null,
    max: null,
    minInclusive: true,
    maxInclusive: true,
    handleId: `range_${index}`
  })
}

// 删除范围分支
function removeRangeBranch(index: number) {
  if (nodeConfig.value.rangeBranches) {
    nodeConfig.value.rangeBranches.splice(index, 1)
    // 重新分配 handleId
    nodeConfig.value.rangeBranches.forEach((branch: any, i: number) => {
      branch.handleId = `range_${i}`
    })
  }
}

// 监听节点选中，初始化分支数据
watch(selectedNode, (newNode) => {
  if (newNode && newNode.type === 'condition') {
    // 确保 branches 字段存在
    if (!nodeConfig.value.branches) {
      nodeConfig.value.branches = []
    }
    // 确保 rangeBranches 字段存在
    if (!nodeConfig.value.rangeBranches) {
      nodeConfig.value.rangeBranches = []
    }
  }
})
function onConnect(params: any) {
  const edge = {
    id: `e_${params.source}_${params.target}`,
    source: params.source,
    target: params.target,
    sourceHandle: params.sourceHandle,
    animated: false
  }
  addEdges([edge])
}

// 点击节点
function onNodeClick(event: any) {
  // 关闭右键菜单（如果打开）
  contextMenu.value.visible = false
  selectedNode.value = event.node
  nodeConfig.value = { ...event.node.data }
}

// 右键/长按节点 - 显示上下文菜单
function onNodeContextMenu(event: any) {
  event.event.preventDefault()
  const node = event.node
  contextMenu.value = {
    visible: true,
    x: event.event.clientX,
    y: event.event.clientY,
    type: 'node',
    nodeId: node.id,
    edgeId: null
  }
}

// 右键/长按连接线 - 显示上下文菜单
function onEdgeContextMenu(event: any) {
  event.event.preventDefault()
  const edge = event.edge
  contextMenu.value = {
    visible: true,
    x: event.event.clientX,
    y: event.event.clientY,
    type: 'edge',
    nodeId: null,
    edgeId: edge.id
  }
}

// 上下文菜单 - 查看详情
function onContextMenuDetail() {
  if (contextMenu.value.type === 'node' && contextMenu.value.nodeId) {
    const node = findNode(contextMenu.value.nodeId)
    if (node) {
      selectedNode.value = node
      nodeConfig.value = { ...node.data }
    }
  } else if (contextMenu.value.type === 'edge' && contextMenu.value.edgeId) {
    // 对于连接线，可以选中它（Vue Flow 会高亮显示）
    // 或者可以显示一个 toast/alert 显示连接信息
    const edge = elements.value.find(e => e.id === contextMenu.value.edgeId && e.source)
    if (edge) {
      alert(`连接线详情:\n从: ${edge.source}\n到: ${edge.target}${edge.sourceHandle ? '\n源句柄: ' + edge.sourceHandle : ''}`)
    }
  }
  contextMenu.value.visible = false
}

// 上下文菜单 - 删除
function onContextMenuDelete() {
  if (contextMenu.value.type === 'node' && contextMenu.value.nodeId) {
    removeNodes([contextMenu.value.nodeId])
    if (selectedNode.value?.id === contextMenu.value.nodeId) {
      selectedNode.value = null
    }
  } else if (contextMenu.value.type === 'edge' && contextMenu.value.edgeId) {
    removeEdges([contextMenu.value.edgeId])
  }
  contextMenu.value.visible = false
}

// 点击画布空白处
function onPaneClick() {
  selectedNode.value = null
  contextMenu.value.visible = false
}

// 删除节点
function deleteNode() {
  if (selectedNode.value) {
    removeNodes([selectedNode.value.id])
    selectedNode.value = null
  }
}

// 监听配置变化并更新节点
watch(nodeConfig, (newConfig) => {
  if (selectedNode.value) {
    const node = findNode(selectedNode.value.id)
    if (node) {
      node.data = { ...newConfig }
    }
  }
}, { deep: true })

// 保存模板
async function saveTemplate() {
  saving.value = true

  const data = {
    nodes: elements.value.filter((e: any) => !e.source),
    edges: elements.value.filter((e: any) => e.source)
  }

  emit('save', data)

  saving.value = false
}

// 获取当前定义
function getDefinition() {
  const nodes = elements.value.filter(e => !e.source)
  const edges = elements.value.filter(e => e.source)
  return { nodes, edges }
}

defineExpose({
  getDefinition,
  addNode
})
</script>

<style scoped>
.flowchart-editor {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #f5f5f5;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: white;
  border-bottom: 1px solid #e0e0e0;
}

.toolbar-left {
  display: flex;
  gap: 8px;
}

.btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border: 1px solid #d0d0d0;
  border-radius: 6px;
  background: white;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
}

.btn:hover {
  background: #f0f0f0;
  border-color: #b0b0b0;
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
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-delete {
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

.btn-delete:hover {
  background: #fee2e2;
}

.canvas-container {
  flex: 1;
  position: relative;
}

.config-panel {
  position: absolute;
  right: 16px;
  top: 16px;
  width: 300px;
  max-height: calc(100% - 32px);
  background: white;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  overflow-y: auto;
  z-index: 10;
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
  padding: 16px;
}

.form-group {
  margin-bottom: 16px;
}

.form-group label {
  display: block;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 500;
  color: #374151;
}

.form-group input,
.form-group select,
.form-group textarea {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #d0d0d0;
  border-radius: 6px;
  font-size: 14px;
  box-sizing: border-box;
}

.form-group input:focus,
.form-group select:focus,
.form-group textarea:focus {
  outline: none;
  border-color: #4f46e5;
}

/* 分支配置样式 */
.branches-section {
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  padding: 12px;
  margin-top: 8px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.section-header label {
  font-size: 13px;
  font-weight: 500;
  color: #374151;
  margin: 0;
}

.btn-sm {
  padding: 4px 10px;
  border: 1px solid #d0d0d0;
  border-radius: 4px;
  background: white;
  cursor: pointer;
  font-size: 12px;
  transition: all 0.2s;
}

.btn-sm:hover {
  background: #f0f0f0;
  border-color: #b0b0b0;
}

.branch-item {
  background: #f9fafb;
  border-radius: 6px;
  padding: 10px;
  margin-bottom: 10px;
}

.branch-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.branch-index {
  font-size: 12px;
  font-weight: 600;
  color: #6b7280;
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #e5e7eb;
  border-radius: 4px;
}

.btn-icon-sm {
  width: 24px;
  height: 24px;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
}

.btn-icon-sm:hover {
  background: #fee2e2;
}

.branch-fields {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.branch-input,
.branch-select {
  padding: 6px 10px;
  border: 1px solid #d0d0d0;
  border-radius: 4px;
  font-size: 13px;
  box-sizing: border-box;
}

.branch-input:focus,
.branch-select:focus {
  outline: none;
  border-color: #4f46e5;
}

/* 范围分支样式 */
.range-branch {
  background: #fffbeb;
  border: 1px solid #fcd34d;
}

.range-fields {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.range-fields .full-width {
  width: 100%;
}

.range-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.range-bound {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
}

.range-select {
  width: 50px;
  padding: 6px 4px;
  border: 1px solid #d0d0d0;
  border-radius: 4px;
  font-size: 13px;
  background: white;
  text-align: center;
}

.range-input {
  flex: 1;
  min-width: 60px;
  padding: 6px 8px;
  border: 1px solid #d0d0d0;
  border-radius: 4px;
  font-size: 13px;
}

.range-separator {
  font-size: 14px;
  color: #6b7280;
  font-weight: 500;
  padding: 0 2px;
}

/* 节点样式 */
:deep(.node) {
  padding: 12px 16px;
  border-radius: 8px;
  background: white;
  border: 2px solid #e0e0e0;
  min-width: 120px;
}

:deep(.node-start) {
  border-color: #10b981;
  background: #ecfdf5;
}

:deep(.node-llm) {
  border-color: #4f46e5;
  background: #eef2ff;
}

:deep(.node-condition) {
  border-color: #f59e0b;
  background: #fffbeb;
}

:deep(.node-variable) {
  border-color: #8b5cf6;
  background: #f5f3ff;
}

:deep(.node-code) {
  border-color: #0ea5e9;
  background: #f0f9ff;
}

:deep(.node-wait) {
  border-color: #6b7280;
  background: #f9fafb;
}

:deep(.node-end) {
  border-color: #ef4444;
  background: #fef2f2;
}

:deep(.node-content) {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 500;
}

:deep(.node-info) {
  display: flex;
  flex-direction: column;
}

:deep(.node-title) {
  font-weight: 500;
}

:deep(.node-subtitle) {
  font-size: 11px;
  color: #6b7280;
}

:deep(.handle-label) {
  position: absolute;
  top: -20px;
  font-size: 11px;
  color: #6b7280;
}

/* Handle 连接点样式 */
:deep(.vue-flow__handle) {
  width: 10px;
  height: 10px;
  background: #4f46e5;
  border: 2px solid white;
  border-radius: 50%;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.2);
}

:deep(.vue-flow__handle:hover) {
  background: #4338ca;
  transform: scale(1.2);
}

:deep(.vue-flow__handle-top) {
  top: -5px;
}

:deep(.vue-flow__handle-bottom) {
  bottom: -5px;
}

/* 连接线和动画 */
:deep(.vue-flow__edge-path) {
  stroke: #4f46e5;
  stroke-width: 2;
}

:deep(.vue-flow__edge.animated .vue-flow__edge-path) {
  stroke-dasharray: 5;
  animation: dashdraw 0.5s linear infinite;
}

@keyframes dashdraw {
  from {
    stroke-dashoffset: 10;
  }
  to {
    stroke-dashoffset: 0;
  }
}

/* 右键/长按上下文菜单 */
.node-context-menu {
  position: fixed;
  background: white;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.15), 0 2px 4px rgba(0, 0, 0, 0.1);
  padding: 4px;
  min-width: 120px;
  z-index: 1000;
  animation: menuPopIn 0.15s ease-out;
}

@keyframes menuPopIn {
  from {
    opacity: 0;
    transform: scale(0.95);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}

.context-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  color: #374151;
  transition: all 0.15s;
}

.context-menu-item:hover {
  background: #f3f4f6;
}

.context-menu-item.delete {
  color: #dc2626;
}

.context-menu-item.delete:hover {
  background: #fee2e2;
}

.menu-icon {
  font-size: 14px;
  width: 16px;
  text-align: center;
}

.context-menu-divider {
  height: 1px;
  background: #e5e7eb;
  margin: 4px 0;
}
</style>
