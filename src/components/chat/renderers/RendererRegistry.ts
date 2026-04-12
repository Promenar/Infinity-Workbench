/**
 * RendererRegistry - 渲染器注册表
 *
 * 管理所有 Artifact 渲染器配置，支持运行时注册和查询。
 * 新增渲染器类型只需实现 ArtifactRendererConfig 并调用 register()。
 */

import { ArtifactRendererConfig } from './types';

class RendererRegistryClass {
    private registry = new Map<string, ArtifactRendererConfig>();

    /**
     * 注册一个渲染器配置
     */
    register(config: ArtifactRendererConfig): void {
        if (this.registry.has(config.type)) {
            console.warn(`[RendererRegistry] Renderer for type "${config.type}" is already registered. Overwriting.`);
        }
        this.registry.set(config.type, config);
    }

    /**
     * 获取指定类型的渲染器配置
     */
    get(type: string): ArtifactRendererConfig | undefined {
        return this.registry.get(type);
    }

    /**
     * 检查指定类型是否已注册
     */
    has(type: string): boolean {
        return this.registry.has(type);
    }

    /**
     * 获取所有已注册的类型
     */
    getRegisteredTypes(): string[] {
        return Array.from(this.registry.keys());
    }

    /**
     * 获取所有已注册的渲染器配置
     */
    getAll(): ArtifactRendererConfig[] {
        return Array.from(this.registry.values());
    }
}

/** 全局渲染器注册表单例 */
export const RendererRegistry = new RendererRegistryClass();
