export interface SearchResult {
  title: string;
  url: string;
  snippet: string;
  source: string;
}

export interface SearchOptions {
  provider: 'google' | 'tavily' | 'bing' | 'bocha' | 'searxng';
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
  try {
    let result: { context: string, sources: SearchResult[] };

    switch (options.provider) {
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

    return result;
  } catch (error) {
    console.error(`[WebSearch] ${options.provider} failed:`, error);
    return getMockResults(query, true, options.maxResults);
  }
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
