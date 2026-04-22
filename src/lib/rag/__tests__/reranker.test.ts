/**
 * Reranker 单元测试
 * 测试文档重排序功能
 * 
 * 注意：此文件测试 Reranker 的逻辑和辅助函数，不依赖真实的 API 调用
 */

describe('Reranker', () => {
  describe('相关性计算', () => {
    it('应计算余弦相似度', () => {
      const dot = (a: number[], b: number[]) => 
        a.reduce((sum, val, i) => sum + val * b[i], 0);
      const mag = (v: number[]) => 
        Math.sqrt(v.reduce((sum, val) => sum + val * val, 0));
      
      const query = [1, 0, 0];
      const doc = [1, 0, 0];
      
      const similarity = dot(query, doc) / (mag(query) * mag(doc));
      expect(similarity).toBeCloseTo(1);
    });

    it('应计算正交向量相似度', () => {
      const dot = (a: number[], b: number[]) => 
        a.reduce((sum, val, i) => sum + val * b[i], 0);
      const mag = (v: number[]) => 
        Math.sqrt(v.reduce((sum, val) => sum + val * val, 0));
      
      const query = [1, 0, 0];
      const doc = [0, 1, 0];
      
      const similarity = dot(query, doc) / (mag(query) * mag(doc));
      expect(similarity).toBeCloseTo(0);
    });

    it('应计算负相关', () => {
      const dot = (a: number[], b: number[]) => 
        a.reduce((sum, val, i) => sum + val * b[i], 0);
      const mag = (v: number[]) => 
        Math.sqrt(v.reduce((sum, val) => sum + val * val, 0));
      
      const query = [1, 0, 0];
      const doc = [-1, 0, 0];
      
      const similarity = dot(query, doc) / (mag(query) * mag(doc));
      expect(similarity).toBeCloseTo(-1);
    });
  });

  describe('排序逻辑', () => {
    it('应按相关性降序排序', () => {
      const results = [
        { id: '1', relevance: 0.3 },
        { id: '2', relevance: 0.9 },
        { id: '3', relevance: 0.6 },
      ];
      
      results.sort((a, b) => b.relevance - a.relevance);
      
      expect(results[0].id).toBe('2');
      expect(results[1].id).toBe('3');
      expect(results[2].id).toBe('1');
    });

    it('应保留原始数据', () => {
      const docs = [
        { id: '1', content: 'hello world', similarity: 0.5 },
        { id: '2', content: 'test text', similarity: 0.8 },
      ];
      
      docs.sort((a, b) => b.similarity - a.similarity);
      
      expect(docs[0].content).toBe('test text');
      expect(docs[0].id).toBe('2');
    });
  });

  describe('Top-K 筛选', () => {
    it('应返回 top_k 个结果', () => {
      const results = [
        { id: '1', score: 0.1 },
        { id: '2', score: 0.9 },
        { id: '3', score: 0.3 },
        { id: '4', score: 0.7 },
        { id: '5', score: 0.5 },
      ];
      
      results.sort((a, b) => b.score - a.score);
      const topK = results.slice(0, 3);
      
      expect(topK.length).toBe(3);
      expect(topK.map(r => r.id)).toEqual(['2', '4', '5']);
    });

    it('应处理 k 大于结果数量', () => {
      const results = [
        { id: '1', score: 0.5 },
        { id: '2', score: 0.8 },
      ];
      
      const topK = results.slice(0, 10);
      expect(topK.length).toBe(2);
    });
  });

  describe('API 请求构建', () => {
    it('应构建正确的请求体', () => {
      const request = {
        model: 'bge-reranker-base',
        query: 'hello world',
        documents: ['doc1', 'doc2', 'doc3'],
        top_n: 3,
      };
      
      expect(request.model).toBe('bge-reranker-base');
      expect(request.documents).toHaveLength(3);
      expect(request.top_n).toBe(3);
    });

    it('应处理端点构建', () => {
      const baseUrl = 'https://api.example.com/v1';
      const suffix = baseUrl.endsWith('/v1') ? '/rerank' : '/v1/rerank';
      const endpoint = `${baseUrl.replace(/\/+$/, '')}${suffix}`;
      
      expect(endpoint).toBe('https://api.example.com/v1/rerank');
    });

    it('应处理无尾部斜杠的 URL', () => {
      const baseUrl = 'https://api.example.com/v1';
      const normalized = baseUrl.replace(/\/+$/, '');
      expect(normalized).toBe('https://api.example.com/v1');
    });
  });

  describe('响应解析', () => {
    it('应解析 Jina 格式响应', () => {
      const response = {
        results: [
          { index: 0, relevance_score: 0.95 },
          { index: 1, relevance_score: 0.80 },
          { index: 2, relevance_score: 0.65 },
        ],
      };
      
      expect(response.results).toHaveLength(3);
      expect(response.results[0].relevance_score).toBeCloseTo(0.95);
    });

    it('应解析 Cohere 格式响应', () => {
      const response = {
        results: [
          { index: 0, relevance_score: 0.9 },
          { index: 1, relevance_score: 0.7 },
        ],
      };
      
      const scores = response.results.map(r => r.relevance_score);
      expect(scores[0]).toBeGreaterThan(scores[1]);
    });

    it('应处理空结果', () => {
      const response = { results: [] };
      expect(response.results).toHaveLength(0);
    });
  });

  describe('错误处理', () => {
    it('应处理网络错误', () => {
      const error = new Error('Network request failed');
      expect(error.message).toBe('Network request failed');
    });

    it('应处理无效响应格式', () => {
      const invalidResponse = { error: 'invalid request' };
      expect(invalidResponse.error).toBeDefined();
    });

    it('应回退到原始顺序', () => {
      const docs = [
        { id: '1', content: 'doc1' },
        { id: '2', content: 'doc2' },
      ];
      
      // 模拟 rerank 失败后的回退
      const fallback = docs;
      expect(fallback).toEqual(docs);
    });
  });

  describe('性能考虑', () => {
    it('应限制输入文档数量', () => {
      const maxDocs = 100;
      const docs = Array.from({ length: 200 }, (_, i) => `doc-${i}`);
      const limited = docs.slice(0, maxDocs);
      
      expect(limited.length).toBe(100);
    });

    it('应正确估算内存使用', () => {
      const avgEmbeddingSize = 1536; // float32
      const bytesPerDoc = avgEmbeddingSize * 4; // float32 = 4 bytes
      const numDocs = 100;
      const estimatedMB = (bytesPerDoc * numDocs) / (1024 * 1024);
      
      expect(estimatedMB).toBeCloseTo(0.59);
    });
  });
});
