import type { BridgeMessage } from '../types/bridge'
import { postToRN } from '../bridge'

interface MessageActionsProps {
  message: BridgeMessage
}

export function MessageActions({ message }: MessageActionsProps) {
  const isUser = message.role === 'user'

  const handleCopy = () => {
    navigator.clipboard?.writeText(message.content).catch(() => {})
  }

  const handleDelete = () => {
    postToRN({ type: 'DELETE_MESSAGE', messageId: message.id })
  }

  const handleResend = () => {
    postToRN({ type: 'RESEND_MESSAGE', messageId: message.id, content: message.content })
  }

  const handleExtractGraph = () => {
    postToRN({ type: 'EXTRACT_GRAPH', messageId: message.id })
  }

  const handleVectorize = () => {
    postToRN({ type: 'VECTORIZE', messageId: message.id })
  }

  const handleSummarize = () => {
    postToRN({ type: 'SUMMARIZE' })
  }

  const handleShare = () => {
    postToRN({ type: 'SHARE_MESSAGE', messageId: message.id })
  }

  return (
    <div className="message-actions">
      <button className="action-btn" onClick={handleCopy} title="复制" aria-label="复制内容">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
        </svg>
      </button>

      <button className="action-btn" onClick={handleShare} title="分享" aria-label="分享消息">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/>
          <line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/>
        </svg>
      </button>

      {isUser ? (
        <button className="action-btn" onClick={handleResend} title="重发" aria-label="重新发送">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/>
          </svg>
        </button>
      ) : (
        <>
          <button className="action-btn" onClick={handleExtractGraph} title="图谱" aria-label="知识图谱">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M9.5 2A2.5 2.5 0 0 1 12 4.5v15a2.5 2.5 0 0 1-4.96.44A2.5 2.5 0 0 1 9.5 2Z"/>
              <path d="M14.5 2A2.5 2.5 0 0 1 17 4.5v15a2.5 2.5 0 0 1-4.96.44A2.5 2.5 0 0 1 14.5 2Z"/>
            </svg>
          </button>
          <button className="action-btn" onClick={handleVectorize} title="向量" aria-label="向量化">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z"/><path d="M14 2v6h6"/><path d="M16 13H8"/><path d="M16 17H8"/><path d="M10 9H8"/>
            </svg>
          </button>
          <button className="action-btn" onClick={handleSummarize} title="摘要" aria-label="摘要">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/>
            </svg>
          </button>
        </>
      )}

      <button className="action-btn action-btn-danger" onClick={handleDelete} title="删除" aria-label="删除消息">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
        </svg>
      </button>
    </div>
  )
}
