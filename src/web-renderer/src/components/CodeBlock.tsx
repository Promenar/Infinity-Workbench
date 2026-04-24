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
    <div style={{ margin: '8px 0', borderRadius: '12px', overflow: 'hidden' }}>
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        padding: '6px 12px',
        backgroundColor: isDark ? '#1a1a2e' : '#e8ecf0',
        fontSize: '12px',
        color: isDark ? '#888' : '#666',
      }}>
        <span>{language.toUpperCase()}</span>
        <button
          onClick={handleCopy}
          style={{
            background: 'none', border: 'none',
            color: isDark ? '#aaa' : '#666', cursor: 'pointer',
            fontSize: '12px', padding: '2px 8px', borderRadius: '4px',
          }}
        >
          {copied ? '已复制' : '复制'}
        </button>
      </div>
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
