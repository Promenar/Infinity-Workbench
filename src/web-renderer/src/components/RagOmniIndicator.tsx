import { useEffect, useState, useRef } from 'react'
import type { RagIndicatorState } from '../types/bridge'

/**
 * RagOmniIndicator — RAG 全能指示器（WebView 版）
 * 对齐 RN: src/features/chat/components/RagOmniIndicator.tsx
 *
 * Reanimated 动画 → CSS 动画替代：
 * - withTiming 进度条 → CSS width transition
 * - withRepeat + withSequence 脉冲 → CSS @keyframes pulse
 * - FadeIn/FadeOut → CSS opacity transition
 */

interface RagOmniIndicatorProps {
  messageId: string
  ragState?: RagIndicatorState
  isGenerating?: boolean
  referencesCount?: number
}

export function RagOmniIndicator({ messageId, ragState, isGenerating, referencesCount = 0 }: RagOmniIndicatorProps) {
  const [expanded, setExpanded] = useState(false)
  const progressRef = useRef<HTMLDivElement>(null)

  // 状态判定
  const isActiveRetrieval = ragState?.stage !== undefined && ragState?.status === 'retrieving'
  const isActiveKG = ragState?.kgStatus === 'extracting'
  const isActiveArchive = ragState?.status === 'chunking' || ragState?.status === 'summarizing' || ragState?.status === 'vectorizing'
  const isCompleted = ragState?.history !== undefined || referencesCount > 0

  // 进度条动画
  useEffect(() => {
    let target = 0
    if (isActiveRetrieval) target = ragState?.progress || 0
    else if (isActiveKG) target = ragState?.kgProgress || 0
    else if (isActiveArchive) target = ragState?.progress || 0
    else if (isCompleted) target = 100

    if (progressRef.current) {
      progressRef.current.style.width = `${target}%`
      progressRef.current.style.opacity = (target > 0 && target < 100) ? '1' : '0'
    }
  }, [isActiveRetrieval, isActiveKG, isActiveArchive, isCompleted, ragState?.progress, ragState?.kgProgress])

  const getStatusContent = () => {
    if (isActiveRetrieval) {
      const dict: Record<string, string> = {
        'INTENT': '语义意图识别...',
        'API_TX': '上推请求数据...',
        'API_WAIT': '模型推理中...',
        'API_RX': '接收 RAG 响应...',
        'RERANK': '精排：深度相关性重排序...',
        'KG_SCAN': '知识图谱：关系溯源...',
        'KG_JIT': '图谱：正在实时关联知识...',
      }
      const label = (ragState?.subStage && dict[ragState.subStage]) ||
        (ragState?.networkStats && !ragState.networkStats.rxBytes ? '模型推理中...' : '知识库检索中...')
      return { label, color: 'var(--accent)', iconType: 'search' }
    }

    if (isActiveKG) {
      const kgLabels: Record<string, string> = {
        'ENTITY_PARSE': '图谱：实体识别...',
        'GRAPH_WALK': '图谱：构建逻辑链...',
      }
      return {
        label: (ragState?.subStage && kgLabels[ragState.subStage]) || '全域知识同步...',
        color: '#60a5fa',
        iconType: 'brainCircuit',
      }
    }

    if (isActiveArchive) {
      const label = ragState?.status === 'chunking' ? '记忆：语义切片...' :
        ragState?.status === 'vectorizing' ? '记忆：向量化存储...' : '记忆：智能摘要...'
      return { label, color: '#34d399', iconType: 'database' }
    }

    if (isCompleted) {
      const kpLabel = `已关联 ${referencesCount} 个知识点`
      let statusLabel = referencesCount > 0 ? kpLabel : '未匹配相关知识'
      if (ragState?.history?.type === 'retrieved') {
        statusLabel = referencesCount > 0 ? `${kpLabel} (就绪)` : '检索完成 (无匹配)'
      } else if (ragState?.history) {
        const typeLabel = ragState.history.type === 'summarized' ? '已摘要' : '已归档'
        statusLabel = referencesCount > 0 ? `${kpLabel} (${typeLabel})` : `处理完成 (${typeLabel})`
      }
      return {
        label: statusLabel,
        color: 'var(--text-secondary)',
        iconType: ragState?.history?.type === 'retrieved' ? 'library' : 'database',
        showCaret: referencesCount > 0,
      }
    }

    if (isGenerating) {
      return {
        label: referencesCount > 0 ? `已关联 ${referencesCount} 个知识点` : '模型思考中...',
        color: 'var(--accent)',
        iconType: 'zap',
      }
    }

    return null
  }

  const status = getStatusContent()
  if (!status) return null

  return (
    <div className="rag-indicator" onClick={() => status.showCaret && setExpanded(!expanded)}>
      <div className="rag-content">
        <div className="rag-left">
          <span className={`rag-icon ${isActiveRetrieval || isActiveKG || isActiveArchive ? 'rag-pulse' : ''}`}>
            {status.iconType === 'search' && (
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke={status.color} strokeWidth="2"><circle cx="11" cy="11" r="8" /><line x1="21" y1="21" x2="16.65" y2="16.65" /></svg>
            )}
            {status.iconType === 'brainCircuit' && (
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#60a5fa" strokeWidth="2"><path d="M12 2a7 7 0 0 1 7 7c0 2.38-1.19 4.47-3 5.74V17a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2v-2.26C6.19 13.47 5 11.38 5 9a7 7 0 0 1 7-7Z" /></svg>
            )}
            {status.iconType === 'database' && (
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#34d399" strokeWidth="2"><ellipse cx="12" cy="5" rx="9" ry="3" /><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3" /><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5" /></svg>
            )}
            {status.iconType === 'library' && (
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke={status.color} strokeWidth="2"><path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20" /></svg>
            )}
            {status.iconType === 'zap' && (
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke={status.color} strokeWidth="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" /></svg>
            )}
          </span>
          <span className="rag-label" style={{ color: status.color }}>{status.label}</span>
          {status.showCaret && (
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="var(--text-tertiary)" strokeWidth="2"
              style={{ transform: expanded ? 'rotate(180deg)' : 'rotate(0deg)', transition: 'transform 0.2s' }}>
              <polyline points="6 9 12 15 18 9" />
            </svg>
          )}
        </div>

        <div className="rag-right">
          {isActiveRetrieval && ragState?.networkStats && (
            <>
              {ragState.networkStats.txBytes !== undefined && (
                <span className="rag-stat">↑{Math.round(ragState.networkStats.txBytes / 1024)}K</span>
              )}
              {ragState.networkStats.rxBytes !== undefined && (
                <span className="rag-stat">↓{Math.round(ragState.networkStats.rxBytes / 1024)}K</span>
              )}
            </>
          )}
          {!isActiveRetrieval && isCompleted && (
            <span className="rag-stat">{ragState?.history?.chunkCount || 0} Chunks</span>
          )}
        </div>
      </div>

      {/* Progress Rail */}
      <div className="rag-progress-rail">
        <div
          ref={progressRef}
          className="rag-progress-bar"
          style={{ backgroundColor: status.color || 'var(--accent)' }}
        />
      </div>
    </div>
  )
}
