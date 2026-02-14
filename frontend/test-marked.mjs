import {marked} from 'marked';

// 测试 renderContent 的核心逻辑
function testMarkedParse() {
  const testCases = [
    {
      name: "基本粗体",
      input: "**粗体文字**",
      shouldContain: ["<strong>", "</strong>"]
    },
    {
      name: "基本斜体", 
      input: "*斜体文字*",
      shouldContain: ["<em>", "</em>"]
    },
    {
      name: "代码块",
      input: "```javascript\nconst x = 1;\n```",
      shouldContain: ["<pre>", "<code", "language-javascript"]
    },
    {
      name: "列表",
      input: "- 项目1\n- 项目2",
      shouldContain: ["<ul>", "<li>"]
    }
  ];
  
  console.log("=== Markdown 渲染自测 ===\n");
  
  let pass = 0;
  let fail = 0;
  
  testCases.forEach(tc => {
    try {
      // 模拟 renderContent 中的解析逻辑
      const parsed = marked.parse(tc.input, { async: false });
      const html = String(parsed);
      
      const success = tc.shouldContain.every(s => html.includes(s));
      
      if (success) {
        console.log(`✅ ${tc.name}: 通过`);
        console.log(`   输出: ${html.substring(0, 80)}...`);
        pass++;
      } else {
        console.log(`❌ ${tc.name}: 失败`);
        console.log(`   期望包含: ${tc.shouldContain.join(', ')}`);
        console.log(`   实际输出: ${html}`);
        fail++;
      }
    } catch (e) {
      console.log(`❌ ${tc.name}: 异常`);
      console.log(`   错误: ${e}`);
      fail++;
    }
    console.log();
  });
  
  console.log(`总计: ${pass} 通过, ${fail} 失败`);
  return fail === 0;
}

// 运行测试
const ok = testMarkedParse();
process.exit(ok ? 0 : 1);
