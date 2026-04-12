/**
 * Artifact 渲染器架构 - 类型定义
 *
 * 定义统一的渲染器配置接口，支持注册表模式扩展新类型。
 */

import React from 'react';
import { ParseResult } from '../../../lib/artifact-parser';

// ---------------------------------------------------------------------------
// 渲染器配置接口
// ---------------------------------------------------------------------------

/**
 * 渲染器配置 — 每种 Artifact 类型需实现此接口
 */
export interface ArtifactRendererConfig {
    /** 渲染器对应的 Artifact 类型标识 */
    type: string;

    /** 卡片显示的 badge 标签 */
    badgeLabel: string;

    /**
     * 解析原始内容，返回结构化数据
     * 如果渲染器不需要预解析（如直接透传字符串），可返回 null
     */
    parseContent(content: string): ParseResult<any>;

    /**
     * 从解析后的数据中提取元数据（标题、子类型等）
     */
    getMetadata(data: any): RendererCardMetadata;

    /**
     * 渲染卡片 badge 区的图标组件
     */
    renderBadgeIcon: (size: number, color: string) => React.ReactNode;

    /**
     * 渲染主体内容（包含卡片 + 全屏 WebView）
     * 接收经过 parseContent 处理的内容
     */
    renderContent: (props: RendererContentProps) => React.ReactNode;
}

// ---------------------------------------------------------------------------
// 渲染器元数据
// ---------------------------------------------------------------------------

export interface RendererCardMetadata {
    /** 卡片标题 */
    title: string;
    /** 类型 badge 文本（如 "BAR"、"DIAGRAM"） */
    badgeText: string;
    /** 卡片图标类型标识（用于选择 lucide icon） */
    iconVariant: string;
}

// ---------------------------------------------------------------------------
// 渲染器内容 Props
// ---------------------------------------------------------------------------

export interface RendererContentProps {
    /** 经过解析的内容字符串 */
    content: string;
    /** 主题色对象 */
    colors: any;
    /** 是否暗色模式 */
    isDark: boolean;
}
