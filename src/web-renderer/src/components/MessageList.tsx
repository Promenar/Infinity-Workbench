import type { BridgeMessage, WebViewThemePayload } from '../types/bridge'
import { useAutoScroll } from '../hooks/useAutoScroll'
import { MessageBubble } from './MessageBubble'

interface MessageListProps {
  messages: BridgeMessage[]
  theme: WebViewThemePayload
  isGenerating: boolean
}

export function MessageList({ messages, theme, isGenerating }: MessageListProps) {
  const { containerRef, showScrollButton, scrollToBottom } = useAutoScroll([messages, isGenerating])

  return (
    <>
      <div className="message-list" ref={containerRef}>
        {messages.map(msg => (
          <MessageBubble key={msg.id} message={msg} />
        ))}

        {/* 等待内容时显示加载动画 */}
        {isGenerating && messages.length > 0 && !messages[messages.length - 1]?.content && (
          <div className="loading-dots">
            <span className="dot" />
            <span className="dot" />
            <span className="dot" />
          </div>
        )}

        {/* 流式生成中的底部淡出脉冲 */}
        {isGenerating && messages.length > 0 && messages[messages.length - 1]?.content && (
          <div className="stream-pulse" />
        )}
      </div>

      {/* 回到底部按钮 */}
      <button
        className={`scroll-to-bottom ${showScrollButton ? 'visible' : ''}`}
        onClick={scrollToBottom}
        aria-label="滚动到底部"
      >
        <svg viewBox="0 0 24 24"><path d="M7 10l5 5 5-5z" fill="var(--text-secondary)"/></svg>
      </button>
    </>
  )
}
