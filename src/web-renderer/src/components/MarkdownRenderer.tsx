import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import { CodeBlock } from './CodeBlock'
import { MermaidRenderer } from './MermaidRenderer'
import { EChartsRenderer } from './EChartsRenderer'

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
              const lang = match[1].toLowerCase()
              // Mermaid / ECharts 图表 — 委托专用渲染器
              if (lang === 'mermaid' || lang === 'mmd') {
                return <MermaidRenderer code={codeString} />
              }
              if (lang === 'echarts') {
                return <EChartsRenderer code={codeString} />
              }
              return <CodeBlock code={codeString} language={match[1]} />
            }

            return (
              <code {...props}>
                {children}
              </code>
            )
          },
          pre({ children }) {
            return <>{children}</>
          },
          /* 表格 — 对齐 useMarkdownRules table/th/td */
          table({ children }) {
            return (
              <div style={{ overflowX: 'auto', margin: '10px 0' }}>
                <table style={{
                  borderCollapse: 'collapse',
                  width: 'max-content',
                  minWidth: '100%',
                  fontSize: '13px',
                  border: '1px solid var(--border-default)',
                  borderRadius: '6px',
                  overflow: 'hidden',
                }}>
                  {children}
                </table>
              </div>
            )
          },
          th({ children }) {
            return (
              <th style={{
                minWidth: 80,
                maxWidth: 200,
                padding: '8px 12px',
                backgroundColor: 'var(--bg-secondary)',
                borderBottom: '1px solid var(--border-default)',
                borderRight: '1px solid var(--border-default)',
                fontWeight: 'bold',
                fontSize: '13px',
                color: 'var(--text-primary)',
                textAlign: 'left',
              }}>
                {children}
              </th>
            )
          },
          td({ children }) {
            return (
              <td style={{
                minWidth: 80,
                maxWidth: 200,
                padding: '8px 12px',
                borderBottom: '1px solid var(--border-default)',
                borderRight: '1px solid var(--border-default)',
                fontSize: '13px',
                color: 'var(--text-secondary)',
                lineHeight: '20px',
              }}>
                {children}
              </td>
            )
          },
          /* 引用 — 对齐 markdown-theme.ts blockquote */
          blockquote({ children }) {
            return (
              <blockquote style={{
                backgroundColor: 'var(--bg-secondary)',
                borderLeft: '3px solid var(--accent)',
                paddingInline: 12,
                paddingBlock: 8,
                borderRadius: 'var(--radius-sm)',
                margin: '8px 0',
                color: 'var(--text-secondary)',
              }}>
                {children}
              </blockquote>
            )
          },
          /* 链接 — 对齐 useMarkdownRules link */
          a({ href, children }) {
            return (
              <a
                href={href}
                style={{
                  color: 'var(--accent)',
                  textDecorationColor: 'color-mix(in srgb, var(--accent) 40%, transparent)',
                }}
              >
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
