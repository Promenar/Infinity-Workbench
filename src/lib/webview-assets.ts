import { Asset } from 'expo-asset';
import { Platform } from 'react-native';

// 静态资源模块映射（通过 require 让 Metro 打包为 asset）
const ASSET_MODULES = {
  echarts: require('../../assets/web-libs/echarts.min.bundle'),
  mermaid: require('../../assets/web-libs/mermaid.min.bundle'),
  katex_js: require('../../assets/web-libs/katex.min.bundle'),
  katex_css: require('../../assets/web-libs/katex.min.css'),
} as const;

type LibName = keyof typeof ASSET_MODULES;

// 缓存已解析的本地 URI
const uriCache = new Map<LibName, string>();

/**
 * 解析本地打包的 WebView JS 库 URI
 *
 * 使用 expo-asset 加载 Metro 打包的 .bundle 文件，
 * 返回 file:// URI 供 WebView <script src> 使用。
 *
 * @param lib 库名称：'echarts' | 'mermaid'
 * @returns 本地 file:// URI 或 null（解析失败时）
 */
export async function resolveLocalLibUri(lib: LibName): Promise<string | null> {
  // 命中缓存
  if (uriCache.has(lib)) {
    return uriCache.get(lib)!;
  }

  try {
    const asset = Asset.fromModule(ASSET_MODULES[lib]);

    // Android 需要显式 downloadAsync（debug 模式下 asset 尚未解压到文件系统）
    if (Platform.OS === 'android' && !asset.localUri) {
      await asset.downloadAsync();
    }

    const uri = asset.localUri || asset.uri;
    if (!uri) return null;

    uriCache.set(lib, uri);
    return uri;
  } catch (e) {
    console.warn(`[webview-assets] 解析 ${lib} 本地资源失败:`, e);
    return null;
  }
}

/**
 * 生成带 CDN 降级的 <script> 标签
 *
 * 优先使用本地打包资源，如果不可用则 fallback 到 CDN。
 * 调用方需要在组件挂载时通过 resolveLocalLibUri 预加载。
 *
 * @param lib 库名称
 * @param localUri 已解析的本地 URI（可为 null）
 * @param cdnUrl CDN fallback 地址
 */
export function scriptTagWithFallback(
  lib: LibName,
  localUri: string | null,
  cdnUrl: string,
): string {
  if (localUri) {
    // 本地优先 + CDN 降级：onerror 时动态插入 CDN script
    return `<script src="${localUri}" onerror="(function(){var s=document.createElement('script');s.src='${cdnUrl}';document.head.appendChild(s);})()"><\/script>`;
  }
  return `<script src="${cdnUrl}"><\/script>`;
}
