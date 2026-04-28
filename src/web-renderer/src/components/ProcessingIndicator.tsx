import { useState } from 'react'
import type { ProcessingState as ProcessingStateType } from '../types/bridge'

/**
 * ProcessingIndicator — 记忆处理状态指示器（WebView 版）
 * 对齐 RN: src/features/chat/components/ProcessingIndicator.tsx
 *
 * 含两个子组件：Chip（胶囊入口）+ Details（展开详情）
 */

interface ProcessingIndicatorProps {
  messageId: string
  processingState?: ProcessingStateType
}

export function ProcessingIndicator({ messageId, processingState }: ProcessingIndicatorProps) {
  const [expanded, setExpanded] = useState(false)

  if (!processingState) return null

  const { status, summary, chunkCount, type } = processingState
  const isCompleted = type !== undefined
  const isSummarized = type === 'summarized'

  // 完成状态：只显示图标
  if (isCompleted) {
    return (
      <div className="processing-completed">
        {(type === 'archived' || isSummarized) && (
          <button className="processing-chip-btn" onClick={() => setExpanded(!expanded)}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--color-success)" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" /><polyline points="22 4 12 14.01 9 11.01" />
            </svg>
          </button>
        )}
        {isSummarized && (
          <button className="processing-chip-btn" onClick={() => setExpanded(!expanded)}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M12 2a7 7 0 0 1 7 7c0 2.38-1.19 4.47-3 5.74V17a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2v-2.26C6.19 13.47 5 11.38 5 9a7 7 0 0 1 7-7Z" />
            </svg>
          </button>
        )}

        {/* Expanded Details */}
        {expanded && (
          <ProcessingDetails summary={summary} chunkCount={chunkCount} type={type} />
        )}
      </div>
    )
  }

  // 活跃状态：胶囊
  const getStatusText = () => {
    switch (status) {
      case 'chunking': return '切片归档中...'
      case 'summarizing': return '生成摘要...'
      case 'completed': return '已完成背景归档'
      default: return '记忆处理中...'
    }
  }

  return (
    <div>
      <button className="processing-capsule" onClick={() => setExpanded(!expanded)}>
        {status === 'completed' ? (
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--color-success)" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
            <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" /><polyline points="22 4 12 14.01 9 11.01" />
          </svg>
        ) : (
          <div className="processing-spinner" />
        )}
        <span className="processing-status-text">{getStatusText()}</span>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"
          style={{ transform: expanded ? 'rotate(180deg)' : 'rotate(0deg)', transition: 'transform 0.2s' }}>
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>

      {expanded && (
        <ProcessingDetails summary={summary} chunkCount={chunkCount} type={type} />
      )}
    </div>
  )
}

function ProcessingDetails({ summary, chunkCount, type }: {
  summary?: string
  chunkCount?: number
  type?: 'retrieved' | 'archived' | 'summarized'
}) {
  return (
    <div className="processing-details">
      {summary && (
        <div className="processing-details-block" style={{
          borderLeftColor: '#3b82f6',
        }}>
          <div className="processing-section-header">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" strokeWidth="2"><path d="M12 2a7 7 0 0 1 7 7c0 2.38-1.19 4.47-3 5.74V17a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2v-2.26C6.19 13.47 5 11.38 5 9a7 7 0 0 1 7-7Z" /></svg>
            <span className="processing-section-title" style={{ color: '#3b82f6' }}>
              {type === 'summarized' ? '核心摘要' : '摘要生成中...'}
            </span>
          </div>
          <div className="processing-chunk-content">{summary}</div>
        </div>
      )}

      {type === 'archived' && !summary && (
        <div className="processing-details-block" style={{
          borderLeftColor: 'var(--color-success)',
        }}>
          <div className="processing-section-header">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="var(--color-success)" strokeWidth="2"><ellipse cx="12" cy="5" rx="9" ry="3" /><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3" /><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5" /></svg>
            <span className="processing-section-title" style={{ color: 'var(--color-success)' }}>背景归档已完成</span>
          </div>
          <div className="processing-chunk-content">
            消息已成功切片 ({chunkCount || 0} 个切片) 并存入向量数据库，以便后续检索参考。
          </div>
        </div>
      )}
    </div>
  )
}
