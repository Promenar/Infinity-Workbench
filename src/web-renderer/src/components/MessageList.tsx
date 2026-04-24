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
        {isGenerating && (
          <div style={{
            textAlign: 'center',
            padding: '8px',
            color: 'var(--text-tertiary)',
            fontSize: '13px',
          }}>
            <span>正在生成...</span>
          </div>
        )}
      </div>
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
