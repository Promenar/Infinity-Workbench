/**
 * Artifact类型定义
 * 用于Workspace中管理各类生成内容
 */

export type ArtifactType = 'echarts' | 'mermaid' | 'math' | 'html' | 'svg';

export interface Artifact {
    id: string;
    type: ArtifactType;
    title: string;
    content: string;
    previewImage?: string;
    sessionId: string;
    messageId: string;
    workspacePath?: string; // ✅ 所属工作区路径（关联 Session.workspacePath）
    createdAt: number;
    updatedAt: number;
    tags?: string[];
}

export type ArtifactSortField = 'createdAt' | 'updatedAt' | 'title' | 'type';
export type ArtifactSortOrder = 'asc' | 'desc';

export interface ArtifactFilter {
    type?: ArtifactType;
    sessionId?: string;
    workspacePath?: string; // ✅ 按工作区过滤
    searchQuery?: string;
    dateFrom?: number;
    dateTo?: number;
    tags?: string[];
    sortBy?: ArtifactSortField;
    sortOrder?: ArtifactSortOrder;
}

export interface CreateArtifactParams {
    type: ArtifactType;
    title?: string;
    content: string;
    previewImage?: string;
    sessionId: string;
    messageId: string;
    workspacePath?: string; // ✅ 所属工作区路径
    tags?: string[];
}

export interface UpdateArtifactParams {
    title?: string;
    content?: string;
    previewImage?: string;
    tags?: string[];
}
