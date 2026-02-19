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
        <button class="btn" @click="showHelpDialog = true" title="帮助">❓</button>
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

    <!-- 帮助对话框 -->
    <div v-if="showHelpDialog" class="dialog-overlay" @click.self="showHelpDialog = false">
      <div class="help-dialog">
        <div class="help-header">
          <h2>流程图编辑帮助</h2>
          <button class="btn-close" @click="showHelpDialog = false">✕</button>
        </div>
        
        <div class="help-content">
          <section class="help-section">
            <h3>📝 变量语法</h3>
            <p>使用 <code v-pre>{{ }}</code> 语法引用变量，例如：</p>
            <div class="code-block" v-pre>
              请总结群 {{ roomId }} 最近关于 {{ topic }} 的讨论
            </div>
            <p class="note">
              💡 语法来源：<a href="https://mustache.github.io/" target="_blank">Mustache</a> 模板语言，
              被 Vue.js、Angular、LangChain 等广泛采用
            </p>
          </section>

          <section class="help-section">
            <h3>🔧 模板变量（运行参数）</h3>
            <p>在右侧"变量"面板定义的变量，运行时需要用户填写：</p>
            <ul>
              <li><strong>变量名</strong>：英文标识，如 <code>targetRoom</code></li>
              <li><strong>类型</strong>：string / number / boolean</li>
              <li><strong>必填</strong>：运行时必须提供值</li>
              <li><strong>默认值</strong>：未提供时使用的值</li>
            </ul>
          </section>

          <section class="help-section">
            <h3>🧩 节点类型说明</h3>
            <table class="help-table">
              <tr>
                <td><span class="node-badge start">开始</span></td>
                <td>流程入口，每个流程必须有一个</td>
              </tr>
              <tr>
                <td><span class="node-badge llm">AI</span></td>
                <td>调用 AI 模型，支持变量插值</td>
              </tr>
              <tr>
                <td><span class="node-badge condition">条件</span></td>
                <td>分支判断，支持真/假两个分支</td>
              </tr>
              <tr>
                <td><span class="node-badge variable">变量</span></td>
                <td>设置或修改变量值</td>
              </tr>
              <tr>
                <td><span class="node-badge wait">等待</span></td>
                <td>延时等待指定秒数</td>
              </tr>
              <tr>
                <td><span class="node-badge end">结束</span></td>
                <td>流程出口，可指定输出变量</td>
              </tr>
            </table>
          </section>

          <section class="help-section">
            <h3>💡 使用示例</h3>
            <p><strong>场景：生成日报并发送</strong></p>
            <ol>
              <li>定义模板变量：<code>targetRoom</code>（目标群）、<code>date</code>（日期）</li>
              <li v-pre>添加 AI 节点，提示词：<code>请总结 {{ targetRoom }} 群 {{ date }} 的消息</code></li>
              <li>设置输出变量为 <code>report</code></li>
              <li>添加结束节点，输出变量填 <code>report</code></li>
            </ol>
          </section>

          <section class="help-section">
            <h3>⌨️ 快捷键</h3>
            <ul>
              <li><kbd>Delete</kbd> / <kbd>Backspace</kbd>：删除选中节点/连线</li>
              <li><kbd>右键</kbd> / <kbd>长按</kbd>：打开节点菜单</li>
              <li><kbd>拖拽</kbd>：移动节点</li>
              <li><kbd>滚轮</kbd>：缩放画布</li>
            </ul>
          </section>
        </div>
      </div>
    </div>
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
const showHelpDialog = ref(false)
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

/* 帮助对话框 */
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

.help-dialog {
  background: white;
  border-radius: 12px;
  width: 90%;
  max-width: 600px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
}

.help-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #e0e0e0;
}

.help-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.btn-close {
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 18px;
  color: #6b7280;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn-close:hover {
  background: #f3f4f6;
}

.help-content {
  padding: 20px;
  overflow-y: auto;
  max-height: calc(80vh - 65px);
}

.help-section {
  margin-bottom: 24px;
}

.help-section:last-child {
  margin-bottom: 0;
}

.help-section h3 {
  margin: 0 0 12px 0;
  font-size: 15px;
  font-weight: 600;
  color: #111827;
}

.help-section p {
  margin: 0 0 12px 0;
  font-size: 14px;
  line-height: 1.6;
  color: #4b5563;
}

.help-section ul,
.help-section ol {
  margin: 0 0 12px 0;
  padding-left: 20px;
}

.help-section li {
  font-size: 14px;
  line-height: 1.8;
  color: #4b5563;
}

.code-block {
  background: #f3f4f6;
  padding: 12px 16px;
  border-radius: 8px;
  font-family: monospace;
  font-size: 13px;
  color: #1f2937;
  margin: 8px 0;
}

.help-section code {
  background: #f3f4f6;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: monospace;
  font-size: 13px;
  color: #dc2626;
}

.help-section .note {
  font-size: 13px;
  color: #6b7280;
  background: #f9fafb;
  padding: 12px;
  border-radius: 8px;
  border-left: 3px solid #4f46e5;
}

.help-section .note a {
  color: #4f46e5;
  text-decoration: none;
}

.help-section .note a:hover {
  text-decoration: underline;
}

.help-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.help-table td {
  padding: 10px 0;
  border-bottom: 1px solid #f3f4f6;
  vertical-align: middle;
}

.help-table td:first-child {
  width: 80px;
}

.help-table tr:last-child td {
  border-bottom: none;
}

.node-badge {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
}

.node-badge.start {
  background: #ecfdf5;
  color: #059669;
}

.node-badge.llm {
  background: #eef2ff;
  color: #4f46e5;
}

.node-badge.condition {
  background: #fffbeb;
  color: #d97706;
}

.node-badge.variable {
  background: #f5f3ff;
  color: #7c3aed;
}

.node-badge.wait {
  background: #f9fafb;
  color: #4b5563;
}

.node-badge.end {
  background: #fef2f2;
  color: #dc2626;
}

kbd {
  display: inline-block;
  padding: 2px 8px;
  background: #f3f4f6;
  border: 1px solid #d0d0d0;
  border-bottom-width: 2px;
  border-radius: 4px;
  font-family: monospace;
  font-size: 12px;
  color: #374151;
}
</style>
