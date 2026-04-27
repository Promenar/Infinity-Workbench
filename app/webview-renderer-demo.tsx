import React, { useState, useCallback, useRef } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, Switch, SafeAreaView } from 'react-native';
import { Stack } from 'expo-router';
import { WebViewMessageList } from '../src/components/chat/WebViewMessageList';
import { useTheme } from '../src/theme/ThemeProvider';
import type { Message } from '../src/types/chat';

const MOCK_MESSAGES: Message[] = [
  {
    id: '1', role: 'user',
    content: '你好！请展示一下你的 Markdown 渲染能力。',
    createdAt: Date.now() - 60000, status: 'sent',
  },
  {
    id: '2', role: 'assistant',
    content: `当然！下面是一些常见的 Markdown 渲染示例：

## 文本样式

这是 **粗体**、*斜体*、~~删除线~~ 和 \`行内代码\` 的示例。

## 代码块

\`\`\`typescript
interface WebViewBridge {
  postMessage(msg: BridgeMessage): void;
  onMessage(handler: (msg: RNToWebMessage) => void): void;
}

const bridge = createBridge(webViewRef);
bridge.init({ messages, theme });
\`\`\`

\`\`\`python
def fibonacci(n: int) -> list[int]:
    a, b = 0, 1
    result = []
    for _ in range(n):
        result.append(a)
        a, b = b, a + b
    return result
\`\`\`

## 表格

| 特性 | RN 原生 | WebView | 说明 |
|------|---------|---------|------|
| 代码高亮 | ⚠️ 有限 | ✅ 完整 | Prism.js 支持 200+ 语言 |
| 数学公式 | ❌ 多 WebView | ✅ 内联 | KaTeX 直接渲染 |
| 图表 | ❌ 独立实例 | ✅ 统一 | Mermaid/ECharts 集成 |
| 滚动 | ⚠️ 脆弱 | ✅ 原生 | CSS overflow-y |

## 列表

- 无序列表项 1
- 无序列表项 2
  - 嵌套列表项
- 无序列表项 3

1. 有序列表项 1
2. 有序列表项 2

## 引用

> WebView 单一容器架构消除了多 WebView 碎片化问题。

---

以上是基础 Markdown 渲染验证。`,
    createdAt: Date.now() - 55000, status: 'sent',
  },
  {
    id: '3', role: 'user',
    content: '请展示推理过程。',
    createdAt: Date.now() - 50000, status: 'sent',
  },
  {
    id: '4', role: 'assistant',
    content: `好的，这是一个带有推理过程的回复。推理内容可通过折叠展开查看。`,
    createdAt: Date.now() - 45000, status: 'sent',
    reasoning: `用户要求展示推理过程。

分析：
1. 需要展示折叠的推理内容
2. 正常回复内容显示在外部

结论：推理过程渲染正常。`,
  },
  {
    id: '5', role: 'user',
    content: '展示数学公式渲染。',
    createdAt: Date.now() - 40000, status: 'sent',
  },
  {
    id: '6', role: 'assistant',
    content: `## 行内公式

质能方程 $E = mc^2$ 是物理学最著名的公式之一。欧拉公式 $e^{i\\pi} + 1 = 0$ 被誉为最美的数学公式。

## 块级公式

高斯积分：

$$\\int_{-\\infty}^{\\infty} e^{-x^2} dx = \\sqrt{\\pi}$$

麦克斯韦方程组：

$$\\nabla \\times \\mathbf{E} = -\\frac{\\partial \\mathbf{B}}{\\partial t}$$

矩阵运算：

$$\\mathbf{A} = \\begin{pmatrix} a_{11} & a_{12} \\\\ a_{21} & a_{22} \\end{pmatrix}$$`,
    createdAt: Date.now() - 35000, status: 'sent',
  },
  {
    id: '7', role: 'user',
    content: '展示一个错误状态。',
    createdAt: Date.now() - 30000, status: 'sent',
  },
  {
    id: '8', role: 'assistant',
    content: '这条消息模拟了错误状态。',
    createdAt: Date.now() - 25000, status: 'error',
    isError: true,
    errorMessage: '模型响应超时 (30s)，请检查网络连接后重试。',
  },
  {
    id: '9', role: 'user',
    content: '展示一个流程图和图表。',
    createdAt: Date.now() - 20000, status: 'sent',
  },
  {
    id: '10', role: 'assistant',
    content: `## Mermaid 流程图

\`\`\`mermaid
graph TD
    A[用户发送消息] --> B{消息类型}
    B -->|文本| C[Markdown 渲染]
    B -->|代码| D[代码高亮]
    B -->|数学| E[KaTeX 渲染]
    B -->|图表| F[图表渲染器]
    C --> G[WebView 显示]
    D --> G
    E --> G
    F --> G
    G --> H[用户查看]
\`\`\`

## ECharts 图表

\`\`\`echarts
{
  "title": { "text": "WebView vs 原生渲染性能对比" },
  "tooltip": { "trigger": "axis" },
  "xAxis": { "type": "category", "data": ["代码高亮", "数学公式", "表格", "100条消息滚动", "主题切换"] },
  "yAxis": { "type": "value", "name": "FPS" },
  "series": [
    { "name": "WebView", "type": "bar", "data": [58, 60, 59, 55, 60] },
    { "name": "RN 原生", "type": "bar", "data": [55, 30, 52, 45, 50] }
  ]
}
\`\`\``,
    createdAt: Date.now() - 15000, status: 'sent',
  },

  // ===== Phase 2 测试消息 =====

  {
    id: '11', role: 'user',
    content: '展示 Phase 2 高级组件：RAG 指示器 + 任务监控 + 审批卡片 + 记忆处理',
    createdAt: Date.now() - 10000, status: 'sent',
  },

  // --- RAG 指示器（检索完成状态）---
  {
    id: '12', role: 'assistant',
    content: `## RAG 指示器测试

上方显示了 RAG 检索完成状态的指示器，展示了"已关联 5 个知识点"的状态。`,
    createdAt: Date.now() - 9000, status: 'sent',
    ragState: {
      stage: 'complete',
      status: 'idle',
      progress: 100,
      referencesCount: 5,
      history: {
        type: 'retrieved',
        chunkCount: 8,
      },
    },
  },

  // --- 任务监控（进行中）---
  {
    id: '13', role: 'assistant',
    content: `## 任务监控测试

下方展示了一个进行中的多步骤任务监控面板，模拟代码重构任务：`,
    createdAt: Date.now() - 8000, status: 'sent',
    task: {
      title: '代码重构 — WebView 渲染器迁移',
      status: 'in-progress',
      progress: 60,
      steps: [
        { id: 's1', title: '分析现有架构', description: '评估 RN 原生组件与 WebView 方案的优劣', status: 'completed' },
        { id: 's2', title: '设计 Bridge 协议', description: '定义 RN ↔ WebView 双向消息类型', status: 'completed' },
        { id: 's3', title: '构建 Web Renderer', description: 'Vite + React 搭建 WebView 端渲染引擎', status: 'completed' },
        { id: 's4', title: '实现核心组件', description: 'MessageBubble / MarkdownRenderer / CodeBlock', status: 'in-progress' },
        { id: 's5', title: '集成图表渲染', description: 'Mermaid / ECharts iframe 隔离方案', status: 'pending' },
        { id: 's6', title: '实机验证与优化', description: '性能测试、滚动优化、长会话处理', status: 'pending' },
      ],
    },
  },

  // --- 审批卡片（continuation 模式）---
  {
    id: '14', role: 'assistant',
    content: `## 审批卡片测试

下方展示了循环限制审批卡片（蓝色主题），模拟 Agent 执行轮次达到上限时的场景：`,
    createdAt: Date.now() - 7000, status: 'sent',
    approvalRequest: {
      toolName: 'Loop Limit',
      reason: 'Agent 已执行 10 轮工具调用，达到当前会话设定的循环上限。可选择继续执行（追加 10 轮）或终止任务。',
      type: 'continuation',
    },
    loopStatus: 'waiting_for_approval',
  },

  // --- 审批卡片（action 模式）---
  {
    id: '15', role: 'assistant',
    content: `下方展示了高风险操作审批卡片（琥珀色主题），模拟需要人工确认的危险操作：`,
    createdAt: Date.now() - 6000, status: 'sent',
    approvalRequest: {
      toolName: 'execute_shell',
      args: [{ command: 'rm -rf /tmp/test-build && npm run build:release' }],
      reason: '该操作将清理临时目录并执行生产构建，涉及文件系统写入权限。',
      type: 'action',
    },
    loopStatus: 'waiting_for_approval',
  },

  // --- 记忆处理（已完成归档）---
  {
    id: '16', role: 'assistant',
    content: `## 记忆处理测试

下方展示了记忆归档完成状态的指示器，消息已被切片并存入向量数据库。`,
    createdAt: Date.now() - 5000, status: 'sent',
    processingState: {
      status: 'completed',
      chunkCount: 6,
      type: 'archived',
    },
  },

  // --- 记忆处理（已完成摘要）---
  {
    id: '17', role: 'assistant',
    content: `下方展示了记忆摘要完成状态，同时包含归档和摘要两种处理结果。`,
    createdAt: Date.now() - 4000, status: 'sent',
    processingState: {
      status: 'completed',
      summary: '用户询问了 WebView 渲染器的 Phase 2 高级组件开发进度，包括 RAG 指示器、任务监控、审批卡片和记忆处理指示器的测试验证。',
      chunkCount: 4,
      type: 'summarized',
    },
  },

  // --- 任务监控（已完成）---
  {
    id: '18', role: 'assistant',
    content: `下方展示了一个已完成的任务监控面板，所有步骤均已完成：`,
    createdAt: Date.now() - 3000, status: 'sent',
    task: {
      title: '环境初始化',
      status: 'completed',
      progress: 100,
      steps: [
        { id: 'c1', title: '检查 Node.js 版本', status: 'completed' },
        { id: 'c2', title: '安装依赖包', description: 'npm install — 312 packages', status: 'completed' },
        { id: 'c3', title: '配置 Metro bundler', status: 'completed' },
        { id: 'c4', title: '预构建 WebView 资源', description: 'Vite build → 2.2MB inline HTML', status: 'completed' },
      ],
    },
  },

  // --- RAG 指示器（活跃检索状态）---
  {
    id: '19', role: 'assistant',
    content: `上方展示了 RAG 活跃检索中的状态，带有进度条和脉冲动画。`,
    createdAt: Date.now() - 2000, status: 'sent',
    ragState: {
      stage: 'retrieval',
      status: 'retrieving',
      subStage: 'RERANK',
      progress: 72,
      networkStats: { txBytes: 15360, rxBytes: 87040 },
      referencesCount: 0,
    },
  },

  // ===== 工具执行时间线测试 =====

  {
    id: '20', role: 'user',
    content: '展示工具执行时间线（生成完成，已自动折叠）',
    createdAt: Date.now() - 1000, status: 'sent',
  },

  {
    id: '21', role: 'assistant',
    content: `下方展示了已完成的工具执行时间线，包含 3 轮思考 + 2 轮工具调用。生成完成后时间线自动折叠为摘要视图。`,
    createdAt: Date.now() - 500, status: 'sent',
    executionSteps: [
      {
        id: 'es1', type: 'thinking', content: '用户要求展示工具执行时间线。\n\n分析：需要模拟一个典型的 Agent 工具调用流程。\n\n结论：构造思考 → 工具调用 → 结果 → 再思考的流程。',
        timestamp: Date.now() - 480,
      },
      {
        id: 'es2', type: 'tool_call', toolName: 'search_internet',
        toolArgs: { query: 'WebView tool execution timeline best practices 2026', max_results: 5 },
        timestamp: Date.now() - 460,
      },
      {
        id: 'es3', type: 'tool_result', toolName: 'search_internet', content: '搜索完成，找到 5 个相关结果。',
        data: {
          sources: [
            { title: 'React Native WebView Architecture Guide', url: 'https://reactnative.dev/docs/webview', snippet: 'Complete guide to WebView architecture patterns in React Native applications.' },
            { title: 'Agent Tool Execution Patterns', url: 'https://langchain.ai/docs/tools', snippet: 'Best practices for implementing tool execution timelines in AI agent systems.' },
            { title: 'Timeline UI Component Library', url: 'https://ui.dev/timeline', snippet: 'Open source timeline component with expand/collapse and step visualization.' },
          ],
        },
        timestamp: Date.now() - 440,
      },
      {
        id: 'es4', type: 'thinking', content: '搜索结果已获取。分析结果：\n1. WebView 架构指南提供了基础模式\n2. Agent 工具执行模式提供了交互范式\n3. 时间线 UI 组件库提供了视觉参考\n\n现在可以综合这些信息给出答案。',
        timestamp: Date.now() - 420,
      },
      {
        id: 'es5', type: 'tool_call', toolName: 'query_vector_db',
        toolArgs: { query: 'timeline rendering performance', top_k: 3 },
        timestamp: Date.now() - 400,
      },
      {
        id: 'es6', type: 'tool_result', toolName: 'query_vector_db', content: '向量检索完成，返回 3 条相关记录。',
        data: {
          references: [
            { content: '使用虚拟列表渲染长列表可显著提升滚动性能，避免一次性加载所有节点。', score: 0.92 },
            { content: '工具执行时间线应支持折叠/展开，默认折叠以减少视觉噪音。', score: 0.87 },
            { content: 'BlurView 磨砂效果可通过 CSS backdrop-filter 在 WebView 中实现。', score: 0.81 },
          ],
        },
        timestamp: Date.now() - 380,
      },
      {
        id: 'es7', type: 'thinking', content: '向量检索补充了性能优化建议。综合所有信息，可以给出完整答案了。',
        timestamp: Date.now() - 360,
      },
    ],
  },
];

const STREAMING_TEXT = `这是一个**流式输出**的模拟测试。

我将逐字推送内容，验证 WebView 的实时渲染能力。

\`\`\`javascript
const greeting = "Hello, WebView!";
console.log(greeting);
\`\`\`

流式输出测试完成。`;

export default function WebViewRendererDemo() {
  const { isDark, colors } = useTheme();
  const [darkMode, setDarkMode] = useState(isDark);
  const [messages, setMessages] = useState<Message[]>(MOCK_MESSAGES);
  const [isStreaming, setIsStreaming] = useState(false);
  const streamTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const streamIndexRef = useRef(0);

  const toggleTheme = useCallback(() => setDarkMode(prev => !prev), []);

  const startStreaming = useCallback(() => {
    if (isStreaming) return;
    const streamId = `stream-${Date.now()}`;
    const newMsg: Message = {
      id: streamId, role: 'assistant', content: '',
      createdAt: Date.now(), status: 'streaming',
    };
    setMessages(prev => [...prev, {
      id: `user-${Date.now()}`, role: 'user',
      content: '请模拟流式输出。', createdAt: Date.now(), status: 'sent',
    }, newMsg]);
    setIsStreaming(true);
    streamIndexRef.current = 0;

    streamTimerRef.current = setInterval(() => {
      const idx = streamIndexRef.current;
      if (idx >= STREAMING_TEXT.length) {
        if (streamTimerRef.current) clearInterval(streamTimerRef.current);
        setIsStreaming(false);
        setMessages(prev => prev.map(m =>
          m.id === streamId ? { ...m, status: 'sent' as const } : m
        ));
        return;
      }
      const chunkSize = Math.floor(Math.random() * 3) + 1;
      const chunk = STREAMING_TEXT.slice(idx, idx + chunkSize);
      streamIndexRef.current += chunkSize;
      setMessages(prev => prev.map(m =>
        m.id === streamId ? { ...m, content: m.content + chunk } : m
      ));
    }, 50);
  }, [isStreaming]);

  const resetMessages = useCallback(() => {
    if (streamTimerRef.current) clearInterval(streamTimerRef.current);
    setIsStreaming(false);
    setMessages(MOCK_MESSAGES);
  }, []);

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: darkMode ? '#0a0a0c' : '#ffffff' }]}>
      <Stack.Screen
        options={{
          title: 'WebView Renderer POC',
          headerBackTitle: 'Demo',
          headerStyle: { backgroundColor: darkMode ? '#0a0a0c' : '#ffffff' },
          headerTintColor: darkMode ? '#ffffff' : '#000000',
        }}
      />
      <View style={[styles.controlPanel, { borderBottomColor: darkMode ? '#272729' : '#e4e4e7' }]}>
        <View style={styles.controlRow}>
          <Text style={{ color: darkMode ? '#fff' : '#000', fontSize: 14 }}>暗色模式</Text>
          <Switch value={darkMode} onValueChange={toggleTheme} />
        </View>
        <View style={styles.controlRow}>
          <TouchableOpacity
            style={[styles.button, { backgroundColor: isStreaming ? '#666' : '#6366f1' }]}
            onPress={startStreaming} disabled={isStreaming}
          >
            <Text style={styles.buttonText}>{isStreaming ? '流式中...' : '模拟流式输出'}</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.button, { backgroundColor: '#ef4444' }]}
            onPress={resetMessages}
          >
            <Text style={styles.buttonText}>重置</Text>
          </TouchableOpacity>
        </View>
        <Text style={{ color: darkMode ? '#a1a1aa' : '#71717a', fontSize: 12 }}>
          消息数: {messages.length} | Phase 2: RAG + Task + Approval + Processing
        </Text>
      </View>
      <View style={{ flex: 1 }}>
        <WebViewMessageList messages={messages} isDark={darkMode} colors={colors} sessionId="demo-session-poc" />
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  controlPanel: { padding: 12, borderBottomWidth: 1, gap: 8 },
  controlRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
  button: { paddingHorizontal: 16, paddingVertical: 8, borderRadius: 8, flex: 1, alignItems: 'center' },
  buttonText: { color: '#fff', fontWeight: '600', fontSize: 14 },
});
