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
      <!-- 工具调用消息 -->
      <div
        v-for="msg in chatStore.messages"
        :key="msg.id"
        :class="[
          'message',
          {
            'from-me': msg.senderId === authStore.user?.id,
            'from-openclaw': msg.fromOpenClaw,
            'mentioned-me': isMentionedMe(msg),
            'tool-call-message': msg.isToolCall || msg.toolCalls?.length
          }
        ]"
      >
        <!-- 工具调用展示 -->
        <template v-if="msg.isToolCall || msg.toolCalls?.length">
          <!-- 消息头部 -->
          <div class="message-header">
            <span class="sender">{{ msg.senderName }}</span>
            <span v-if="msg.mentionAll" class="mention-tag mention-all">@所有人</span>
            <span v-else-if="msg.mentionHere" class="mention-tag mention-here">@在线</span>
            <span class="time">{{ formatTime(msg.timestamp) }}</span>
          </div>
          <div class="tool-call-header">
            <span class="tool-icon">🔧</span>
            <span class="tool-title">工具调用</span>
          </div>
          <div class="tool-call-list">
            <div
              v-for="tool in (msg.toolCalls || [])"
              :key="tool.id"
              :class="['tool-item', tool.status]"
            >
              <div class="tool-name">
                <code>{{ tool.name }}</code>
                <span v-if="tool.status === 'running'" class="tool-status running">运行中...</span>
                <span v-else-if="tool.status === 'completed'" class="tool-status completed">✓ 完成</span>
                <span v-else-if="tool.status === 'error'" class="tool-status error">✗ 错误</span>
              </div>
              <div v-if="tool.description" class="tool-description">{{ tool.description }}</div>
              <div v-if="tool.result" class="tool-result">
                <pre><code v-html="highlightCode(tool.result)"></code></pre>
              </div>
            </div>
          </div>
          <!-- 工具调用后的回复内容 -->
          <div class="message-content tool-call-content" v-html="renderContent(msg)"></div>
          <!-- 调试：显示工具调用消息的原始内容长度 -->
          <div v-if="!msg.content || msg.content.length < 50" class="empty-content-debug">
            [工具调用消息内容 - 长度: {{ msg.content?.length || 0 }}, ID: {{ msg.id?.slice(-6) }}]
          </div>
        </template>
        
        <!-- 普通消息 -->
        <template v-else>
          <div class="message-header">
            <span class="sender">{{ msg.senderName }}</span>
            <span v-if="msg.mentionAll" class="mention-tag mention-all">@所有人</span>
            <span v-else-if="msg.mentionHere" class="mention-tag mention-here">@在线</span>
            <span class="time">{{ formatTime(msg.timestamp) }}</span>
          </div>
          <div
            class="message-content"
            v-html="renderContent(msg)"
            @touchstart.prevent="handleLongPressStart($event, msg)"
            @touchend="handleLongPressEnd()"
            @touchcancel="handleLongPressCancel($event)"
            @mousedown="handleLongPressStart($event, msg)"
            @mouseup="handleLongPressEnd()"
            @mouseleave="handleLongPressEnd()"
            @contextmenu.prevent
          ></div>
          <!-- 调试用：显示消息原始内容（开发时可见）-->
          <div v-if="!msg.content && !msg.attachments?.length" class="empty-content-debug">
            [空消息 - ID: {{ msg.id?.slice(-6) }}]
          </div>
        </template>
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

    <!-- 选中文本浮动复制按钮 -->
    <div
      v-if="showSelectionCopyBtn"
      class="selection-copy-btn"
      :style="selectionCopyBtnStyle"
      @click="copySelection"
    >
      复制
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
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js'
import 'highlight.js/styles/github-dark.css'

// 配置 marked - marked v17
marked.use({
  breaks: true,
  gfm: true,
  renderer: {
    code({ text, lang }: { text: string; lang?: string }): string {
      const language = lang || 'plaintext'
      const validLang = hljs.getLanguage(language) ? language : 'plaintext'
      const highlighted = hljs.highlight(text, { language: validLang }).value
      return `<pre class="hljs language-${validLang}"><code class="language-${validLang}">${highlighted}</code></pre>`
    }
  }
})

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

// 选中文本复制相关状态
const showSelectionCopyBtn = ref(false)
const selectionCopyBtnStyle = ref({ top: '0px', left: '0px' })

// @提及通知
const mentions = ref<MentionRecord[]>([])
const unreadMentionCount = ref(0)

// 长按复制相关状态
const longPressTimer = ref<number | null>(null)
const longPressTarget = ref<HTMLElement | null>(null)
const LONG_PRESS_DURATION = 500 // 长按触发时间（毫秒）

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

  // 添加全局上下文菜单阻止（用于长按复制）
  document.addEventListener('contextmenu', preventContextMenu)

  // 添加选中文本监听
  document.addEventListener('selectionchange', handleSelectionChange)
})

onUnmounted(() => {
  chatStore.disconnect()
  // 移除 document 级别的 paste 监听
  document.removeEventListener('paste', handleDocumentPaste)

  // 移除全局上下文菜单阻止
  document.removeEventListener('contextmenu', preventContextMenu)

  // 移除选中文本监听
  document.removeEventListener('selectionchange', handleSelectionChange)

  // 清理长按定时器
  handleLongPressEnd()
})

watch(() => chatStore.messages.length, () => {
  nextTick(() => {
    scrollToBottom()
    // 为新增的代码块添加复制按钮
    if (messageContainer.value) {
      addCodeCopyButtons(messageContainer.value)
    }
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

// 复制代码到剪贴板
async function copyCode(button: HTMLElement, code: string) {
  try {
    await navigator.clipboard.writeText(code)
    const originalText = button.textContent
    button.textContent = '已复制!'
    button.classList.add('copied')
    setTimeout(() => {
      button.textContent = originalText
      button.classList.remove('copied')
    }, 2000)
  } catch (err) {
    console.error('Failed to copy code:', err)
  }
}

// 为代码块添加复制按钮
function addCodeCopyButtons(container: HTMLElement) {
  const codeBlocks = container.querySelectorAll('pre code')
  codeBlocks.forEach((codeBlock) => {
    const pre = codeBlock.parentElement as HTMLPreElement
    if (pre.querySelector('.code-copy-btn')) return // 已添加过

    const code = codeBlock.textContent || ''
    const btn = document.createElement('button')
    btn.className = 'code-copy-btn'
    btn.textContent = '复制'
    btn.onclick = () => copyCode(btn, code)
    pre.appendChild(btn)
  })
}

// 高亮代码块
function highlightCode(code: string): string {
  // 简单的高亮处理：转义 HTML 并保留格式
  return code
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function renderContent(msg: Message) {
  // 防御性处理：确保 content 不为 null/undefined
  let content = msg.content || ''

  // 如果是工具调用消息，提取工具详情后的实际回复内容
  if (msg.isToolCall || msg.toolCalls?.length) {
    // 查找 "---" 分隔符，提取前面的内容作为实际回复
    const separatorIndex = content.indexOf('---')
    if (separatorIndex !== -1) {
      content = content.substring(0, separatorIndex).trim()
    }
    // 移除 **Tools used:** 和 **Tool details:** 部分
    const toolsUsedIndex = content.indexOf('**Tools used:**')
    if (toolsUsedIndex !== -1) {
      content = content.substring(0, toolsUsedIndex).trim()
    }
  }

  // 处理转义字符：将字符串 \n \t 转为真正的换行和制表符
  content = content.replace(/\\n/g, '\n').replace(/\\t/g, '\t')

  // Step 1: 先渲染 Markdown（在 HTML 转义之前）
  // 临时替换 @提及，防止 Markdown 解析器破坏它们
  const mentionPlaceholders: string[] = []
  content = content.replace(/(@所有人|@everyone|@all|@在线|@here|@openclaw|@[^\s]+)/gi, (match) => {
    mentionPlaceholders.push(match)
    return `\u0000MENTION_${mentionPlaceholders.length - 1}\u0000`
  })

  // 渲染 Markdown
  let htmlContent: string
  try {
    console.log('[renderContent] Input content:', content.substring(0, 100))
    htmlContent = marked.parse(content, { async: false }) as string
    console.log('[renderContent] Parsed HTML:', htmlContent.substring(0, 100))
  } catch (e) {
    console.error('[renderContent] Markdown parsing error:', e)
    // 解析失败时的 fallback
    htmlContent = content
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/\*(.+?)\*/g, '<em>$1</em>')
      .replace(/`(.+?)`/g, '<code>$1</code>')
      .replace(/~~(.+?)~~/g, '<del>$1</del>')
      .replace(/^### (.+)$/gm, '<h3>$1</h3>')
      .replace(/^## (.+)$/gm, '<h2>$1</h2>')
      .replace(/^# (.+)$/gm, '<h1>$1</h1>')
      .replace(/^- (.+)$/gm, '<li>$1</li>')
      .replace(/\n/g, '<br>')
  }

  // XSS 清理
  htmlContent = DOMPurify.sanitize(htmlContent, {
    ALLOWED_TAGS: [
      'p', 'br', 'hr',
      'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
      'ul', 'ol', 'li',
      'strong', 'em', 'code', 'pre', 'blockquote',
      'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td',
      'del', 'ins', 'sup', 'sub'
    ],
    ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'target']
  })

  // Step 2: 恢复 @提及并添加高亮
  htmlContent = htmlContent.replace(/\u0000MENTION_(\d+)\u0000/g, (_, index) => {
    const mention = mentionPlaceholders[parseInt(index)]
    if (!mention) return ''

    // 判断提及类型并添加对应的 class
    if (/@所有人|@everyone|@all/i.test(mention)) {
      return `<span class="mention mention-all">${mention}</span>`
    } else if (/@在线|@here/i.test(mention)) {
      return `<span class="mention mention-here">${mention}</span>`
    } else if (/@openclaw/i.test(mention)) {
      return `<span class="mention">${mention}</span>`
    } else {
      // 检查是否是已知的用户提及
      const isKnownMention = msg.mentions?.some(m => mention === `@${m.userName}`)
      if (isKnownMention) {
        return `<span class="mention">${mention}</span>`
      }
      // 未知的 @xxx 也高亮
      return `<span class="mention">${mention}</span>`
    }
  })

  // Step 3: 处理 msg.mentions 中可能存在的但未在内容中找到的提及
  if (msg.mentions) {
    msg.mentions.forEach(mention => {
      const regex = new RegExp(`@${mention.userName}`, 'g')
      // 只替换未被替换过的（即不在 placeholder 中的）
      if (!mentionPlaceholders.some(p => p === `@${mention.userName}`)) {
        htmlContent = htmlContent.replace(regex, `<span class="mention">@${mention.userName}</span>`)
      }
    })
  }

  // Step 4: 渲染附件图片
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

  return htmlContent + attachmentsHtml
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

// ============ 长按复制功能 ============

// 复制文本到剪贴板
async function copyTextToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch (err) {
    console.error('复制失败:', err)
    // Fallback: 使用 document.execCommand
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.style.position = 'fixed'
    textarea.style.left = '-9999px'
    document.body.appendChild(textarea)
    textarea.select()
    try {
      const success = document.execCommand('copy')
      document.body.removeChild(textarea)
      return success
    } catch (e) {
      document.body.removeChild(textarea)
      return false
    }
  }
}

// 显示复制成功提示
function showCopyToast() {
  // 创建提示元素
  const toast = document.createElement('div')
  toast.className = 'copy-toast'
  toast.textContent = '已复制'
  document.body.appendChild(toast)

  // 触发动画
  requestAnimationFrame(() => {
    toast.classList.add('show')
  })

  // 2秒后移除
  setTimeout(() => {
    toast.classList.remove('show')
    setTimeout(() => {
      document.body.removeChild(toast)
    }, 300)
  }, 2000)
}

// 长按开始
function handleLongPressStart(event: TouchEvent | MouseEvent, msg: Message) {
  // 只处理普通消息（非工具调用消息）
  if (msg.isToolCall || msg.toolCalls?.length) return

  const target = event.currentTarget as HTMLElement
  if (!target) return

  longPressTarget.value = target

  // 添加长按中样式
  target.classList.add('long-pressing')

  // 设置定时器
  longPressTimer.value = window.setTimeout(async () => {
    longPressTimer.value = null
    longPressTarget.value = null
    target.classList.remove('long-pressing')

    // 执行复制
    const textToCopy = msg.content || ''
    const success = await copyTextToClipboard(textToCopy)
    if (success) {
      showCopyToast()
    }
  }, LONG_PRESS_DURATION)
}

// 长按结束（取消）
function handleLongPressEnd(_event?: TouchEvent | MouseEvent) {
  if (longPressTimer.value) {
    clearTimeout(longPressTimer.value)
    longPressTimer.value = null
  }
  if (longPressTarget.value) {
    longPressTarget.value.classList.remove('long-pressing')
    longPressTarget.value = null
  }
}

// 长按取消（移动手指时）
function handleLongPressCancel(_event: TouchEvent) {
  handleLongPressEnd()
}

// 阻止上下文菜单（防止长按触发浏览器菜单）
function preventContextMenu(event: MouseEvent) {
  const target = event.target as HTMLElement
  if (target?.closest('.message-content') || target?.closest('.message')) {
    // 如果有选中的文本，不阻止右键菜单（允许用户用系统菜单复制）
    const selection = window.getSelection()
    if (selection && selection.toString().trim()) {
      return
    }
    event.preventDefault()
  }
}

// ============ 选中文本复制功能 ============

// 处理选中文本变化
function handleSelectionChange() {
  const selection = window.getSelection()
  if (!selection || selection.isCollapsed || !selection.toString().trim()) {
    showSelectionCopyBtn.value = false
    return
  }

  // 检查选区是否在消息内容区域内
  const range = selection.getRangeAt(0)
  const container = range.commonAncestorContainer as HTMLElement
  const messageContent = container?.closest?.('.message-content')

  if (!messageContent) {
    showSelectionCopyBtn.value = false
    return
  }

  // 获取选区的位置
  const rect = range.getBoundingClientRect()
  selectionCopyBtnStyle.value = {
    top: `${rect.top - 45 + window.scrollY}px`,
    left: `${rect.left + rect.width / 2 - 25 + window.scrollX}px`
  }
  showSelectionCopyBtn.value = true
}

// 复制选中的文本
async function copySelection() {
  const selection = window.getSelection()
  if (!selection) return

  const text = selection.toString()
  const success = await copyTextToClipboard(text)

  if (success) {
    showCopyToast()
  }

  showSelectionCopyBtn.value = false
  selection.removeAllRanges()
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

/* Markdown 样式 */
.message-content :deep(h1),
.message-content :deep(h2),
.message-content :deep(h3),
.message-content :deep(h4),
.message-content :deep(h5),
.message-content :deep(h6) {
  margin: 0.75rem 0 0.5rem;
  font-weight: 600;
  line-height: 1.3;
}

.message-content :deep(h1) { font-size: 1.25rem; }
.message-content :deep(h2) { font-size: 1.15rem; }
.message-content :deep(h3) { font-size: 1.05rem; }
.message-content :deep(h4),
.message-content :deep(h5),
.message-content :deep(h6) { font-size: 1rem; }

.message-content :deep(p) {
  margin: 0.5rem 0;
}

.message-content :deep(p:first-child) {
  margin-top: 0;
}

.message-content :deep(p:last-child) {
  margin-bottom: 0;
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
  margin: 0.5rem 0;
  padding: 0.5rem 0.75rem;
  border-left: 3px solid var(--primary-color);
  background: rgba(59, 130, 246, 0.05);
  border-radius: 0 6px 6px 0;
}

.message.from-me .message-content :deep(blockquote) {
  background: rgba(255,255,255,0.1);
  border-left-color: rgba(255,255,255,0.5);
}

.message-content :deep(pre) {
  margin: 0.5rem 0;
  padding: 0.75rem;
  background: #1e1e2e;
  border-radius: 8px;
  overflow-x: auto;
  position: relative;
}

.message-content :deep(pre:hover .code-copy-btn) {
  opacity: 1;
}

.message-content :deep(.code-copy-btn) {
  position: absolute;
  top: 0.5rem;
  right: 0.5rem;
  padding: 0.25rem 0.75rem;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 4px;
  color: #cdd6f4;
  font-size: 0.75rem;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.2s, background 0.2s;
}

.message-content :deep(.code-copy-btn:hover) {
  background: rgba(255, 255, 255, 0.2);
}

.message-content :deep(.code-copy-btn.copied) {
  background: #22c55e;
  border-color: #22c55e;
  opacity: 1;
}

/* 代码语言标签 */
.message-content :deep(pre[class*="language-"])::before {
  content: attr(data-language);
  position: absolute;
  top: 0;
  right: 0;
  padding: 0.25rem 0.5rem;
  font-size: 0.625rem;
  color: #6c7086;
  text-transform: uppercase;
}

/* 为不同语言添加标签 */
.message-content :deep(pre:has(code[class*="language-js"]))::before,
.message-content :deep(pre:has(code[class*="language-javascript"]))::before {
  content: "JS";
}

.message-content :deep(pre:has(code[class*="language-ts"]))::before,
.message-content :deep(pre:has(code[class*="language-typescript"]))::before {
  content: "TS";
}

.message-content :deep(pre:has(code[class*="language-vue"]))::before {
  content: "VUE";
}

.message-content :deep(pre:has(code[class*="language-html"]))::before {
  content: "HTML";
}

.message-content :deep(pre:has(code[class*="language-css"]))::before {
  content: "CSS";
}

.message-content :deep(pre:has(code[class*="language-python"]))::before,
.message-content :deep(pre:has(code[class*="language-py"]))::before {
  content: "PYTHON";
}

.message-content :deep(pre:has(code[class*="language-java"]))::before {
  content: "JAVA";
}

.message-content :deep(pre:has(code[class*="language-go"]))::before {
  content: "GO";
}

.message-content :deep(pre:has(code[class*="language-rust"]))::before {
  content: "RUST";
}

.message-content :deep(pre:has(code[class*="language-bash"]))::before,
.message-content :deep(pre:has(code[class*="language-sh"]))::before,
.message-content :deep(pre:has(code[class*="language-shell"]))::before {
  content: "BASH";
}

.message-content :deep(pre:has(code[class*="language-json"]))::before {
  content: "JSON";
}

.message-content :deep(pre:has(code[class*="language-yaml"]))::before,
.message-content :deep(pre:has(code[class*="language-yml"]))::before {
  content: "YAML";
}

.message-content :deep(pre:has(code[class*="language-markdown"]))::before,
.message-content :deep(pre:has(code[class*="language-md"]))::before {
  content: "MD";
}

.message-content :deep(pre:has(code[class*="language-sql"]))::before {
  content: "SQL";
}

.message-content :deep(pre:has(code[class*="language-dockerfile"]))::before {
  content: "DOCKER";
}

.message-content :deep(pre:has(code[class*="language-plaintext"]))::before,
.message-content :deep(pre:has(code[class*="language-text"]))::before {
  content: "TEXT";
}

.message-content :deep(code) {
  font-family: 'JetBrains Mono', 'Fira Code', 'SF Mono', Monaco, monospace;
  font-size: 0.875em;
}

.message-content :deep(pre code) {
  color: #cdd6f4;
  background: transparent;
  padding: 0;
}

.message-content :deep(:not(pre) > code) {
  background: rgba(59, 130, 246, 0.1);
  padding: 0.125rem 0.375rem;
  border-radius: 4px;
  color: var(--primary-color);
}

.message.from-me .message-content :deep(:not(pre) > code) {
  background: rgba(255,255,255,0.2);
  color: white;
}

.message-content :deep(table) {
  width: 100%;
  margin: 0.5rem 0;
  border-collapse: collapse;
  font-size: 0.875rem;
}

.message-content :deep(th),
.message-content :deep(td) {
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--border-color);
  text-align: left;
}

.message-content :deep(th) {
  background: var(--bg-color);
  font-weight: 600;
}

.message.from-me .message-content :deep(th) {
  background: rgba(255,255,255,0.15);
}

.message.from-me .message-content :deep(th),
.message.from-me .message-content :deep(td) {
  border-color: rgba(255,255,255,0.2);
}

.message-content :deep(hr) {
  margin: 1rem 0;
  border: none;
  border-top: 1px solid var(--border-color);
}

.message.from-me .message-content :deep(hr) {
  border-top-color: rgba(255,255,255,0.2);
}

.message-content :deep(a) {
  color: var(--primary-color);
  text-decoration: underline;
}

.message.from-me .message-content :deep(a) {
  color: rgba(255,255,255,0.9);
}

/* 长按复制相关样式 */
.message-content {
  user-select: text;
  -webkit-user-select: text;
  cursor: pointer;
  transition: transform 0.15s ease, background-color 0.15s ease;
}

.message-content:active,
.message-content.long-pressing {
  transform: scale(0.98);
  background-color: rgba(0, 0, 0, 0.05);
}

.message.from-me .message-content.long-pressing {
  background-color: rgba(255, 255, 255, 0.1);
}

/* 复制提示 Toast */
.copy-toast {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%) scale(0.9);
  background: rgba(0, 0, 0, 0.8);
  color: white;
  padding: 0.75rem 1.5rem;
  border-radius: 8px;
  font-size: 0.875rem;
  z-index: 9999;
  opacity: 0;
  transition: opacity 0.3s ease, transform 0.3s ease;
  pointer-events: none;
}

.copy-toast.show {
  opacity: 1;
  transform: translate(-50%, -50%) scale(1);
}

/* 选中文本复制按钮 */
.selection-copy-btn {
  position: fixed;
  padding: 0.5rem 1rem;
  background: var(--primary-color);
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  z-index: 1000;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
  animation: popIn 0.2s ease;
  user-select: none;
  -webkit-user-select: none;
}

.selection-copy-btn:hover {
  background: var(--primary-hover);
}

.selection-copy-btn:active {
  transform: scale(0.95);
}

@keyframes popIn {
  from {
    opacity: 0;
    transform: translateY(5px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.message-content :deep(strong) {
  font-weight: 600;
}

.message-content :deep(em) {
  font-style: italic;
}

.message-content :deep(del) {
  text-decoration: line-through;
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

/* 工具调用消息样式 - 改进版 */
.tool-call-message {
  background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%) !important;
  border: 1px solid #e2e8f0;
  border-radius: 16px !important;
  max-width: 95% !important;
  margin: 0.75rem auto !important;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  overflow: hidden;
}

.tool-call-message.from-openclaw {
  background: linear-gradient(135deg, #eff6ff 0%, #dbeafe 100%) !important;
  border-color: #93c5fd;
}

.tool-call-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1rem;
  background: rgba(59, 130, 246, 0.08);
  border-bottom: 1px solid rgba(59, 130, 246, 0.15);
}

.tool-icon {
  font-size: 1.125rem;
}

.tool-title {
  font-weight: 600;
  font-size: 0.875rem;
  color: #1e40af;
}

.tool-call-list {
  padding: 0.75rem 1rem;
}

.tool-item {
  margin-bottom: 0.75rem;
  padding: 0.875rem;
  background: #ffffff;
  border-radius: 12px;
  border-left: 4px solid #3b82f6;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  transition: transform 0.15s ease;
}

.tool-item:hover {
  transform: translateX(2px);
}

.tool-item:last-child {
  margin-bottom: 0;
}

.tool-item.running {
  border-left-color: #f59e0b;
  background: #fffbeb;
}

.tool-item.completed {
  border-left-color: #10b981;
}

.tool-item.error {
  border-left-color: #ef4444;
  background: #fef2f2;
}

.tool-name {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.375rem;
}

.tool-name code {
  background: #1e293b;
  color: #e2e8f0;
  padding: 0.25rem 0.5rem;
  border-radius: 6px;
  font-size: 0.8125rem;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-weight: 500;
}

.tool-status {
  font-size: 0.6875rem;
  padding: 0.25rem 0.5rem;
  border-radius: 99px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.025em;
}

.tool-status.running {
  background: #fef3c7;
  color: #92400e;
}

.tool-status.completed {
  background: #d1fae5;
  color: #065f46;
}

.tool-status.error {
  background: #fee2e2;
  color: #991b1b;
}

.tool-description {
  font-size: 0.8125rem;
  color: #64748b;
  margin-top: 0.375rem;
  line-height: 1.4;
}

.tool-result {
  margin-top: 0.625rem;
  background: #f8fafc;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #e2e8f0;
}

.tool-result pre {
  margin: 0;
  padding: 0.875rem;
  font-size: 0.8125rem;
  overflow-x: auto;
  max-height: 400px;
  overflow-y: auto;
  line-height: 1.5;
  color: #334155;
}

.tool-result code {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

.tool-call-content {
  padding: 1rem;
  border-top: 1px solid #e2e8f0;
  background: #ffffff;
}

.tool-call-content :deep(p:first-child) {
  margin-top: 0;
}

.tool-call-content :deep(p:last-child) {
  margin-bottom: 0;
}

/* 空消息调试样式 */
.empty-content-debug {
  padding: 0.5rem;
  background: #fef3c7;
  border: 1px dashed #f59e0b;
  border-radius: 4px;
  font-size: 0.75rem;
  color: #92400e;
  font-style: italic;
  margin-top: 0.5rem;
}

/* ============================================
   移动端适配 - Mobile Responsive Styles
   ============================================ */

@media (max-width: 768px) {
  .chat-view {
    height: 100dvh;
  }

  /* Header */
  .header {
    height: 56px;
    padding: 0 0.75rem;
    gap: 0.75rem;
  }

  .back {
    font-size: 1.125rem;
    padding: 0.5rem;
    min-width: 36px;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .room-info h1 {
    font-size: 0.9375rem;
  }

  .status {
    font-size: 0.6875rem;
  }

  .actions {
    gap: 0.375rem;
  }

  .actions button {
    padding: 0.5rem 0.625rem;
    font-size: 0.8125rem;
    min-height: 36px;
  }

  .mention-btn {
    font-size: 0.9375rem;
    min-width: 36px;
  }

  .mention-badge {
    font-size: 0.5625rem;
    padding: 1px 4px;
    min-width: 14px;
    top: -4px;
    right: -4px;
  }

  /* 消息容器 */
  .message-container {
    padding: 0.75rem;
    gap: 0.75rem;
    padding-bottom: calc(0.75rem + env(safe-area-inset-bottom, 0px));
  }

  .message {
    max-width: 92%;
    padding: 0.625rem 0.875rem;
    border-radius: 10px;
  }

  .message-header {
    font-size: 0.6875rem;
    gap: 0.375rem;
    margin-bottom: 0.375rem;
  }

  .mention-tag {
    font-size: 0.5625rem;
    padding: 1px 5px;
  }

  .message-content {
    font-size: 0.9375rem;
    line-height: 1.5;
  }

  /* Markdown 样式移动端调整 */
  .message-content :deep(h1) { font-size: 1.1rem; }
  .message-content :deep(h2) { font-size: 1rem; }
  .message-content :deep(h3) { font-size: 0.95rem; }
  .message-content :deep(h4),
  .message-content :deep(h5),
  .message-content :deep(h6) { font-size: 0.9rem; }

  .message-content :deep(pre) {
    padding: 0.625rem;
    font-size: 0.8125rem;
  }

  .message-content :deep(code) {
    font-size: 0.8125em;
  }

  .message-content :deep(table) {
    font-size: 0.8125rem;
    display: block;
    overflow-x: auto;
    white-space: nowrap;
  }

  .message-content :deep(th),
  .message-content :deep(td) {
    padding: 0.375rem 0.5rem;
  }

  /* 消息图片 */
  .message-content :deep(.message-image) {
    max-width: 100%;
    max-height: 180px;
  }

  .message-content :deep(.message-file) {
    padding: 0.5rem 0.75rem;
    font-size: 0.8125rem;
  }

  .empty {
    padding: 2rem 1rem;
    font-size: 0.875rem;
  }

  /* 输入区域 */
  .input-area {
    padding: 0.625rem 0.75rem;
    padding-bottom: calc(0.625rem + env(safe-area-inset-bottom, 8px));
  }

  /* 附件预览 */
  .attachments-preview {
    padding: 0.375rem;
    margin-bottom: 0.5rem;
    gap: 0.375rem;
    max-height: 90px;
  }

  .attachment-item {
    width: 64px;
    height: 64px;
    border-radius: 6px;
  }

  .attachment-remove {
    width: 18px;
    height: 18px;
    font-size: 12px;
    top: 3px;
    right: 3px;
  }

  /* 输入框 */
  .input-wrapper {
    gap: 0.375rem;
  }

  .input-actions {
    gap: 0.375rem;
  }

  .upload-btn {
    width: 40px;
    height: 40px;
    font-size: 1.125rem;
    border-radius: 10px;
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

  /* @提及列表 */
  .mention-list {
    left: 0.5rem;
    right: 0.5rem;
    max-height: 220px;
    border-radius: 10px;
    margin-bottom: 0.375rem;
  }

  .mention-list-header {
    padding: 0.625rem 0.875rem;
    font-size: 0.6875rem;
  }

  .mention-item {
    padding: 0.625rem 0.875rem;
    gap: 0.625rem;
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

  .mention-empty,
  .mention-loading {
    padding: 0.875rem;
    font-size: 0.8125rem;
  }

  .mention-item.shortcut {
    padding: 0.5rem 0.875rem;
  }

  .shortcut-icon {
    font-size: 0.875rem;
  }

  /* 弹窗 */
  .modal-overlay {
    padding: 0.75rem;
    align-items: flex-end;
  }

  .modal {
    max-width: 100%;
    max-height: 85vh;
    border-radius: 16px 16px 0 0;
  }

  .modal-header {
    padding: 1rem;
  }

  .modal-header h2 {
    font-size: 1rem;
  }

  .close-btn {
    font-size: 1.5rem;
    width: 36px;
    height: 36px;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .modal-footer {
    padding: 1rem;
  }

  .modal-footer button {
    padding: 0.625rem 1rem;
    font-size: 0.875rem;
  }

  .empty-mentions {
    padding: 2rem 1rem;
  }

  .mention-record {
    padding: 0.75rem 1rem;
  }

  .mention-record-header {
    font-size: 0.6875rem;
  }

  .mention-record-content {
    font-size: 0.8125rem;
  }

  /* 工具调用消息 */
  .tool-call-message {
    max-width: 95% !important;
    margin: 0.375rem 0.5rem !important;
  }

  .tool-call-header {
    padding: 0.625rem 0.875rem;
  }

  .tool-call-list {
    padding: 0.625rem 0.875rem;
  }

  .tool-item {
    padding: 0.625rem;
    margin-bottom: 0.5rem;
  }

  .tool-name code {
    font-size: 0.75rem;
  }

  .tool-status {
    font-size: 0.6875rem;
  }

  .tool-description {
    font-size: 0.75rem;
  }

  .tool-result pre {
    padding: 0.625rem;
    font-size: 0.75rem;
    max-height: 200px;
  }

  .tool-call-content {
    padding: 0.75rem;
  }

  /* 选中文本复制按钮 - 移动端 */
  .selection-copy-btn {
    padding: 0.625rem 1.25rem;
    font-size: 0.9375rem;
    border-radius: 8px;
  }
}

/* 小屏手机额外优化 */
@media (max-width: 380px) {
  .header {
    height: 52px;
    padding: 0 0.625rem;
  }

  .back {
    font-size: 1rem;
    padding: 0.375rem;
    min-width: 32px;
  }

  .room-info h1 {
    font-size: 0.875rem;
  }

  .actions button {
    padding: 0.375rem 0.5rem;
    font-size: 0.75rem;
    min-height: 32px;
  }

  .mention-btn {
    font-size: 0.875rem;
    min-width: 32px;
  }

  .message {
    max-width: 94%;
    padding: 0.5rem 0.75rem;
  }

  .message-content {
    font-size: 0.875rem;
  }

  .message-header {
    font-size: 0.625rem;
  }

  .upload-btn {
    width: 36px;
    height: 36px;
    font-size: 1rem;
  }

  textarea {
    padding: 0.5rem 0.625rem;
    font-size: 16px;
  }

  .input-wrapper button {
    padding: 0.5rem 0.875rem;
    height: 36px;
    font-size: 0.75rem;
  }

  .mention-list {
    max-height: 200px;
  }
}

/* 横屏模式优化 */
@media (max-height: 500px) and (orientation: landscape) {
  .header {
    height: 48px;
  }

  .message-container {
    padding: 0.5rem;
    gap: 0.5rem;
  }

  .message {
    max-width: 85%;
    padding: 0.5rem 0.75rem;
  }

  .input-area {
    padding: 0.5rem 0.625rem;
  }

  textarea {
    max-height: 60px;
  }

  .modal {
    max-height: 90vh;
    border-radius: 12px;
  }
}
</style>
