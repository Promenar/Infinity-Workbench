import { useState } from 'react'
import { Highlight, themes } from 'prism-react-renderer'

interface CodeBlockProps {
  code: string
  language: string
}

const LANG_MAP: Record<string, string> = {
  js: 'javascript', ts: 'typescript', py: 'python',
  rb: 'ruby', sh: 'bash', yml: 'yaml', md: 'markdown',
  jsx: 'jsx', tsx: 'tsx', json: 'json',
}

export function CodeBlock({ code, language }: CodeBlockProps) {
  const [copied, setCopied] = useState(false)
  const isDark = document.documentElement.getAttribute('data-theme') === 'dark'
  const prismLang = LANG_MAP[language.toLowerCase()] || language.toLowerCase()

  const handleCopy = () => {
    navigator.clipboard?.writeText(code).catch(() => {})
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <div className="code-block">
      {/* 语言标签 + 复制按钮 — Stitch 风格 header */}
      <div className="code-block-header">
        <span className="code-block-lang">{language.toUpperCase() || 'CODE'}</span>
        <button
          className="code-block-copy"
          onClick={handleCopy}
          onMouseEnter={e => { e.currentTarget.style.background = 'var(--bg-secondary)'; e.currentTarget.style.color = 'var(--text-secondary)'; }}
          onMouseLeave={e => { e.currentTarget.style.background = 'none'; e.currentTarget.style.color = 'var(--text-tertiary)'; }}
        >
          {copied ? '已复制' : '复制'}
        </button>
      </div>
      {/* 代码高亮区域 */}
      <Highlight
        theme={isDark ? themes.nightOwl : themes.nightOwlLight}
        code={code}
        language={prismLang}
      >
        {({ style, tokens, getLineProps, getTokenProps }) => (
          <pre style={{
            ...style, margin: 0, padding: '12px',
            overflowX: 'auto', fontSize: '13px', lineHeight: '1.5',
            fontFamily: 'var(--font-mono)',
            background: 'transparent',
          }}>
            {tokens.map((line, i) => (
              <div key={i} {...getLineProps({ line })}>
                {line.map((token, key) => (
                  <span key={key} {...getTokenProps({ token })} />
                ))}
              </div>
            ))}
          </pre>
        )}
      </Highlight>
    </div>
  )
}
