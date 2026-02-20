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
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import type { Message, MemberDto } from '@/types'
import { getBaseUrl } from '@/utils/config'

const props = defineProps<{
  messages: Message[]
  currentUserId?: string
  currentUserAvatar?: string
  typingUsers: string[]
  hasMoreMessages: boolean
  loadingMore: boolean
  roomMembers: MemberDto[]
}>()

defineEmits<{
  'load-more': []
  'scroll-to-message': [messageId: string]
}>()

const containerRef = ref<HTMLDivElement>()

// 暴露容器引用给父组件
defineExpose({ containerRef })

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
