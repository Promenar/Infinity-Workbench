import { useState, useEffect, useRef } from 'react'
import type { ExecutionStep } from '../types/bridge'
import { postToRN } from '../bridge'

/**
 * ToolExecutionTimeline — 工具执行时间线（WebView 版）
 * 对齐 RN: src/components/skills/ToolExecutionTimeline.tsx (750行)
 *
 * 核心功能：
 * - 可折叠 Header（思考轮数 + 工具轮数）
 * - 时间线列表：圆形图标 + 连接线 + 可展开内容
 * - 展开/折叠自动管理（生成中展开，完成后折叠）
 * - 内容类型：思考(Markdown)、工具参数(JSON)、搜索结果、图片、干预
 * - 底部干预输入框
 *
 * RN 依赖替代：
 * - BlurView → CSS backdrop-filter: blur()
 * - Reanimated FadeIn/Layout → CSS transition
 * - ScrollView → CSS overflow-y: auto
 * - ImageView (图片全屏) → 简化为 img 标签
 * - expo-sharing → 通过 Bridge postToRN 委托 RN 侧
 */

// ─── 图标组件 ───

function StepIcon({ type, toolName }: { type: string; toolName?: string }) {
  const color = '#A0A0A0'
  if (type === 'thinking') return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2"><path d="M12 2a7 7 0 0 1 7 7c0 2.38-1.19 4.47-3 5.74V17a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2v-2.26C6.19 13.47 5 11.38 5 9a7 7 0 0 1 7-7Z" /></svg>
  if (type === 'error') return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#FF6B6B" strokeWidth="2"><circle cx="12" cy="12" r="10" /><line x1="15" y1="9" x2="9" y2="15" /><line x1="9" y1="9" x2="15" y2="15" /></svg>
  if (type === 'intervention_required') {
    if (toolName === 'Loop Limit') return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" strokeWidth="2"><polyline points="1 4 1 10 7 10" /><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" /></svg>
    return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#f59e0b" strokeWidth="2"><path d="M18 11V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2" /><path d="M14 10V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v2" /><path d="M10 10.5V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2v8" /><path d="M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15" /></svg>
  }
  if (type === 'intervention_result') return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#10b981" strokeWidth="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" /><circle cx="12" cy="7" r="4" /></svg>
  if (type === 'native_search' || type === 'native_search_result') return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#a855f7" strokeWidth="2"><circle cx="11" cy="11" r="8" /><line x1="21" y1="21" x2="16.65" y2="16.65" /></svg>
  if (type === 'throttled') return <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" strokeWidth="3"><polyline points="1 4 1 10 7 10" /><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" /></svg>
  if (toolName === 'search_internet') return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#4F8EF7" strokeWidth="2"><circle cx="12" cy="12" r="10" /><line x1="2" y1="12" x2="22" y2="12" /><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" /></svg>
  if (toolName === 'query_vector_db') return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#FF9F43" strokeWidth="2"><ellipse cx="12" cy="5" rx="9" ry="3" /><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3" /><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5" /></svg>
  if (toolName === 'generate_image') return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#2ED573" strokeWidth="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2" /><circle cx="8.5" cy="8.5" r="1.5" /><polyline points="21 15 16 10 5 21" /></svg>
  return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="2"><polyline points="4 17 10 11 4 5" /><line x1="12" y1="19" x2="20" y2="19" /></svg>
}

// ─── 搜索结果子组件 ───

function SearchResultsList({ sources }: { sources: Array<{ title?: string; url?: string; snippet?: string; content?: string }> }) {
  if (!sources || sources.length === 0) return null
  return (
    <div className="tl-search-results">
      {sources.map((source, idx) => (
        <div key={idx} className="tl-search-item">
          {source.url && (
            <a href={source.url} className="tl-search-link" target="_blank" rel="noopener">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#4F8EF7" strokeWidth="2"><circle cx="11" cy="11" r="8" /><line x1="21" y1="21" x2="16.65" y2="16.65" /></svg>
              <span>{source.title || 'Source'}</span>
            </a>
          )}
          {source.url && <div className="tl-search-url">{source.url}</div>}
          <div className="tl-search-snippet">{source.snippet || source.content}</div>
        </div>
      ))}
    </div>
  )
}

// ─── 干预 UI 子组件 ───

function InterventionUI({ sessionId, toolName }: { sessionId: string; toolName?: string }) {
  const [inputValue, setInputValue] = useState('')
  const isContinuation = toolName === 'Loop Limit'
  const mainColor = isContinuation ? '#3b82f6' : '#d97706'

  const handleApprove = () => {
    postToRN({
      type: 'APPROVE_ACTION',
      sessionId,
      approved: true,
      instruction: inputValue.trim() || undefined,
    })
  }

  const handleReject = () => {
    postToRN({ type: 'APPROVE_ACTION', sessionId, approved: false })
  }

  return (
    <div className="tl-intervention">
      <div className="tl-intervention-actions">
        <button className="tl-intervention-btn tl-intervention-reject" onClick={handleReject}>
          {isContinuation ? 'End Task' : 'Reject'}
        </button>
        <button className="tl-intervention-btn tl-intervention-approve" style={{ backgroundColor: mainColor }} onClick={handleApprove}>
          {isContinuation ? 'Continue (+10)' : (inputValue.trim() ? '携带指令批准' : '批准并执行')}
        </button>
      </div>
    </div>
  )
}

// ─── 时间线项组件 ───

function TimelineItem({ step, isLast, isMessageGenerating, sessionId }: {
  step: ExecutionStep
  isLast: boolean
  isMessageGenerating?: boolean
  sessionId?: string
}) {
  const [expanded, setExpanded] = useState(false)
  const [timeLeft, setTimeLeft] = useState(0)

  // 思考步骤自动展开/折叠
  useEffect(() => {
    if (step.type === 'thinking') {
      setExpanded(!!(isLast && isMessageGenerating))
    } else {
      setExpanded(false)
    }
  }, [step.type, isLast, isMessageGenerating])

  // 节流倒计时
  useEffect(() => {
    if (step.type === 'throttled' && step.throttledUntil) {
      const timer = setInterval(() => {
        const diff = Math.max(0, Math.ceil((step.throttledUntil! - Date.now()) / 1000))
        setTimeLeft(diff)
        if (diff <= 0) clearInterval(timer)
      }, 500)
      return () => clearInterval(timer)
    }
  }, [step.throttledUntil, step.type])

  const getTitle = () => {
    switch (step.type) {
      case 'thinking': return isLast && isMessageGenerating ? 'Thinking...' : 'Thought'
      case 'tool_call': return `Using ${step.toolName || 'tool'}`
      case 'tool_result': return `Result (${step.toolName || 'tool'})`
      case 'error': return 'Error'
      case 'intervention_required': return 'Approval Required'
      case 'intervention_result': return 'Intervention Taken'
      case 'native_search': return '正在使用原生网络搜索'
      case 'native_search_result': return '已获取执行结果'
      case 'throttled': return '调用频率受限 (等待中)'
      default: return step.type
    }
  }

  const getPreview = () => {
    if (step.type === 'tool_call' && step.toolArgs) {
      return JSON.stringify(step.toolArgs).substring(0, 50) + '...'
    }
    if (step.type === 'throttled') return `倒计时: ${timeLeft} 秒`
    if (step.type === 'tool_result') {
      const data = step.data as any
      if (step.toolName === 'query_vector_db' && data?.references) return `${data.references.length} result(s)`
      if (step.toolName === 'search_internet' && data?.sources) return `${data.sources.length} source(s)`
    }
    if (step.type === 'native_search_result') {
      const data = step.data as any
      if (data?.sources) return `${data.sources.length} source(s) (Google)`
    }
    return (step.content || '').substring(0, 60) + ((step.content || '').length > 60 ? '...' : '')
  }

  const isIntervention = step.type === 'intervention_required'
  const isLoopLimit = step.toolName === 'Loop Limit'
  const data = step.data as any
  const isRagResult = step.toolName === 'query_vector_db' && step.type === 'tool_result' && data?.references
  const isSearchResult = (step.toolName === 'search_internet' && step.type === 'tool_result' && data?.sources) ||
    (step.type === 'native_search_result' && data?.sources)

  return (
    <div className="tl-item">
      {/* 图标 + 连接线 */}
      <div className="tl-item-rail">
        <div className={`tl-item-icon ${isIntervention ? (isLoopLimit ? 'tl-icon-blue' : 'tl-icon-amber') : step.type === 'error' ? 'tl-icon-error' : ''}`}>
          <StepIcon type={step.type} toolName={step.toolName} />
        </div>
        {!isLast && <div className="tl-connector" />}
      </div>

      {/* 内容区 */}
      <div className="tl-item-body">
        <button
          className={`tl-item-header ${isIntervention ? (isLoopLimit ? 'tl-header-blue' : 'tl-header-amber') : ''}`}
          onClick={() => setExpanded(!expanded)}
        >
          <div className="tl-item-title-wrap">
            <span className={`tl-item-title ${isIntervention ? (isLoopLimit ? 'tl-title-blue' : 'tl-title-amber') : ''}`}>
              {getTitle()}
            </span>
            {!expanded && <span className="tl-item-preview">{getPreview()}</span>}
          </div>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#666" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"
            style={{ transform: expanded ? 'rotate(90deg)' : 'rotate(0deg)', transition: 'transform 0.15s', flexShrink: 0 }}>
            <polyline points="9 18 15 12 9 6" />
          </svg>
        </button>

        {/* 展开内容 */}
        {expanded && (
          <div className="tl-item-expanded">
            {/* 工具参数 */}
            {step.type === 'tool_call' && step.toolArgs && (
              <pre className="tl-tool-args">{JSON.stringify(step.toolArgs, null, 2)}</pre>
            )}

            {/* RAG 引用 */}
            {isRagResult && data.references && (
              <div className="tl-rag-refs">
                {data.references.map((ref: any, i: number) => (
                  <div key={i} className="tl-rag-ref">
                    <span className="tl-rag-ref-score">{Math.round((ref.score || 0) * 100)}%</span>
                    <span className="tl-rag-ref-text">{ref.content || ref.text || ''}</span>
                  </div>
                ))}
              </div>
            )}

            {/* 搜索结果 */}
            {isSearchResult && <SearchResultsList sources={data.sources} />}

            {/* 通用内容 */}
            {!isRagResult && !isSearchResult && step.content && (
              <div className="tl-item-content">{step.content}</div>
            )}
          </div>
        )}

        {/* 干预 UI */}
        {step.type === 'intervention_required' && isLast && sessionId && (
          <InterventionUI sessionId={sessionId} toolName={step.toolName} />
        )}
      </div>
    </div>
  )
}

// ─── 底部干预输入 ───

function LoopActiveIntervention({ sessionId }: { sessionId?: string }) {
  const [value, setValue] = useState('')

  if (!sessionId) return null

  const handleSubmit = () => {
    if (value.trim()) {
      postToRN({ type: 'SET_INTERVENTION', sessionId, instruction: value.trim() })
      setValue('')
    }
  }

  return (
    <div className="tl-loop-intervention">
      <div className="tl-loop-input-wrap">
        <input
          className="tl-loop-input"
          value={value}
          onChange={e => setValue(e.target.value)}
          placeholder="Direct agent..."
          onKeyDown={e => { if (e.key === 'Enter') handleSubmit() }}
        />
        <div className="tl-loop-send">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><line x1="22" y1="2" x2="11" y2="13" /><polygon points="22 2 15 22 11 13 2 9 22 2" /></svg>
        </div>
      </div>
    </div>
  )
}

// ─── 主组件 ───

interface ToolExecutionTimelineProps {
  steps: ExecutionStep[]
  isMessageGenerating?: boolean
  sessionId?: string
}

export function ToolExecutionTimeline({ steps, isMessageGenerating, sessionId }: ToolExecutionTimelineProps) {
  const [isCollapsed, setIsCollapsed] = useState(!isMessageGenerating)
  const [hasManuallyToggled, setHasManuallyToggled] = useState(false)
  const wasEverGenerating = useRef(isMessageGenerating)
  const scrollRef = useRef<HTMLDivElement>(null)
  const autoScrollRef = useRef(true)

  const stepsCount = steps.length
  const thoughtsCount = steps.filter(s => s.type === 'thinking').length
  const toolsCount = steps.filter(s => s.type === 'tool_result').length
  const lastStepLen = steps.length > 0 ? (steps[steps.length - 1].content?.length || 0) : 0

  // 自动滚动
  useEffect(() => {
    if (autoScrollRef.current && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [stepsCount, lastStepLen])

  // 自动折叠/展开
  useEffect(() => {
    if (hasManuallyToggled) return
    if (isMessageGenerating) wasEverGenerating.current = true
    if (!wasEverGenerating.current) return

    if (isMessageGenerating) {
      const timer = setTimeout(() => setIsCollapsed(false), 150)
      return () => clearTimeout(timer)
    } else if (stepsCount > 0) {
      const timer = setTimeout(() => setIsCollapsed(true), 1000)
      return () => clearTimeout(timer)
    }
  }, [isMessageGenerating, stepsCount, hasManuallyToggled])

  // 滚动跟踪
  const handleScroll = () => {
    if (!scrollRef.current) return
    const { scrollTop, scrollHeight, clientHeight } = scrollRef.current
    autoScrollRef.current = scrollHeight - scrollTop - clientHeight < 20
  }

  const handleToggle = () => {
    setHasManuallyToggled(true)
    setIsCollapsed(!isCollapsed)
  }

  if (!steps || steps.length === 0) return null

  return (
    <div className="tl-container">
      <div className="tl-blur">
        {/* Header */}
        <button className="tl-header" onClick={handleToggle}>
          <div className="tl-header-left">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="var(--text-tertiary)" strokeWidth="2">
              <path d="M12 2a7 7 0 0 1 7 7c0 2.38-1.19 4.47-3 5.74V17a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2v-2.26C6.19 13.47 5 11.38 5 9a7 7 0 0 1 7-7Z" />
            </svg>
            <span className="tl-header-text">
              {thoughtsCount > 0 && `已思考 ${thoughtsCount} 轮`}
              {thoughtsCount > 0 && toolsCount > 0 && '，'}
              {toolsCount > 0 && `已调用工具 ${toolsCount} 轮`}
              {thoughtsCount === 0 && toolsCount === 0 && (isCollapsed ? '暂无执行详情' : '执行详情')}
            </span>
          </div>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="var(--text-tertiary)" strokeWidth="2"
            style={{ transform: isCollapsed ? 'rotate(0deg)' : 'rotate(180deg)', transition: 'transform 0.2s' }}>
            <polyline points="6 9 12 15 18 9" />
          </svg>
        </button>

        {/* 展开的步骤列表 */}
        {!isCollapsed && (
          <div
            ref={scrollRef}
            className="tl-scroll"
            onScroll={handleScroll}
          >
            {steps.filter(s => s.type !== 'plan_item').map((step, index, arr) => (
              <TimelineItem
                key={step.id}
                step={step}
                isLast={index === arr.length - 1}
                isMessageGenerating={isMessageGenerating}
                sessionId={sessionId}
              />
            ))}
          </div>
        )}
      </div>

      {/* 底部干预输入 */}
      <LoopActiveIntervention sessionId={sessionId} />
    </div>
  )
}
