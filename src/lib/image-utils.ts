import * as ImageManipulator from 'expo-image-manipulator';
import * as FileSystem from 'expo-file-system/legacy';

/**
 * 缩略图配置
 */
export interface ThumbnailOptions {
  maxWidth?: number;
  maxHeight?: number;
  compress?: number;
}

const DEFAULT_OPTIONS: Required<ThumbnailOptions> = {
  maxWidth: 512,
  maxHeight: 512,
  compress: 0.75,
};

/**
 * 生成缩略图
 * @param uri 原图 URI (file:// 或 content://)
 * @param options 缩略图选项
 * @returns 缩略图 URI (file://)
 */
export async function generateThumbnail(
  uri: string,
  options: ThumbnailOptions = {},
): Promise<string> {
  try {
    const opts = { ...DEFAULT_OPTIONS, ...options };

    // 使用 expo-image-manipulator 生成缩略图
    const result = await ImageManipulator.manipulateAsync(
      uri,
      [
        {
          resize: {
            width: opts.maxWidth,
            // height 会自动按比例计算
          },
        },
      ],
      {
        compress: opts.compress,
        format: ImageManipulator.SaveFormat.JPEG, // JPEG 压缩更好
      },
    );

    return result.uri;
  } catch (error) {
    console.error('[ImageUtils] Thumbnail generation failed:', error);
    // 降级：返回原图
    return uri;
  }
}

/**
 * 将图片复制到应用缓存目录
 * @param sourceUri 源图片 URI
 * @param subdir 子目录名称 ('thumbnails' | 'originals')
 * @returns 新的 file:// URI
 */
export async function copyToCache(
  sourceUri: string,
  subdir: 'thumbnails' | 'originals' = 'originals',
): Promise<string> {
  try {
    const fs = FileSystem as any;
    const cacheDir = fs.cacheDirectory || fs.documentDirectory;
    if (!cacheDir) {
      throw new Error('No cache directory available');
    }

    // 确保子目录存在
    const targetDir = `${cacheDir}images/${subdir}/`;
    await FileSystem.makeDirectoryAsync(targetDir, { intermediates: true });

    // 生成唯一文件名
    const ext = sourceUri.split('.').pop() || 'jpg';
    const filename = `${subdir === 'thumbnails' ? 'thumb' : 'orig'}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}.${ext}`;
    const targetUri = `${targetDir}${filename}`;

    // 复制文件
    await FileSystem.copyAsync({
      from: sourceUri,
      to: targetUri,
    });

    return targetUri;
  } catch (error) {
    console.error('[ImageUtils] Copy to cache failed:', error);
    // 降级：返回原 URI
    return sourceUri;
  }
}

/**
 * 保存 Base64 图片到文件系统
 * @param base64 Base64 字符串（不含前缀）
 * @param subdir 子目录名称
 * @param mimeType MIME 类型
 * @returns file:// URI
 */
export async function saveBase64ToFile(
  base64: string,
  subdir: 'thumbnails' | 'originals' = 'originals',
  mimeType: string = 'image/png',
): Promise<string> {
  try {
    const fs = FileSystem as any;
    const cacheDir = fs.cacheDirectory || fs.documentDirectory;
    if (!cacheDir) {
      throw new Error('No cache directory available');
    }

    const targetDir = `${cacheDir}images/${subdir}/`;
    await FileSystem.makeDirectoryAsync(targetDir, { intermediates: true });

    const ext = mimeType.split('/')[1] || 'png';
    const filename = `${subdir === 'thumbnails' ? 'thumb' : 'orig'}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}.${ext}`;
    const targetUri = `${targetDir}${filename}`;

    await FileSystem.writeAsStringAsync(targetUri, base64, {
      encoding: (FileSystem as any).EncodingType?.Base64 || 'base64',
    });

    return targetUri;
  } catch (error) {
    console.error('[ImageUtils] Save Base64 failed:', error);
    throw error;
  }
}

/**
 * 清理旧缓存
 * @param maxAgeMs 最大缓存时间（毫秒），默认 7 天
 */
export async function cleanupOldCache(maxAgeMs: number = 7 * 24 * 60 * 60 * 1000): Promise<void> {
  try {
    const fs = FileSystem as any;
    const cacheDir = fs.cacheDirectory || fs.documentDirectory;
    if (!cacheDir) return;

    const imagesDir = `${cacheDir}images/`;
    const subdirs = ['thumbnails', 'originals'];

    const now = Date.now();

    for (const subdir of subdirs) {
      const dir = `${imagesDir}${subdir}/`;
      try {
        const files = await FileSystem.readDirectoryAsync(dir);

        for (const file of files) {
          const fileUri = `${dir}${file}`;
          const info = await FileSystem.getInfoAsync(fileUri);

          if (info.exists && (info as any).modificationTime) {
            const age = now - (info as any).modificationTime * 1000;

            if (age > maxAgeMs) {
              await FileSystem.deleteAsync(fileUri, { idempotent: true });
              console.log(`[ImageUtils] Deleted old cache: ${file}`);
            }
          }
        }
      } catch (error) {
        // 目录不存在或其他错误，跳过
        console.warn(`[ImageUtils] Cleanup ${subdir} failed:`, error);
      }
    }
  } catch (error) {
    console.error('[ImageUtils] Cleanup failed:', error);
  }
}

/**
 * 获取缓存统计信息
 */
export async function getCacheStats(): Promise<{
  thumbnails: { count: number; totalSize: number };
  originals: { count: number; totalSize: number };
}> {
  const stats = {
    thumbnails: { count: 0, totalSize: 0 },
    originals: { count: 0, totalSize: 0 },
  };

  try {
    const fs = FileSystem as any;
    const cacheDir = fs.cacheDirectory || fs.documentDirectory;
    if (!cacheDir) return stats;

    const imagesDir = `${cacheDir}images/`;
    const subdirs: Array<'thumbnails' | 'originals'> = ['thumbnails', 'originals'];

    for (const subdir of subdirs) {
      const dir = `${imagesDir}${subdir}/`;
      try {
        const files = await FileSystem.readDirectoryAsync(dir);

        for (const file of files) {
          const fileUri = `${dir}${file}`;
          const info = await FileSystem.getInfoAsync(fileUri, { size: true } as any);

          if (info.exists) {
            stats[subdir].count++;
            stats[subdir].totalSize += (info as any).size || 0;
          }
        }
      } catch (error) {
        // 目录不存在或其他错误，跳过
      }
    }
  } catch (error) {
    console.error('[ImageUtils] Get cache stats failed:', error);
  }

  return stats;
}
