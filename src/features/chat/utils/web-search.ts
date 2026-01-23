export interface SearchResult {
  title: string;
  url: string;
  snippet: string;
  source: string;
}

export interface SearchOptions {
  provider: 'google' | 'tavily' | 'bing' | 'bocha' | 'searxng';
  engineOrder?: ('google' | 'tavily' | 'bing' | 'bocha' | 'searxng')[];
  maxResults: number;
  google?: { apiKey: string; cx: string };
  tavily?: { apiKey: string };
  bing?: { apiKey: string };
  bocha?: { apiKey: string };
  searxng?: { baseUrl: string };
}

export async function performWebSearch(
  query: string,
  options: SearchOptions
): Promise<{ context: string; sources: SearchResult[] }> {
  // 1. 确定搜索序列：以当前 provider 为首，后面跟随 engineOrder 中的其他引擎
  const defaultOrder: ('google' | 'tavily' | 'bing' | 'bocha' | 'searxng')[] = ['google', 'tavily', 'bing', 'bocha', 'searxng'];
  const userOrder = options.engineOrder || defaultOrder;

  // 按照优先级排序：用户选中的 provider 第一位，其余按 engineOrder 顺序排列（排除已选中的）
  const searchSequence = [
    options.provider,
    ...userOrder.filter(p => p !== options.provider)
  ];

  let lastError: any = null;

  // 2. 依次尝试引擎
  for (const currentProvider of searchSequence) {
    try {
      console.log(`[WebSearch] Trying provider: ${currentProvider}`);
      let result: { context: string, sources: SearchResult[] };

      switch (currentProvider) {
        case 'tavily':
          result = await performTavilySearch(query, options.tavily?.apiKey, options.maxResults);
          break;
        case 'bing':
          result = await performBingSearch(query, options.bing?.apiKey, options.maxResults);
          break;
        case 'bocha':
          result = await performBochaSearch(query, options.bocha?.apiKey, options.maxResults);
          break;
        case 'searxng':
          result = await performSearXNGSearch(query, options.searxng?.baseUrl, options.maxResults);
          break;
        case 'google':
        default:
          result = await performGoogleSearch(query, options.google?.apiKey, options.google?.cx, options.maxResults);
          break;
      }

      // 如果结果有效且不是 Mock 数据，则返回
      // 注意：performGoogleSearch 等函数如果缺少 Key 会返回 Mock。如果没有 Key，我们应该跳过尝试下一个。
      const isMock = result.sources.length === 1 && result.sources[0].source === 'System';
      if (!isMock) {
        return result;
      } else {
        console.log(`[WebSearch] Provider ${currentProvider} is not configured (Mock returned), skipping...`);
      }
    } catch (error) {
      lastError = error;
      console.warn(`[WebSearch] Provider ${currentProvider} failed:`, error);
      // 继续循环尝试下一个
    }
  }

  // 3. 所有真实引擎都失败或未配置，最终回退到带有错误标识的 Mock
  console.error('[WebSearch] All search providers failed or unconfigured.', lastError);
  return getMockResults(query, true, options.maxResults);
}

async function performGoogleSearch(
  query: string,
  apiKey: string | undefined,
  cx: string | undefined,
  maxResults: number
) {
  if (!apiKey || !cx) return getMockResults(query, false, maxResults);

  const url = `https://www.googleapis.com/customsearch/v1?key=${apiKey}&cx=${cx}&q=${encodeURIComponent(query)}&num=${Math.min(maxResults, 10)}`;
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Google error: ${response.status}`);

  const data = await response.json();
  const sources: SearchResult[] = (data.items || []).slice(0, maxResults).map((item: any) => ({
    title: item.title,
    url: item.link,
    snippet: item.snippet,
    source: item.displayLink || 'Google',
  }));

  return formatResult(sources);
}

async function performTavilySearch(query: string, apiKey: string | undefined, maxResults: number) {
  if (!apiKey) return getMockResults(query, false, maxResults);

  const response = await fetch('https://api.tavily.com/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      api_key: apiKey,
      query,
      search_depth: 'smart',
      max_results: maxResults,
    }),
  });

  if (!response.ok) throw new Error(`Tavily error: ${response.status}`);
  const data = await response.json();
  const sources: SearchResult[] = (data.results || []).map((r: any) => ({
    title: r.title,
    url: r.url,
    snippet: r.content,
    source: 'Tavily',
  }));

  return formatResult(sources);
}

async function performBingSearch(query: string, apiKey: string | undefined, maxResults: number) {
  if (!apiKey) return getMockResults(query, false, maxResults);

  const url = `https://api.bing.microsoft.com/v7.0/search?q=${encodeURIComponent(query)}&count=${maxResults}`;
  const response = await fetch(url, {
    headers: { 'Ocp-Apim-Subscription-Key': apiKey },
  });

  if (!response.ok) throw new Error(`Bing error: ${response.status}`);
  const data = await response.json();
  const sources: SearchResult[] = (data.webPages?.value || []).map((p: any) => ({
    title: p.name,
    url: p.url,
    snippet: p.snippet,
    source: 'Bing',
  }));

  return formatResult(sources);
}

async function performBochaSearch(query: string, apiKey: string | undefined, maxResults: number) {
  if (!apiKey) return getMockResults(query, false, maxResults);

  // Bocha (博查) API: https://open.bochaai.com/
  const response = await fetch('https://api.bochaai.com/v1/web-search', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      query,
      freshness: 'noLimit',
      count: maxResults,
    }),
  });

  if (!response.ok) throw new Error(`Bocha error: ${response.status}`);
  const data = await response.json();
  const sources: SearchResult[] = (data.data?.webPages?.value || []).map((v: any) => ({
    title: v.name,
    url: v.url,
    snippet: v.snippet,
    source: 'Bocha',
  }));

  return formatResult(sources);
}

async function performSearXNGSearch(query: string, baseUrl: string | undefined, maxResults: number) {
  if (!baseUrl) return getMockResults(query, false, maxResults);

  const url = `${baseUrl.replace(/\/$/, '')}/search?q=${encodeURIComponent(query)}&format=json&number_of_results=${maxResults * 2}`;
  const response = await fetch(url);

  if (!response.ok) throw new Error(`SearXNG error: ${response.status}`);
  const data = await response.json();
  const sources: SearchResult[] = (data.results || []).slice(0, maxResults).map((r: any) => ({
    title: r.title,
    url: r.url,
    snippet: r.content,
    source: r.engine || 'SearXNG',
  }));

  return formatResult(sources);
}

function formatResult(sources: SearchResult[]) {
  const context = `
[Web Search Results]
${sources.map((r, i) => `[${i + 1}] ${r.title}\nSource: ${r.source}\nURL: ${r.url}\nSnippet: ${r.snippet}`).join('\n\n')}
`;
  return { context, sources };
}

function getMockResults(query: string, isFallback: boolean, maxResults: number) {
  const mockResults: SearchResult[] = [
    {
      title: `${isFallback ? '[Fallback] ' : ''}Mock Result: ${query}`,
      url: 'https://example.com/search',
      snippet: `Simulated result for "${query}". Keys may be missing or API failed.`,
      source: 'System',
    },
  ];

  return formatResult(mockResults);
}

/**
 * 抓取网页内容并转换为 Markdown
 */
export async function fetchWebPageContent(url: string): Promise<string> {
  try {
    console.log(`[WebSearch] Fetching content for: ${url}`);
    // 使用 Jina Reader 转 Markdown
    const jinaUrl = `https://r.jina.ai/${url}`;
    const response = await fetch(jinaUrl, {
      headers: {
        'X-Return-Format': 'markdown',
      },
    });

    if (!response.ok) {
      throw new Error(`Failed to fetch page: ${response.status} ${response.statusText}`);
    }

    const content = await response.text();

    // 简单清洗：如果内容太长，截断至 15000 字符（约 10k-12k tokens）
    const MAX_CONTENT_LENGTH = 15000;
    if (content.length > MAX_CONTENT_LENGTH) {
      return content.substring(0, MAX_CONTENT_LENGTH) + '\n\n...[Content truncated for length]...';
    }

    return content;
  } catch (err) {
    console.error(`[WebSearch] Error fetching page content:`, err);
    return `Error reading page content: ${err instanceof Error ? err.message : String(err)}`;
  }
}
