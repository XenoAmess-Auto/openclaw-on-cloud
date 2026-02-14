import {marked} from 'marked';

// 模拟 OpenClaw 典型返回的 Markdown 内容
const testCases = [
  {
    name: "粗体和斜体",
    input: "**粗体文字** 和 *斜体文字*",
    expectedContains: ["<strong>", "<em>"]
  },
  {
    name: "标题",
    input: "# 一级标题\n## 二级标题\n### 三级标题",
    expectedContains: ["<h1>", "<h2>", "<h3>"]
  },
  {
    name: "列表",
    input: "- 项目1\n- 项目2\n- 项目3",
    expectedContains: ["<ul>", "<li>"]
  },
  {
    name: "有序列表",
    input: "1. 第一项\n2. 第二项\n3. 第三项",
    expectedContains: ["<ol>", "<li>"]
  },
  {
    name: "代码块",
    input: "```javascript\nconst x = 1;\nconsole.log(x);\n```",
    expectedContains: ["<pre>", "<code", "language-javascript"]
  },
  {
    name: "引用块",
    input: "> 这是引用文本\n> 多行引用",
    expectedContains: ["<blockquote>"]
  },
  {
    name: "行内代码",
    input: "使用 `console.log()` 输出",
    expectedContains: ["<code>console.log()</code>"]
  },
  {
    name: "链接",
    input: "[点击这里](https://example.com)",
    expectedContains: ['<a href="https://example.com">', "点击这里</a>"]
  },
  {
    name: "水平分隔线",
    input: "---",
    expectedContains: ["<hr>"]
  },
  {
    name: "综合内容（类似 OpenClaw 返回）",
    input: `**Tools used:**
- \`exec\`: 执行命令

\`\`\`bash
$ curl -s "wttr.in/Beijing?format=3"
Beijing: +15°C\n\`\`\`

天气查询完成！`,
    expectedContains: ["<strong>", "<ul>", "<li>", "<code>", "<pre>", "bash"]
  }
];

console.log("=== OpenClaw Markdown 渲染自测 ===\n");

let passCount = 0;
let failCount = 0;

testCases.forEach((test, index) => {
  const html = marked.parse(test.input, { async: false });
  const passed = test.expectedContains.every(expected => html.includes(expected));
  
  console.log(`测试 ${index + 1}: ${test.name}`);
  console.log(`  输入: ${test.input.substring(0, 50).replace(/\n/g, '\\n')}${test.input.length > 50 ? '...' : ''}`);
  console.log(`  输出: ${html.substring(0, 80).replace(/\n/g, '')}...`);
  console.log(`  期望包含: ${test.expectedContains.join(', ')}`);
  console.log(`  结果: ${passed ? '✅ 通过' : '❌ 失败'}`);
  
  if (!passed) {
    console.log(`  ❌ 实际输出: ${html}`);
    failCount++;
  } else {
    passCount++;
  }
  console.log();
});

console.log("=".repeat(40));
console.log(`总计: ${passCount} 通过, ${failCount} 失败`);

if (failCount > 0) {
  process.exit(1);
}
