export interface SearchResult {
  title: string;
  url: string;
  snippet: string;
  source: string;
}

export async function performWebSearch(
  query: string,
  apiKey?: string,
  cx?: string,
): Promise<{ context: string; sources: SearchResult[] }> {
  // If no keys provided, use mock
  if (!apiKey || !cx) {
    console.log('Missing Google Search keys, using mock results.');
    // Simulate network delay
    await new Promise((resolve) => setTimeout(resolve, 1000));
    return getMockResults(query);
  }

  try {
    const cleanApiKey = apiKey.trim();
    const cleanCx = cx.trim();

    console.log(
      `Searching Google: ${query} (Key: ${cleanApiKey.substring(0, 5)}... length=${cleanApiKey.length})`,
    );

    const url = `https://www.googleapis.com/customsearch/v1?key=${cleanApiKey}&cx=${cleanCx}&q=${encodeURIComponent(query)}`;
    const response = await fetch(url);

    if (!response.ok) {
      const errorText = await response.text();
      console.error('Google Search API Error:', response.status, errorText);
      throw new Error(`Google Search failed (${response.status}): ${errorText.substring(0, 200)}`);
    }

    const data = await response.json();
    const items = data.items || [];

    const sources: SearchResult[] = items.slice(0, 5).map((item: any) => ({
      title: item.title,
      url: item.link,
      snippet: item.snippet,
      source: item.displayLink || 'Google Search',
    }));

    const context = `
[Web Search Results]
${sources.map((r, i) => `[${i + 1}] ${r.title}\nSource: ${r.source}\nURL: ${r.url}\nSnippet: ${r.snippet}`).join('\n\n')}
`;

    return { context, sources };
  } catch (error) {
    console.error('Web Search Exception:', error);
    // Fallback to mock on error to prevent crash?
    // Or rethrow? Let's return mock for stability but log error.
    return getMockResults(query, true);
  }
}

function getMockResults(
  query: string,
  isFallback = false,
): { context: string; sources: SearchResult[] } {
  const mockResults: SearchResult[] = [
    {
      title: `${isFallback ? '[Fallback] ' : ''}Mock Result: ${query}`,
      url: 'https://example.com/search',
      snippet: `This is a simulated ${isFallback ? 'fallback ' : ''}result because search failed or keys are missing. term: "${query}".`,
      source: 'System',
    },
    {
      title: 'NeuralFlow Documentation',
      url: 'https://github.com/narciswl/neuralflow',
      snippet: 'Official documentation for the NeuralFlow project.',
      source: 'GitHub',
    },
  ];

  const context = `
[Web Search Results (Simulated)]
${mockResults.map((r, i) => `[${i + 1}] ${r.title}\nSource: ${r.source}\nURL: ${r.url}\nSnippet: ${r.snippet}`).join('\n\n')}
`;
  return { context, sources: mockResults };
}
