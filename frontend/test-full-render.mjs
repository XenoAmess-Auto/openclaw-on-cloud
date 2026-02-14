import {marked} from 'marked';
import DOMPurify from 'dompurify';
import {JSDOM} from 'jsdom';

const window = new JSDOM('').window;
const purify = DOMPurify(window);

// 模拟 ChatView.vue 中的 renderContent 逻辑
function renderContent(content) {
  // 处理转义字符
  content = content.replace(/\\n/g, '\n').replace(/\\t/g, '\t');
  
  // 渲染 Markdown
  let htmlContent = marked.parse(content, { async: false });
  
  // XSS 清理 - 与 ChatView.vue 相同的配置
  htmlContent = purify.sanitize(htmlContent, {
    ALLOWED_TAGS: [
      'p', 'br', 'hr',
      'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
      'ul', 'ol', 'li',
      'strong', 'em', 'code', 'pre', 'blockquote',
      'a', 'img', 'table', 'thead', 'tbody', 'tr', 'th', 'td',
      'del', 'ins', 'sup', 'sub'
    ],
    ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'target', 'class']  // 关键：包含 class
  });
  
  return htmlContent;
}

// 测试用例 - 模拟 OpenClaw 返回
const testCases = [
  {
    name: "代码块 class 保留测试",
    input: "```javascript\nconst x = 1;\n```",
    check: (html) => {
      const hasPre = html.includes('<pre>');
      const hasCode = html.includes('<code');
      const hasClass = html.includes('class="language-javascript"');
      console.log(`    检查: <pre>=${hasPre}, <code=${hasCode}, class=${hasClass}`);
      return hasPre && hasCode && hasClass;
    }
  },
  {
    name: "工具调用返回内容",
    input: `**Tools used:**
- \`web_search\`: 搜索信息

\`\`\`json
{"result": "success"}
\`\`\`

查询结果：找到相关信息。`,
    check: (html) => {
      const hasStrong = html.includes('<strong>');
      const hasUl = html.includes('<ul>');
      const hasCode = html.includes('<code');
      const hasPre = html.includes('<pre>');
      const hasClass = html.includes('class="language-json"');
      console.log(`    检查: strong=${hasStrong}, ul=${hasUl}, code=${hasCode}, pre=${hasPre}, class=${hasClass}`);
      return hasStrong && hasUl && hasCode && hasPre && hasClass;
    }
  },
  {
    name: "复杂 Markdown 混合",
    input: `# 标题

**粗体** 和 *斜体*

- 列表项1
- 列表项2

> 引用内容

\`\`\`bash
echo "hello"
\`\`\`

[链接](https://example.com)`,
    check: (html) => {
      const checks = {
        h1: html.includes('<h1>'),
        strong: html.includes('<strong>'),
        em: html.includes('<em>'),
        ul: html.includes('<ul>'),
        blockquote: html.includes('<blockquote>'),
        pre: html.includes('<pre>'),
        codeClass: html.includes('class="language-bash"'),
        a: html.includes('<a href=')
      };
      console.log(`    检查: ${JSON.stringify(checks)}`);
      return Object.values(checks).every(v => v);
    }
  }
];

console.log("=== OpenClaw 返回内容 Markdown + DOMPurify 渲染自测 ===\n");

let passCount = 0;
let failCount = 0;

testCases.forEach((test, index) => {
  const html = renderContent(test.input);
  const passed = test.check(html);
  
  console.log(`测试 ${index + 1}: ${test.name}`);
  console.log(`  输入: ${test.input.substring(0, 60).replace(/\n/g, '\\n')}...`);
  console.log(`  输出: ${html.substring(0, 120).replace(/\n/g, '')}...`);
  console.log(`  结果: ${passed ? '✅ 通过' : '❌ 失败'}`);
  
  if (!passed) {
    console.log(`  ❌ 完整输出: ${html}`);
    failCount++;
  } else {
    passCount++;
  }
  console.log();
});

console.log("=".repeat(50));
console.log(`总计: ${passCount} 通过, ${failCount} 失败`);

if (failCount > 0) {
  process.exit(1);
} else {
  console.log("\n✅ 所有测试通过！Markdown 渲染和 DOMPurify 清理工作正常。");
  console.log("   class 属性已正确保留，代码块样式将正常显示。");
}
