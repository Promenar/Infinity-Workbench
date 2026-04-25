import type { BridgeMessage } from '../types/bridge'
import { MarkdownRenderer } from './MarkdownRenderer'
import { MessageActions } from './MessageActions'

interface MessageBubbleProps {
  message: BridgeMessage
}

export function MessageBubble({ message }: MessageBubbleProps) {
  const isUser = message.role === 'user'
  const isError = message.isError || message.status === 'error'
  const isStreaming = message.status === 'streaming'

  return (
    <div className={`message-row ${isUser ? 'message-row-user' : 'message-row-assistant'}`}>
      {/* Assistant 头像 */}
      {!isUser && (
        <div className="avatar-wrapper">
          <div className="avatar-icon">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z"/>
            </svg>
          </div>
        </div>
      )}

      <div className={isUser ? 'bubble-user' : 'bubble-assistant'}>
        {/* User 标签 */}
        {isUser && (
          <div className="bubble-label">你</div>
        )}

        {/* 消息内容 */}
        <div className="bubble-content">
          <MarkdownRenderer content={message.content} />
        </div>

        {/* 错误提示 */}
        {isError && message.errorMessage && (
          <div className="error-card">
            <strong>错误：</strong>{message.errorMessage}
            <div className="error-retry">点击重试</div>
          </div>
        )}

        {/* 推理过程折叠 */}
        {message.reasoning && (
          <details className="reasoning-block">
            <summary>思考过程</summary>
            <div className="reasoning-content">{message.reasoning}</div>
          </details>
        )}

        {/* 流式生成动画指示 */}
        {isStreaming && (
          <div className="streaming-cursor">
            <span className="dot" />
            <span className="dot" />
            <span className="dot" />
          </div>
        )}

        {/* 消息操作按钮栏 — 非流式状态时显示 */}
        {!isStreaming && message.content && (
          <MessageActions message={message} />
        )}
      </div>
    </div>
  )
}
