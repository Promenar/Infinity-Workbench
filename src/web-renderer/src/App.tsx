import { useState, useEffect, useCallback } from 'react'
import { initBridge, onRNMessage, sendReady } from './bridge'
import { applyTheme } from './bridge/theme'
import { MessageList } from './components/MessageList'
import type { RNToWebMessage, WebViewState, WebViewThemePayload } from './types/bridge'

const DEFAULT_THEME: WebViewThemePayload = {
  isDark: false,
  accentColor: '#6366f1',
  palette: {
    50: '#eef2ff', 100: '#e0e7ff', 200: '#c7d2fe', 300: '#a5b4fc',
    400: '#818cf8', 500: '#6366f1', 600: '#4f46e5', 700: '#4338ca',
    800: '#3730a3', 900: '#312e81', opacity10: '#6366f11a',
    opacity20: '#6366f133', opacity30: '#6366f14d',
  },
}

function App() {
  const [state, setState] = useState<WebViewState>({
    messages: [],
    theme: DEFAULT_THEME,
    isGenerating: false,
  })

  const handleMessage = useCallback((msg: RNToWebMessage) => {
    switch (msg.type) {
      case 'INIT':
        applyTheme(msg.payload.theme)
        setState(prev => ({
          ...prev,
          messages: msg.payload.messages,
          theme: msg.payload.theme,
          sessionId: msg.payload.sessionId,
        }))
        break

      case 'APPEND_MESSAGE':
        setState(prev => ({
          ...prev,
          messages: [...prev.messages, msg.payload],
        }))
        break

      case 'UPDATE_MESSAGE':
        setState(prev => ({
          ...prev,
          messages: prev.messages.map(m =>
            m.id === msg.payload.id ? { ...m, ...msg.payload.partial } : m
          ),
        }))
        break

      case 'STREAM_CHUNK':
        setState(prev => ({
          ...prev,
          messages: prev.messages.map(m =>
            m.id === msg.payload.messageId
              ? { ...m, content: m.content + msg.payload.content }
              : m
          ),
        }))
        break

      case 'DELETE_MESSAGE':
        setState(prev => ({
          ...prev,
          messages: prev.messages.filter(m => m.id !== msg.payload.id),
        }))
        break

      case 'THEME_CHANGE':
        applyTheme(msg.payload)
        setState(prev => ({ ...prev, theme: msg.payload }))
        break

      case 'SCROLL_TO_BOTTOM':
        window.dispatchEvent(new CustomEvent('scroll-to-bottom', {
          detail: { animated: msg.payload?.animated ?? true },
        }))
        break

      case 'SET_GENERATING':
        setState(prev => ({ ...prev, isGenerating: msg.payload.isGenerating }))
        break
    }
  }, [])

  useEffect(() => {
    initBridge()
    onRNMessage(handleMessage)
    sendReady()
  }, [handleMessage])

  return (
    <MessageList
      messages={state.messages}
      theme={state.theme}
      isGenerating={state.isGenerating}
      sessionId={state.sessionId}
    />
  )
}

export default App
