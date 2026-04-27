/**
 * WebView Bridge 协议类型定义（RN 侧）
 *
 * 定义 RN ↔ WebView 之间的双向消息协议。
 * RN 侧组件和 web-renderer 侧各自维护独立的类型定义，保持解耦。
 *
 * @see src/web-renderer/src/types/bridge.ts — Web 端镜像定义
 * @see .agent/docs/plans/single-webview-architecture-plan-v2.md §4.2
 */

// ---------------------------------------------------------------------------
// 消息基础结构
// ---------------------------------------------------------------------------

/** RN 侧已知的 Message 子集字段（避免全量序列化） */
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
  task?: WebViewTaskState;
  /** Phase 2: 工具执行步骤 */
  executionSteps?: WebViewExecutionStep[];
  /** Phase 2: RAG 指示器状态 */
  ragState?: WebViewRagIndicatorState;
  /** Phase 2: 审批请求 */
  approvalRequest?: WebViewApprovalRequest | null;
  /** Phase 2: 记忆处理状态 */
  processingState?: WebViewProcessingState;
  /** Phase 2: 会话循环状态 */
  loopStatus?: 'idle' | 'running' | 'waiting_for_approval' | 'completed';
}

// ---------------------------------------------------------------------------
// Phase 2: 扩展数据结构（RN 侧定义，序列化后传给 WebView）
// ---------------------------------------------------------------------------

export interface WebViewTaskStep {
  id: string;
  title: string;
  description?: string;
  status: 'completed' | 'in-progress' | 'failed' | 'skipped' | 'pending';
}

export interface WebViewTaskState {
  title: string;
  status: 'in-progress' | 'completed' | 'failed';
  progress: number;
  steps: WebViewTaskStep[];
}

export interface WebViewExecutionStep {
  id: string;
  type: string;
  toolName?: string;
  toolArgs?: Record<string, unknown>;
  content?: string;
  data?: Record<string, unknown>;
  timestamp?: number;
  throttledUntil?: number;
}

export interface WebViewRagIndicatorState {
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

export interface WebViewApprovalRequest {
  toolName: string;
  args?: unknown[];
  reason?: string;
  type: 'continuation' | 'action';
}

export interface WebViewProcessingState {
  status: string;
  summary?: string;
  chunkCount?: number;
  type?: 'retrieved' | 'archived' | 'summarized';
}

// ---------------------------------------------------------------------------
// RN → WebView 消息类型
// ---------------------------------------------------------------------------

/** WebView 主题数据 */
export interface WebViewThemePayload {
  isDark: boolean;
  accentColor: string;
  /** 完整色阶值（由 generatePalette 生成） */
  palette: {
    50: string; 100: string; 200: string; 300: string;
    400: string; 500: string; 600: string; 700: string;
    800: string; 900: string;
    opacity10: string; opacity20: string; opacity30: string;
  };
}

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
