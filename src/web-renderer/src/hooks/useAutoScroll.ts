import { useRef, useEffect, useCallback } from 'react'

/**
 * 自动滚动追踪 Hook
 *
 * 管理消息列表的滚动行为：
 * - 新消息到达时自动追踪底部（如果用户没有主动上滑）
 * - 监听 RN 侧的强制滚动指令
 */
export function useAutoScroll(deps: unknown[]) {
  const containerRef = useRef<HTMLDivElement>(null)
  const userScrolledAway = useRef(false)

  useEffect(() => {
    const el = containerRef.current
    if (!el) return

    const onScroll = () => {
      const threshold = 100
      const isAtBottom = el.scrollHeight - el.scrollTop - el.clientHeight < threshold
      if (!isAtBottom) {
        userScrolledAway.current = true
      } else {
        userScrolledAway.current = false
      }
    }

    el.addEventListener('scroll', onScroll, { passive: true })
    return () => el.removeEventListener('scroll', onScroll)
  }, [])

  // 自动追踪：新消息到达时如果用户没有主动上滑，则滚动到底部
  useEffect(() => {
    if (!userScrolledAway.current) {
      const el = containerRef.current
      if (el) {
        requestAnimationFrame(() => {
          el.scrollTop = el.scrollHeight
        })
      }
    }
  }, deps)

  // 监听 RN 侧的强制滚动指令
  useEffect(() => {
    const handler = (e: Event) => {
      const { animated } = (e as CustomEvent).detail || {}
      const el = containerRef.current
      if (el) {
        if (animated) {
          el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
        } else {
          el.scrollTop = el.scrollHeight
        }
        userScrolledAway.current = false
      }
    }
    window.addEventListener('scroll-to-bottom', handler)
    return () => window.removeEventListener('scroll-to-bottom', handler)
  }, [])

  const scrollToBottom = useCallback(() => {
    const el = containerRef.current
    if (el) {
      el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
      userScrolledAway.current = false
    }
  }, [])

  const showScrollButton = userScrolledAway.current

  return { containerRef, showScrollButton, scrollToBottom }
}
