import {marked} from 'marked';
import fs from 'fs';
import path from 'path';
import {fileURLToPath} from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// 模拟 ChatView.vue 中的 renderContent 逻辑（仅 Markdown 部分）
function renderMarkdown(content) {
  // 处理转义字符
  content = content.replace(/\\n/g, '\n').replace(/\\t/g, '\t');
  
  // 渲染 Markdown
  const htmlContent = marked.parse(content, { async: false });
  
  return htmlContent;
}

// 验证 DOMPurify 配置是否正确
function verifyDOMPurifyConfig() {
  const chatViewPath = path.join(process.cwd(), 'src/views/ChatView.vue');
  const content = fs.readFileSync(chatViewPath, 'utf-8');
  
  // 检查 ALLOWED_ATTR 是否包含 'class'
  const allowedAttrMatch = content.match(/ALLOWED_ATTR:\s*\[([^\]]+)\]/);
  if (!allowedAttrMatch) {
    console.log('❌ 未找到 ALLOWED_ATTR 配置');
    return false;
  }
  
  const hasClass = allowedAttrMatch[1].includes("'class'");
  console.log(`  ALLOWED_ATTR 配置: [${allowedAttrMatch[1].trim()}]`);
  console.log(`  包含 'class': ${hasClass ? '✅ 是' : '❌ 否'}`);
  
  return hasClass;
}

// 测试用例 - 模拟 OpenClaw 返回
const testCases = [
  {
    name: "代码块 class 生成测试",
    input: "```javascript\nconst x = 1;\n```",
    expectedClass: "language-javascript",
    check: (html) => {
      const hasPre = html.includes('<pre>');
      const hasCode = html.includes('<code');
      const hasClass = html.includes('class="language-javascript"');
      console.log(`    检查: <pre>=${hasPre}, <code=${hasCode}, class=${hasClass}`);
      console.log(`    输出: ${html}`);
      return hasPre && hasCode && hasClass;
    }
  },
  {
    name: "bash 代码块",
    input: "```bash\necho hello\n```",
    expectedClass: "language-bash",
    check: (html) => {
      const hasClass = html.includes('class="language-bash"');
      console.log(`    检查: class="language-bash"=${hasClass}`);
      console.log(`    输出: ${html}`);
      return hasClass;
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
      console.log(`    输出: ${html.substring(0, 200)}...`);
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
      console.log(`    输出: ${html.substring(0, 200)}...`);
      return Object.values(checks).every(v => v);
    }
  }
];

console.log("=== OpenClaw 返回内容 Markdown 渲染自测 ===\n");

// 先验证配置
console.log("步骤 1: 验证 DOMPurify 配置...");
const configOk = verifyDOMPurifyConfig();
console.log();

console.log("步骤 2: Markdown 渲染测试...\n");

let passCount = 0;
let failCount = 0;

testCases.forEach((test, index) => {
  console.log(`测试 ${index + 1}: ${test.name}`);
  
  const html = renderMarkdown(test.input);
  const passed = test.check(html);
  
  console.log(`  结果: ${passed ? '✅ 通过' : '❌ 失败'}`);
  
  if (!passed) {
    failCount++;
  } else {
    passCount++;
  }
  console.log();
});

console.log("=".repeat(50));
console.log(`配置检查: ${configOk ? '✅ 通过' : '❌ 失败'}`);
console.log(`渲染测试: ${passCount} 通过, ${failCount} 失败`);

if (!configOk || failCount > 0) {
  console.log("\n❌ 自测未通过");
  process.exit(1);
} else {
  console.log("\n✅ 所有自测通过！");
  console.log("   - Markdown 渲染工作正常");
  console.log("   - class 属性已正确生成");
  console.log("   - DOMPurify 配置已包含 class 属性保留");
}
