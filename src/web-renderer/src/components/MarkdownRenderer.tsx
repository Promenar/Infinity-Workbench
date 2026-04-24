import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import { CodeBlock } from './CodeBlock'

interface MarkdownRendererProps {
  content: string
}

export function MarkdownRenderer({ content }: MarkdownRendererProps) {
  return (
    <div className="markdown-body">
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkMath]}
        rehypePlugins={[rehypeKatex]}
        components={{
          code({ className, children, ...props }) {
            const match = /language-(\w+)/.exec(className || '')
            const codeString = String(children).replace(/\n$/, '')

            if (match) {
              return <CodeBlock code={codeString} language={match[1]} />
            }

            return (
              <code
                style={{
                  backgroundColor: 'var(--code-bg)',
                  padding: '2px 6px',
                  borderRadius: '4px',
                  fontSize: '0.9em',
                  fontFamily: 'var(--font-mono)',
                }}
                {...props}
              >
                {children}
              </code>
            )
          },
          pre({ children }) {
            return <>{children}</>
          },
          table({ children }) {
            return (
              <div style={{ overflowX: 'auto', margin: '8px 0' }}>
                <table style={{
                  borderCollapse: 'collapse',
                  width: '100%',
                  fontSize: '13px',
                }}>
                  {children}
                </table>
              </div>
            )
          },
          th({ children }) {
            return (
              <th style={{
                border: '1px solid var(--border-default)',
                padding: '6px 10px',
                textAlign: 'left',
                backgroundColor: 'var(--bg-secondary)',
                fontWeight: 600,
              }}>
                {children}
              </th>
            )
          },
          td({ children }) {
            return (
              <td style={{
                border: '1px solid var(--border-default)',
                padding: '6px 10px',
              }}>
                {children}
              </td>
            )
          },
          blockquote({ children }) {
            return (
              <blockquote style={{
                borderLeft: '3px solid var(--accent)',
                paddingLeft: '12px',
                margin: '8px 0',
                color: 'var(--text-secondary)',
              }}>
                {children}
              </blockquote>
            )
          },
          a({ href, children }) {
            return (
              <a href={href} style={{ color: 'var(--accent)', textDecoration: 'none' }}>
                {children}
              </a>
            )
          },
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}
