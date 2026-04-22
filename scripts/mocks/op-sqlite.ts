/**
 * op-sqlite 内存模拟器
 * 支持基本的 SQLite 操作
 */

interface Table {
  name: string;
  columns: string[];
  rows: any[][];
}

interface Database {
  name: string;
  tables: Map<string, Table>;
  close?: () => void;
}

const databases = new Map<string, Database>();

function createDatabase(name: string): Database {
  return {
    name,
    tables: new Map(),
  };
}

function parseCreateTable(sql: string): { name: string; columns: string[] } {
  const match = sql.match(
    /CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[`'"]?(\w+)[`'"]?\s*\((.+)\)/is
  );
  if (!match) throw new Error(`Invalid CREATE TABLE: ${sql}`);

  const tableName = match[1];
  const columnsDef = match[2];

  const columns: string[] = [];
  const columnMatches = columnsDef.matchAll(/[`'"]?(\w+)[`'"]?\s+\w+/gi);
  for (const m of columnMatches) {
    columns.push(m[1]);
  }

  return { name: tableName, columns };
}

function parseInsert(sql: string): {
  table: string;
  columns: string[];
  values: any[][];
} {
  const match = sql.match(
    /INSERT\s+INTO\s+[`'"]?(\w+)[`'"]?\s*(?:\(([^)]+)\))?\s*VALUES\s+(.+)/is
  );
  if (!match) throw new Error(`Invalid INSERT: ${sql}`);

  const table = match[1];
  const columnsStr = match[2];
  const valuesStr = match[3];

  const columns = columnsStr
    ? columnsStr.split(',').map((c: string) => c.trim().replace(/[`'"]/g, ''))
    : [];

  const values: any[][] = [];
  const valueMatches = valuesStr.matchAll(/\(([^)]+)\)/g);
  for (const m of valueMatches) {
    const row = m[1].split(',').map((v: string) => {
      v = v.trim();
      if (v === 'NULL') return null;
      if (v === 'CURRENT_TIMESTAMP') return Date.now();
      if ((v.startsWith("'") && v.endsWith("'")) || (v.startsWith('"') && v.endsWith('"'))) {
        return v.slice(1, -1);
      }
      const num = Number(v);
      return isNaN(num) ? v : num;
    });
    values.push(row);
  }

  return { table, columns, values };
}

function parseSelect(sql: string): { table: string; where?: string } {
  const match = sql.match(
    /SELECT\s+.+\s+FROM\s+[`'"]?(\w+)[`'"]?(?:\s+WHERE\s+(.+))?/is
  );
  if (!match) throw new Error(`Invalid SELECT: ${sql}`);
  return { table: match[1], where: match[2] };
}

function executeOnDb(db: Database, sql: string, params: any[] = []): any {
  const trimmed = sql.trim().toUpperCase();

  try {
    if (trimmed.startsWith('CREATE TABLE')) {
      const { name, columns } = parseCreateTable(sql);
      db.tables.set(name, { name, columns, rows: [] });
      return { rows: [] };
    }

    if (trimmed.startsWith('INSERT')) {
      const { table, values } = parseInsert(sql);
      const tbl = db.tables.get(table);
      if (!tbl) throw new Error(`op-sqlite: Table not found: ${table}`);

      for (const row of values) {
        tbl.rows.push(row);
      }
      return { rows: [] };
    }

    if (trimmed.startsWith('SELECT')) {
      const { table, where } = parseSelect(sql);
      const tbl = db.tables.get(table);
      if (!tbl) return { rows: [] };

      let results = tbl.rows;

      if (where) {
        const paramMatch = where.match(/(\w+)\s*=\s*\?/i);
        if (paramMatch) {
          const colIndex = tbl.columns.indexOf(paramMatch[1]);
          if (colIndex >= 0) {
            results = results.filter((row) => row[colIndex] === params[0]);
          }
        }
      }

      return { rows: results };
    }

    if (trimmed.startsWith('UPDATE')) {
      const match = trimmed.match(
        /UPDATE\s+[`'"]?(\w+)[`'"]?\s+SET\s+(.+?)\s+WHERE\s+(.+)/i
      );
      if (match) {
        const tbl = db.tables.get(match[1]);
        if (tbl) {
          return { rows: [] };
        }
      }
    }

    if (trimmed.startsWith('DELETE')) {
      const match = trimmed.match(
        /DELETE\s+FROM\s+[`'"]?(\w+)[`'"]?(?:\s+WHERE\s+(.+))?/i
      );
      if (match) {
        const tbl = db.tables.get(match[1]);
        if (tbl && match[2]) {
          const paramMatch = match[2].match(/(\w+)\s*=\s*\?/i);
          if (paramMatch) {
            const colIndex = tbl.columns.indexOf(paramMatch[1]);
            if (colIndex >= 0) {
              tbl.rows = tbl.rows.filter((row) => row[colIndex] !== params[0]);
            }
          }
        }
        return { rows: [] };
      }
    }

    return { rows: [] };
  } catch (error) {
    return { rows: [], error: (error as Error).message };
  }
}

function open(dbName: string = ':memory:'): Database {
  if (!databases.has(dbName)) {
    databases.set(dbName, createDatabase(dbName));
  }
  const db = databases.get(dbName)!;

  return {
    execute: (sql: string, params?: any[]) => executeOnDb(db, sql, params),
    executeAsync: async (sql: string, params?: any[]) =>
      executeOnDb(db, sql, params),
    close: () => databases.delete(dbName),
  } as any;
}

function resetDatabase(db: Database): void {
  for (const table of db.tables.values()) {
    table.rows = [];
  }
}

function clearAll(): void {
  databases.clear();
}

module.exports = { open, resetDatabase, clearAll };
