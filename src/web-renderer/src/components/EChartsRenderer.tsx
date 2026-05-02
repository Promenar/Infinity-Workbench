import { useEffect, useRef, useState } from 'react'

/**
 * ECharts 图表渲染器 — iframe 隔离方案
 *
 * 与 MermaidRenderer 相同的隔离策略：
 * echarts 在独立 iframe 中通过 CDN 加载，通过 postMessage 通信。
 * 即使 echarts 崩溃，主文档不受影响。
 *
 * 副作用：移除 echarts 动态导入 → bundle 保持 ~2.1MB
 */

interface EChartsRendererProps {
  code: string
}

const CDN_URL = 'https://cdn.jsdelivr.net/npm/echarts@5/dist/echarts.min.js'
const RENDER_TIMEOUT = 20_000

let iframeEl: HTMLIFrameElement | null = null
let iframeReady = false
let idSeq = 0

const renderCallbacks = new Map<string, {
  resolve: (dataUrl: string) => void
  reject: (err: Error) => void
}>()

const pendingQueue: Array<{
  id: string
  option: string
  resolve: (dataUrl: string) => void
  reject: (err: Error) => void
}> = []

function onIframeMessage(e: MessageEvent) {
  if (e.data?.source !== 'echarts-iso') return

  if (e.data.type === 'ready') {
    iframeReady = true
    for (const item of pendingQueue) {
      renderCallbacks.set(item.id, { resolve: item.resolve, reject: item.reject })
      iframeEl?.contentWindow?.postMessage(
        { type: 'render', id: item.id, option: item.option }, '*'
      )
    }
    pendingQueue.length = 0
    return
  }

  if (e.data.type === 'load-failed') {
    const err = new Error('ECharts CDN 加载失败，请检查网络连接')
    for (const item of pendingQueue) item.reject(err)
    pendingQueue.length = 0
    for (const [, cb] of renderCallbacks) cb.reject(err)
    renderCallbacks.clear()
    return
  }

  const cb = renderCallbacks.get(e.data.id)
  if (!cb) return
  renderCallbacks.delete(e.data.id)

  if (e.data.type === 'result') cb.resolve(e.data.dataUrl)
  else if (e.data.type === 'error') cb.reject(new Error(e.data.error || '渲染失败'))
}

function ensureIframe(): HTMLIFrameElement {
  if (iframeEl) return iframeEl

  window.addEventListener('message', onIframeMessage)

  iframeEl = document.createElement('iframe')
  iframeEl.style.cssText =
    'position:fixed;left:-9999px;width:800px;height:600px;border:none;opacity:0;pointer-events:none;z-index:-1'
  iframeEl.setAttribute('aria-hidden', 'true')
  iframeEl.setAttribute('tabindex', '-1')

  iframeEl.srcdoc = `<!DOCTYPE html><html><head><meta charset="utf-8">
<script src="${CDN_URL}" onerror="handleLoadError()"><\/script>
<style>*{margin:0;padding:0}html,body,#c{width:100%;height:100%;overflow:hidden}</style>
</head><body><div id="c"></div><script>
function handleLoadError(){
  window.parent.postMessage({source:'echarts-iso',type:'load-failed'},'*');
}
if(typeof echarts==='undefined'){
  handleLoadError();
} else {
  var chart=echarts.init(document.getElementById('c'));
  window.addEventListener('message',function(e){
    if(e.data.type==='render'){
      try{
        var opt=JSON.parse(e.data.option);
        chart.setOption(opt);
        setTimeout(function(){
          var url=chart.getDataURL({type:'png',pixelRatio:2,backgroundColor:'#fff'});
          window.parent.postMessage({source:'echarts-iso',type:'result',id:e.data.id,dataUrl:url},'*');
        },500);
      }catch(err){
        window.parent.postMessage({source:'echarts-iso',type:'error',id:e.data.id,error:err.message||'Render failed'},'*');
      }
    }
  });
  window.parent.postMessage({source:'echarts-iso',type:'ready'},'*');
}
<\/script></body></html>`

  document.body.appendChild(iframeEl)
  return iframeEl
}

function renderECharts(option: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const id = `e${++idSeq}`

    const timer = setTimeout(() => {
      renderCallbacks.delete(id)
      reject(new Error('渲染超时（20s）'))
    }, RENDER_TIMEOUT)

    const wrappedResolve = (url: string) => { clearTimeout(timer); resolve(url) }
    const wrappedReject = (err: Error) => { clearTimeout(timer); reject(err) }

    if (iframeReady) {
      renderCallbacks.set(id, { resolve: wrappedResolve, reject: wrappedReject })
      ensureIframe().contentWindow?.postMessage(
        { type: 'render', id, option }, '*'
      )
    } else {
      pendingQueue.push({ id, option, resolve: wrappedResolve, reject: wrappedReject })
      ensureIframe()
    }
  })
}

// ─── React 组件 ───

export function EChartsRenderer({ code }: EChartsRendererProps) {
  const [dataUrl, setDataUrl] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const containerRef = useRef<HTMLDivElement>(null)

  let title = 'ECharts 图表'
  try {
    const opt = JSON.parse(code.trim())
    if (opt.title?.text) title = opt.title.text
  } catch {}

  useEffect(() => {
    let cancelled = false
    setDataUrl(null)
    setError(null)
    setLoading(true)

    renderECharts(code.trim())
      .then(url => {
        if (!cancelled) { setDataUrl(url); setLoading(false) }
      })
      .catch(err => {
        if (!cancelled) {
          console.error('[EChartsRenderer] error:', err)
          setError(err.message || '渲染失败')
          setLoading(false)
        }
      })

    return () => { cancelled = true }
  }, [code])

  if (error) {
    return (
      <div className="chart-card">
        <div className="chart-card-header">
          <div className="chart-card-header-left">
            <svg className="chart-card-header-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <rect x="3" y="3" width="18" height="18" rx="2" /><path d="M3 9h18M9 3v18" />
            </svg>
            <span className="chart-card-header-label">ECharts</span>
          </div>
          <span className="chart-card-header-badge">{title}</span>
        </div>
        <div className="chart-error">
          图表渲染失败: {error}
        </div>
      </div>
    )
  }

  return (
    <div className="chart-card" ref={containerRef}>
      <div className="chart-card-header">
        <div className="chart-card-header-left">
          <svg className="chart-card-header-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <rect x="3" y="3" width="18" height="18" rx="2" /><path d="M3 9h18M9 3v18" />
          </svg>
          <span className="chart-card-header-label">ECharts</span>
        </div>
        <span className="chart-card-header-badge">{title}</span>
      </div>
      <div className="chart-card-content">
        {dataUrl && (
          <img
            src={dataUrl}
            alt={title}
            style={{ width: '100%', height: 'auto', borderRadius: '8px' }}
          />
        )}
        {loading && !dataUrl && <div className="chart-loading">渲染中…</div>}
      </div>
    </div>
  )
}
