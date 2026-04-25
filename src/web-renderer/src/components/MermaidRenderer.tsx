import { useEffect, useState } from 'react'

/**
 * Mermaid 图表渲染器 — iframe 隔离方案
 *
 * 问题：mermaid.render() 在 RN WebView 中会导致整个 JS 上下文崩溃（白屏），
 * try-catch 无法捕获（引擎级崩溃，非 JS 异常）。
 *
 * 方案：将 mermaid 加载和渲染完全隔离到独立 iframe 中执行。
 * 即使 mermaid 崩溃，也只影响 iframe，主文档不受影响。
 * 通过 postMessage 在主文档与 iframe 之间传递渲染结果。
 *
 * 副作用：移除 mermaid 动态导入 → bundle 从 ~4.8MB 降至 ~2.1MB
 */

interface MermaidRendererProps {
  code: string
}

// ─── iframe 单例管理 ───

const CDN_URL = 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js'
const RENDER_TIMEOUT = 20_000

let iframeEl: HTMLIFrameElement | null = null
let iframeReady = false
let idSeq = 0

const renderCallbacks = new Map<string, {
  resolve: (svg: string) => void
  reject: (err: Error) => void
}>()

const pendingQueue: Array<{
  id: string
  code: string
  resolve: (svg: string) => void
  reject: (err: Error) => void
}> = []

function onIframeMessage(e: MessageEvent) {
  if (e.data?.source !== 'mermaid-iso') return

  if (e.data.type === 'ready') {
    iframeReady = true
    // 清空排队请求
    for (const item of pendingQueue) {
      renderCallbacks.set(item.id, { resolve: item.resolve, reject: item.reject })
      iframeEl?.contentWindow?.postMessage(
        { type: 'render', id: item.id, code: item.code }, '*'
      )
    }
    pendingQueue.length = 0
    return
  }

  if (e.data.type === 'load-failed') {
    // CDN 加载失败，拒绝所有排队的请求
    const err = new Error('Mermaid CDN 加载失败，请检查网络连接')
    for (const item of pendingQueue) {
      item.reject(err)
    }
    pendingQueue.length = 0
    // 也拒绝所有等待中的回调
    for (const [, cb] of renderCallbacks) {
      cb.reject(err)
    }
    renderCallbacks.clear()
    return
  }

  const cb = renderCallbacks.get(e.data.id)
  if (!cb) return
  renderCallbacks.delete(e.data.id)

  if (e.data.type === 'result') cb.resolve(e.data.svg)
  else if (e.data.type === 'error') cb.reject(new Error(e.data.error || '渲染失败'))
}

function ensureIframe(): HTMLIFrameElement {
  if (iframeEl) return iframeEl

  window.addEventListener('message', onIframeMessage)

  const isDark = document.documentElement.getAttribute('data-theme') === 'dark'

  iframeEl = document.createElement('iframe')
  iframeEl.style.cssText =
    'position:fixed;left:-9999px;width:800px;height:600px;border:none;opacity:0;pointer-events:none;z-index:-1'
  iframeEl.setAttribute('aria-hidden', 'true')
  iframeEl.setAttribute('tabindex', '-1')

  iframeEl.srcdoc = `<!DOCTYPE html><html><head><meta charset="utf-8">
<script src="${CDN_URL}" onerror="handleLoadError()"><\/script>
</head><body><script>
function handleLoadError(){
  window.parent.postMessage({source:'mermaid-iso',type:'load-failed'},'*');
}
if(typeof mermaid==='undefined'){
  handleLoadError();
} else {
  mermaid.initialize({
    startOnLoad:false,
    theme:'${isDark ? 'dark' : 'default'}',
    securityLevel:'loose',
    fontFamily:'inherit'
  });
  window.addEventListener('message',async function(e){
    if(e.data.type!=='render')return;
    try{
      var old=document.getElementById(e.data.id);
      if(old)old.remove();
      var res=await mermaid.render(e.data.id,e.data.code);
      window.parent.postMessage({source:'mermaid-iso',type:'result',id:e.data.id,svg:res.svg},'*');
    }catch(err){
      window.parent.postMessage({source:'mermaid-iso',type:'error',id:e.data.id,error:err.message||'Render failed'},'*');
    }
  });
  window.parent.postMessage({source:'mermaid-iso',type:'ready'},'*');
}
<\/script></body></html>`

  document.body.appendChild(iframeEl)
  return iframeEl
}

/** 发送渲染请求到 iframe，返回 SVG 字符串 */
function renderMermaid(code: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const id = `m${++idSeq}`

    const timer = setTimeout(() => {
      renderCallbacks.delete(id)
      reject(new Error('渲染超时（20s）'))
    }, RENDER_TIMEOUT)

    const wrappedResolve = (svg: string) => { clearTimeout(timer); resolve(svg) }
    const wrappedReject = (err: Error) => { clearTimeout(timer); reject(err) }

    if (iframeReady) {
      renderCallbacks.set(id, { resolve: wrappedResolve, reject: wrappedReject })
      ensureIframe().contentWindow?.postMessage(
        { type: 'render', id, code }, '*'
      )
    } else {
      pendingQueue.push({ id, code, resolve: wrappedResolve, reject: wrappedReject })
      ensureIframe() // 触发创建（如果尚未创建）
    }
  })
}

/** 重置 iframe（CDN 失败后可调用以重试） */
function resetIframe() {
  if (iframeEl) {
    window.removeEventListener('message', onIframeMessage)
    iframeEl.remove()
    iframeEl = null
    iframeReady = false
    renderCallbacks.clear()
    pendingQueue.length = 0
  }
}

// ─── React 组件 ───

export function MermaidRenderer({ code }: MermaidRendererProps) {
  const [svg, setSvg] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  const cleanContent = code
    .replace(/^```mermaid\n?/, '')
    .replace(/```$/, '')
    .trim()

  useEffect(() => {
    let cancelled = false
    setSvg(null)
    setError(null)
    setLoading(true)

    renderMermaid(cleanContent)
      .then(svgStr => {
        if (!cancelled) { setSvg(svgStr); setLoading(false) }
      })
      .catch(err => {
        if (!cancelled) {
          console.error('[MermaidRenderer] error:', err)
          setError(err.message || '渲染失败')
          setLoading(false)
        }
      })

    return () => { cancelled = true }
  }, [cleanContent])

  if (error) {
    return (
      <div className="chart-card">
        <div className="chart-badge">
          <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="var(--accent)" strokeWidth="2">
            <circle cx="12" cy="12" r="10" /><path d="M12 6v6l4 2" />
          </svg>
          <span className="chart-badge-text">MERMAID</span>
        </div>
        <div className="chart-error">
          图表渲染失败: {error}
          <div style={{ marginTop: 8 }}>
            <button
              className="action-btn"
              onClick={() => { resetIframe(); setError(null); setLoading(true) }}
              style={{ fontSize: 12, color: 'var(--accent)' }}
            >
              重试
            </button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="chart-card">
      <div className="chart-badge">
        <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="var(--accent)" strokeWidth="2">
          <circle cx="12" cy="12" r="10" /><path d="M12 6v6l4 2" />
        </svg>
        <span className="chart-badge-text">MERMAID</span>
      </div>
      <div
        style={{
          padding: '16px',
          minHeight: loading ? '120px' : 'auto',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          overflow: 'auto',
        }}
      >
        {svg && <div dangerouslySetInnerHTML={{ __html: svg }} />}
        {loading && !svg && <div className="chart-loading">渲染中…</div>}
      </div>
    </div>
  )
}
