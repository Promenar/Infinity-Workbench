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

// ---------------------------------------------------------------------------
// WebView 内部状态
// ---------------------------------------------------------------------------

export interface WebViewState {
  messages: BridgeMessage[];
  theme: WebViewThemePayload;
  isGenerating: boolean;
}
