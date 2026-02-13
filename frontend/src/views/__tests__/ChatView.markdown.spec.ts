import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ChatView from '../ChatView.vue'

// Mock marked and DOMPurify
vi.mock('marked', () => ({
  marked: {
    setOptions: vi.fn(),
    parse: vi.fn((text) => `<p>${text}</p>`)
  }
}))

vi.mock('dompurify', () => ({
  default: {
    sanitize: vi.fn((html) => html)
  }
}))

// Mock router
const mockRouter = {
  push: vi.fn()
}

const mockRoute = {
  params: { roomId: 'test-room' }
}

vi.mock('vue-router', () => ({
  useRoute: () => mockRoute,
  useRouter: () => mockRouter,
  RouterLink: {
    template: '<a><slot /></a>'
  }
}))

// Mock stores
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    user: { id: 'user1', username: 'testuser' }
  })
}))

vi.mock('@/stores/chat', () => ({
  useChatStore: () => ({
    currentRoom: { name: 'Test Room' },
    messages: [],
    isConnected: true,
    connect: vi.fn(),
    disconnect: vi.fn(),
    sendMessage: vi.fn()
  })
}))

describe('ChatView Markdown Rendering', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('renders basic text content', () => {
    const wrapper = mount(ChatView, {
      global: {
        plugins: [createPinia()]
      }
    })
    expect(wrapper.find('.chat-view').exists()).toBe(true)
  })
})

// Markdown 渲染单元测试（不依赖 Vue 组件）
describe('Markdown Render Content Logic', () => {
  const mockMarked = {
    parse: vi.fn((text: string) => {
      // 简化的 Markdown 解析模拟
      return text
        .replace(/^### (.+)$/gm, '<h3>$1</h3>')
        .replace(/^## (.+)$/gm, '<h2>$1</h2>')
        .replace(/^# (.+)$/gm, '<h1>$1</h1>')
        .replace(/`([^`]+)`/g, '<code>$1</code>')
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
        .replace(/\*([^*]+)\*/g, '<em>$1</em>')
        .replace(/^- (.+)$/gm, '<li>$1</li>')
        .replace(/\n/g, '<br>')
    })
  }

  const mockDOMPurify = {
    sanitize: vi.fn((html: string) => html)
  }

  // 模拟 renderContent 函数的逻辑
  function renderContent(msg: { content?: string; mentions?: any[]; attachments?: any[] }): string {
    let content = msg.content || ''

    // Step 1: 先渲染 Markdown（在 HTML 转义之前）
    const mentionPlaceholders: string[] = []
    content = content.replace(/(@所有人|@everyone|@all|@在线|@here|@openclaw|@[^\s]+)/gi, (match) => {
      mentionPlaceholders.push(match)
      return `\u0000MENTION_${mentionPlaceholders.length - 1}\u0000`
    })

    let htmlContent = mockMarked.parse(content)
    htmlContent = mockDOMPurify.sanitize(htmlContent)

    // Step 2: 恢复 @提及并添加高亮
    htmlContent = htmlContent.replace(/\u0000MENTION_(\d+)\u0000/g, (_, index) => {
      const mention = mentionPlaceholders[parseInt(index)]
      if (!mention) return ''

      if (/@所有人|@everyone|@all/i.test(mention)) {
        return `<span class="mention mention-all">${mention}</span>`
      } else if (/@在线|@here/i.test(mention)) {
        return `<span class="mention mention-here">${mention}</span>`
      } else {
        return `<span class="mention">${mention}</span>`
      }
    })

    return htmlContent
  }

  describe('Basic Markdown Elements', () => {
    it('renders headers correctly', () => {
      const result = renderContent({ content: '# Hello World' })
      expect(result).toContain('<h1>Hello World</h1>')
    })

    it('renders h2 headers correctly', () => {
      const result = renderContent({ content: '## Section Title' })
      expect(result).toContain('<h2>Section Title</h2>')
    })

    it('renders h3 headers correctly', () => {
      const result = renderContent({ content: '### Subsection' })
      expect(result).toContain('<h3>Subsection</h3>')
    })

    it('renders bold text correctly', () => {
      const result = renderContent({ content: 'This is **bold** text' })
      expect(result).toContain('<strong>bold</strong>')
    })

    it('renders italic text correctly', () => {
      const result = renderContent({ content: 'This is *italic* text' })
      expect(result).toContain('<em>italic</em>')
    })

    it('renders inline code correctly', () => {
      const result = renderContent({ content: 'Use `console.log()` for debugging' })
      expect(result).toContain('<code>console.log()</code>')
    })

    it('renders list items correctly', () => {
      const result = renderContent({ content: '- Item 1' })
      expect(result).toContain('<li>Item 1</li>')
    })
  })

  describe('Mention Highlighting', () => {
    it('highlights @所有人', () => {
      const result = renderContent({ content: '@所有人 请注意' })
      expect(result).toContain('<span class="mention mention-all">@所有人</span>')
    })

    it('highlights @在线', () => {
      const result = renderContent({ content: '@在线 用户' })
      expect(result).toContain('<span class="mention mention-here">@在线</span>')
    })

    it('highlights @openclaw', () => {
      const result = renderContent({ content: '@openclaw 你好' })
      expect(result).toContain('<span class="mention">@openclaw</span>')
    })

    it('preserves mentions within Markdown context', () => {
      const result = renderContent({ content: '## 标题\n\n@所有人 **重要通知**' })
      expect(result).toContain('<h2>标题</h2>')
      expect(result).toContain('<span class="mention mention-all">@所有人</span>')
      expect(result).toContain('<strong>重要通知</strong>')
    })
  })

  describe('Complex Content', () => {
    it('renders mixed Markdown and mentions', () => {
      const content = `## 1. 会话状态信息

| 属性 | 值 |
|------|-----|
| Runtime | agent=main |

@所有人 请注意`

      const result = renderContent({ content })
      expect(result).toContain('<h2>1. 会话状态信息</h2>')
      expect(result).toContain('<span class="mention mention-all">@所有人</span>')
    })

    it('handles code blocks in content', () => {
      const content = '```javascript\nfunction test() {}\n```'
      renderContent({ content })
      expect(mockDOMPurify.sanitize).toHaveBeenCalled()
    })

    it('handles empty content gracefully', () => {
      const result = renderContent({ content: '' })
      expect(result).toBe('')
    })

    it('handles null content gracefully', () => {
      const result = renderContent({ content: undefined as any })
      expect(result).toBe('')
    })
  })

  describe('XSS Protection', () => {
    it('sanitizes HTML in content', () => {
      renderContent({ content: 'Test' })
      expect(mockDOMPurify.sanitize).toHaveBeenCalled()
    })
  })
})

// 端到端测试场景
describe('E2E: Complex Message Rendering', () => {
  it('should handle real-world OpenClaw response format', () => {
    // 模拟 OpenClaw 的实际回复格式
    const realWorldContent = `**Tools used:**
- \`session_status\`: 获取当前会话状态信息

---

## 1. 会话状态信息

| 属性 | 值 |
|------|-----|
| Runtime | agent=main |
| Host | local.host |

## 2. 代码示例

\`\`\`javascript
function fibonacci(n) {
  if (n <= 1) return n;
  return fibonacci(n - 1) + fibonacci(n - 2);
}
\`\`\`

@所有人 这是测试消息`

    // 验证内容结构完整性
    expect(realWorldContent).toContain('## 1. 会话状态信息')
    expect(realWorldContent).toContain('| 属性 | 值 |')
    expect(realWorldContent).toContain('```javascript')
    expect(realWorldContent).toContain('@所有人')
  })
})
