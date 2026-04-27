import { useState } from 'react'
import type { ApprovalRequest } from '../types/bridge'
import { postToRN } from '../bridge'

/**
 * ApprovalCard — 人工审批卡片（WebView 版）
 * 对齐 RN: src/features/chat/components/ApprovalCard.tsx
 *
 * 支持 continuation（蓝色，循环限制）和 action（琥珀色，高风险操作）两种模式。
 */

interface ApprovalCardProps {
  approvalRequest: ApprovalRequest
  sessionId: string
}

export function ApprovalCard({ approvalRequest, sessionId }: ApprovalCardProps) {
  const [interventionText, setInterventionText] = useState('')
  const isContinuation = approvalRequest.type === 'continuation'

  const mainColor = isContinuation ? '#3b82f6' : '#d97706'
  const bgColor = isContinuation
    ? 'rgba(59, 130, 246, 0.1)'
    : 'rgba(217, 119, 6, 0.1)'
  const borderColor = isContinuation
    ? 'rgba(59, 130, 246, 0.3)'
    : 'rgba(217, 119, 6, 0.3)'

  const title = isContinuation ? 'Loop Limit Reached' : 'Action Approval Required'
  const rejectLabel = isContinuation ? 'End Task' : 'Reject'
  const approveLabel = isContinuation
    ? 'Continue (+10)'
    : (interventionText.trim() ? '携带指令批准' : '批准并执行')

  const handleApprove = () => {
    postToRN({
      type: 'APPROVE_ACTION',
      sessionId,
      approved: true,
      instruction: interventionText.trim() || undefined,
    })
  }

  const handleReject = () => {
    postToRN({
      type: 'APPROVE_ACTION',
      sessionId,
      approved: false,
    })
  }

  return (
    <div className="approval-card" style={{
      backgroundColor: bgColor,
      borderColor: borderColor,
    }}>
      {/* Header */}
      <div className="approval-header">
        {isContinuation ? (
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke={mainColor} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="1 4 1 10 7 10" /><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" />
          </svg>
        ) : (
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke={mainColor} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z" /><line x1="12" y1="9" x2="12" y2="13" /><line x1="12" y1="17" x2="12.01" y2="17" />
          </svg>
        )}
        <span className="approval-title" style={{ color: mainColor }}>{title}</span>
      </div>

      {/* Content */}
      <div className="approval-content">
        <div className="approval-reason">
          Reason: {approvalRequest.reason || 'High-risk action detected.'}
        </div>
        <div className="approval-tool-card">
          <div className="approval-tool-name">
            Tool: {approvalRequest.toolName}
          </div>
          {approvalRequest.args && approvalRequest.args.length > 0 && (
            <div className="approval-tool-args">
              {JSON.stringify(approvalRequest.args, null, 2).slice(0, 100)}
              {JSON.stringify(approvalRequest.args).length > 100 ? '...' : ''}
            </div>
          )}
        </div>
      </div>

      {/* Intervention Input */}
      <div className="approval-input-wrapper">
        <div className="approval-input-label">可选：提供修改指令以调整执行行为</div>
        <textarea
          className="approval-input"
          value={interventionText}
          onChange={e => setInterventionText(e.target.value)}
          placeholder="例如: '仅写入 /tmp 目录' 或 '使用安全模式'"
          rows={2}
        />
      </div>

      {/* Actions */}
      <div className="approval-actions">
        <button className="approval-btn approval-btn-reject" onClick={handleReject}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="10" /><line x1="15" y1="9" x2="9" y2="15" /><line x1="9" y1="9" x2="15" y2="15" />
          </svg>
          {rejectLabel}
        </button>
        <button className="approval-btn approval-btn-approve" style={{ backgroundColor: mainColor }} onClick={handleApprove}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <polygon points="6 3 20 12 6 21 6 3" />
          </svg>
          {approveLabel}
        </button>
      </div>
    </div>
  )
}
