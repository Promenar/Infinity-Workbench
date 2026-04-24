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
  | { type: 'INIT'; payload: { messages: BridgeMessage[]; theme: WebViewThemePayload } }
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
  | { type: 'PERF_METRICS'; payload: { fps: number; renderTime: number; memoryMb: number } };
