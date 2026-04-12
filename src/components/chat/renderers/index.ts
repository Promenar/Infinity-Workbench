/**
 * 渲染器注册入口
 *
 * 导入此文件即自动注册所有内置渲染器到 RendererRegistry。
 * 新增渲染器类型时，只需在此添加 import 即可。
 */

import { RendererRegistry } from './RendererRegistry';
import { echartsRendererConfig } from './echarts-renderer-config';
import { mermaidRendererConfig } from './mermaid-renderer-config';

// 注册内置渲染器
RendererRegistry.register(echartsRendererConfig);
RendererRegistry.register(mermaidRendererConfig);

// Re-export for convenience
export { RendererRegistry } from './RendererRegistry';
export type { ArtifactRendererConfig, RendererCardMetadata, RendererContentProps } from './types';
