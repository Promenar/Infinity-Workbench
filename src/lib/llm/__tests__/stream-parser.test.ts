/**
 * StreamParser 单元测试
 * 测试 LLM 流式输出的解析功能
 */

import { StreamParser } from '../stream-parser';

describe('StreamParser', () => {
  let parser: StreamParser;

  beforeEach(() => {
    parser = new StreamParser('openai');
  });

  describe('纯文本解析', () => {
    it('应正确解析纯文本内容', () => {
      const result = parser.process('Hello, world!');
      expect(result.content).toBe('Hello, world!');
      expect(result.toolCalls).toBeUndefined();
    });

    it('应正确处理多段文本', () => {
      // StreamParser 使用增量解析，需要累计所有 process() 的输出
      const r1 = parser.process('Hello');
      const r2 = parser.process(', world!');
      // 合并所有调用的输出
      const allContent = r1.content + r2.content;
      expect(allContent).toContain('Hello');
      expect(allContent).toContain('world');
    });

    it('应正确处理空 chunk', () => {
      const result = parser.process('');
      expect(result.content).toBe('');
    });

    it('应正确处理中文内容', () => {
      const result = parser.process('你好，世界！这是一段测试文本。');
      expect(result.content).toBe('你好，世界！这是一段测试文本。');
    });
  });

  describe('代码块解析', () => {
    it('应正确解析 markdown 代码块', () => {
      const result = parser.process('```typescript\nconst x = 1;\n```');
      expect(result.content).toContain('```typescript');
      expect(result.content).toContain('const x = 1;');
      expect(result.content).toContain('```');
    });

    it('应正确解析行内代码', () => {
      const result = parser.process('Use `console.log()` to debug.');
      expect(result.content).toContain('`console.log()`');
    });

    it('应正确处理分段到达的代码块', () => {
      // 分段到达时，parser 会在 buffer 中等待完整块
      // 第一个 chunk 可能只输出开头部分
      const partial = parser.process('```python\nprint');
      // 继续发送剩余内容
      const result = parser.process('("hello")\n```');
      // 累计内容应该包含代码块内容
      const allContent = partial.content + result.content;
      expect(allContent).toContain('```python');
      expect(allContent).toContain('print');
    });

    it('应正确解析嵌套代码块', () => {
      const result = parser.process('```js\nconsole.log(`template`);\n```');
      expect(result.content).toContain('console.log(`template`)');
    });

    it('应正确解析 ~~~ 风格的代码块', () => {
      const result = parser.process('~~~\ncode here\n~~~');
      expect(result.content).toContain('~~~');
      expect(result.content).toContain('code here');
    });
  });

  describe('工具调用解析', () => {
    it('应正确解析 JSON 格式的工具调用 (Kimi格式)', () => {
      const result = parser.process('<tool_code>{"name": "search", "arguments": {"query": "test"}}</tool_code>');
      expect(result.toolCalls).toBeDefined();
      expect(result.toolCalls!.length).toBeGreaterThanOrEqual(1);
      expect(result.toolCalls![0].name).toBe('search');
    });

    it('应正确解析数组格式的工具调用', () => {
      const result = parser.process('<tool_calls>[{"name": "search", "arguments": {}}, {"name": "save", "arguments": {}}]</tool_calls>');
      expect(result.toolCalls).toBeDefined();
      expect(result.toolCalls!.length).toBeGreaterThanOrEqual(1);
    });

    it('应正确解析 DeepSeek <call> 格式', () => {
      const result = parser.process('<call tool="search">{"query": "test query"}</call>');
      expect(result.toolCalls).toBeDefined();
      expect(result.toolCalls![0].name).toBe('search');
    });

    it('应正确解析带 <tool_input> 的 DeepSeek 格式', () => {
      const result = parser.process('<call tool="save"><tool_input>{"data": "important"}</tool_input></call>');
      expect(result.toolCalls).toBeDefined();
      expect(result.toolCalls![0].name).toBe('save');
    });

    it('应正确解析 DeepSeek Chat 格式', () => {
      const xml = '<tool_call><function_name>calculate</function_name><parameters><a>5</a><b>3</b></parameters></tool_call>';
      const result = parser.process(xml);
      expect(result.toolCalls).toBeDefined();
      expect(result.toolCalls![0].name).toBe('calculate');
    });

    it('应正确处理混合格式（文本+工具调用）', () => {
      const result = parser.process('Let me search for that.<tool_code>{"name": "search", "arguments": {"q": "info"}}</tool_code>');
      expect(result.content).toContain('Let me search for that.');
      expect(result.toolCalls).toBeDefined();
    });
  });

  describe('计划块解析', () => {
    it('应正确解析 JSON 格式的计划块', () => {
      const result = parser.process('<plan>[{"id": "1", "title": "Step 1"}, {"id": "2", "title": "Step 2"}]</plan>');
      expect(result.plan).toBeDefined();
      expect(result.plan).toHaveLength(2);
      expect(result.plan![0].title).toBe('Step 1');
    });

    it('应正确解析带 steps 字段的计划块', () => {
      const result = parser.process('<plan>{"steps": [{"title": "A"}, {"title": "B"}]}</plan>');
      expect(result.plan).toBeDefined();
      expect(result.plan).toHaveLength(2);
    });

    it('应正确处理纯文本计划（降级）', () => {
      const result = parser.process('<plan>1. First step\n2. Second step</plan>');
      expect(result.plan).toBeDefined();
      expect(result.plan!.length).toBeGreaterThan(0);
    });

    it('应正确处理带 markdown 的计划', () => {
      const result = parser.process('<plan>```json\n[{"title": "Test"}]\n```</plan>');
      expect(result.plan).toBeDefined();
      expect(result.plan![0].title).toBe('Test');
    });
  });

  describe('内容清理', () => {
    it('getCleanContent 应移除工具调用标签', () => {
      const content = 'Hello<tool_code>{"name": "x"}</tool_code>World';
      const cleaned = parser.getCleanContent(content);
      expect(cleaned).toBe('HelloWorld');
    });

    it('getCleanContent 应移除计划标签', () => {
      const content = 'Plan:<plan>[{"title": "X"}]</plan>End';
      const cleaned = parser.getCleanContent(content);
      expect(cleaned).not.toContain('<plan>');
      expect(cleaned).not.toContain('</plan>');
    });

    it('getRawContent 应保持原始内容', () => {
      const content = 'Raw <content>';
      expect(parser.getRawContent(content)).toBe(content);
    });
  });

  describe('边界情况', () => {
    it('应处理连续的工具调用', () => {
      const result = parser.process('<tool_code>{"name": "a", "arguments": {}}</tool_code><tool_code>{"name": "b", "arguments": {}}</tool_code>');
      expect(result.toolCalls).toBeDefined();
      expect(result.toolCalls!.length).toBe(2);
    });

    it('应处理超长文本块', () => {
      const longText = 'a'.repeat(5000);
      const result = parser.process(longText);
      expect(result.content.length).toBeGreaterThan(0);
    });

    it('应处理特殊字符', () => {
      const result = parser.process('Special: < > & " \' { }');
      expect(result.content).toContain('Special:');
    });

    it('应处理 Unicode 字符', () => {
      const result = parser.process('Unicode: \u4e2d\u6587 \u{1F600} \u{1F4A9}');
      expect(result.content).toContain('Unicode:');
    });
  });

  describe('状态管理', () => {
    it('多个 parser 实例应相互独立', () => {
      const parser1 = new StreamParser('openai');
      const parser2 = new StreamParser('deepseek');

      parser1.process('Text 1');
      const result = parser2.process('Text 2');

      expect(result.content).toBe('Text 2');
      expect(result.content).not.toContain('Text 1');
    });
  });
});
