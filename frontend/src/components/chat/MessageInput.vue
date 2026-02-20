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

function sendMessage() {
  const content = inputMessage.value.trim()
  if ((!content && attachments.value.length === 0) || !props.isConnected) return

  const chatAttachments = attachments.value.map(att => ({
    id: att.filename,
    dataUrl: att.previewUrl || att.url,
    mimeType: att.contentType || 'application/octet-stream'
  }))

  emit('send-message', content, chatAttachments)
  inputMessage.value = ''
  attachments.value = []
  showMentionList.value = false
  adjustTextareaHeight()
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
