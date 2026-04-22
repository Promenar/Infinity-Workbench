/**
 * UUID 生成工具
 */

export function generateId(): string {
  const timestamp = Date.now().toString(36);
  const randomPart = Math.random().toString(36).slice(2, 9);
  return `${timestamp}-${randomPart}`;
}

export function generateTimestampId(): string {
  return `id-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
}
