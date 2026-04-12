/**
 * ECharts 渲染器配置
 *
 * 实现 ArtifactRendererConfig 接口，将 ECharts 特定的
 * 解析逻辑、元数据提取、图标和内容渲染注册到 RendererRegistry。
 */

import React from 'react';
import { BarChart3 } from 'lucide-react-native';
import { parseEChartsContent, extractEChartsMetadata } from '../../../lib/artifact-parser';
import { EChartsRenderer } from '../EChartsRenderer';
import { ArtifactRendererConfig, RendererCardMetadata, RendererContentProps } from './types';

export const echartsRendererConfig: ArtifactRendererConfig = {
    type: 'echarts',
    badgeLabel: 'Chart',

    parseContent(content: string) {
        return parseEChartsContent(content);
    },

    getMetadata(data: any): RendererCardMetadata {
        const meta = extractEChartsMetadata(data);
        return {
            title: meta.title,
            badgeText: meta.chartType.toUpperCase(),
            iconVariant: meta.chartType,
        };
    },

    renderBadgeIcon(size: number, color: string) {
        return <BarChart3 size={size} color={color} />;
    },

    renderContent(props: RendererContentProps) {
        return <EChartsRenderer content={props.content} />;
    },
};
