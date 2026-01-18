import { Buffer } from 'buffer';
import * as FileSystem from 'expo-file-system/legacy';
import iconv from 'iconv-lite';

/**
 * 读取文件内容并自动处理编码（支持 UTF-8 和 GBK）
 * @param uri 文件 URI
 * @returns 解码后的字符串内容
 */
/**
 * 读取文件内容并自动处理编码（支持 UTF-8 和 GBK）
 * @param uri 文件 URI
 * @returns 解码后的字符串内容
 */
export const readFileAsBase64 = async (uri: string): Promise<string> => {
  return await FileSystem.readAsStringAsync(uri, {
    encoding: FileSystem.EncodingType.Base64,
  });
};

import jschardet from 'jschardet';

// 常见的 UTF-8 被误识别为 GBK 导致的乱码特征字符
// 例如 "李" (E6 9D 8E) 被误读为 "鏉" (GBK: E6 9D) + "..."
const MOJIBAKE_PATTERNS = [
  '鏉', '牋', '浜', '澶', '寰', '鎴', '鏄', '浠', '鍙', '瓑', '闇', '鎾', '鏈'
];

/**
 * 检查字符串是否包含典型的 "UTF-8 Bytes interpreted as GBK" 乱码
 */
const hasMojibake = (text: string): boolean => {
  let count = 0;
  for (const char of MOJIBAKE_PATTERNS) {
    if (text.includes(char)) count++;
  }
  // 如果包含 2 个以上特征字符，大概率是乱码
  return count >= 2;
};

export const readFileContent = async (uri: string): Promise<string> => {
  try {
    // 1. 读取为 Base64
    const base64 = await FileSystem.readAsStringAsync(uri, {
      encoding: FileSystem.EncodingType.Base64,
    });

    // 2. 转换为 Buffer
    const buffer = Buffer.from(base64, 'base64');

    // 3. 优先尝试 UTF-8 解码
    const utf8Content = iconv.decode(buffer, 'utf-8');

    // 检查是否包含无效字符
    const replacementCount = (utf8Content.match(/\uFFFD/g) || []).length;
    const isCleanUtf8 = replacementCount === 0;

    if (isCleanUtf8) {
      return utf8Content;
    }

    // 4. 使用 jschardet 检测
    // 将 buffer 转为 string (binary) 传给 jschardet (虽然 jschardet node 版支持 buffer，但 RN 环境稳妥起见)
    // 注意: iconv-lite 的 buffer 是 Node Buffer，jschardet 可以直接吃
    const detected = jschardet.detect(buffer);
    console.log(`[FileUtils] Detected encoding: ${detected.encoding} (${detected.confidence})`);

    let content = '';

    // 如果检测到 GB2312/GBK 且置信度高
    if ((detected.encoding === 'GB2312' || detected.encoding === 'GB18030') && (detected.confidence || 0) > 0.8) {
      content = iconv.decode(buffer, 'gbk');
    } else if (detected.encoding && iconv.encodingExists(detected.encoding)) {
      content = iconv.decode(buffer, detected.encoding);
    } else {
      // 兜底 GBK
      content = iconv.decode(buffer, 'gbk');
    }

    // 5. 关键：乱码二次修正 (Anti-Mojibake)
    // 如果解码出来的结果包含大量“UTF-8 误读为 GBK”的特征字符，则强制回退到 UTF-8
    // 这种情况常见于 jschardet 被一些特殊序列误导认为非 UTF-8，或者文件头损坏
    if (hasMojibake(content)) {
      console.warn('[FileUtils] Detected Mojibake (UTF-8 bytes read as GBK/Binary). Forcing UTF-8.');
      // 忽略错误强制 UTF-8
      // stripBOM 可能会有帮助
      return iconv.decode(buffer, 'utf-8').replace(/\uFFFD/g, '');
    }

    return content;

  } catch (e) {
    console.error('[FileUtils] Failed to read file:', e);
    throw new Error('文件读取失败: ' + (e as Error).message);
  }
};


/**
 * 格式化文件大小
 */
export const formatFileSize = (bytes: number): string => {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
};
