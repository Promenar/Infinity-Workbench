import React, { useEffect } from 'react';
import { View } from 'react-native';
import { RagFolder, RagDocument } from '../../types/rag';
import { FolderItem } from './FolderItem';
import { CompactDocItem } from './CompactDocItem';
import Animated, { FadeIn, FadeOut, Layout } from 'react-native-reanimated';

interface FolderTreeProps {
    folders: RagFolder[];
    documents: RagDocument[];
    expandedFolders: Set<string>;
    onToggleFolder: (id: string) => void;
    onSelectFolder: (id: string) => void;
    onDeleteDocument: (id: string, title: string) => void;
    onVectorizeDocument: (id: string) => void;
    onDeleteFolder?: (id: string, name: string) => void;
    onRenameFolder?: (id: string, name: string) => void;
    onMoveDocument?: (docId: string) => void;
    level?: number;
    parentId?: string;
}

export const FolderTree: React.FC<FolderTreeProps> = ({
    folders,
    documents,
    expandedFolders,
    onToggleFolder,
    onSelectFolder,
    onDeleteDocument,
    onVectorizeDocument,
    onDeleteFolder,
    onRenameFolder,
    onMoveDocument,
    level = 0,
    parentId = undefined
}) => {
    // 获取当前层级的文件夹
    const currentFolders = folders.filter(f => f.parentId === parentId);

    // 获取当前层级的文档（未分类文档在根级别）
    const currentDocuments = documents.filter(d => {
        if (parentId === undefined) {
            return !d.folderId; // 根级别显示未分类文档
        }
        return d.folderId === parentId;
    });

    // 调试日志
    useEffect(() => {
        if (level === 0) {
            console.log(`[FolderTree] Root level - total folders: ${folders.length}, current level folders: ${currentFolders.length}`);
            console.log(`[FolderTree] Current folders:`, currentFolders.map(f => ({ name: f.name, parentId: f.parentId })));
        }
    }, [folders, currentFolders, level]);

    return (
        <>
            {/* 渲染文件夹 */}
            {currentFolders.map(folder => {
                const isExpanded = expandedFolders.has(folder.id);

                return (
                    <View key={folder.id}>
                        <FolderItem
                            id={folder.id}
                            name={folder.name}
                            childCount={folder.childCount}
                            isExpanded={isExpanded}
                            level={level}
                            onToggle={() => onToggleFolder(folder.id)}
                            onPress={() => onSelectFolder(folder.id)}
                            onLongPress={() => { }}
                            onDelete={() => onDeleteFolder?.(folder.id, folder.name)}
                            onRename={() => onRenameFolder?.(folder.id, folder.name)}
                        />

                        {/* 递归渲染子内容 */}
                        {isExpanded && (
                            <Animated.View
                                entering={FadeIn.duration(200)}
                                exiting={FadeOut.duration(150)}
                                layout={Layout.springify()}
                            >
                                <FolderTree
                                    folders={folders}
                                    documents={documents}
                                    expandedFolders={expandedFolders}
                                    onToggleFolder={onToggleFolder}
                                    onSelectFolder={onSelectFolder}
                                    onDeleteDocument={onDeleteDocument}
                                    onVectorizeDocument={onVectorizeDocument}
                                    onDeleteFolder={onDeleteFolder}
                                    onRenameFolder={onRenameFolder}
                                    onMoveDocument={onMoveDocument}
                                    level={level + 1}
                                    parentId={folder.id}
                                />
                            </Animated.View>
                        )}
                    </View>
                );
            })}

            {/* 渲染当前层级的文档 */}
            {currentDocuments.map(doc => (
                <View key={doc.id} style={{ paddingLeft: level * 16 }}>
                    <CompactDocItem
                        id={doc.id}
                        title={doc.title}
                        vectorized={doc.vectorized}
                        vectorCount={doc.vectorCount}
                        fileSize={doc.fileSize}
                        onPress={() => { }}
                        onLongPress={() => { }}
                        onDelete={() => onDeleteDocument(doc.id, doc.title)}
                        onVectorize={() => onVectorizeDocument(doc.id)}
                        onMove={() => onMoveDocument?.(doc.id)}
                    />
                </View>
            ))}
        </>
    );
};
