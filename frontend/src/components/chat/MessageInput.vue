<template>
  <div class="input-area">
    <!-- 附件预览 -->
    <div v-if="attachments.length > 0" class="attachments-preview">
      <div v-for="(file, index) in attachments" :key="index" class="attachment-item">
        <img v-if="isImageFile(file)" :src="file.previewUrl" class="attachment-preview-img" />
        <div v-else class="attachment-file">
          <span class="file-icon">📎</span>
          <span class="file-name">{{ file.originalName }}</span>
        </div>
        <button class="remove-attachment" @click="removeAttachment(index)">×</button>
      </div>
    </div>
    
    <div class="input-wrapper">
      <input
        type="file"
        ref="fileInputRef"
        @change="handleFileSelect"
        accept="*/*"
        style="display: none"
      />
      <button 
        class="attach-btn" 
        @click="fileInputRef?.click()"
        :disabled="isUploading"
        title="上传附件"
      >
        📎
      </button>
      
      <textarea
        v-model="inputMessage"
        @keydown="handleKeydown"
        @input="handleInput"
        @paste="handlePaste"
        :placeholder="isUploading ? '上传中...' : '输入消息... 使用 @ 提及他人 (Enter发送, Ctrl+Enter换行)'"
        rows="1"
        ref="inputRef"
        :disabled="isUploading"
      />
      
      <VoiceInput @send="handleVoiceSend" />
      
      <div class="send-section">
        <button
          @click="sendMessage"
          :disabled="(!inputMessage.trim() && attachments.length === 0) || !isConnected || isUploading"
        >
          {{ isUploading ? '上传中...' : '发送' }}
        </button>
        <span v-if="sendDisabledReason" class="send-disabled-hint">{{ sendDisabledReason }}</span>
      </div>
    </div>
    
    <!-- @提及下拉列表 -->
    <div v-if="showMentionList" class="mention-list" ref="mentionListRef">
      <div class="mention-list-header">
        <span v-if="mentionQuery">搜索 "{{ mentionQuery }}"</span>
        <span v-else>选择要@的人</span>
      </div>
      
      <!-- 快捷选项 -->
      <div class="mention-shortcuts">
        <div 
          class="mention-item shortcut" 
          :class="{ active: mentionSelectedIndex === 0 }"
          @click="insertMentionOpenClaw"
          @mouseenter="mentionSelectedIndex = 0"
        >
          <span class="shortcut-icon">🤖</span>
          <span>@openclaw</span>
        </div>
        <div 
          class="mention-item shortcut" 
          :class="{ active: mentionSelectedIndex === 1 }"
          @click="insertMentionAll"
          @mouseenter="mentionSelectedIndex = 1"
        >
          <span class="shortcut-icon">👥</span>
          <span>@所有人</span>
        </div>
        <div 
          class="mention-item shortcut" 
          :class="{ active: mentionSelectedIndex === 2 }"
          @click="insertMentionHere"
          @mouseenter="mentionSelectedIndex = 2"
        >
          <span class="shortcut-icon">🟢</span>
          <span>@在线</span>
        </div>
      </div>
      
      <!-- 分隔线 -->
      <div v-if="roomMembers.length > 0 || mentionQuery" class="mention-divider"></div>
      
      <!-- 加载中状态 -->
      <div v-if="roomMembers.length === 0 && !mentionQuery" class="mention-loading">
        正在加载成员列表...
      </div>
      
      <!-- 用户列表 -->
      <div
        v-for="(user, index) in filteredMentionUsers"
        :key="user.id"
        :class="['mention-item', { active: index + 3 === mentionSelectedIndex }]"
        @click="insertMention(user)"
        @mouseenter="mentionSelectedIndex = index + 3"
      >
        <img v-if="user.avatar" :src="user.avatar" class="mention-avatar" />
        <div v-else class="mention-avatar-placeholder">{{ getInitials(user.nickname || user.username) }}</div>
        
        <div class="mention-info">
          <div class="mention-name">{{ user.nickname || user.username }}</div>
          <div class="mention-username">@{{ user.username }}</div>
        </div>
        <span v-if="user.id === currentUserId" class="mention-self">自己</span>
      </div>
      
      <div v-if="filteredMentionUsers.length === 0 && mentionQuery" class="mention-empty">
        未找到用户
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick } from 'vue'
import VoiceInput from '@/components/VoiceInput.vue'
import { fileApi } from '@/api/file'
import { getBaseUrl } from '@/utils/config'
import type { MemberDto, FileUploadResponse } from '@/types'

const props = defineProps<{
  roomMembers: MemberDto[]
  currentUserId?: string
  isConnected: boolean
}>()

const emit = defineEmits<{
  'send-message': [content: string, attachments: Array<{ id: string; dataUrl: string; mimeType: string }>]
  'typing': []
}>()

// 输入相关
const inputMessage = ref('')
const inputRef = ref<HTMLTextAreaElement>()

// 文件上传相关
const fileInputRef = ref<HTMLInputElement>()
const attachments = ref<Array<FileUploadResponse & { previewUrl?: string }>>([])
const isUploading = ref(false)

// @提及相关状态
const showMentionList = ref(false)
const mentionQuery = ref('')
const mentionSelectedIndex = ref(0)
const mentionStartIndex = ref(-1)
const mentionListRef = ref<HTMLDivElement>()

// 计算不能发送的原因
const sendDisabledReason = computed(() => {
  if (isUploading.value) {
    return '文件上传中，请稍候...'
  }
  if (!props.isConnected) {
    return '未连接到服务器，请检查网络'
  }
  if (!inputMessage.value.trim() && attachments.value.length === 0) {
    return '请输入消息或上传附件'
  }
  return ''
})

// 所有可选项（快捷选项 + 用户）用于键盘导航
type MentionOption =
  | { type: 'shortcut'; key: 'openclaw' | 'all' | 'here'; label: string; icon: string }
  | { type: 'user'; user: MemberDto }

const allMentionOptions = computed<MentionOption[]>(() => {
  const options: MentionOption[] = [
    { type: 'shortcut', key: 'openclaw', label: '@openclaw', icon: '🤖' },
    { type: 'shortcut', key: 'all', label: '@所有人', icon: '👥' },
    { type: 'shortcut', key: 'here', label: '@在线', icon: '🟢' }
  ]
  filteredMentionUsers.value.forEach(user => {
    options.push({ type: 'user', user })
  })
  return options
})

// 过滤后的用户列表
const filteredMentionUsers = computed(() => {
  const members = props.roomMembers || []
  if (!mentionQuery.value) {
    return [...members].sort((a, b) => {
      if (a.id === props.currentUserId) return 1
      if (b.id === props.currentUserId) return -1
      return 0
    })
  }
  const query = mentionQuery.value.toLowerCase()
  return members.filter(user => {
    const nickname = (user.nickname || user.username || '').toLowerCase()
    const username = (user.username || '').toLowerCase()
    return nickname.includes(query) || username.includes(query)
  })
})

// ============ 消息发送 ============

async function sendMessage() {
  const content = inputMessage.value.trim()
  if ((!content && attachments.value.length === 0) || !props.isConnected) return

  const chatAttachments = attachments.value.map(att => ({
    id: att.filename,
    dataUrl: att.previewUrl || att.url,
    mimeType: att.contentType || 'application/octet-stream'
  }))

  try {
    emit('send-message', content, chatAttachments)
    inputMessage.value = ''
    attachments.value = []
    showMentionList.value = false
    adjustTextareaHeight()
  } catch (error) {
    console.error('Failed to send message:', error)
    // 发送失败时不清空输入，让用户可以重试
    // 显示错误提示（使用 alert 或 toast）
    alert('消息发送失败，请检查网络连接后重试')
  }
}

// 处理语音输入发送
function handleVoiceSend(text: string) {
  if (!text.trim() || !props.isConnected) return
  emit('send-message', text.trim(), [])
  showMentionList.value = false
}

// ============ 文件处理 ============

function resolveFileUrl(url: string): string {
  if (url.startsWith('http://') || url.startsWith('https://')) {
    return url
  }
  const baseUrl = getBaseUrl()
  return baseUrl + url
}

function isImageFile(file: FileUploadResponse): boolean {
  const typeStr = (file.type || '').toUpperCase()
  if (typeStr === 'IMAGE') return true

  const contentTypeStr = (file.contentType || '').toLowerCase()
  if (contentTypeStr.startsWith('image/')) return true

  const filename = (file.originalName || file.filename || '').toLowerCase()
  if (/\.(png|jpg|jpeg|gif|webp|bmp|svg|ico)$/i.test(filename)) return true

  const urlStr = (file.url || '').toLowerCase()
  if (urlStr.startsWith('data:image/')) return true
  if (/\.(png|jpg|jpeg|gif|webp|bmp|svg|ico)$/i.test(urlStr)) return true

  return false
}

async function handleFileSelect(event: Event) {
  const target = event.target as HTMLInputElement
  const files = target.files
  if (!files || files.length === 0) return

  isUploading.value = true

  try {
    for (const file of Array.from(files)) {
      const response = await fileApi.upload(file)
      const previewUrl = resolveFileUrl(response.data.url)

      attachments.value.push({
        ...response.data,
        previewUrl
      })
    }
  } catch (err: any) {
    console.error('File upload failed:', err)
    alert('文件上传失败: ' + (err.response?.data?.message || err.message))
  } finally {
    isUploading.value = false
    if (fileInputRef.value) {
      fileInputRef.value.value = ''
    }
  }
}

async function handlePaste(event: ClipboardEvent) {
  const clipboardData = event.clipboardData
  if (!clipboardData) return

  const files: File[] = []

  if (clipboardData.files && clipboardData.files.length > 0) {
    for (let i = 0; i < clipboardData.files.length; i++) {
      files.push(clipboardData.files[i])
    }
  }

  if (files.length === 0 && clipboardData.items) {
    for (let i = 0; i < clipboardData.items.length; i++) {
      const item = clipboardData.items[i]
      if (item.kind === 'file') {
        const file = item.getAsFile()
        if (file) {
          files.push(file)
        }
      }
    }
  }

  if (files.length === 0) return

  event.preventDefault()
  isUploading.value = true

  try {
    for (const file of files) {
      const response = await fileApi.upload(file)
      const previewUrl = resolveFileUrl(response.data.url)
      attachments.value.push({
        ...response.data,
        previewUrl
      })
    }
  } catch (err: any) {
    console.error('Paste upload failed:', err)
    alert('文件上传失败: ' + (err.response?.data?.message || err.message))
  } finally {
    isUploading.value = false
  }
}

function removeAttachment(index: number) {
  const attachment = attachments.value[index]
  if (attachment.previewUrl) {
    URL.revokeObjectURL(attachment.previewUrl)
  }
  attachments.value.splice(index, 1)
}

// ============ @提及处理 ============

function handleKeydown(event: KeyboardEvent) {
  if (showMentionList.value) {
    const options = allMentionOptions.value
    
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault()
        mentionSelectedIndex.value = (mentionSelectedIndex.value + 1) % options.length
        scrollMentionIntoView()
        return
      case 'ArrowUp':
        event.preventDefault()
        mentionSelectedIndex.value = (mentionSelectedIndex.value - 1 + options.length) % options.length
        scrollMentionIntoView()
        return
      case 'Enter':
        event.preventDefault()
        const selectedOption = options[mentionSelectedIndex.value]
        if (selectedOption) {
          if (selectedOption.type === 'shortcut') {
            if (selectedOption.key === 'openclaw') {
              insertMentionOpenClaw()
            } else if (selectedOption.key === 'all') {
              insertMentionAll()
            } else {
              insertMentionHere()
            }
          } else {
            insertMention(selectedOption.user)
          }
        }
        return
      case 'Escape':
        showMentionList.value = false
        return
    }
  }
  
  if (event.key === 'Enter') {
    if (event.ctrlKey || event.metaKey) {
      event.preventDefault()
      const target = event.target as HTMLTextAreaElement
      const start = target.selectionStart
      const end = target.selectionEnd
      const value = target.value
      inputMessage.value = value.substring(0, start) + '\n' + value.substring(end)
      nextTick(() => {
        target.selectionStart = target.selectionEnd = start + 1
      })
    } else {
      event.preventDefault()
      sendMessage()
    }
  }
}

function scrollMentionIntoView() {
  nextTick(() => {
    const list = mentionListRef.value
    if (!list) return
    const items = list.querySelectorAll('.mention-item')
    const activeItem = items[mentionSelectedIndex.value] as HTMLElement
    if (activeItem) {
      activeItem.scrollIntoView({ block: 'nearest' })
    }
  })
}

function handleInput() {
  adjustTextareaHeight()
  
  emit('typing')
  
  const text = inputMessage.value
  const cursorPos = inputRef.value?.selectionStart || 0
  
  const textBeforeCursor = text.slice(0, cursorPos)
  const lastAtIndex = textBeforeCursor.lastIndexOf('@')
  
  if (lastAtIndex >= 0) {
    const textBetween = textBeforeCursor.slice(lastAtIndex + 1)
    if (!textBetween.includes(' ')) {
      mentionStartIndex.value = lastAtIndex
      mentionQuery.value = textBetween
      showMentionList.value = true
      mentionSelectedIndex.value = 0
      return
    }
  }
  
  showMentionList.value = false
}

function insertMention(user: MemberDto) {
  const beforeMention = inputMessage.value.slice(0, mentionStartIndex.value)
  const afterCursor = inputMessage.value.slice(inputRef.value?.selectionStart || 0)
  inputMessage.value = beforeMention + '@' + (user.nickname || user.username) + ' ' + afterCursor
  showMentionList.value = false
  inputRef.value?.focus()
  
  nextTick(() => {
    const newPos = mentionStartIndex.value + (user.nickname || user.username).length + 2
    inputRef.value?.setSelectionRange(newPos, newPos)
  })
}

function insertMentionOpenClaw() {
  const beforeMention = inputMessage.value.slice(0, mentionStartIndex.value)
  const afterCursor = inputMessage.value.slice(inputRef.value?.selectionStart || 0)
  inputMessage.value = beforeMention + '@openclaw ' + afterCursor
  showMentionList.value = false
  inputRef.value?.focus()
}

function insertMentionAll() {
  const beforeMention = inputMessage.value.slice(0, mentionStartIndex.value)
  const afterCursor = inputMessage.value.slice(inputRef.value?.selectionStart || 0)
  inputMessage.value = beforeMention + '@所有人 ' + afterCursor
  showMentionList.value = false
  inputRef.value?.focus()
}

function insertMentionHere() {
  const beforeMention = inputMessage.value.slice(0, mentionStartIndex.value)
  const afterCursor = inputMessage.value.slice(inputRef.value?.selectionStart || 0)
  inputMessage.value = beforeMention + '@在线 ' + afterCursor
  showMentionList.value = false
  inputRef.value?.focus()
}

function adjustTextareaHeight() {
  if (inputRef.value) {
    inputRef.value.style.height = 'auto'
    inputRef.value.style.height = Math.min(inputRef.value.scrollHeight, 200) + 'px'
  }
}

function getInitials(name: string): string {
  return name.slice(0, 2).toUpperCase()
}

// 暴露方法给父组件
defineExpose({
  focus: () => inputRef.value?.focus()
})
</script>

<style scoped>
.input-area {
  border-top: 1px solid var(--border-color);
  padding: 1rem;
  flex-shrink: 0;
  position: relative;
}

/* 附件预览 */
.attachments-preview {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
  padding: 0.5rem;
  background: var(--bg-color);
  border-radius: 8px;
}

.attachment-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.25rem 0.5rem;
  background: var(--surface-color);
  border-radius: 6px;
  border: 1px solid var(--border-color);
}

.attachment-preview-img {
  width: 48px;
  height: 48px;
  object-fit: cover;
  border-radius: 4px;
}

.attachment-file {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.file-icon {
  font-size: 1.25rem;
}

.file-name {
  font-size: 0.75rem;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-secondary);
}

.remove-attachment {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: none;
  background: var(--border-color);
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 0.875rem;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-left: 0.25rem;
}

.remove-attachment:hover {
  background: #ef4444;
  color: white;
}

.attach-btn {
  width: 44px;
  height: 44px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--surface-color);
  cursor: pointer;
  font-size: 1.25rem;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.attach-btn:hover:not(:disabled) {
  background: var(--bg-color);
  border-color: var(--primary-color);
}

.attach-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.input-wrapper {
  display: flex;
  gap: 0.5rem;
  align-items: flex-end;
}

textarea {
  flex: 1;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  resize: none;
  font-size: 1rem;
  font-family: inherit;
  min-height: 44px;
  max-height: 200px;
  overflow-y: auto;
  line-height: 1.5;
}

textarea:disabled {
  background: var(--bg-color);
  cursor: not-allowed;
}

textarea:focus {
  outline: none;
  border-color: var(--primary-color);
}

.input-wrapper button {
  padding: 0.75rem 1.5rem;
  background: var(--primary-color);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 0.875rem;
  cursor: pointer;
  transition: background 0.2s;
  height: 44px;
}

.input-wrapper button:hover:not(:disabled) {
  background: var(--primary-hover);
}

.input-wrapper button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 发送区域 */
.send-section {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 0.25rem;
}

.send-disabled-hint {
  font-size: 0.75rem;
  color: var(--text-secondary);
  max-width: 120px;
  text-align: right;
  line-height: 1.3;
}

/* @提及下拉列表 */
.mention-list {
  position: absolute;
  bottom: 100%;
  left: 1rem;
  right: 1rem;
  max-height: 300px;
  background: var(--surface-color);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  box-shadow: 0 -4px 20px rgba(0,0,0,0.15);
  margin-bottom: 0.5rem;
  overflow-y: auto;
  z-index: 100;
}

.mention-list-header {
  padding: 0.75rem 1rem;
  font-size: 0.75rem;
  color: var(--text-secondary);
  border-bottom: 1px solid var(--border-color);
  background: rgba(0,0,0,0.02);
}

.mention-shortcuts {
  background: rgba(59, 130, 246, 0.05);
}

.mention-divider {
  height: 1px;
  background: var(--border-color);
  margin: 0;
}

.mention-item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  cursor: pointer;
  transition: background 0.15s;
  border-bottom: 1px solid var(--border-color);
}

.mention-item:last-child {
  border-bottom: none;
}

.mention-item:hover,
.mention-item.active {
  background: var(--bg-color);
}

.mention-item.shortcut {
  padding: 0.5rem 1rem;
}

.shortcut-icon {
  font-size: 1rem;
}

.mention-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  object-fit: cover;
}

.mention-avatar-placeholder {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--primary-color);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.75rem;
  font-weight: 600;
}

.mention-info {
  flex: 1;
}

.mention-name {
  font-weight: 500;
  font-size: 0.875rem;
}

.mention-username {
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.mention-self {
  font-size: 0.625rem;
  color: var(--text-secondary);
  background: var(--bg-color);
  padding: 2px 6px;
  border-radius: 4px;
}

.mention-empty {
  padding: 1rem;
  text-align: center;
  color: var(--text-secondary);
  font-size: 0.875rem;
}

.mention-loading {
  padding: 1rem;
  text-align: center;
  color: var(--text-secondary);
  font-size: 0.875rem;
  font-style: italic;
}

/* ============================================
   移动端适配 - Mobile Responsive Styles
   ============================================ */

@media (max-width: 768px) {
  .input-area {
    padding: 0.625rem 0.75rem;
    padding-bottom: calc(0.625rem + env(safe-area-inset-bottom, 8px));
    border-top: 1px solid var(--border-color);
    background: var(--surface-color);
  }

  .attachments-preview {
    padding: 0.375rem;
    margin-bottom: 0.5rem;
    max-height: 80px;
  }

  .attachment-item {
    padding: 0.25rem 0.375rem;
  }

  .attachment-preview-img {
    width: 40px;
    height: 40px;
  }

  .file-name {
    max-width: 100px;
    font-size: 0.6875rem;
  }

  .remove-attachment {
    width: 18px;
    height: 18px;
    font-size: 0.75rem;
  }

  .input-wrapper {
    gap: 0.375rem;
  }

  .attach-btn {
    width: 40px;
    height: 40px;
    font-size: 1.125rem;
  }

  textarea {
    padding: 0.625rem 0.75rem;
    font-size: 16px;
    min-height: 40px;
    max-height: 100px;
    border-radius: 10px;
  }

  .input-wrapper button {
    padding: 0.625rem 1rem;
    font-size: 0.8125rem;
    height: 40px;
    border-radius: 10px;
  }

  .send-disabled-hint {
    font-size: 0.6875rem;
    max-width: 100px;
  }

  .mention-list {
    left: 0.5rem;
    right: 0.5rem;
    max-height: 240px;
    border-radius: 10px;
  }

  .mention-list-header {
    padding: 0.625rem 0.875rem;
    font-size: 0.6875rem;
  }

  .mention-item {
    padding: 0.625rem 0.875rem;
  }

  .mention-avatar,
  .mention-avatar-placeholder {
    width: 28px;
    height: 28px;
  }

  .mention-avatar-placeholder {
    font-size: 0.6875rem;
  }

  .mention-name {
    font-size: 0.8125rem;
  }

  .mention-username {
    font-size: 0.6875rem;
  }

  .mention-self {
    font-size: 0.5625rem;
    padding: 1px 4px;
  }

  .shortcut-icon {
    font-size: 0.875rem;
  }
}

/* 小屏手机额外优化 */
@media (max-width: 380px) {
  .attach-btn {
    width: 36px;
    height: 36px;
    font-size: 1rem;
  }

  textarea {
    padding: 0.5rem 0.625rem;
  }

  .input-wrapper button {
    padding: 0.5rem 0.875rem;
    height: 36px;
  }

  .send-disabled-hint {
    display: none;
  }
}

/* 横屏模式优化 */
@media (max-height: 500px) and (orientation: landscape) {
  .input-area {
    padding: 0.5rem 0.75rem;
  }
}
</style>
