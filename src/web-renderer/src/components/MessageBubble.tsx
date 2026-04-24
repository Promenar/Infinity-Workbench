import type { BridgeMessage } from '../types/bridge'
import { MarkdownRenderer } from './MarkdownRenderer'

interface MessageBubbleProps {
  message: BridgeMessage
}

export function MessageBubble({ message }: MessageBubbleProps) {
  const isUser = message.role === 'user'
  const isError = message.isError || message.status === 'error'

  return (
    <div style={{
      display: 'flex',
      justifyContent: isUser ? 'flex-end' : 'flex-start',
      marginBottom: 'var(--spacing-md)',
      paddingInline: 'var(--spacing-sm)',
    }}>
      <div style={{
        maxWidth: '85%',
        minWidth: '60px',
        padding: isUser ? '10px 14px' : '4px 0px',
        borderRadius: isUser ? 'var(--radius-lg)' : '0',
        backgroundColor: isUser ? 'var(--bubble-user-bg)' : 'var(--bubble-assistant-bg)',
        border: isUser ? '1px solid var(--bubble-user-border)' : 'none',
        ...(isError ? { borderLeft: '3px solid var(--color-error)' } : {}),
      }}>
        {isUser && (
          <div style={{
            fontSize: '13px',
            color: 'var(--text-tertiary)',
            marginBottom: '4px',
            fontWeight: 600,
          }}>
            你
          </div>
        )}

        <MarkdownRenderer content={message.content} />

        {isError && message.errorMessage && (
          <div style={{
            marginTop: '8px',
            padding: '8px',
            backgroundColor: 'rgba(239, 68, 68, 0.1)',
            borderRadius: 'var(--radius-sm)',
            fontSize: '13px',
            color: 'var(--color-error)',
          }}>
            {message.errorMessage}
          </div>
        )}

        {message.reasoning && (
          <details style={{
            marginTop: '8px',
            fontSize: '13px',
            color: 'var(--text-secondary)',
          }}>
            <summary style={{ cursor: 'pointer', opacity: 0.8 }}>
              思考过程
            </summary>
            <div style={{
              marginTop: '6px',
              padding: '8px',
              backgroundColor: 'var(--bg-secondary)',
              borderRadius: 'var(--radius-sm)',
              whiteSpace: 'pre-wrap',
            }}>
              {message.reasoning}
            </div>
          </details>
        )}
      </div>
    </div>
  )
}
