import { useState, useEffect } from 'react'
import type { TaskState, TaskStep } from '../types/bridge'

/**
 * TaskMonitor — 多步骤任务监控面板（WebView 版）
 * 对齐 RN: src/features/chat/components/TaskMonitor.tsx
 *
 * 智能展开：进行中展开，完成后折叠。支持干预决策提示。
 */

interface TaskMonitorProps {
  task: TaskState
  isLatest?: boolean
  pendingIntervention?: string
}

function StepIcon({ status }: { status: TaskStep['status'] }) {
  switch (status) {
    case 'completed':
      return (
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#22c55e" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
          <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" /><polyline points="22 4 12 14.01 9 11.01" />
        </svg>
      )
    case 'in-progress':
      return (
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="var(--accent)" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" className="spin-animation">
          <path d="M21 12a9 9 0 1 1-6.219-8.56" />
        </svg>
      )
    case 'failed':
      return (
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#ef4444" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="12" cy="12" r="10" /><line x1="15" y1="9" x2="9" y2="15" /><line x1="9" y1="9" x2="15" y2="15" />
        </svg>
      )
    case 'skipped':
      return (
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#fbbf24" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <polygon points="5 4 15 12 5 20 5 4" /><line x1="19" y1="5" x2="19" y2="19" />
        </svg>
      )
    default:
      return (
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="var(--text-tertiary)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="12" cy="12" r="10" />
        </svg>
      )
  }
}

function getStepStatusClass(status: TaskStep['status']): string {
  switch (status) {
    case 'completed': return 'task-step-completed'
    case 'in-progress': return 'task-step-active'
    default: return 'task-step-pending'
  }
}

export function TaskMonitor({ task, isLatest = true, pendingIntervention }: TaskMonitorProps) {
  const [expanded, setExpanded] = useState(
    !!pendingIntervention || (isLatest && task.status === 'in-progress')
  )

  useEffect(() => {
    if (pendingIntervention) setExpanded(true)
  }, [pendingIntervention])

  const currentStepIndex = task.steps.findIndex(s => s.status === 'in-progress')
  let displayStepIndex = 0
  if (task.status === 'completed') {
    displayStepIndex = task.steps.length - 1
  } else if (currentStepIndex !== -1) {
    displayStepIndex = currentStepIndex
  } else {
    displayStepIndex = task.steps.findIndex(s => s.status !== 'completed')
    if (displayStepIndex === -1) displayStepIndex = task.steps.length - 1
  }

  const progressText = task.status === 'completed' ? '100%' : `${Math.round(task.progress)}%`
  const stepCountText = task.status === 'completed'
    ? `${task.steps.length}/${task.steps.length}`
    : `${Math.min(displayStepIndex + 1, task.steps.length)}/${task.steps.length}`
  const currentStep = task.steps[displayStepIndex] || task.steps[0]

  return (
    <div className="task-monitor" style={{ opacity: isLatest ? 1 : 0.6 }}>
      <div className="task-monitor-inner" style={{
        borderColor: pendingIntervention
          ? (CSS.supports('color', 'var(--color-warning)') ? 'var(--color-warning)' : '#eab308')
          : 'var(--border-glass)',
      }}>
        <button className="task-monitor-header" onClick={() => setExpanded(!expanded)}>
          <div className="task-header-left">
            <div className={`task-status-dot ${task.status === 'in-progress' ? 'task-dot-pulse' : task.status === 'completed' ? 'task-dot-done' : 'task-dot-idle'}`} />
            <span className="task-title">{task.title}</span>
            {task.status === 'completed' && (
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#22c55e" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" /><polyline points="22 4 12 14.01 9 11.01" />
              </svg>
            )}
            <span className="task-progress-pill">{stepCountText} · {progressText}</span>
          </div>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="var(--text-tertiary)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"
            style={{ transform: expanded ? 'rotate(180deg)' : 'rotate(0deg)', transition: 'transform 0.2s' }}>
            <polyline points="6 9 12 15 18 9" />
          </svg>
        </button>

        {/* Intervention Card */}
        {pendingIntervention && (
          <div className="task-intervention-card">
            <div className="task-intervention-header">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#eab308" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="spin-animation">
                <path d="M21 12a9 9 0 1 1-6.219-8.56" />
              </svg>
              <span className="task-intervention-label">Decision Required</span>
            </div>
            <div className="task-intervention-text">{pendingIntervention}</div>
            <div className="task-intervention-hint">Please reply to continue...</div>
          </div>
        )}

        {/* Expanded Step List */}
        {expanded && (
          <div className="task-steps">
            {task.steps.map((step, index) => (
              <div key={step.id || `step-${index}`} className={`task-step ${getStepStatusClass(step.status)}`}>
                <div className="task-step-icon"><StepIcon status={step.status} /></div>
                <div className="task-step-text">
                  <div className="task-step-title">{step.title}</div>
                  {step.description && <div className="task-step-desc">{step.description}</div>}
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Collapsed Preview */}
        {!expanded && task.status === 'in-progress' && (
          <div className="task-collapsed-preview">
            Current: {currentStep?.title}
          </div>
        )}
      </div>
    </div>
  )
}
