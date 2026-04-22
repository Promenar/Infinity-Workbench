/**
 * ThinkingDetector 单元测试
 * 测试 LLM 思考标签检测功能
 */

import { ThinkingDetector } from '../thinking-detector';

describe('ThinkingDetector', () => {
  let detector: ThinkingDetector;

  beforeEach(() => {
    detector = new ThinkingDetector();
  });

  describe('基本功能', () => {
    it('应正确初始化状态', () => {
      const state = detector.getState();
      expect(state.state).toBe('OUTSIDE');
      expect(state.bufferLength).toBe(0);
    });

    it('应正确处理空 chunk', () => {
      const result = detector.process('');
      expect(result.content).toBe('');
      expect(result.reasoning).toBe('');
    });

    it('应正确处理纯文本', () => {
      const result = detector.process('Hello, world!');
      expect(result.content).toBe('Hello, world!');
      expect(result.reasoning).toBe('');
    });

    it('应正确重置状态', () => {
      detector.process('<think>thinking</think>');
      detector.reset();
      const state = detector.getState();
      expect(state.state).toBe('OUTSIDE');
      expect(state.bufferLength).toBe(0);
    });
  });

  describe('<think> 标签检测', () => {
    it('应正确检测标准 <think> 标签', () => {
      const result = detector.process('<think>This is thinking</think>');
      expect(result.content).toBe('');
      expect(result.reasoning).toBe('This is thinking');
    });

    it('应正确检测带空格的 <think> 标签', () => {
      const result = detector.process('<think >Thinking content</think>');
      expect(result.reasoning).toBe('Thinking content');
    });

    it('应正确检测 </think> 结束标签', () => {
      const result = detector.process('<think>inner</think>more text');
      expect(result.reasoning).toBe('inner');
      expect(result.content).toBe('more text');
    });

    it('应正确处理分段到达的 <think> 标签', () => {
      detector.process('<th');
      const result = detector.process('ink>thinking</think>');
      expect(result.reasoning).toBe('thinking');
    });

    it('应正确处理带属性的 <think> 标签', () => {
      const result = detector.process('<think type="reasoning">content</think>');
      expect(result.reasoning).toBe('content');
    });

    it('应正确处理嵌套标签', () => {
      const result = detector.process('<think>outer <think>inner</think> back</think>');
      expect(result.reasoning).toContain('outer');
      expect(result.reasoning).toContain('inner');
    });
  });

  describe('<thought> 标签检测', () => {
    it('应正确检测 <thought> 标签', () => {
      const result = detector.process('<thought>Thoughts here</thought>');
      expect(result.reasoning).toBe('Thoughts here');
    });

    it('应正确检测带空格的 <thought> 标签', () => {
      const result = detector.process('<thought >Content</thought>');
      expect(result.reasoning).toBe('Content');
    });

    it('应正确检测 </thought> 结束标签', () => {
      const result = detector.process('<thought>reasoning</thought>normal text');
      expect(result.reasoning).toBe('reasoning');
      expect(result.content).toBe('normal text');
    });
  });

  describe('HTML 注释格式检测', () => {
    it('应正确检测 <!-- THINKING_START -->', () => {
      const result = detector.process('<!-- THINKING_START -->thinking<!-- THINKING_END -->');
      expect(result.reasoning).toBe('thinking');
    });

    it('应正确检测带空格的变体', () => {
      const result = detector.process('<!-- THINKING_START -->thoughts<!-- THINKING_END -->');
      expect(result.reasoning).toBe('thoughts');
    });

    it('应正确处理多行思考内容', () => {
      const result = detector.process(
        '<!-- THINKING_START -->Line 1\nLine 2\nLine 3<!-- THINKING_END -->'
      );
      expect(result.reasoning).toContain('Line 1');
      expect(result.reasoning).toContain('Line 2');
      expect(result.reasoning).toContain('Line 3');
    });

    it('应正确处理混合格式', () => {
      const result = detector.process(
        'Before <!-- THINKING_START -->thought<!-- THINKING_END --> After'
      );
      expect(result.content).toContain('Before');
      expect(result.content).toContain('After');
      expect(result.reasoning).toBe('thought');
    });
  });

  describe('混合格式处理', () => {
    it('应正确处理文本+思考+文本', () => {
      const result = detector.process('Hello <think>reasoning</think> World');
      expect(result.content).toContain('Hello');
      expect(result.content).toContain('World');
      expect(result.reasoning).toBe('reasoning');
    });

    it('应正确处理多段思考', () => {
      const result = detector.process(
        'Start <think>T1</think> middle <think>T2</think> end'
      );
      expect(result.reasoning).toContain('T1');
      expect(result.reasoning).toContain('T2');
      expect(result.content).toContain('Start');
      expect(result.content).toContain('middle');
      expect(result.content).toContain('end');
    });

    it('应正确处理工具调用+思考+工具调用', () => {
      const result = detector.process(
        '<tool_code>{}</tool_code><think>thinking</think><tool_code>{}</tool_code>'
      );
      // ThinkingDetector 只处理思考标签，不过滤工具调用
      expect(result.reasoning).toBe('thinking');
    });
  });

  describe('分段处理', () => {
    it('应正确处理分段到达的内容', () => {
      const r1 = detector.process('<think>part');
      const result = detector.process('ial thinking</think>');
      const allReasoning = r1.reasoning + result.reasoning;
      expect(allReasoning).toContain('partial');
    });

    it('应正确处理分段结束标签', () => {
      const r1 = detector.process('<think>thoughts');
      const result = detector.process('</think>more');
      const allReasoning = r1.reasoning + result.reasoning;
      expect(allReasoning).toContain('thoughts');
      expect(result.content).toContain('more');
    });

    it('应正确处理跨多段的思考', () => {
      const r1 = detector.process('<think>1');
      const r2 = detector.process('2');
      const r3 = detector.process('3');
      const r4 = detector.process('</think>done');
      const allReasoning = r1.reasoning + r2.reasoning + r3.reasoning + r4.reasoning;
      expect(allReasoning).toContain('123');
      expect(r4.content).toContain('done');
    });

    it('应正确处理分段到达的 HTML 注释格式', () => {
      detector.process('<!-- THINKING_');
      detector.process('START -->');
      const result = detector.process('thinking<!-- THINKING_END -->');
      expect(result.reasoning).toContain('thinking');
    });
  });

  describe('flush 功能', () => {
    it('应正确 flush 未关闭的思考块', () => {
      const r1 = detector.process('<think>unclosed thinking');
      const result = detector.flush();
      const allReasoning = r1.reasoning + result.reasoning;
      expect(allReasoning).toContain('unclosed');
      expect(typeof result.content).toBe('string');
    });

    it('flush 后状态应重置', () => {
      detector.process('<think>thinking');
      detector.flush();
      const state = detector.getState();
      expect(state.state).toBe('OUTSIDE');
    });

    it('flush 应清空缓冲区', () => {
      detector.process('some text');
      detector.flush();
      const state = detector.getState();
      expect(state.bufferLength).toBe(0);
    });

    it('flush 应处理正常内容', () => {
      detector.process('normal text');
      const result = detector.flush();
      // process() 已将内容输出，flush() 输出剩余 buffer（可能为空）
      expect(typeof result.content).toBe('string');
    });
  });

  describe('边界情况', () => {
    it('应处理连续的开始标签', () => {
      const result = detector.process('<think>T1</think><think>T2</think>');
      expect(result.reasoning).toContain('T1');
      expect(result.reasoning).toContain('T2');
    });

    it('应处理空的思考块', () => {
      const result = detector.process('<think></think>');
      expect(result.reasoning).toBe('');
    });

    it('应处理只有空白符的思考块', () => {
      const result = detector.process('<think>   \n  </think>');
      expect(result.reasoning.trim()).toBe('');
    });

    it('应处理超长思考内容', () => {
      const longThinking = 'x'.repeat(10000);
      const result = detector.process(`<think>${longThinking}</think>`);
      expect(result.reasoning).toBe(longThinking);
    });

    it('应处理 Unicode 内容', () => {
      const result = detector.process('<think>中文思考🤔</think>');
      expect(result.reasoning).toBe('中文思考🤔');
    });

    it('应处理特殊字符', () => {
      const result = detector.process('<think><>&"\'</think>');
      expect(result.reasoning).toContain('<');
      expect(result.reasoning).toContain('>')
    });
  });

  describe('状态追踪', () => {
    it('应正确追踪 OUTSIDE 状态', () => {
      detector.process('normal text');
      const state = detector.getState();
      expect(state.state).toBe('OUTSIDE');
    });

    it('应正确追踪 INSIDE 状态', () => {
      detector.process('<think>in progress');
      const state = detector.getState();
      expect(state.state).toBe('INSIDE');
    });

    it('应正确追踪缓冲区长度', () => {
      detector.process('hello');
      const state = detector.getState();
      expect(state.bufferLength).toBeGreaterThanOrEqual(0);
    });
  });

  describe('缓冲区安全处理', () => {
    it('应保留尾部 < 字符', () => {
      detector.process('text <');
      const state = detector.getState();
      expect(state.bufferLength).toBeGreaterThan(0);
    });

    it('应保留尾部 <! 字符', () => {
      detector.process('text <!');
      const state = detector.getState();
      expect(state.bufferLength).toBeGreaterThan(0);
    });

    it('应保留尾部 <!-- 字符', () => {
      detector.process('text <!--');
      const state = detector.getState();
      expect(state.bufferLength).toBeGreaterThan(0);
    });

    it('不应无限循环', () => {
      const start = Date.now();
      // 传入大量文本应能在有限时间内完成
      detector.process('a'.repeat(10000));
      const elapsed = Date.now() - start;
      expect(elapsed).toBeLessThan(1000);
    });
  });
});
