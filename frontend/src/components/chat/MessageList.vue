<template>
  <div class="message-container" ref="containerRef">
    <!-- 加载更多提示 -->
    <div v-if="hasMoreMessages || loadingMore" class="load-more-container">
      <button 
        v-if="!loadingMore" 
        class="load-more-btn"
        @click="$emit('load-more')"
      >
        ↑ 加载更多历史消息
      </button>
      <div v-else class="load-more-loading">
        <span class="loading-spinner"></span>
        加载中...
      </div>
    </div>
    
    <template v-for="(msg, index) in messages" :key="msg.id">
      <!-- 时间分隔线 -->
      <div v-if="shouldShowDateSeparator(index)" class="date-separator">
        <span>{{ formatDateSeparator(msg.timestamp) }}</span>
      </div>
      
      <!-- 系统消息（排除 Flowbot 消息） -->
      <div v-if="!!msg.isSystem && msg.senderName !== 'Flowbot'" class="system-message">
        <span class="system-text">{{ msg.content }}</span>
      </div>
      
      <!-- Flowbot 结果消息（带变量展开/折叠） -->
      <div v-else-if="isFlowbotResultMessage(msg)" :id="'msg-' + msg.id" class="message flowbot-message">
        <div class="message-avatar">
          <div class="avatar-placeholder">{{ msg.senderAvatar || '🤖' }}</div>
        </div>
        <div class="message-body flowbot-body">
          <div class="message-header">
            <span class="sender flowbot-sender">{{ msg.senderName }}</span>
            <span class="time">{{ formatTime(msg.timestamp) }}</span>
          </div>
          <div class="message-content flowbot-content" v-html="renderContent(msg)"></div>
          <!-- 消息操作按钮 -->
          <div class="message-actions">
            <button class="action-btn" @click="copyMessage(msg)" title="复制">📋 复制</button>
            <button class="action-btn" @click="forwardMessage(msg)" title="转发">↗️ 转发</button>
            <button v-if="msg.senderId === currentUserId" class="action-btn" @click="resendMessage(msg)" title="重发">🔄 重发</button>
          </div>
        </div>
      </div>
      
      <!-- Flowbot 普通消息 -->
      <div v-else-if="msg.senderName === 'Flowbot'" :id="'msg-' + msg.id" class="message flowbot-message">
        <div class="message-avatar">
          <div class="avatar-placeholder">{{ msg.senderAvatar || '🤖' }}</div>
        </div>
        <div class="message-body flowbot-body">
          <div class="message-header">
            <span class="sender flowbot-sender">{{ msg.senderName }}</span>
            <span class="time">{{ formatTime(msg.timestamp) }}</span>
          </div>
          <div class="message-content flowbot-content">{{ msg.content }}</div>
          <!-- 消息操作按钮 -->
          <div class="message-actions">
            <button class="action-btn" @click="copyMessage(msg)" title="复制">📋 复制</button>
            <button class="action-btn" @click="forwardMessage(msg)" title="转发">↗️ 转发</button>
            <button v-if="msg.senderId === currentUserId" class="action-btn" @click="resendMessage(msg)" title="重发">🔄 重发</button>
          </div>
        </div>
      </div>
      
      <!-- OpenClaw 消息（包含工具调用） -->
      <template v-else-if="msg.fromOpenClaw">
        <template v-for="(segment, _segIndex) in renderSegments(msg)" :key="segment.type + _segIndex">
          <div :id="'msg-' + msg.id" class="message openclaw-message-container">
            <div class="message-avatar">
              <img v-if="msg.senderAvatar" :src="msg.senderAvatar" :alt="msg.senderName" />
              <div v-else class="avatar-placeholder">🤖</div>
            </div>
            <div class="message-body openclaw-body">
              <div class="message-header">
                <span class="sender">{{ msg.senderName }}</span>
                <span class="time">{{ formatTime(msg.timestamp) }}</span>
                <span v-if="msg.id" class="message-id" title="Message ID">{{ msg.id.slice(-6) }}</span>
                <span v-if="msg.replyToMessageId" class="reply-to-id clickable" :title="'点击跳转到消息: ' + msg.replyToMessageId" @click="$emit('scroll-to-message', msg.replyToMessageId!)">
                  ↩ {{ msg.replyToMessageId.slice(-6) }}
                </span>
              </div>
              <div class="message-content" v-html="segment.html"></div>
              <!-- 消息操作按钮 -->
              <div class="message-actions" v-if="_segIndex === renderSegments(msg).length - 1">
                <button class="action-btn" @click="copyMessage(msg)" title="复制">📋 复制</button>
                <button class="action-btn" @click="forwardMessage(msg)" title="转发">↗️ 转发</button>
                <button v-if="msg.senderId === currentUserId" class="action-btn" @click="resendMessage(msg)" title="重发">🔄 重发</button>
              </div>
            </div>
          </div>
        </template>
      </template>
      
      <!-- 纯工具调用消息（不含 fromOpenClaw） -->
      <div v-else-if="msg.isToolCall || msg.toolCalls?.length" :id="'msg-' + msg.id" class="tool-call-message">
        <div v-if="msg.id || msg.replyToMessageId" class="tool-call-header">
          <span v-if="msg.id" class="message-id" title="Message ID">{{ msg.id.slice(-6) }}</span>
          <span v-if="msg.replyToMessageId" class="reply-to-id clickable" :title="'点击跳转到消息: ' + msg.replyToMessageId" @click="$emit('scroll-to-message', msg.replyToMessageId!)">
            ↩ {{ msg.replyToMessageId.slice(-6) }}
          </span>
        </div>
        <div class="message-content tool-call-content" v-html="renderContent(msg)"></div>
        <!-- 消息操作按钮 -->
        <div class="message-actions">
          <button class="action-btn" @click="copyMessage(msg)" title="复制">📋 复制</button>
          <button class="action-btn" @click="forwardMessage(msg)" title="转发">↗️ 转发</button>
          <button v-if="msg.senderId === currentUserId" class="action-btn" @click="resendMessage(msg)" title="重发">🔄 重发</button>
        </div>
      </div>
      
      <!-- 普通消息 -->
      <div
        v-else
        :id="'msg-' + msg.id"
        :class="[
          'message',
          {
            'from-me': msg.senderId === currentUserId,
            'mentioned-me': isMentionedMe(msg)
          }
        ]"
      >
        <!-- 头像 -->
        <div class="message-avatar">
          <img v-if="getMessageAvatar(msg)" :src="getMessageAvatar(msg)" :alt="msg.senderName" />
          <div v-else class="avatar-placeholder">{{ getInitials(msg.senderName) }}</div>
        </div>

        <div class="message-body">
          <div class="message-header">
            <span class="sender">{{ msg.senderName }}</span>
            <span v-if="msg.mentionAll" class="mention-tag mention-all">@所有人</span>
            <span v-else-if="msg.mentionHere" class="mention-tag mention-here">@在线</span>
            <span class="time">{{ formatTime(msg.timestamp) }}</span>
            <span v-if="msg.id" class="message-id" title="Message ID">{{ msg.id.slice(-6) }}</span>
            <span v-if="msg.replyToMessageId" class="reply-to-id clickable" :title="'点击跳转到消息: ' + msg.replyToMessageId" @click="$emit('scroll-to-message', msg.replyToMessageId!)">
              ↩ {{ msg.replyToMessageId.slice(-6) }}
            </span>
          </div>
          <div class="message-content" v-html="renderContent(msg)"></div>

          <!-- 被@提示 -->
          <div v-if="isMentionedMe(msg) && msg.senderId !== currentUserId" class="mention-notice">
            👤 @了你
          </div>

          <!-- 消息操作按钮 -->
          <div class="message-actions">
            <button class="action-btn" @click="copyMessage(msg)" title="复制">📋 复制</button>
            <button class="action-btn" @click="forwardMessage(msg)" title="转发">↗️ 转发</button>
            <button v-if="msg.senderId === currentUserId" class="action-btn" @click="resendMessage(msg)" title="重发">🔄 重发</button>
          </div>
        </div>
      </div>
    </template>
    
    <div v-if="messages.length === 0" class="empty-messages">
      发送消息开始对话
      <br/>
      <span>在群聊中使用 @openclaw 召唤 AI</span>
    </div>
    
    <!-- 正在输入提示 -->
    <div v-if="typingUsers.length > 0" class="typing-indicator">
      <span class="typing-dots">
        <span></span>
        <span></span>
        <span></span>
      </span>
      <span class="typing-text">{{ formatTypingUsers(typingUsers) }}</span>
    </div>

    <!-- 转发对话框 -->
    <div v-if="showForwardDialog" class="forward-dialog-overlay" @click="showForwardDialog = false">
      <div class="forward-dialog" @click.stop>
        <div class="forward-dialog-header">
          <h3>转发消息</h3>
          <button class="close-btn" @click="showForwardDialog = false">&times;</button>
        </div>
        <div class="forward-dialog-content">
          <p style="margin: 0 0 0.5rem; font-size: 0.875rem; color: var(--text-secondary);">
            选择要转发到的聊天室：
          </p>
          <ul class="forward-room-list">
            <li
              v-for="room in availableRoomsForForward"
              :key="room.id"
              class="forward-room-item"
              @click="doForward(room.id)"
            >
              <div class="room-name">{{ room.name }}</div>
              <div v-if="room.description" class="room-desc">{{ room.description }}</div>
            </li>
          </ul>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import type { Message, MemberDto, ChatRoom } from '@/types'
import { getBaseUrl } from '@/utils/config'
import { chatRoomApi } from '@/api/chatRoom'
import { useChatStore } from '@/stores/chat'

const props = defineProps<{
  messages: Message[]
  currentUserId?: string
  currentUserAvatar?: string
  typingUsers: string[]
  hasMoreMessages: boolean
  loadingMore: boolean
  roomMembers: MemberDto[]
}>()

const emit = defineEmits<{
  'load-more': []
  'scroll-to-message': [messageId: string]
  'resend-message': [content: string, attachments: any[]]
}>()

// 转发对话框状态
const showForwardDialog = ref(false)
const selectedMessage = ref<Message | null>(null)
const chatStore = useChatStore()

// 可用于转发的聊天室列表（排除当前聊天室）
const availableRoomsForForward = computed(() => {
  return chatStore.rooms.filter((room: ChatRoom) => room.id !== chatStore.currentRoom?.id)
})

// 执行转发
async function doForward(targetRoomId: string) {
  if (!selectedMessage.value) return

  try {
    const msg = selectedMessage.value
    // 构建转发消息内容
    const forwardContent = `转发自 ${msg.senderName}：\n${msg.content || ''}`

    // 调用 API 发送消息到目标聊天室
    await chatRoomApi.sendMessage(targetRoomId, {
      content: forwardContent,
      attachments: msg.attachments || []
    })

    showToast('转发成功')
    showForwardDialog.value = false
    selectedMessage.value = null
  } catch (error) {
    showToast('转发失败')
  }
}

const containerRef = ref<HTMLDivElement>()

// 暴露容器引用给父组件
defineExpose({ containerRef })

// ============ 消息操作 ============

// 复制消息内容
function copyMessage(msg: Message) {
  const textToCopy = msg.content || ''
  navigator.clipboard.writeText(textToCopy).then(() => {
    showToast('已复制到剪贴板')
  }).catch(() => {
    showToast('复制失败')
  })
}

// 转发消息
function forwardMessage(msg: Message) {
  showForwardDialog.value = true
  selectedMessage.value = msg
}

// 重发消息
async function resendMessage(msg: Message) {
  if (!msg.content) return
  try {
    await emit('resend-message', msg.content, msg.attachments || [])
    showToast('消息已重发')
  } catch (error) {
    showToast('重发失败')
  }
}

// 显示提示
function showToast(message: string) {
  const toast = document.createElement('div')
  toast.className = 'toast-message'
  toast.textContent = message
  document.body.appendChild(toast)
  setTimeout(() => {
    document.body.removeChild(toast)
  }, 2000)
}

// ============ 消息类型判断 ============

function isFlowbotResultMessage(msg: Message): boolean {
  return msg.senderName === 'Flowbot' &&
         !!(msg.attachments?.some(att => att.type === 'FLOWCHART_VARIABLES'))
}

function isMentionedMe(msg: Message): boolean {
  if (!props.currentUserId) return false
  if (msg.mentionAll) return true
  if (msg.mentions?.some(m => m.userId === props.currentUserId)) return true
  return false
}

// ============ 头像和格式化 ============

function getMessageAvatar(msg: Message): string | undefined {
  if (msg.senderId === props.currentUserId) {
    return props.currentUserAvatar || msg.senderAvatar
  }
  return msg.senderAvatar
}

function getInitials(name: string): string {
  return name.slice(0, 2).toUpperCase()
}

function formatTime(timestamp: string): string {
  const date = new Date(timestamp)
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function formatTypingUsers(users: string[]): string {
  if (users.length === 1) {
    return `${users[0]} 正在输入...`
  } else if (users.length === 2) {
    return `${users[0]} 和 ${users[1]} 正在输入...`
  } else {
    return `${users.slice(0, 2).join('、')} 等 ${users.length} 人正在输入...`
  }
}

// ============ 日期分隔线 ============

function shouldShowDateSeparator(index: number): boolean {
  if (index === 0) return true
  const current = new Date(props.messages[index].timestamp)
  const prev = new Date(props.messages[index - 1].timestamp)
  return !isSameDay(current, prev)
}

function formatDateSeparator(timestamp: string): string {
  const date = new Date(timestamp)
  const now = new Date()
  const yesterday = new Date(now)
  yesterday.setDate(yesterday.getDate() - 1)

  if (isSameDay(date, now)) {
    return '今天'
  } else if (isSameDay(date, yesterday)) {
    return '昨天'
  } else {
    return date.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric' })
  }
}

function isSameDay(d1: Date, d2: Date): boolean {
  return d1.getFullYear() === d2.getFullYear() &&
         d1.getMonth() === d2.getMonth() &&
         d1.getDate() === d2.getDate()
}

// ============ 工具调用渲染 ============

function generateToolCallsHtml(toolCalls: Message['toolCalls']): string {
  if (!toolCalls || toolCalls.length === 0) return ''

  return `<div class="tool-call-section">
    <div class="tool-call-list">
      ${toolCalls.map(tool => `
        <div class="tool-item ${tool.status || 'completed'}">
          <div class="tool-item-header">
            <span class="tool-icon-small">${getToolIcon(tool.name)}</span>
            <span class="tool-name"><code>${tool.name}</code></span>
          </div>
          ${tool.description ? `<div class="tool-item-body">
            <div class="tool-description">${formatToolDescription(tool.name, tool.description)}</div>
          </div>` : ''}
          ${tool.result ? `<div class="tool-item-body">
            <div class="tool-result"><pre>${escapeHtml(tool.result)}</pre></div>
          </div>` : ''}
        </div>
      `).join('')}
    </div>
  </div>`
}

function generateToolCallsHtmlFromArray(tools: Array<{name: string, desc: string}>): string {
  if (!tools || tools.length === 0) return ''

  return `<div class="tool-call-section">
    <div class="tool-call-list">
      ${tools.map(tool => `
        <div class="tool-item completed">
          <div class="tool-item-header">
            <span class="tool-icon-small">${getToolIcon(tool.name)}</span>
            <span class="tool-name"><code>${tool.name}</code></span>
          </div>
          ${tool.desc ? `<div class="tool-item-body"><div class="tool-description">${escapeHtml(tool.desc)}</div></div>` : ''}
        </div>
      `).join('')}
    </div>
  </div>`
}

function getToolIcon(toolName: string): string {
  const iconMap: Record<string, string> = {
    'read': '📄',
    'write': '✏️',
    'edit': '🔧',
    'exec': '⚡',
    'web_search': '🔍',
    'weather': '🌤️',
    'browser': '🌐',
    'canvas': '🎨',
    'nodes': '📱',
    'cron': '⏰',
    'message': '💬',
    'gateway': '🔌',
    'sessions_spawn': '🚀',
    'memory_search': '🧠',
    'tts': '🔊',
    'github': '🐙',
    'gh': '🐙',
  }
  return iconMap[toolName] || '🔧'
}

function formatToolDescription(toolName: string, description: string): string {
  if (!description) return ''
  
  if (toolName === 'exec' || toolName === ' Exec') {
    const cmdMatch = description.match(/command=["'](.+?)["']/s)
    if (cmdMatch) {
      const cmd = cmdMatch[1].replace(/\\"/g, '"').replace(/\\n/g, '\n')
      const displayCmd = cmd.length > 200 ? cmd.substring(0, 200) + '...' : cmd
      return `<div class="exec-command">
        <div class="exec-label">命令</div>
        <pre class="exec-code">${escapeHtml(displayCmd)}</pre>
      </div>`
    }
  }
  
  if (toolName === 'web_search') {
    const queryMatch = description.match(/query=["'](.+?)["']/)
    if (queryMatch) {
      return `<span class="search-query">🔍 ${escapeHtml(queryMatch[1])}</span>`
    }
  }
  
  if (['read', 'write', 'edit'].includes(toolName)) {
    const pathMatch = description.match(/path=["'](.+?)["']/)
    if (pathMatch) {
      return `<span class="file-path">📄 ${escapeHtml(pathMatch[1])}</span>`
    }
  }
  
  return escapeHtml(description)
}

function escapeHtml(text: string): string {
  const div = document.createElement('div')
  div.textContent = text
  return div.innerHTML
}

// ============ Markdown 渲染 ============

function renderMarkdown(content: string): string {
  try {
    const parsed = (marked as any).marked?.(content) || marked.parse(content, { async: false })
    const htmlContent = String(parsed)

    if (htmlContent === '[object Promise]' || !htmlContent.includes('<')) {
      throw new Error('Invalid parsed content')
    }
    return htmlContent
  } catch (e) {
    return content
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
}

function highlightMentions(htmlContent: string, msg: Message): string {
  htmlContent = htmlContent.replace(/(@所有人|@everyone|@all)/gi, '<span class="mention mention-all">$1</span>')
  htmlContent = htmlContent.replace(/(@在线|@here)/gi, '<span class="mention mention-here">$1</span>')
  htmlContent = htmlContent.replace(/(@openclaw)/gi, '<span class="mention">$1</span>')

  if (msg.mentions) {
    msg.mentions.forEach(mention => {
      const regex = new RegExp(`@${mention.userName}`, 'g')
      htmlContent = htmlContent.replace(regex, `<span class="mention">@${mention.userName}</span>`)
    })
  }

  props.roomMembers.forEach(member => {
    const displayName = member.nickname || member.username
    if (displayName && displayName !== 'openclaw') {
      const regex = new RegExp(`(?<!<span class="mention">)@${escapeRegExp(displayName)}`, 'g')
      htmlContent = htmlContent.replace(regex, `<span class="mention">@${displayName}</span>`)
    }
  })

  const currentUserName = props.roomMembers.find(m => m.id === props.currentUserId)?.nickname 
    || props.roomMembers.find(m => m.id === props.currentUserId)?.username
  if (currentUserName && currentUserName !== 'openclaw') {
    const regex = new RegExp(`(?<!<span class="mention">)@${escapeRegExp(currentUserName)}`, 'g')
    htmlContent = htmlContent.replace(regex, `<span class="mention">@${currentUserName}</span>`)
  }

  return htmlContent
}

function escapeRegExp(string: string): string {
  return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function renderAttachments(msg: Message): string {
  if (!msg.attachments || msg.attachments.length === 0) {
    return ''
  }

  return '<div class="message-attachments">' +
    msg.attachments.map(att => {
      const typeStr = (att.type || '').toUpperCase()
      const contentTypeStr = (att.contentType || '').toLowerCase()
      const urlStr = (att.url || '').toLowerCase()

      const isImage = typeStr === 'IMAGE' ||
                     contentTypeStr.startsWith('image/') ||
                     urlStr.startsWith('data:image/') ||
                     urlStr.endsWith('.png') ||
                     urlStr.endsWith('.jpg') ||
                     urlStr.endsWith('.jpeg') ||
                     urlStr.endsWith('.gif') ||
                     urlStr.endsWith('.webp')

      const fullUrl = resolveFileUrl(att.url || '')

      if (isImage) {
        return `<img src="${fullUrl}" alt="${att.name || '图片'}" class="message-image" loading="lazy" />`
      }
      return `<a href="${fullUrl}" target="_blank" class="message-file">${att.name || '附件'}</a>`
    }).join('') +
    '</div>'
}

function resolveFileUrl(url: string): string {
  if (url.startsWith('http://') || url.startsWith('https://')) {
    return url
  }
  const baseUrl = getBaseUrl()
  return baseUrl + url
}

// ============ 主渲染函数 ============

function renderContent(msg: Message): string {
  let content = msg.content || ''
  content = content.replace(/\\n/g, '\n').replace(/\\t/g, '\t')

  let toolCallsHtml = ''
  
  if (msg.toolCalls && msg.toolCalls.length > 0) {
    toolCallsHtml = generateToolCallsHtml(msg.toolCalls)
    
    const toolsMatch = content.match(/(\*\*Tools used:\*\*.*?)(?=\n\n|$)/s)
    if (toolsMatch) {
      content = content.replace(toolsMatch[0], '\n<!--TOOL_CALLS_PLACEHOLDER-->\n')
    } else {
      content = '<!--TOOL_CALLS_PLACEHOLDER-->\n\n' + content
    }
  } else {
    const toolsMatch = content.match(/(\*\*Tools used:\*\*.*?)(?=\n\n|$)/s)
    if (toolsMatch) {
      const toolsSection = toolsMatch[1]
      const toolLines = toolsSection.split('\n').slice(1)
      const tools: Array<{name: string, desc: string}> = []
      
      for (const line of toolLines) {
        const match = line.match(/^[-*]\s*`?(\w+)`?\s*:?\s*(.*)/)
        if (match) {
          tools.push({ name: match[1], desc: match[2] || '' })
        }
      }
      
      if (tools.length > 0) {
        toolCallsHtml = generateToolCallsHtmlFromArray(tools)
        content = content.replace(toolsMatch[0], '\n<!--TOOL_CALLS_PLACEHOLDER-->\n')
      }
    }
  }

  let htmlContent = renderMarkdown(content)

  htmlContent = DOMPurify.sanitize(htmlContent, {
    ALLOWED_TAGS: [
      'p', 'br', 'hr',
      'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
      'ul', 'ol', 'li',
      'strong', 'em', 'code', 'pre', 'blockquote',
      'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td',
      'del', 'ins', 'sup', 'sub',
      'div', 'span'
    ],
    ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'target', 'class']
  })

  if (toolCallsHtml) {
    if (htmlContent.includes('TOOL_CALLS_PLACEHOLDER')) {
      htmlContent = htmlContent.replace(/&lt;!--TOOL_CALLS_PLACEHOLDER--&gt;/g, toolCallsHtml)
      htmlContent = htmlContent.replace(/<!--TOOL_CALLS_PLACEHOLDER-->/g, toolCallsHtml)
    } else {
      htmlContent = toolCallsHtml + '\n' + htmlContent
    }
  }

  htmlContent = highlightMentions(htmlContent, msg)

  let attachmentsHtml = renderAttachments(msg)

  return htmlContent + attachmentsHtml
}

// ============ 段落式渲染（OpenClaw 消息） ============

function renderSegments(msg: Message): Array<{ type: 'text' | 'tools', html: string }> {
  const segments: Array<{ type: 'text' | 'tools', html: string }> = []
  
  if (!msg.content && (!msg.toolCalls || msg.toolCalls.length === 0)) {
    return segments
  }
  
  const sortedToolCalls = [...(msg.toolCalls || [])].sort((a, b) => {
    const posA = a.position ?? Infinity
    const posB = b.position ?? Infinity
    return posA - posB
  })
  
  let content = msg.content || ''
  content = content.replace(/\\n/g, '\n').replace(/\\t/g, '\t')
  const toolsMatch = content.match(/(\*\*Tools used:\*\*.*?)(?=\n\n|$)/s)
  if (toolsMatch) {
    content = content.replace(toolsMatch[0], '')
  }
  
  if (sortedToolCalls.length === 0 || sortedToolCalls[0].position === undefined) {
    if (msg.toolCalls?.length) {
      segments.push({
        type: 'tools',
        html: generateToolCallsHtml(msg.toolCalls)
      })
    }
    if (content.trim()) {
      let htmlContent = renderMarkdown(content)
      htmlContent = DOMPurify.sanitize(htmlContent, {
        ALLOWED_TAGS: ['p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'strong', 'em', 'code', 'pre', 'blockquote', 'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'del', 'ins', 'sup', 'sub', 'div', 'span'],
        ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'target', 'class']
      })
      htmlContent = highlightMentions(htmlContent, msg)
      htmlContent += renderAttachments(msg)
      segments.push({ type: 'text', html: htmlContent })
    }
    return segments
  }
  
  let lastPosition = 0
  
  for (const toolCall of sortedToolCalls) {
    const position = toolCall.position ?? 0
    
    if (position > lastPosition) {
      const textSegment = content.substring(lastPosition, position)
      if (textSegment.trim()) {
        let htmlContent = renderMarkdown(textSegment)
        htmlContent = DOMPurify.sanitize(htmlContent, {
          ALLOWED_TAGS: ['p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'strong', 'em', 'code', 'pre', 'blockquote', 'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'del', 'ins', 'sup', 'sub', 'div', 'span'],
          ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'target', 'class']
        })
        htmlContent = highlightMentions(htmlContent, msg)
        segments.push({ type: 'text', html: htmlContent })
      }
    }
    
    segments.push({
      type: 'tools',
      html: generateToolCallsHtml([toolCall])
    })
    
    lastPosition = position
  }
  
  if (lastPosition < content.length) {
    const textSegment = content.substring(lastPosition)
    if (textSegment.trim()) {
      let htmlContent = renderMarkdown(textSegment)
      htmlContent = DOMPurify.sanitize(htmlContent, {
        ALLOWED_TAGS: ['p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'strong', 'em', 'code', 'pre', 'blockquote', 'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'del', 'ins', 'sup', 'sub', 'div', 'span'],
        ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'target', 'class']
      })
      htmlContent = highlightMentions(htmlContent, msg)
      htmlContent += renderAttachments(msg)
      segments.push({ type: 'text', html: htmlContent })
    }
  }
  
  return segments
}
</script>

<style scoped>
.message-container {
  flex: 1;
  overflow-y: auto;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

/* 加载更多消息 */
.load-more-container {
  display: flex;
  justify-content: center;
  padding: 0.5rem 0;
  min-height: 40px;
}

.load-more-btn {
  padding: 0.5rem 1rem;
  background: var(--bg-color);
  border: 1px solid var(--border-color);
  border-radius: 20px;
  font-size: 0.875rem;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s;
}

.load-more-btn:hover {
  background: var(--surface-color);
  border-color: var(--primary-color);
  color: var(--primary-color);
}

.load-more-loading {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.loading-spinner {
  display: inline-block;
  width: 16px;
  height: 16px;
  border: 2px solid var(--border-color);
  border-top-color: var(--primary-color);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.message {
  display: flex;
  gap: 0.75rem;
  max-width: 80%;
  min-width: 0;
  align-self: flex-start;
}

.message.from-me {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.message.mentioned-me .message-body {
  background: #fef3c7;
  border: 2px solid #f59e0b;
}

.message.from-me.mentioned-me .message-body {
  background: var(--primary-color);
  border: 2px solid #f59e0b;
}

.message-avatar {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
}

.message-avatar img {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  object-fit: cover;
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  background: var(--primary-color);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.875rem;
  font-weight: 600;
}

.message-body {
  background: var(--bg-color);
  padding: 0.75rem 1rem;
  border-radius: 12px;
  min-width: 0;
  flex: 1;
}

.message.from-me .message-body {
  background: var(--primary-color);
  color: white;
}

.message-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.25rem;
  font-size: 0.75rem;
}

.message-id, .reply-to-id {
  font-size: 0.625rem;
  color: var(--text-secondary);
  background: var(--bg-color);
  padding: 1px 4px;
  border-radius: 3px;
  font-family: 'SF Mono', monospace;
  opacity: 0.7;
  cursor: help;
}

.message-id:hover, .reply-to-id:hover {
  opacity: 1;
}

.reply-to-id {
  background: #e0e7ff;
  color: #4f46e5;
}

.reply-to-id.clickable {
  cursor: pointer;
  transition: all 0.2s ease;
}

.reply-to-id.clickable:hover {
  background: #4f46e5;
  color: white;
  opacity: 1;
}

@keyframes message-highlight {
  0% {
    box-shadow: 0 0 0 0 rgba(79, 70, 229, 0.7);
  }
  50% {
    box-shadow: 0 0 0 8px rgba(79, 70, 229, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(79, 70, 229, 0);
  }
}

.highlight-message {
  animation: message-highlight 1s ease-out;
  border-radius: 12px;
}

.highlight-message .message-body {
  background: linear-gradient(135deg, #e0e7ff 0%, #c7d2fe 100%) !important;
  transition: background 0.3s ease;
}

.highlight-message.from-me .message-body {
  background: linear-gradient(135deg, #6366f1 0%, #4f46e5 100%) !important;
}

.message.from-me .message-id {
  background: rgba(255,255,255,0.2);
  color: rgba(255,255,255,0.9);
}

.message.from-me .reply-to-id {
  background: rgba(255,255,255,0.25);
  color: rgba(255,255,255,0.95);
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

.message.from-me .mention-tag.mention-all,
.message.from-me .mention-tag.mention-here {
  background: rgba(255,255,255,0.3);
  color: white;
}

.message-content {
  line-height: 1.5;
  word-break: break-word;
  overflow-wrap: break-word;
  max-width: 100%;
  min-width: 0;
}

.message-content :deep(pre) {
  max-width: 100%;
  width: 100%;
  overflow-x: auto;
  white-space: pre-wrap !important;
  word-wrap: break-word !important;
  word-break: break-all !important;
  box-sizing: border-box;
}

.message-content :deep(pre code) {
  white-space: pre-wrap !important;
  word-wrap: break-word !important;
  word-break: break-all !important;
  display: block;
  max-width: 100%;
}

.message-content :deep(code) {
  word-wrap: break-word;
  white-space: pre-wrap;
  word-break: break-all;
  max-width: 100%;
}

.message-content :deep(*) {
  max-width: 100%;
  box-sizing: border-box;
}

.message-content :deep(p),
.message-content :deep(div),
.message-content :deep(span) {
  max-width: 100%;
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

.message-content :deep(.message-image) {
  margin-top: 0.5rem;
  max-width: 100%;
  max-height: 300px;
  border-radius: 8px;
  cursor: pointer;
  transition: transform 0.2s;
  object-fit: contain;
}

.message-content :deep(.message-image:hover) {
  transform: scale(1.02);
}

.message-content :deep(.file-link) {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  background: var(--bg-color);
  border-radius: 6px;
  color: var(--primary-color);
  text-decoration: none;
  font-size: 0.875rem;
  margin-top: 0.25rem;
}

.message-content :deep(.file-link:hover) {
  background: rgba(59, 130, 246, 0.1);
}

.message.from-me .message-content :deep(.file-link) {
  background: rgba(255,255,255,0.2);
  color: white;
}

.mention-notice {
  font-size: 0.75rem;
  color: #f59e0b;
  margin-top: 0.5rem;
  font-weight: 500;
}

.message.from-me .mention-notice {
  color: rgba(255,255,255,0.9);
}

.empty-messages {
  text-align: center;
  color: var(--text-secondary);
  padding: 3rem 1rem;
}

.empty-messages span {
  display: block;
  margin-top: 0.5rem;
  font-size: 0.875rem;
}

/* Flowbot 消息样式 */
.flowbot-message {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border-radius: 12px;
  padding: 0.75rem 1rem;
  margin: 0.5rem 0;
  max-width: 85%;
  align-self: flex-start;
}

.flowbot-body {
  background: rgba(255, 255, 255, 0.15);
  border-radius: 8px;
  padding: 0.75rem;
}

.flowbot-sender {
  color: #fff !important;
  font-weight: 600;
}

.flowbot-content {
  color: #fff;
  white-space: pre-wrap;
}

.flowbot-content :deep(p) {
  color: #fff;
  margin: 0.5rem 0;
}

.flowbot-content :deep(strong) {
  color: #ffd700;
}

/* 系统消息 */
.system-message {
  text-align: center;
  padding: 0.5rem 1rem;
  margin: 0.5rem 0;
}

.system-text {
  font-size: 0.75rem;
  color: var(--text-secondary);
  background: var(--bg-color);
  padding: 0.25rem 0.75rem;
  border-radius: 12px;
}

/* 工具调用消息 */
.tool-call-message {
  background: var(--bg-color);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 1rem;
  margin: 0.5rem 1rem;
  max-width: 80%;
  align-self: flex-start;
}

.tool-call-message > .tool-call-header {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
  padding-bottom: 0.375rem;
  border-bottom: 1px solid var(--border-color);
}

:deep(.tool-call-section) {
  margin: 0.5rem 0;
}

:deep(.tool-call-list) {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

:deep(.tool-item) {
  background: white;
  border-radius: 12px;
  padding: 0;
  border: 1px solid #e5e7eb;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
  transition: all 0.2s ease;
  overflow: hidden;
}

:deep(.tool-item:hover) {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  transform: translateY(-2px);
}

:deep(.tool-item.running) {
  border-color: #3b82f6;
  box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.2);
  animation: tool-pulse 2s infinite;
}

@keyframes tool-pulse {
  0%, 100% {
    box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.2);
  }
  50% {
    box-shadow: 0 0 0 4px rgba(59, 130, 246, 0.3);
  }
}

:deep(.tool-item.completed) {
  border-color: #22c55e;
}

:deep(.tool-item.completed:hover) {
  box-shadow: 0 4px 12px rgba(34, 197, 94, 0.2);
}

:deep(.tool-item.error) {
  border-color: #ef4444;
  background: #fef2f2;
}

:deep(.tool-item.error:hover) {
  box-shadow: 0 4px 12px rgba(239, 68, 68, 0.2);
}

:deep(.tool-item-header) {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem;
  background: linear-gradient(135deg, #f8fafc, #f1f5f9);
  border-bottom: 1px solid #e5e7eb;
}

:deep(.tool-item.running .tool-item-header) {
  background: linear-gradient(135deg, #eff6ff, #dbeafe);
}

:deep(.tool-item.completed .tool-item-header) {
  background: linear-gradient(135deg, #f0fdf4, #dcfce7);
}

:deep(.tool-item.error .tool-item-header) {
  background: linear-gradient(135deg, #fef2f2, #fee2e2);
}

:deep(.tool-icon-small) {
  font-size: 1rem;
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: white;
  border-radius: 6px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
}

:deep(.tool-name) {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
  font-weight: 600;
  color: #1f2937;
  flex: 1;
}

:deep(.tool-name code) {
  background: rgba(0, 0, 0, 0.08);
  padding: 0.125rem 0.375rem;
  border-radius: 4px;
  font-family: 'SF Mono', monospace;
  font-size: 0.8rem;
  font-weight: 600;
  color: #4b5563;
}

:deep(.tool-item-body) {
  padding: 0.75rem;
}

:deep(.tool-description) {
  font-size: 0.8125rem;
  color: #6b7280;
  line-height: 1.5;
}

:deep(.tool-description .exec-command) {
  background: #f3f4f6;
  border-radius: 8px;
  padding: 0.5rem 0.75rem;
  margin-top: 0.25rem;
}

:deep(.tool-description .exec-label) {
  font-size: 0.7rem;
  color: #9ca3af;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 0.25rem;
  font-weight: 600;
}

:deep(.tool-description .exec-code) {
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Consolas', monospace;
  font-size: 0.8rem;
  color: #374151;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.4;
}

:deep(.tool-result) {
  margin-top: 0.5rem;
  padding: 0.5rem 0.75rem;
  background: #f9fafb;
  border-radius: 8px;
  border-left: 3px solid #d1d5db;
}

:deep(.tool-result pre) {
  font-family: 'SF Mono', monospace;
  font-size: 0.75rem;
  color: #4b5563;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 200px;
  overflow-y: auto;
}

/* OpenClaw 消息容器样式 */
.openclaw-message-container {
  display: flex;
  gap: 0.75rem;
  max-width: 80%;
  min-width: 0;
  align-self: flex-start;
}

.openclaw-message-container .openclaw-body {
  background: linear-gradient(135deg, #f0f9ff 0%, #e0f2fe 100%);
  border: 1px solid #bae6fd;
  border-radius: 12px;
  flex: 1;
  min-width: 0;
  padding: 0.75rem 1rem;
}

.openclaw-message-container .openclaw-body .message-content {
  padding: 0;
}

/* 时间分隔线 */
.date-separator {
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 1rem 0;
  position: relative;
}

.date-separator::before {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  height: 1px;
  background: var(--border-color);
}

.date-separator span {
  position: relative;
  background: var(--surface-color);
  padding: 0 1rem;
  font-size: 0.75rem;
  color: var(--text-secondary);
  z-index: 1;
}

/* 正在输入提示 */
.typing-indicator {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  margin-top: 0.5rem;
  color: var(--text-secondary);
  font-size: 0.875rem;
}

.typing-dots {
  display: flex;
  gap: 3px;
}

.typing-dots span {
  width: 6px;
  height: 6px;
  background: var(--text-secondary);
  border-radius: 50%;
  animation: typing-bounce 1.4s infinite ease-in-out both;
}

.typing-dots span:nth-child(1) {
  animation-delay: -0.32s;
}

.typing-dots span:nth-child(2) {
  animation-delay: -0.16s;
}

@keyframes typing-bounce {
  0%, 80%, 100% {
    transform: scale(0);
  }
  40% {
    transform: scale(1);
  }
}

.typing-text {
  font-style: italic;
}

/* ============================================
   移动端适配 - Mobile Responsive Styles
   ============================================ */

@media (max-width: 768px) {
  .message-container {
    padding: 0.75rem;
    gap: 0.75rem;
    padding-bottom: calc(0.75rem + env(safe-area-inset-bottom, 0px));
  }

  .message {
    max-width: 90%;
    gap: 0.5rem;
  }

  .message-avatar {
    width: 36px;
    height: 36px;
  }

  .avatar-placeholder {
    font-size: 0.8125rem;
  }

  .message-body {
    padding: 0.625rem 0.875rem;
    border-radius: 10px;
  }

  .message-header {
    font-size: 0.6875rem;
    gap: 0.375rem;
  }

  .mention-tag {
    font-size: 0.5625rem;
    padding: 1px 4px;
  }

  .message-content {
    font-size: 0.9375rem;
    line-height: 1.5;
  }

  .system-message {
    padding: 0.375rem 0;
  }

  .system-text {
    font-size: 0.6875rem;
    padding: 0.25rem 0.625rem;
  }

  .tool-call-message {
    max-width: 95%;
    padding: 0.75rem;
    margin: 0.375rem 0.5rem;
  }

  .date-separator {
    margin: 0.75rem 0;
  }

  .date-separator span {
    font-size: 0.6875rem;
    padding: 0 0.75rem;
  }

  .typing-indicator {
    padding: 0.375rem 0.75rem;
    font-size: 0.8125rem;
  }
}

/* 小屏手机额外优化 */
@media (max-width: 380px) {
  .message {
    max-width: 92%;
  }

  .message-avatar {
    width: 32px;
    height: 32px;
  }

  .message-body {
    padding: 0.5rem 0.75rem;
  }

  .message-content {
    font-size: 0.875rem;
  }
}

/* 横屏模式优化 */
@media (max-height: 500px) and (orientation: landscape) {
  .message-container {
    padding: 0.5rem;
  }
}

/* ============ 消息操作按钮 ============ */
.message-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-top: 0.5rem;
  padding-top: 0.5rem;
  border-top: 1px solid var(--border-color);
}

.action-btn {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.375rem 0.75rem;
  font-size: 0.75rem;
  color: var(--text-secondary);
  background: transparent;
  border: 1px solid var(--border-color);
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.action-btn:hover {
  color: var(--primary-color);
  background: var(--bg-color);
  border-color: var(--primary-color);
}

/* 移动端始终显示操作按钮 */
@media (max-width: 768px) {
  .message-actions {
    opacity: 1;
    gap: 0.375rem;
  }

  .action-btn {
    padding: 0.25rem 0.5rem;
    font-size: 0.6875rem;
  }
}

/* Toast 提示 */
.toast-message {
  position: fixed;
  bottom: 100px;
  left: 50%;
  transform: translateX(-50%);
  padding: 0.75rem 1.5rem;
  background: rgba(0, 0, 0, 0.8);
  color: white;
  border-radius: 8px;
  font-size: 0.875rem;
  z-index: 1000;
  animation: fadeInUp 0.3s ease;
}

@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateX(-50%) translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateX(-50%) translateY(0);
  }
}

/* 转发对话框 */
.forward-dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.forward-dialog {
  background: var(--surface-color);
  border-radius: 12px;
  width: 90%;
  max-width: 400px;
  max-height: 80vh;
  overflow: hidden;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
}

.forward-dialog-header {
  padding: 1rem 1.25rem;
  border-bottom: 1px solid var(--border-color);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.forward-dialog-header h3 {
  margin: 0;
  font-size: 1rem;
  color: var(--text-primary);
}

.close-btn {
  background: none;
  border: none;
  font-size: 1.5rem;
  color: var(--text-secondary);
  cursor: pointer;
  padding: 0;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  transition: background 0.2s;
}

.close-btn:hover {
  background: var(--bg-color);
}

.forward-dialog-content {
  padding: 1rem;
  max-height: 300px;
  overflow-y: auto;
}

.forward-room-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.forward-room-item {
  padding: 0.75rem;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
  margin-bottom: 0.5rem;
}

.forward-room-item:hover {
  background: var(--bg-color);
}

.forward-room-item .room-name {
  font-weight: 500;
  color: var(--text-primary);
}

.forward-room-item .room-desc {
  font-size: 0.75rem;
  color: var(--text-secondary);
  margin-top: 0.25rem;
}
</style>
