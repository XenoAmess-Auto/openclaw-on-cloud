<template>
  <div
    class="message-item"
    :class="{
      'message-self': isSelf,
      'message-other': !isSelf,
      'message-bot': isBot,
      'message-system': message.isSystem,
      'message-streaming': message.isStreaming,
      'message-tool-call': message.isToolCall
    }"
    :data-message-id="message.id"
  >
    <!-- 系统消息 -->
    <template v-if="message.isSystem">
      <div class="system-message">
        <span class="system-icon">🔔</span>
        <span class="system-content" v-html="renderedContent"></span>
      </div>
    </template>

    <!-- 普通消息 -->
    <template v-else>
      <div class="message-avatar">
        <img
          v-if="message.senderAvatar"
          :src="message.senderAvatar"
          :alt="message.senderName"
          @error="onAvatarError"
        />
        <div v-else class="avatar-fallback">
          {{ getInitials(message.senderName) }}
        </div>
        <span v-if="isBot" class="bot-indicator">🤖</span>
      </div>

      <div class="message-body">
        <div class="message-header">
          <span class="sender-name">{{ message.senderName }}</span>
          <span class="message-time" :title="fullTime">{{ formattedTime }}</span>
          <span v-if="message.isStreaming" class="streaming-indicator">
            <span class="dot"></span>
            <span class="dot"></span>
            <span class="dot"></span>
          </span>
        </div>

        <!-- 工具调用展示 -->
        <div v-if="message.isToolCall && message.toolCalls?.length" class="tool-calls">
          <div class="tool-call-header">
            <span class="tool-icon">🔧</span>
            <span>使用了 {{ message.toolCalls.length }} 个工具</span>
          </div>
          <div class="tool-list">
            <div
              v-for="tool in message.toolCalls"
              :key="tool.id"
              class="tool-item"
              :class="tool.status"
            >
              <span class="tool-name">{{ tool.name }}</span>
              <span v-if="tool.description" class="tool-desc">{{ tool.description }}</span>
              <span class="tool-status" :class="tool.status">
                {{ getToolStatusText(tool.status) }}
              </span>
            </div>
          </div>
        </div>

        <!-- 消息内容 -->
        <div class="message-content" v-html="renderedContent"></div>

        <!-- 附件 -->
        <div v-if="hasAttachments" class="message-attachments">
          <div
            v-for="(attachment, index) in message.attachments"
            :key="index"
            class="attachment-item"
            :class="getAttachmentClass(attachment)"
          >
            <!-- 图片附件 -->
            <template v-if="isImage(attachment)">
              <div class="image-attachment">
                <img
                  :src="attachment.url"
                  :alt="attachment.name"
                  @click="previewImage(attachment.url!)"
                  @load="onImageLoad"
                  @error="onImageError"
                />
                <div class="image-overlay">
                  <span class="image-name">{{ attachment.name }}</span>
                  <span v-if="attachment.size" class="image-size">{{ formatFileSize(attachment.size) }}</span>
                </div>
              </div>
            </template>

            <!-- 文件附件 -->
            <template v-else>
              <a
                :href="attachment.url"
                target="_blank"
                class="file-attachment"
                download
              >
                <span class="file-icon">{{ getFileIcon(attachment) }}</span>
                <div class="file-info">
                  <span class="file-name">{{ attachment.name }}</span>
                  <span v-if="attachment.size" class="file-size">{{ formatFileSize(attachment.size) }}</span>
                </div>                <span class="file-action">⬇️</span>
              </a>
            </template>
          </div>
        </div>

        <!-- 消息操作 -->
        <div class="message-actions">
          <button
            v-if="canCopy"
            class="action-btn"
            @click="copyContent"
            title="复制"
          >
            📋
          </button>
          <button
            v-if="canReply"
            class="action-btn"
            @click="reply"
            title="回复"
          >
            ↩️
          </button>
          <button
            v-if="canDelete"
            class="action-btn delete"
            @click="deleteMessage"
            title="删除"
          >
            🗑️
          </button>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

interface Attachment {
  name?: string
  url?: string
  type?: string
  contentType?: string
  size?: number
}

interface ToolCall {
  id: string
  name: string
  description?: string
  status: 'pending' | 'running' | 'completed' | 'failed'
  timestamp?: string
}

interface Message {
  id: string
  senderId: string
  senderName: string
  senderAvatar?: string
  content: string
  timestamp: string
  isSystem?: boolean
  isStreaming?: boolean
  isToolCall?: boolean
  fromOpenClaw?: boolean
  toolCalls?: ToolCall[]
  attachments?: Attachment[]
}

const props = defineProps<{
  message: Message
  currentUserId?: string
  isCreator?: boolean
}>()

const emit = defineEmits<{
  reply: [message: Message]
  delete: [messageId: string]
  imagePreview: [url: string]
}>()

const isSelf = computed(() => props.message.senderId === props.currentUserId)
const isBot = computed(() => props.message.fromOpenClaw || props.message.senderId === 'openclaw')
const hasAttachments = computed(() => 
  props.message.attachments && props.message.attachments.length > 0
)

const canCopy = computed(() => !props.message.isSystem && props.message.content)
const canReply = computed(() => !props.message.isSystem)
const canDelete = computed(() => 
  props.isCreator || props.message.senderId === props.currentUserId
)

const formattedTime = computed(() => {
  const date = new Date(props.message.timestamp)
  const now = new Date()
  const isToday = date.toDateString() === now.toDateString()
  
  if (isToday) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
})

const fullTime = computed(() => {
  return new Date(props.message.timestamp).toLocaleString('zh-CN')
})

const renderedContent = computed(() => {
  if (!props.message.content) return ''
  
  // 系统消息直接返回
  if (props.message.isSystem) {
    return DOMPurify.sanitize(props.message.content)
  }
  
  // Markdown 渲染
  const html = marked(props.message.content, {
    breaks: true,
    gfm: true
  })
  return DOMPurify.sanitize(html as string)
})

function getInitials(name: string): string {
  if (!name) return '?'
  return name.charAt(0).toUpperCase()
}

function onAvatarError(event: Event) {
  const img = event.target as HTMLImageElement
  img.style.display = 'none'
  const parent = img.parentElement
  if (parent) {
    const fallback = document.createElement('div')
    fallback.className = 'avatar-fallback'
    fallback.textContent = getInitials(props.message.senderName)
    parent.appendChild(fallback)
  }
}

function getToolStatusText(status?: string): string {
  const statusMap: Record<string, string> = {
    pending: '等待中',
    running: '执行中',
    completed: '已完成',
    failed: '失败'
  }
  return statusMap[status || ''] || status || '未知'
}

function isImage(attachment: Attachment): boolean {
  if (attachment.type === 'IMAGE') return true
  if (attachment.contentType?.startsWith('image/')) return true
  if (attachment.url?.match(/\.(jpg|jpeg|png|gif|webp|svg)$/i)) return true
  return false
}

function getAttachmentClass(attachment: Attachment): string {
  if (isImage(attachment)) return 'attachment-image'
  return 'attachment-file'
}

function getFileIcon(attachment: Attachment): string {
  const ext = attachment.name?.split('.').pop()?.toLowerCase()
  const iconMap: Record<string, string> = {
    pdf: '📄',
    doc: '📝',
    docx: '📝',
    xls: '📊',
    xlsx: '📊',
    ppt: '📽️',
    pptx: '📽️',
    zip: '📦',
    rar: '📦',
    tar: '📦',
    gz: '📦',
    txt: '📃',
    json: '⚙️',
    js: '⚙️',
    ts: '⚙️',
    java: '☕',
    py: '🐍',
    mp3: '🎵',
    mp4: '🎬',
    mov: '🎬'
  }
  return iconMap[ext || ''] || '📎'
}

function formatFileSize(bytes?: number): string {
  if (!bytes) return ''
  const units = ['B', 'KB', 'MB', 'GB']
  let size = bytes
  let unitIndex = 0
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024
    unitIndex++
  }
  return `${size.toFixed(1)} ${units[unitIndex]}`
}

function previewImage(url: string) {
  emit('imagePreview', url)
}

function onImageLoad() {
  // 图片加载完成，可以通知父组件滚动
}

function onImageError(event: Event) {
  const img = event.target as HTMLImageElement
  img.src = '/placeholder-image.png'
}

function copyContent() {
  navigator.clipboard.writeText(props.message.content)
    .then(() => {
      // 可以显示 toast 提示
      console.log('已复制到剪贴板')
    })
    .catch(err => {
      console.error('复制失败:', err)
    })
}

function reply() {
  emit('reply', props.message)
}

function deleteMessage() {
  if (!confirm('确定要删除这条消息吗？')) return
  emit('delete', props.message.id)
}
</script>

<style scoped>
.message-item {
  display: flex;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  animation: messageAppear 0.3s ease;
}

@keyframes messageAppear {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.message-self {
  flex-direction: row-reverse;
}

.message-self .message-body {
  align-items: flex-end;
}

.message-self .message-content {
  background: var(--primary-color);
  color: white;
  border-bottom-right-radius: 4px;
}

.message-self .message-content :deep(a) {
  color: rgba(255, 255, 255, 0.9);
}

.message-self .message-content :deep(code) {
  background: rgba(0, 0, 0, 0.2);
  color: #fff;
}

.message-bot .message-content {
  background: linear-gradient(135deg, #f0f9ff 0%, #e0f2fe 100%);
  border-left: 3px solid #0ea5e9;
}

.message-streaming .message-content {
  opacity: 0.8;
}

.message-system {
  justify-content: center;
}

/* 头像 */
.message-avatar {
  position: relative;
  flex-shrink: 0;
}

.message-avatar img,
.avatar-fallback {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
  background: linear-gradient(135deg, var(--primary-color) 0%, #8b5cf6 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-weight: 600;
  font-size: 0.875rem;
}

.bot-indicator {
  position: absolute;
  bottom: -2px;
  right: -2px;
  font-size: 0.75rem;
  background: white;
  border-radius: 50%;
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

/* 消息体 */
.message-body {
  display: flex;
  flex-direction: column;
  max-width: 70%;
  min-width: 0;
}

.message-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.25rem;
  padding: 0 0.25rem;
}

.message-self .message-header {
  flex-direction: row-reverse;
}

.sender-name {
  font-weight: 600;
  font-size: 0.8125rem;
  color: var(--text-primary);
}

.message-time {
  font-size: 0.6875rem;
  color: var(--text-secondary);
}

.streaming-indicator {
  display: flex;
  gap: 2px;
}

.streaming-indicator .dot {
  width: 4px;
  height: 4px;
  background: var(--primary-color);
  border-radius: 50%;
  animation: bounce 1.4s infinite ease-in-out;
}

.streaming-indicator .dot:nth-child(1) { animation-delay: 0s; }
.streaming-indicator .dot:nth-child(2) { animation-delay: 0.2s; }
.streaming-indicator .dot:nth-child(3) { animation-delay: 0.4s; }

@keyframes bounce {
  0%, 80%, 100% { transform: scale(0); }
  40% { transform: scale(1); }
}

/* 工具调用 */
.tool-calls {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 0.75rem;
  margin-bottom: 0.5rem;
  font-size: 0.8125rem;
}

.tool-call-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-weight: 500;
  color: #475569;
  margin-bottom: 0.5rem;
}

.tool-list {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}

.tool-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.375rem 0.5rem;
  background: white;
  border-radius: 4px;
  border: 1px solid #e2e8f0;
}

.tool-name {
  font-family: monospace;
  font-size: 0.75rem;
  color: #0ea5e9;
  font-weight: 500;
}

.tool-desc {
  flex: 1;
  color: #64748b;
  font-size: 0.75rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tool-status {
  font-size: 0.625rem;
  padding: 0.125rem 0.375rem;
  border-radius: 4px;
  font-weight: 500;
}

.tool-status.completed {
  background: #dcfce7;
  color: #166534;
}

.tool-status.running {
  background: #dbeafe;
  color: #1e40af;
}

.tool-status.pending {
  background: #f3f4f6;
  color: #6b7280;
}

.tool-status.failed {
  background: #fee2e2;
  color: #991b1b;
}

/* 消息内容 */
.message-content {
  background: var(--bg-color);
  padding: 0.75rem 1rem;
  border-radius: 12px;
  border-bottom-left-radius: 4px;
  font-size: 0.9375rem;
  line-height: 1.6;
  color: var(--text-primary);
  word-wrap: break-word;
}

.message-content :deep(p) {
  margin: 0 0 0.5rem;
}

.message-content :deep(p:last-child) {
  margin-bottom: 0;
}

.message-content :deep(pre) {
  background: #1e293b;
  color: #e2e8f0;
  padding: 1rem;
  border-radius: 8px;
  overflow-x: auto;
  margin: 0.5rem 0;
}

.message-content :deep(code) {
  font-family: 'Fira Code', monospace;
  font-size: 0.875rem;
}

.message-content :deep(:not(pre) > code) {
  background: rgba(0, 0, 0, 0.05);
  padding: 0.125rem 0.375rem;
  border-radius: 4px;
  font-size: 0.875em;
}

.message-content :deep(a) {
  color: var(--primary-color);
  text-decoration: none;
}

.message-content :deep(a:hover) {
  text-decoration: underline;
}

.message-content :deep(ul),
.message-content :deep(ol) {
  margin: 0.5rem 0;
  padding-left: 1.5rem;
}

.message-content :deep(li) {
  margin: 0.25rem 0;
}

.message-content :deep(blockquote) {
  border-left: 3px solid var(--primary-color);
  margin: 0.5rem 0;
  padding-left: 1rem;
  color: var(--text-secondary);
}

/* 附件 */
.message-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-top: 0.5rem;
}

.attachment-item {
  max-width: 100%;
}

.image-attachment {
  position: relative;
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.2s ease;
}

.image-attachment:hover {
  transform: scale(1.02);
}

.image-attachment img {
  max-width: 300px;
  max-height: 200px;
  object-fit: cover;
  display: block;
}

.image-overlay {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  background: linear-gradient(transparent, rgba(0, 0, 0, 0.7));
  padding: 1.5rem 0.75rem 0.5rem;
  color: white;
  font-size: 0.75rem;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.file-attachment {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.625rem 0.875rem;
  background: var(--bg-color);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  text-decoration: none;
  color: var(--text-primary);
  transition: all 0.2s ease;
  min-width: 200px;
}

.file-attachment:hover {
  background: var(--surface-color);
  border-color: var(--primary-color);
}

.file-icon {
  font-size: 1.5rem;
}

.file-info {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.file-name {
  font-size: 0.8125rem;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.file-size {
  font-size: 0.6875rem;
  color: var(--text-secondary);
}

.file-action {
  opacity: 0;
  transition: opacity 0.2s ease;
}

.file-attachment:hover .file-action {
  opacity: 1;
}

/* 消息操作 */
.message-actions {
  display: flex;
  gap: 0.25rem;
  margin-top: 0.25rem;
  opacity: 0;
  transition: opacity 0.2s ease;
}

.message-item:hover .message-actions {
  opacity: 1;
}

.action-btn {
  padding: 0.25rem 0.5rem;
  border: none;
  background: transparent;
  border-radius: 4px;
  cursor: pointer;
  font-size: 0.875rem;
  opacity: 0.6;
  transition: all 0.2s ease;
}

.action-btn:hover {
  opacity: 1;
  background: var(--bg-color);
}

.action-btn.delete:hover {
  background: #fee2e2;
}

/* 系统消息 */
.system-message {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  background: #fef3c7;
  border-radius: 20px;
  font-size: 0.8125rem;
  color: #92400e;
}

.system-icon {
  font-size: 0.875rem;
}

.system-content :deep(a) {
  color: #92400e;
  text-decoration: underline;
}
</style>
