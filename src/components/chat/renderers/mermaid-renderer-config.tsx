/**
 * Mermaid 渲染器配置
 *
 * 实现 ArtifactRendererConfig 接口，将 Mermaid 特定的
 * 解析逻辑、元数据提取、图标和内容渲染注册到 RendererRegistry。
 */

import React from 'react';
import { Network } from 'lucide-react-native';
import { parseMermaidContent } from '../../../lib/artifact-parser';
import { MermaidRenderer } from '../MermaidRenderer';
import { ArtifactRendererConfig, RendererCardMetadata, RendererContentProps } from './types';

export const mermaidRendererConfig: ArtifactRendererConfig = {
    type: 'mermaid',
    badgeLabel: 'Diagram',

    parseContent(content: string) {
        return parseMermaidContent(content);
    },

    getMetadata(_data: any): RendererCardMetadata {
        return {
            title: 'Mermaid 流程图',
            badgeText: 'DIAGRAM',
            iconVariant: 'network',
        };
    },

    renderBadgeIcon(size: number, color: string) {
        return <Network size={size} color={color} />;
    },

    renderContent(props: RendererContentProps) {
        return <MermaidRenderer content={props.content} />;
    },
};
