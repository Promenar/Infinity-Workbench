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
          消息数: {messages.length} | 模式: 完整 Web Renderer (react-markdown + KaTeX + Prism.js)
        </Text>
      </View>
      <View style={{ flex: 1 }}>
        <WebViewMessageList messages={messages} isDark={darkMode} colors={colors} />
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
