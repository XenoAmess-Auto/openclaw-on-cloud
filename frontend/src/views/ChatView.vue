<template>
  <div class="chat-view">
    <header class="header">
      <router-link to="/" class="back">←</router-link>
      <div class="room-info">
        <h1>{{ chatStore.currentRoom?.name || '聊天室' }}</h1>
        <span :class="['status', { connected: chatStore.isConnected }]">
          {{ chatStore.isConnected ? '已连接' : '连接中...' }}
        </span>
      </div>
      <div class="actions">
        <button class="mention-btn" @click="showMentions = true">
          @
          <span v-if="unreadMentionCount > 0" class="mention-badge">{{ unreadMentionCount }}</span>
        </button>
        <button @click="showMembers = true">成员</button>
        <button @click="showSessions = true">会话</button>
      </div>
    </header>
    
    <div class="message-container" ref="messageContainer">
      <div
        v-for="msg in chatStore.messages"
        :key="msg.id"
        :class="[
          'message', 
          { 
            'from-me': msg.senderId === authStore.user?.id, 
            'from-openclaw': msg.fromOpenClaw,
            'mentioned-me': isMentionedMe(msg)
          }
        ]"
      >
        <div class="message-header">
          <span class="sender">{{ msg.senderName }}</span>
          <span v-if="msg.mentionAll" class="mention-tag mention-all">@所有人</span>
          <span v-else-if="msg.mentionHere" class="mention-tag mention-here">@在线</span>
          <span class="time">{{ formatTime(msg.timestamp) }}</span>
        </div>
        <div class="message-content" v-html="renderContent(msg)"></div>
      </div>
      
      <div v-if="chatStore.messages.length === 0" class="empty">
        发送消息开始对话
        <br/>
        <span v-if="isPrivateChat">这是私聊，每条消息都会触发 OpenClaw</span>
        <span v-else>在群聊中使用 @openclaw 召唤 AI，使用 @昵称 @所有人 @在线</span>
      </div>
    </div>
    
    <div class="input-area" @paste="handlePaste">
      <!-- 附件预览区域 -->
      <div v-if="attachments.length > 0" class="attachments-preview">
        <div v-for="att in attachments" :key="att.id" class="attachment-item">
          <img :src="att.dataUrl" alt="附件预览" class="attachment-img" />
          <button class="attachment-remove" @click="removeAttachment(att.id)" title="移除图片">
            ×
          </button>
        </div>
      </div>
      
      <div class="input-wrapper">
        <div class="input-actions">
          <button 
            class="upload-btn" 
            @click="triggerFileUpload"
            title="上传图片"
            :disabled="!chatStore.isConnected"
          >
            📷
          </button>
          <input
            ref="fileInputRef"
            type="file"
            accept="image/*"
            multiple
            style="display: none"
            @change="handleFileSelect"
          />
        </div>
        <textarea
          v-model="inputMessage"
          @keydown="handleKeydown"
          @input="handleInput"
          @paste="handlePaste"
          placeholder="输入消息... 使用 @ 提及他人，粘贴或点击按钮添加图片"
          rows="1"
          ref="inputRef"
        />
        <button
          @click="sendMessage"
          :disabled="(!inputMessage.trim() && attachments.length === 0) || !chatStore.isConnected"
        >
          发送
        </button>
      </div>
      
      <!-- @提及下拉列表 -->
      <div v-if="showMentionList" class="mention-list" ref="mentionListRef">
        <div class="mention-list-header">
          <span v-if="mentionQuery">搜索 "{{ mentionQuery }}"</span>
          <span v-else>选择要@的人</span>
        </div>
        
        <!-- 快捷选项 - 始终显示 -->
        <div class="mention-shortcuts">
          <div 
            class="mention-item shortcut" 
            :class="{ active: mentionSelectedIndex === 0 }"
            @click="insertMentionAll"
            @mouseenter="mentionSelectedIndex = 0"
          >
            <span class="shortcut-icon">👥</span>
            <span>@所有人</span>
          </div>
          <div 
            class="mention-item shortcut" 
            :class="{ active: mentionSelectedIndex === 1 }"
            @click="insertMentionHere"
            @mouseenter="mentionSelectedIndex = 1"
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
          :class="['mention-item', { active: index + 2 === mentionSelectedIndex }]"
          @click="insertMention(user)"
          @mouseenter="mentionSelectedIndex = index + 2"
        >
          <img v-if="user.avatar" :src="user.avatar" class="mention-avatar" />
          <div v-else class="mention-avatar-placeholder">{{ getInitials(user.nickname || user.username) }}</div>
          <div class="mention-info">
            <div class="mention-name">{{ user.nickname || user.username }}</div>
            <div class="mention-username">@{{ user.username }}</div>
          </div>
          <span v-if="user.id === authStore.user?.id" class="mention-self">自己</span>
        </div>
        <div v-if="filteredMentionUsers.length === 0 && mentionQuery" class="mention-empty">
          未找到用户
        </div>
      </div>
    </div>

    <!-- @提及通知弹窗 -->
    <div v-if="showMentions" class="modal-overlay" @click="showMentions = false">
      <div class="modal mention-modal" @click.stop>
        <div class="modal-header">
          <h2>@我的消息</h2>
          <button class="close-btn" @click="showMentions = false">×</button>
        </div>
        <div class="modal-body">
          <div v-if="mentions.length === 0" class="empty-mentions">
            暂无@你的消息
          </div>
          <div v-else class="mention-list-container">
            <div
              v-for="mention in mentions"
              :key="mention.id"
              :class="['mention-record', { unread: !mention.isRead }]"
              @click="goToMention(mention)"
            >
              <div class="mention-record-header">
                <span class="mention-record-sender">{{ mention.mentionerUserName }}</span>
                <span class="mention-record-room">{{ mention.roomName }}</span>
                <span class="mention-record-time">{{ formatTime(mention.createdAt) }}</span>
              </div>
              <div class="mention-record-content">{{ mention.messageContent }}</div>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button @click="markAllMentionsAsRead" :disabled="unreadMentionCount === 0">
            全部标记已读
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useChatStore, type Attachment } from '@/stores/chat'
import { chatRoomApi } from '@/api/chatRoom'
import { mentionApi } from '@/api/mention'
import type { MemberDto, Message, MentionRecord } from '@/types'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const chatStore = useChatStore()

const roomId = computed(() => route.params.roomId as string)
const inputMessage = ref('')
const messageContainer = ref<HTMLDivElement>()
const inputRef = ref<HTMLTextAreaElement>()
const fileInputRef = ref<HTMLInputElement>()
const mentionListRef = ref<HTMLDivElement>()
const showMembers = ref(false)
const showSessions = ref(false)
const showMentions = ref(false)

// 附件相关状态
const attachments = ref<Attachment[]>([])

// @提及相关状态
const showMentionList = ref(false)
const mentionQuery = ref('')
const mentionSelectedIndex = ref(0)
const roomMembers = ref<MemberDto[]>([])
const mentionStartIndex = ref(-1)

// @提及通知
const mentions = ref<MentionRecord[]>([])
const unreadMentionCount = ref(0)

const isPrivateChat = computed(() => {
  return false
})

// 过滤后的用户列表
const filteredMentionUsers = computed(() => {
  const members = roomMembers.value || []
  console.log('[Mention] Filtering users, query:', mentionQuery.value, 'total members:', members.length)
  
  if (!mentionQuery.value) {
    // 显示所有成员，自己排在最后
    const me = authStore.user
    return [...members].sort((a, b) => {
      if (a.id === me?.id) return 1
      if (b.id === me?.id) return -1
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

// 所有可选项（快捷选项 + 用户）用于键盘导航
type MentionOption = 
  | { type: 'shortcut'; key: 'all' | 'here'; label: string; icon: string }
  | { type: 'user'; user: MemberDto }

const allMentionOptions = computed<MentionOption[]>(() => {
  const options: MentionOption[] = [
    { type: 'shortcut', key: 'all', label: '@所有人', icon: '👥' },
    { type: 'shortcut', key: 'here', label: '@在线', icon: '🟢' }
  ]
  filteredMentionUsers.value.forEach(user => {
    options.push({ type: 'user', user })
  })
  return options
})

onMounted(() => {
  chatStore.connect(roomId.value)
  loadRoomMembers()
  loadMentions()
  
  // 添加 document 级别的 paste 监听
  document.addEventListener('paste', handleDocumentPaste)
})

onUnmounted(() => {
  chatStore.disconnect()
  // 移除 document 级别的 paste 监听
  document.removeEventListener('paste', handleDocumentPaste)
})

watch(() => chatStore.messages.length, () => {
  nextTick(() => {
    scrollToBottom()
  })
})

async function loadRoomMembers() {
  try {
    const response = await chatRoomApi.getMembers(roomId.value)
    roomMembers.value = response.data || []
    console.log('[Mention] Loaded room members:', roomMembers.value.length, roomMembers.value)
  } catch (err) {
    console.error('Failed to load room members:', err)
    roomMembers.value = []
  }
}

async function loadMentions() {
  try {
    const [mentionsRes, countRes] = await Promise.all([
      mentionApi.getHistory(0, 50),
      mentionApi.getUnreadCount()
    ])
    mentions.value = mentionsRes.data
    unreadMentionCount.value = countRes.data.count
  } catch (err) {
    console.error('Failed to load mentions:', err)
  }
}

async function markAllMentionsAsRead() {
  try {
    await mentionApi.markAllAsRead()
    unreadMentionCount.value = 0
    mentions.value.forEach(m => m.isRead = true)
  } catch (err) {
    console.error('Failed to mark mentions as read:', err)
  }
}

function goToMention(mention: MentionRecord) {
  if (mention.roomId !== roomId.value) {
    router.push(`/chat/${mention.roomId}`)
  }
  showMentions.value = false
  // 标记已读
  if (!mention.isRead) {
    mentionApi.markAsRead(mention.id)
    mention.isRead = true
    unreadMentionCount.value = Math.max(0, unreadMentionCount.value - 1)
  }
}

function scrollToBottom() {
  if (messageContainer.value) {
    messageContainer.value.scrollTop = messageContainer.value.scrollHeight
  }
}

function sendMessage() {
  const content = inputMessage.value.trim()
  // 允许发送纯附件消息（即使没有文字内容）
  if ((!content && attachments.value.length === 0) || !chatStore.isConnected) return
  
  chatStore.sendMessage(content, attachments.value)
  inputMessage.value = ''
  attachments.value = [] // 清空附件
  showMentionList.value = false
  adjustTextareaHeight()
}

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
            if (selectedOption.key === 'all') {
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
  
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    sendMessage()
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
  
  const text = inputMessage.value
  const cursorPos = inputRef.value?.selectionStart || 0
  
  console.log('[Mention] Input changed:', { text, cursorPos })
  
  // 查找光标前最近的 @
  const textBeforeCursor = text.slice(0, cursorPos)
  const lastAtIndex = textBeforeCursor.lastIndexOf('@')
  
  console.log('[Mention] Last @ index:', lastAtIndex, 'textBeforeCursor:', textBeforeCursor)
  
  if (lastAtIndex >= 0) {
    // 检查 @ 和光标之间是否有空格
    const textBetween = textBeforeCursor.slice(lastAtIndex + 1)
    if (!textBetween.includes(' ')) {
      mentionStartIndex.value = lastAtIndex
      mentionQuery.value = textBetween
      showMentionList.value = true
      mentionSelectedIndex.value = 0
      console.log('[Mention] Showing list, query:', mentionQuery.value, 'members:', roomMembers.value.length)
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
  
  // 将光标放到@后面
  nextTick(() => {
    const newPos = mentionStartIndex.value + (user.nickname || user.username).length + 2
    inputRef.value?.setSelectionRange(newPos, newPos)
  })
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
    inputRef.value.style.height = Math.min(inputRef.value.scrollHeight, 120) + 'px'
  }
}

function formatTime(timestamp: string) {
  const date = new Date(timestamp)
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function renderContent(msg: Message) {
  // 防御性处理：确保 content 不为 null/undefined
  let content = (msg.content || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

  // 高亮 @所有人 和 @在线
  content = content
    .replace(/@所有人|@everyone|@all/gi, '<span class="mention mention-all">$\u0026</span>')
    .replace(/@在线|@here/gi, '<span class="mention mention-here">$\u0026</span>')

  // 高亮 @openclaw
  content = content.replace(/@openclaw/gi, '<span class="mention">@openclaw</span>')

  // 高亮具体用户@
  if (msg.mentions) {
    msg.mentions.forEach(mention => {
      const regex = new RegExp(`@${mention.userName}`, 'g')
      content = content.replace(regex, `<span class="mention">@${mention.userName}</span>`)
    })
  }

  // 渲染附件图片
  let attachmentsHtml = ''
  if (msg.attachments && msg.attachments.length > 0) {
    attachmentsHtml = '<div class="message-attachments">' +
      msg.attachments.map(att => {
        // 更可靠的图片检测：检查 type、contentType 或 url
        const typeStr = (att.type || '').toUpperCase()
        const contentTypeStr = (att.contentType || '').toLowerCase()
        const urlStr = (att.url || '').toLowerCase()

        // 多种方式检测图片
        const isImage = typeStr === 'IMAGE' ||
                       contentTypeStr.startsWith('image/') ||
                       urlStr.startsWith('data:image/') ||
                       urlStr.endsWith('.png') ||
                       urlStr.endsWith('.jpg') ||
                       urlStr.endsWith('.jpeg') ||
                       urlStr.endsWith('.gif') ||
                       urlStr.endsWith('.webp')

        if (isImage) {
          return `<img src="${att.url}" alt="${att.name || '图片'}" class="message-image" loading="lazy" />`
        }
        return `<a href="${att.url}" target="_blank" class="message-file">${att.name || '附件'}</a>`
      }).join('') +
      '</div>'
  }

  return content.replace(/\n/g, '<br>') + attachmentsHtml
}

function isMentionedMe(msg: Message): boolean {
  if (!authStore.user) return false
  if (msg.mentionAll) return true
  if (msg.mentions?.some(m => m.userId === authStore.user?.id)) return true
  return false
}

function getInitials(name: string): string {
  return name.slice(0, 2).toUpperCase()
}

// 处理 document 级别的粘贴事件
function handleDocumentPaste(e: ClipboardEvent) {
  // 如果焦点在输入框，让 handlePaste 处理
  if (inputRef.value && document.activeElement === inputRef.value) {
    return
  }
  // 否则调用 handlePaste
  handlePaste(e)
}

// 生成附件唯一ID
function generateAttachmentId(): string {
  return `att-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

// 处理粘贴事件
function handlePaste(e: ClipboardEvent) {
  console.log('[Paste] Event triggered', e)
  const items = e.clipboardData?.items
  if (!items) {
    console.log('[Paste] No clipboard items')
    return
  }

  console.log('[Paste] Clipboard items count:', items.length)
  
  const imageItems: DataTransferItem[] = []
  for (let i = 0; i < items.length; i++) {
    const item = items[i]
    console.log(`[Paste] Item ${i}: type=${item.type}, kind=${item.kind}`)
    if (item.type.startsWith('image/')) {
      imageItems.push(item)
    }
  }

  if (imageItems.length === 0) {
    console.log('[Paste] No image items found')
    return
  }

  e.preventDefault()
  console.log('[Paste] Processing', imageItems.length, 'image(s)')

  for (const item of imageItems) {
    const file = item.getAsFile()
    if (!file) {
      console.log('[Paste] Could not get file from item')
      continue
    }
    
    console.log('[Paste] Processing file:', file.name, file.type, file.size)

    const reader = new FileReader()
    reader.onload = () => {
      const dataUrl = reader.result as string
      console.log('[Paste] File read success, dataUrl length:', dataUrl.length)
      attachments.value.push({
        id: generateAttachmentId(),
        dataUrl,
        mimeType: file.type
      })
    }
    reader.onerror = (err) => {
      console.error('[Paste] FileReader error:', err)
    }
    reader.readAsDataURL(file)
  }
}

// 触发文件选择
function triggerFileUpload() {
  fileInputRef.value?.click()
}

// 处理文件选择
function handleFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  const files = input.files
  if (!files || files.length === 0) return

  for (const file of files) {
    if (!file.type.startsWith('image/')) continue

    const reader = new FileReader()
    reader.onload = () => {
      const dataUrl = reader.result as string
      attachments.value.push({
        id: generateAttachmentId(),
        dataUrl,
        mimeType: file.type
      })
    }
    reader.readAsDataURL(file)
  }

  // 清空 input 值，允许重复选择相同文件
  input.value = ''
}

// 移除附件
function removeAttachment(id: string) {
  attachments.value = attachments.value.filter(att => att.id !== id)
}
</script>

<style scoped>
.chat-view {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--surface-color);
}

.header {
  height: 60px;
  background: var(--surface-color);
  border-bottom: 1px solid var(--border-color);
  display: flex;
  align-items: center;
  padding: 0 1rem;
  gap: 1rem;
}

.back {
  font-size: 1.25rem;
  color: var(--text-secondary);
  text-decoration: none;
}

.room-info {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.room-info h1 {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
}

.status {
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.status.connected {
  color: var(--success-color);
}

.actions {
  display: flex;
  gap: 0.5rem;
}

.actions button {
  padding: 0.5rem 0.75rem;
  background: transparent;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 0.875rem;
  cursor: pointer;
  position: relative;
}

.mention-btn {
  font-weight: 600;
  color: var(--primary-color);
}

.mention-badge {
  position: absolute;
  top: -6px;
  right: -6px;
  background: #ef4444;
  color: white;
  font-size: 0.625rem;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 10px;
  min-width: 16px;
  text-align: center;
}

.message-container {
  flex: 1;
  overflow-y: auto;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.message {
  max-width: 70%;
  padding: 0.75rem 1rem;
  border-radius: 12px;
  background: var(--bg-color);
  align-self: flex-start;
}

.message.from-me {
  align-self: flex-end;
  background: var(--primary-color);
  color: white;
}

.message.from-openclaw {
  background: #e0e7ff;
  border: 1px solid var(--primary-color);
}

.message.mentioned-me {
  background: #fef3c7;
  border: 2px solid #f59e0b;
}

.message.from-me.mentioned-me {
  background: var(--primary-color);
  border: 2px solid #f59e0b;
}

.message-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.25rem;
  font-size: 0.75rem;
}

.message.from-me .message-header {
  color: rgba(255,255,255,0.8);
}

.message:not(.from-me) .message-header {
  color: var(--text-secondary);
}

.sender {
  font-weight: 500;
}

.mention-tag {
  font-size: 0.625rem;
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 600;
}

.mention-tag.mention-all {
  background: #fef3c7;
  color: #92400e;
}

.mention-tag.mention-here {
  background: #dbeafe;
  color: #1e40af;
}

.message.from-me .mention-tag.mention-all {
  background: rgba(255,255,255,0.3);
  color: white;
}

.message-content {
  line-height: 1.5;
  word-break: break-word;
}

.message-content :deep(.mention) {
  color: var(--primary-color);
  font-weight: 500;
  background: rgba(59, 130, 246, 0.1);
  padding: 0 2px;
  border-radius: 3px;
}

.message-content :deep(.mention.mention-all) {
  color: #f59e0b;
  background: rgba(245, 158, 11, 0.1);
}

.message-content :deep(.mention.mention-here) {
  color: #3b82f6;
  background: rgba(59, 130, 246, 0.1);
}

.message.from-me .message-content :deep(.mention) {
  color: rgba(255,255,255,0.95);
  background: rgba(255,255,255,0.2);
}

/* 消息附件样式 */
.message-content :deep(.message-attachments) {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-top: 0.5rem;
}

.message-content :deep(.message-image) {
  max-width: 300px;
  max-height: 200px;
  border-radius: 8px;
  cursor: pointer;
  transition: transform 0.2s;
}

.message-content :deep(.message-image:hover) {
  transform: scale(1.02);
}

.message-content :deep(.message-file) {
  display: inline-flex;
  align-items: center;
  padding: 0.5rem 1rem;
  background: var(--bg-color);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  color: var(--primary-color);
  text-decoration: none;
  font-size: 0.875rem;
}

.message.from-me .message-content :deep(.message-file) {
  background: rgba(255,255,255,0.2);
  border-color: rgba(255,255,255,0.3);
  color: white;
}

.empty {
  text-align: center;
  color: var(--text-secondary);
  padding: 3rem 1rem;
}

.empty span {
  display: block;
  margin-top: 0.5rem;
  font-size: 0.875rem;
}

.input-area {
  border-top: 1px solid var(--border-color);
  padding: 1rem;
  position: relative;
}

/* 附件预览样式 */
.attachments-preview {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
  padding: 0.5rem;
  background: var(--bg-color);
  border-radius: 8px;
  max-height: 120px;
  overflow-y: auto;
}

.attachment-item {
  position: relative;
  width: 80px;
  height: 80px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--border-color);
  flex-shrink: 0;
}

.attachment-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.attachment-remove {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.6);
  color: white;
  border: none;
  font-size: 14px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  line-height: 1;
  padding: 0;
}

.attachment-remove:hover {
  background: rgba(0, 0, 0, 0.8);
}

.input-wrapper {
  display: flex;
  gap: 0.75rem;
  align-items: flex-end;
}

.input-actions {
  display: flex;
  gap: 0.5rem;
}

.upload-btn {
  padding: 0.75rem;
  background: var(--bg-color);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 1.25rem;
  cursor: pointer;
  transition: background 0.2s;
  height: 44px;
  width: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.upload-btn:hover:not(:disabled) {
  background: var(--border-color);
}

.upload-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
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
  max-height: 120px;
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

.mention-shortcuts {
  background: rgba(59, 130, 246, 0.05);
}

.mention-divider {
  height: 1px;
  background: var(--border-color);
  margin: 0;
}

.mention-item.shortcut {
  padding: 0.5rem 1rem;
}

.shortcut-icon {
  font-size: 1rem;
}

/* 弹窗样式 */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 1rem;
}

.modal {
  background: var(--surface-color);
  border-radius: 16px;
  width: 100%;
  max-width: 500px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0,0,0,0.3);
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem 1.25rem;
  border-bottom: 1px solid var(--border-color);
}

.modal-header h2 {
  font-size: 1.125rem;
  font-weight: 600;
}

.close-btn {
  background: none;
  border: none;
  font-size: 1.5rem;
  color: var(--text-secondary);
  cursor: pointer;
  padding: 0.25rem;
}

.modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 0;
}

.modal-footer {
  padding: 1rem 1.25rem;
  border-top: 1px solid var(--border-color);
  display: flex;
  justify-content: flex-end;
}

.modal-footer button {
  padding: 0.5rem 1rem;
  background: var(--primary-color);
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

.modal-footer button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.empty-mentions {
  padding: 3rem 1rem;
  text-align: center;
  color: var(--text-secondary);
}

.mention-list-container {
  padding: 0.5rem 0;
}

.mention-record {
  padding: 0.875rem 1.25rem;
  border-bottom: 1px solid var(--border-color);
  cursor: pointer;
  transition: background 0.15s;
}

.mention-record:last-child {
  border-bottom: none;
}

.mention-record:hover {
  background: var(--bg-color);
}

.mention-record.unread {
  background: rgba(59, 130, 246, 0.05);
  border-left: 3px solid var(--primary-color);
}

.mention-record-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.375rem;
  font-size: 0.75rem;
}

.mention-record-sender {
  font-weight: 600;
  color: var(--text-primary);
}

.mention-record-room {
  color: var(--text-secondary);
  background: var(--bg-color);
  padding: 2px 6px;
  border-radius: 4px;
}

.mention-record-time {
  color: var(--text-secondary);
  margin-left: auto;
}

.mention-record-content {
  color: var(--text-primary);
  font-size: 0.875rem;
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

@media (max-width: 768px) {
  .message {
    max-width: 85%;
  }
  
  .input-wrapper button {
    padding: 0.75rem 1rem;
  }
  
  .mention-list {
    left: 0.5rem;
    right: 0.5rem;
  }
  
  .modal {
    max-height: 90vh;
    border-radius: 12px;
  }
}
</style>
