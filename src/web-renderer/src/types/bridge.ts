/**
 * WebView Bridge 协议类型定义（Web 端）
 *
 * Web 端独立维护的类型定义，与 RN 侧 webview-bridge.ts 镜像对应。
 * 两侧通过 JSON 序列化传输，结构约定一致但代码解耦。
 *
 * @see src/types/webview-bridge.ts — RN 侧镜像定义
 */

// ---------------------------------------------------------------------------
// 消息基础结构
// ---------------------------------------------------------------------------

export interface BridgeMessage {
  id: string;
  role: 'user' | 'assistant' | 'system' | 'tool';
  content: string;
  createdAt: number;
  status?: 'sending' | 'sent' | 'error' | 'streaming';
  isError?: boolean;
  errorMessage?: string;
  reasoning?: string;
  /** Phase 2: 任务监控数据 */
  task?: TaskState;
  /** Phase 2: 工具执行步骤 */
  executionSteps?: ExecutionStep[];
  /** Phase 2: RAG 指示器状态 */
  ragState?: RagIndicatorState;
  /** Phase 2: 审批请求 */
  approvalRequest?: ApprovalRequest | null;
  /** Phase 2: 记忆处理状态 */
  processingState?: ProcessingState;
  /** Phase 2: 会话循环状态 */
  loopStatus?: 'idle' | 'running' | 'waiting_for_approval' | 'completed';
}

// ---------------------------------------------------------------------------
// Phase 2: 扩展数据结构
// ---------------------------------------------------------------------------

export interface TaskStep {
  id: string;
  title: string;
  description?: string;
  status: 'completed' | 'in-progress' | 'failed' | 'skipped' | 'pending';
}

export interface TaskState {
  title: string;
  status: 'in-progress' | 'completed' | 'failed';
  progress: number;
  steps: TaskStep[];
}

export interface ExecutionStep {
  id: string;
  type: string;
  toolName?: string;
  toolArgs?: Record<string, unknown>;
  content?: string;
  data?: Record<string, unknown>;
  timestamp?: number;
  throttledUntil?: number;
}

export interface RagIndicatorState {
  stage?: string;
  status?: string;
  subStage?: string;
  progress?: number;
  kgStatus?: string;
  kgProgress?: number;
  pulseActive?: boolean;
  networkStats?: { txBytes?: number; rxBytes?: number };
  chunks?: string[];
  referencesCount?: number;
  history?: {
    type: 'retrieved' | 'archived' | 'summarized';
    chunkCount?: number;
    summary?: string;
  };
}

export interface ApprovalRequest {
  toolName: string;
  args?: unknown[];
  reason?: string;
  type: 'continuation' | 'action';
}

export interface ProcessingState {
  status: string;
  summary?: string;
  chunkCount?: number;
  type?: 'retrieved' | 'archived' | 'summarized';
}

// ---------------------------------------------------------------------------
// 主题数据
// ---------------------------------------------------------------------------

export interface WebViewThemePayload {
  isDark: boolean;
  accentColor: string;
  palette: {
    50: string; 100: string; 200: string; 300: string;
    400: string; 500: string; 600: string; 700: string;
    800: string; 900: string;
    opacity10: string; opacity20: string; opacity30: string;
  };
}

// ---------------------------------------------------------------------------
// RN → WebView 消息类型
// ---------------------------------------------------------------------------

export type RNToWebMessage =
  | { type: 'INIT'; payload: { messages: BridgeMessage[]; theme: WebViewThemePayload; sessionId?: string } }
  | { type: 'APPEND_MESSAGE'; payload: BridgeMessage }
  | { type: 'UPDATE_MESSAGE'; payload: { id: string; partial: Partial<BridgeMessage> } }
  | { type: 'STREAM_CHUNK'; payload: { messageId: string; content: string } }
  | { type: 'DELETE_MESSAGE'; payload: { id: string } }
  | { type: 'THEME_CHANGE'; payload: WebViewThemePayload }
  | { type: 'SCROLL_TO_BOTTOM'; payload?: { animated: boolean } }
  | { type: 'SET_GENERATING'; payload: { isGenerating: boolean } };

// ---------------------------------------------------------------------------
// WebView → RN 消息类型
// ---------------------------------------------------------------------------

export type WebToRNMessage =
  | { type: 'READY' }
  | { type: 'REQUEST_SCROLL_TO_BOTTOM' }
  | { type: 'DELETE_MESSAGE'; messageId: string }
  | { type: 'RESEND_MESSAGE'; messageId: string; content: string }
  | { type: 'EXTRACT_GRAPH'; messageId: string }
  | { type: 'VECTORIZE'; messageId: string }
  | { type: 'SUMMARIZE' }
  | { type: 'SHARE_MESSAGE'; messageId: string }
  | { type: 'SCROLL_POSITION'; offset: number }
  | { type: 'ERROR'; message: string }
  | { type: 'PERF_METRICS'; payload: { fps: number; renderTime: number; memoryMb: number } }
  /** Phase 2: 审批操作 */
  | { type: 'APPROVE_ACTION'; sessionId: string; approved: boolean; instruction?: string }
  /** Phase 2: 干预指令 */
  | { type: 'SET_INTERVENTION'; sessionId: string; instruction: string }
  /** Phase 2: 展开/折叠组件 */
  | { type: 'TOGGLE_COMPONENT'; messageId: string; component: string; expanded: boolean };

// ---------------------------------------------------------------------------
// WebView 内部状态
// ---------------------------------------------------------------------------

export interface WebViewState {
  messages: BridgeMessage[];
  theme: WebViewThemePayload;
  isGenerating: boolean;
  sessionId?: string;
}
