import { open } from '@op-engineering/op-sqlite';

export const db = open({
  name: 'neuralflow.sqlite',
});

export const initDb = async () => {
  // Enable WAL mode for better performance
  await db.execute('PRAGMA journal_mode = WAL;');
  await db.execute('PRAGMA foreign_keys = ON;');
  console.log('[DB] Initialized with WAL mode');
};
