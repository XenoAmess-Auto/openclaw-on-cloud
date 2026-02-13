#&# ChatView Markdown 渲染自测脚本

## 快速开始

### 1. 运行单元测试

```bash
# 在 frontend 目录下
pnpm test src/views/__tests__/ChatView.markdown.spec.ts
```

### 2. 浏览器端实时测试

打开浏览器开发者控制台(F12)，复制粘贴以下代码测试 Markdown 渲染：

```javascript
// === ChatView Markdown 渲染自测脚本 ===

// 模拟 marked 和 DOMPurify（简化版）
const testRenderer = {
  // 简化的 Markdown 解析
  parseMarkdown(text) {
    return text
      .replace(/^### (.+)$/gm, '<h3>$1</h3>')
      .replace(/^## (.+)$/gm, '<h2>$1</h2>')
      .replace(/^# (.+)$/gm, '<h1>$1</h1>')
      .replace(/^\*\*\*(.+?)\*\*\*/gm, '<strong><em>$1</em></strong>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/\*([^*]+)\*/g, '<em>$1</em>')
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/^- (.+)$/gm, '<li>$1</li>')
      .replace(/^\|(.+)\|$/gm, (match, p1) => {
        const cells = p1.split('|').map(c => `<td>${c.trim()}</td>`).join('')
        return `<tr>${cells}</tr>`
      })
      .replace(/\n/g, '<br>')
  },

  // 简化的 XSS 清理
  sanitize(html) {
    // 只允许特定标签
    const allowed = ['p', 'br', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 
                     'strong', 'em', 'code', 'pre', 'blockquote', 'a', 'img', 
                     'table', 'thead', 'tbody', 'tr', 'th', 'td', 'span']
    return html.replace(/&lt;(\/?)(\w+)([^&]*)&gt;/g, (match, slash, tag, attrs) => {
      if (allowed.includes(tag.toLowerCase())) {
        return `<${slash}${tag}${attrs.replace(/on\w+=/gi, 'data-blocked-')}>`
      }
      return match
    })
  },

  // 主渲染函数（复刻 ChatView.vue 的 renderContent）
  renderContent(msg) {
    let content = msg.content || ''

    // Step 1: 保护 @提及
    const mentionPlaceholders = []
    content = content.replace(/(@所有人|@everyone|@all|@在线|@here|@openclaw|@[^\s]+)/gi, (match) => {
      mentionPlaceholders.push(match)
      return `\u0000MENTION_${mentionPlaceholders.length - 1}\u0000`
    })

    // Step 2: 渲染 Markdown
    let htmlContent = this.parseMarkdown(content)
    htmlContent = this.sanitize(htmlContent)

    // Step 3: 恢复 @提及
    htmlContent = htmlContent.replace(/\u0000MENTION_(\d+)\u0000/g, (_, index) => {
      const mention = mentionPlaceholders[parseInt(index)]
      if (!mention) return ''
      
      let className = 'mention'
      if (/@所有人|@everyone|@all/i.test(mention)) className += ' mention-all'
      else if (/@在线|@here/i.test(mention)) className += ' mention-here'
      
      return `<span class="${className}">${mention}</span>`
    })

    return htmlContent
  }
}

// 测试用例
const testCases = [
  {
    name: '基础标题',
    input: '# Hello World',
    expectContains: ['<h1>Hello World</h1>']
  },
  {
    name: '二级标题',
    input: '## Section Title',
    expectContains: ['<h2>Section Title</h2>']
  },
  {
    name: '粗体和斜体',
    input: '**bold** and *italic*',
    expectContains: ['<strong>bold</strong>', '<em>italic</em>']
  },
  {
    name: '行内代码',
    input: 'Use `console.log()`',
    expectContains: ['<code>console.log()</code>']
  },
  {
    name: '@所有人高亮',
    input: '@所有人 请注意',
    expectContains: ['<span class="mention mention-all">@所有人</span>']
  },
  {
    name: '@在线高亮',
    input: '@在线 用户',
    expectContains: ['<span class="mention mention-here">@在线</span>']
  },
  {
    name: '@openclaw 高亮',
    input: '@openclaw 你好',
    expectContains: ['<span class="mention">@openclaw</span>']
  },
  {
    name: '混合 Markdown 和提及',
    input: '## 标题\n\n@所有人 **重要通知**',
    expectContains: ['<h2>标题</h2>', '<span class="mention mention-all">@所有人</span>', '<strong>重要通知</strong>']
  },
  {
    name: '复杂消息（模拟 OpenClaw 回复）',
    input: `## 1. 会话状态信息

| 属性 | 值 |
|------|-----|
| Runtime | agent=main |

@所有人 请注意查看`,
    expectContains: ['<h2>1. 会话状态信息</h2>', '<span class="mention mention-all">@所有人</span>']
  }
]

// 运行测试
function runTests() {
  console.log('%c=== ChatView Markdown 渲染自测 ===', 'color: #3b82f6; font-size: 16px; font-weight: bold')
  console.log('')
  
  let passed = 0
  let failed = 0
  
  testCases.forEach((test, index) => {
    const result = testRenderer.renderContent({ content: test.input })
    const success = test.expectContains.every(expected => result.includes(expected))
    
    if (success) {
      passed++
      console.log(`%c✓ ${test.name}`, 'color: #22c55e')
    } else {
      failed++
      console.log(`%c✗ ${test.name}`, 'color: #ef4444')
      console.log('  输入:', test.input.substring(0, 100))
      console.log('  输出:', result.substring(0, 200))
      console.log('  期望包含:', test.expectContains)
    }
  })
  
  console.log('')
  console.log(`%c结果: ${passed} 通过, ${failed} 失败`, failed > 0 ? 'color: #ef4444' : 'color: #22c55e')
  
  return { passed, failed, total: testCases.length }
}

// 运行测试并返回结果
runTests()
```

## 手动测试步骤

### 步骤 1: 启动前端开发服务器

```bash
cd /home/xenoamess/.openclaw/workspace/openclaw-on-cloud/frontend
pnpm dev
```

### 步骤 2: 打开浏览器访问

http://localhost:3000

### 步骤 3: 登录并进入聊天室

### 步骤 4: @openclaw 发送测试消息

复制以下测试消息：

```
@openclaw 回复复杂信息测试
```

### 步骤 5: 验证渲染结果

预期应显示：
- [ ] 标题层级正确（## 显示为 h2）
- [ ] 表格渲染正确
- [ ] 代码块有深色背景
- [ ] 列表项正确缩进
- [ ] @提及高亮显示
- [ ] 粗体、斜体样式正确

## 测试检查清单

- [ ] Markdown 标题（# ## ###）渲染正常
- [ ] 粗体（**text**）渲染正常  
- [ ] 斜体（*text*）渲染正常
- [ ] 行内代码（`code`）渲染正常
- [ ] 代码块（```language\ncode\n```）渲染正常
- [ ] 无序列表（- item）渲染正常
- [ ] 有序列表（1. item）渲染正常
- [ ] 表格（| col | col |）渲染正常
- [ ] 引用块（> text）渲染正常
- [ ] 分隔线（---）渲染正常
- [ ] 链接（[text](url)）渲染正常
- [ ] @所有人 高亮显示
- [ ] @在线 高亮显示
- [ ] @openclaw 高亮显示
- [ ] 普通用户提及高亮显示
- [ ] XSS 攻击被阻止（如 <script>alert(1)</script> 不执行）
- [ ] 附件图片正确显示

## 常见问题排查

### 问题: Markdown 未渲染，显示为纯文本

**检查点:**
1. 确认 `marked` 库已安装：`pnpm list marked`
2. 检查浏览器控制台是否有 import 错误
3. 确认 `renderContent` 函数被正确调用

### 问题: HTML 标签被转义显示

**原因:** 旧的 `renderContent` 先执行了 HTML 转义
**解决:** 确保新代码先渲染 Markdown 再处理 XSS

### 问题: @提及未高亮

**检查点:**
1. 确认 placeholder 替换逻辑正确
2. 检查 CSS 样式 `.mention` 是否定义

### 问题: 表格样式错乱

**检查点:**
1. 确认 CSS 中定义了 table、th、td 样式
2. 检查 `DOMPurify.sanitize` 允许了 table 相关标签

## 回归测试

修复后需要验证的功能：

1. **纯文本消息** - 正常显示
2. **带换行的消息** - 换行符转换为 <br>
3. **@提及消息** - 提及高亮
4. **图片附件** - 正确显示缩略图
5. **混合内容** - Markdown + 提及 + 附件
