/**
 * Artifact类型配置
 * 定义各类型Artifact的显示属性和行为
 */

import { ArtifactType } from '../types/artifact';

export const ARTIFACT_TYPE_INFO: Record<ArtifactType, {
    label: string;
    icon: string;
    color: string;
    extensions: string[];
}> = {
    echarts: {
        label: 'ECharts图表',
        icon: 'bar-chart-2',
        color: '#3b82f6',
        extensions: ['json'],
    },
    mermaid: {
        label: 'Mermaid图表',
        icon: 'git-branch',
        color: '#8b5cf6',
        extensions: ['mmd', 'mermaid'],
    },
    math: {
        label: '数学公式',
        icon: 'function-square',
        color: '#10b981',
        extensions: ['tex', 'latex'],
    },
    html: {
        label: 'HTML页面',
        icon: 'code',
        color: '#f59e0b',
        extensions: ['html', 'htm'],
    },
    svg: {
        label: 'SVG图形',
        icon: 'image',
        color: '#ec4899',
        extensions: ['svg'],
    },
};

/**
 * 获取Artifact类型的显示标签
 */
export function getArtifactTypeLabel(type: ArtifactType): string {
    return ARTIFACT_TYPE_INFO[type]?.label ?? type;
}

/**
 * 获取Artifact类型的图标名称
 */
export function getArtifactTypeIcon(type: ArtifactType): string {
    return ARTIFACT_TYPE_INFO[type]?.icon ?? 'file';
}

/**
 * 获取Artifact类型的颜色
 */
export function getArtifactTypeColor(type: ArtifactType): string {
    return ARTIFACT_TYPE_INFO[type]?.color ?? '#64748b';
}

/**
 * 获取Artifact类型的文件扩展名
 */
export function getArtifactTypeExtensions(type: ArtifactType): string[] {
    return ARTIFACT_TYPE_INFO[type]?.extensions ?? [];
}

/**
 * 所有Artifact类型列表
 */
export const ARTIFACT_TYPES = Object.keys(ARTIFACT_TYPE_INFO) as ArtifactType[];
