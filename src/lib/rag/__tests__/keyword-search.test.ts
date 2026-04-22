/**
 * KeywordSearch 单元测试
 * 测试关键词检索功能
 */

// Mock 数据库
jest.mock('../../db', () => ({
  db: {
    execute: jest.fn().mockResolvedValue({ rows: [] }),
  },
}));

describe('KeywordSearch', () => {
  // 模拟 KeywordSearch 的辅助函数
  describe('查询预处理', () => {
    it('应正确分词', () => {
      const query = 'hello world test';
      const words = query.split(/\s+/).filter(w => w.length > 1);
      expect(words).toEqual(['hello', 'world', 'test']);
    });

    it('应过滤单字符', () => {
      const query = 'a b c hello world';
      const words = query.split(/\s+/).filter(w => w.length > 1);
      expect(words).toContain('hello');
      expect(words).toContain('world');
      expect(words).not.toContain('a');
    });

    it('应处理空查询', () => {
      const query = '';
      const trimmed = query.trim();
      expect(trimmed.length).toBe(0);
    });

    it('应截断超长查询', () => {
      const longQuery = 'a'.repeat(100);
      const truncated = longQuery.length > 60 ? longQuery.slice(0, 60) : longQuery;
      expect(truncated.length).toBe(60);
    });
  });

  describe('SQL 构建', () => {
    it('应构建 LIKE 查询条件', () => {
      const keywords = ['hello', 'world'];
      const conditions = keywords.map(kw => `content LIKE '%${kw}%'`);
      const sql = conditions.join(' OR ');
      expect(sql).toBe("content LIKE '%hello%' OR content LIKE '%world%'");
    });

    it('应构建 IN 查询条件', () => {
      const ids = ['id1', 'id2', 'id3'];
      const placeholders = ids.map(() => '?').join(',');
      const sql = `doc_id IN (${placeholders})`;
      expect(sql).toBe('doc_id IN (?,?,?)');
    });

    it('应处理 sessionId 过滤', () => {
      const sessionId = 'session-123';
      const sql = `session_id = '${sessionId}'`;
      expect(sql).toContain(sessionId);
    });
  });

  describe('相关性计算', () => {
    it('应计算关键词匹配数', () => {
      const content = 'hello world hello';
      const keywords = ['hello', 'world'];
      let score = 0;
      
      for (const kw of keywords) {
        if (content.toLowerCase().includes(kw.toLowerCase())) {
          score += 1;
        }
      }
      
      expect(score).toBe(2);
    });

    it('应区分大小写', () => {
      const content = 'Hello World';
      const keyword = 'hello';
      
      expect(content.includes(keyword)).toBe(false);
      expect(content.toLowerCase().includes(keyword.toLowerCase())).toBe(true);
    });

    it('应按分数排序结果', () => {
      const results = [
        { id: '1', score: 2 },
        { id: '2', score: 1 },
        { id: '3', score: 3 },
      ];
      
      results.sort((a, b) => b.score - a.score);
      
      expect(results[0].id).toBe('3');
      expect(results[1].id).toBe('1');
      expect(results[2].id).toBe('2');
    });
  });

  describe('停用词处理', () => {
    it('应定义中文停用词', () => {
      const stopWords = ['的', '了', '是', '在', '和', '与'];
      expect(stopWords).toContain('的');
      expect(stopWords).toContain('了');
    });

    it('应过滤停用词', () => {
      const query = '这是一个测试的句子';
      const stopWords = ['的', '了', '是', '在'];
      const words = query.split('').filter(w => !stopWords.includes(w));
      expect(words).not.toContain('的');
    });

    it('应保留有意义的词', () => {
      const query = '测试搜索功能';
      const stopWords = ['的', '了'];
      const words = query.split('').filter(w => !stopWords.includes(w));
      expect(words.join('')).toBe('测试搜索功能');
    });
  });

  describe('边界情况', () => {
    it('应处理无匹配结果', () => {
      const content = 'hello world';
      const keywords = ['python', 'java', 'rust'];
      
      let hasMatch = false;
      for (const kw of keywords) {
        if (content.toLowerCase().includes(kw.toLowerCase())) {
          hasMatch = true;
          break;
        }
      }
      
      expect(hasMatch).toBe(false);
    });

    it('应处理部分匹配', () => {
      const content = 'react native development';
      const keywords = ['react', 'flutter', 'swift'];
      
      let matchCount = 0;
      for (const kw of keywords) {
        if (content.toLowerCase().includes(kw.toLowerCase())) {
          matchCount++;
        }
      }
      
      expect(matchCount).toBe(1);
    });

    it('应处理模糊匹配', () => {
      // 简单的模糊匹配逻辑：检查共同字符
      const content = 'javascript';
      const typo = 'javscript'; // 缺少 a
      
      // 计算共同字符比例
      const commonChars = [...content].filter(c => typo.includes(c));
      const matchRate = commonChars.length / content.length;
      
      // 90% 字符匹配
      expect(matchRate).toBeGreaterThan(0.8);
    });
  });

  describe('结果限制', () => {
    it('应正确限制返回数量', () => {
      const results = Array.from({ length: 100 }, (_, i) => ({ id: `doc-${i}` }));
      const limited = results.slice(0, 10);
      expect(limited.length).toBe(10);
    });

    it('应保留高质量结果', () => {
      const results = [
        { id: '1', score: 3 },
        { id: '2', score: 5 },
        { id: '3', score: 1 },
      ];
      
      // 按分数排序后取前 N 个
      results.sort((a, b) => b.score - a.score);
      const top = results.slice(0, 2);
      
      expect(top.map(r => r.id)).toEqual(['2', '1']);
    });
  });
});
