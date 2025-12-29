import React, { useState, useCallback, useEffect, useMemo } from 'react';
import { View, TouchableOpacity, Text, Modal, TextInput, ActivityIndicator } from 'react-native';
import { PageLayout, Typography, useToast, ConfirmDialog } from '../../src/components/ui';
import { Search, X, FolderInput, Folder } from 'lucide-react-native';
import { Stack, useRouter } from 'expo-router';
import { FlashList } from '@shopify/flash-list';
import * as Haptics from 'expo-haptics';
import * as DocumentPicker from 'expo-document-picker';
import * as FileSystem from 'expo-file-system/legacy';
import { useI18n } from '../../src/lib/i18n';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useRagStore } from '../../src/store/rag-store';
import { useTheme } from '../../src/theme/ThemeProvider';
import { ControlBar } from '../../src/components/rag/ControlBar';
import { FolderTree } from '../../src/components/rag/FolderTree';
import { CompactDocItem } from '../../src/components/rag/CompactDocItem';
import { ScrollView } from 'react-native-gesture-handler';

export default function RagScreen() {
    const router = useRouter();
    const { showToast } = useToast();
    const { isDark } = useTheme();
    const insets = useSafeAreaInsets();
    const { t } = useI18n();

    // RagStore
    const {
        documents,
        folders,
        loadDocuments,
        loadFolders,
        addDocument,
        deleteDocument,
        vectorizeDocument,
        vectorizationQueue,
        currentTask,
        addFolder,
        deleteFolder,
        renameFolder,
        moveDocument,
        expandedFolders,
        toggleFolder,
        moveFolder
    } = useRagStore();

    // 搜索状态
    const [searchQuery, setSearchQuery] = useState('');
    const [isSearchFocused, setIsSearchFocused] = useState(false);

    // 文件夹Modal状态
    const [showFolderModal, setShowFolderModal] = useState(false);
    const [folderModalMode, setFolderModalMode] = useState<'create' | 'rename'>('create');
    const [editingFolderId, setEditingFolderId] = useState<string | null>(null);
    const [newFolderName, setNewFolderName] = useState('');

    // 移动操作状态
    const [showMoveModal, setShowMoveModal] = useState(false);
    const [movingDocId, setMovingDocId] = useState<string | null>(null);
    const [movingFolderId, setMovingFolderId] = useState<string | null>(null);

    // 确认弹窗状态
    const [confirmState, setConfirmState] = useState<{
        visible: boolean;
        title: string;
        message: string;
        onConfirm: () => void;
        isDestructive?: boolean;
    }>({
        visible: false,
        title: '',
        message: '',
        onConfirm: () => { },
        isDestructive: false
    });

    useEffect(() => {
        loadDocuments();
        loadFolders();
    }, []);

    // 搜索过滤
    const filteredDocuments = useMemo(() => {
        if (!searchQuery.trim()) return documents;
        const query = searchQuery.toLowerCase();
        return documents.filter(doc =>
            doc.title.toLowerCase().includes(query)
        );
    }, [documents, searchQuery]);

    // 文件导入
    const handleFileImport = useCallback(async () => {
        try {
            const result = await DocumentPicker.getDocumentAsync({
                type: ['text/plain', 'application/pdf', 'application/json', 'text/markdown'],
                copyToCacheDirectory: true
            });

            if (result.canceled) return;

            const file = result.assets[0];

            if (file.size && file.size > 5 * 1024 * 1024) {
                showToast('文件过大 (最大 5MB)', 'error');
                return;
            }

            let content = '';
            if (file.mimeType === 'application/pdf') {
                showToast('PDF 解析暂未实现', 'error');
                return;
            } else {
                content = await FileSystem.readAsStringAsync(file.uri);
            }

            if (!content.trim()) {
                showToast('文件为空', 'error');
                return;
            }

            await addDocument(file.name, content, file.size || content.length, 'text');
            showToast('文档已加入队列！', 'success');

        } catch (e) {
            console.error(e);
            showToast('导入失败: ' + (e as Error).message, 'error');
        }
    }, [addDocument, showToast]);

    // 删除文档
    const handleDeleteDocument = useCallback((id: string, title: string) => {
        setConfirmState({
            visible: true,
            title: '删除文档',
            message: `确定删除 "${title}"? 此操作不可撤销。`,
            isDestructive: true,
            onConfirm: async () => {
                await deleteDocument(id);
                showToast('已删除', 'success');
                setConfirmState(prev => ({ ...prev, visible: false }));
            }
        });
    }, [deleteDocument, showToast]);

    // 文件夹操作
    const handleNewFolder = useCallback(() => {
        setFolderModalMode('create');
        setEditingFolderId(null);
        setNewFolderName('');
        setShowFolderModal(true);
    }, []);

    const handleRenameFolder = useCallback((id: string, name: string) => {
        setFolderModalMode('rename');
        setEditingFolderId(id);
        setNewFolderName(name);
        setShowFolderModal(true);
    }, []);

    const handleFolderSubmit = useCallback(async () => {
        if (!newFolderName.trim()) {
            showToast('请输入名称', 'error');
            return;
        }

        try {
            if (folderModalMode === 'create') {
                await addFolder(newFolderName.trim());
                showToast('文件夹已创建', 'success');
            } else if (editingFolderId) {
                await renameFolder(editingFolderId, newFolderName.trim());
                showToast('已重命名', 'success');
            }
            setShowFolderModal(false);
            setNewFolderName('');
            setEditingFolderId(null);
        } catch (e) {
            showToast('操作失败: ' + (e as Error).message, 'error');
        }
    }, [newFolderName, folderModalMode, editingFolderId, addFolder, renameFolder, showToast]);

    const handleDeleteFolder = useCallback((id: string, name: string) => {
        setConfirmState({
            visible: true,
            title: '删除文件夹',
            message: `确定删除 "${name}"? 文档将移至根目录，子文件夹将被级联删除。`,
            isDestructive: true,
            onConfirm: async () => {
                await deleteFolder(id);
                showToast('已删除', 'success');
                setConfirmState(prev => ({ ...prev, visible: false }));
            }
        });
    }, [deleteFolder, showToast]);

    // 移动操作
    const handleStartMoveDoc = useCallback((docId: string) => {
        setMovingDocId(docId);
        setMovingFolderId(null);
        setShowMoveModal(true);
    }, []);

    const handleStartMoveFolder = useCallback((folderId: string) => {
        setMovingFolderId(folderId);
        setMovingDocId(null);
        setShowMoveModal(true);
    }, []);

    const handleConfirmMove = useCallback(async (targetFolderId: string | null) => {
        try {
            if (movingDocId) {
                await moveDocument(movingDocId, targetFolderId);
                showToast('文档已移动', 'success');
            } else if (movingFolderId) {
                // Prevent moving folder into itself or its children (simple check: validation logic needed ideally)
                if (targetFolderId === movingFolderId) {
                    showToast('不能移动到自己', 'error');
                    return;
                }
                await moveFolder(movingFolderId, targetFolderId);
                showToast('文件夹已移动', 'success');
            }
            setShowMoveModal(false);
            setMovingDocId(null);
            setMovingFolderId(null);
        } catch (e) {
            showToast('移动失败: ' + (e as Error).message, 'error');
        }
    }, [movingDocId, movingFolderId, moveDocument, moveFolder, showToast]);

    // 批量向量化
    const handleBatchVectorize = useCallback(() => {
        const unprocessed = documents.filter(d => d.vectorized === 0 || d.vectorized === -1);
        if (unprocessed.length === 0) {
            showToast('没有需要处理的文档', 'info');
            return;
        }

        setConfirmState({
            visible: true,
            title: '批量向量化',
            message: `确定要开始对 ${unprocessed.length} 个文档进行向量化处理吗？`,
            onConfirm: () => {
                unprocessed.forEach(doc => {
                    vectorizeDocument(doc.id);
                });
                showToast(`已加入 ${unprocessed.length} 个任务`, 'success');
                setConfirmState(prev => ({ ...prev, visible: false }));
            }
        });
    }, [documents, vectorizeDocument, showToast]);

    // 渲染标题栏
    const renderHeader = () => (
        <View className="mb-4">
            {/* 搜索栏 */}
            <View className="px-6 pb-3">
                <View className={`h-12 ${isSearchFocused ? 'bg-indigo-50 dark:bg-indigo-900/20 border-indigo-500' : 'bg-gray-50 dark:bg-zinc-900 border-gray-100 dark:border-zinc-800'} 
                               border rounded-2xl flex-row items-center px-4 transition-all`}>
                    <Search size={18} color={isSearchFocused ? "#6366f1" : "#94a3b8"} strokeWidth={2} />
                    <TextInput
                        value={searchQuery}
                        onChangeText={setSearchQuery}
                        onFocus={() => setIsSearchFocused(true)}
                        onBlur={() => setIsSearchFocused(false)}
                        placeholder={t.library.searchPlaceholder}
                        placeholderTextColor="#94a3b8"
                        className="flex-1 ml-3 text-gray-900 dark:text-white font-semibold text-base"
                    />
                    {searchQuery.length > 0 && (
                        <TouchableOpacity onPress={() => setSearchQuery('')}>
                            <X size={18} color="#94a3b8" />
                        </TouchableOpacity>
                    )}
                </View>
            </View>

            {/* 控制栏 */}
            <ControlBar
                onNewFolder={handleNewFolder}
                onBatchVectorize={handleBatchVectorize}
                currentTask={currentTask ? {
                    docTitle: currentTask.docTitle,
                    progress: currentTask.progress
                } : null}
                queueLength={vectorizationQueue.length}
            />

            {/* 文档数量 */}
            <View className="px-6 mb-3">
                <Typography variant="sectionHeader" className="text-gray-400 font-bold text-[11px] uppercase tracking-wider">
                    {searchQuery ? `搜索结果 (${filteredDocuments.length})` : `文档 (${documents.length})`}
                </Typography>
            </View>
        </View>
    );

    return (
        <PageLayout safeArea={false} className="bg-white dark:bg-black">
            <Stack.Screen options={{ headerShown: false }} />

            {/* 标题 */}
            <View style={{ paddingTop: 64, paddingBottom: 8, paddingHorizontal: 24 }}>
                <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', height: 56, marginBottom: 24 }}>
                    <View>
                        <Text style={{ fontSize: 32, fontWeight: '900', color: isDark ? '#fff' : '#111', letterSpacing: -1.5, lineHeight: 38 }}>
                            {t.library.title}
                        </Text>
                        <Text style={{ fontSize: 11, fontWeight: 'bold', color: '#9ca3af', textTransform: 'uppercase', letterSpacing: 2, marginTop: 4, lineHeight: 11 }}>
                            知识库管理
                        </Text>
                    </View>
                    <TouchableOpacity
                        onPress={handleFileImport}
                        style={{
                            width: 48,
                            height: 48,
                            backgroundColor: isDark ? '#18181b' : '#eef2ff',
                            borderWidth: 1,
                            borderColor: isDark ? '#27272a' : '#e0e7ff',
                            borderRadius: 16,
                            alignItems: 'center',
                            justifyContent: 'center'
                        }}
                    >
                        <FolderInput size={24} color="#6366f1" strokeWidth={2.5} />
                    </TouchableOpacity>
                </View>
            </View>

            {/* 文档/文件夹列表 */}
            <ScrollView
                contentContainerStyle={{ paddingBottom: 100 }}
                showsVerticalScrollIndicator={false}
            >
                {renderHeader()}

                {searchQuery ? (
                    filteredDocuments.map(doc => (
                        <CompactDocItem
                            key={doc.id}
                            id={doc.id}
                            title={doc.title}
                            vectorized={doc.vectorized}
                            vectorCount={doc.vectorCount}
                            fileSize={doc.fileSize}
                            onPress={() => { }}
                            onLongPress={() => { }}
                            onDelete={() => handleDeleteDocument(doc.id, doc.title)}
                            onVectorize={() => vectorizeDocument(doc.id)}
                            onMove={() => handleStartMoveDoc(doc.id)}
                        />
                    ))
                ) : (
                    <FolderTree
                        key={`folders-${folders.length}`}
                        folders={folders}
                        documents={documents}
                        expandedFolders={expandedFolders}
                        onToggleFolder={toggleFolder}
                        onSelectFolder={(id) => console.log('Selected folder:', id)}
                        onDeleteDocument={handleDeleteDocument}
                        onVectorizeDocument={vectorizeDocument}
                        onDeleteFolder={handleDeleteFolder}
                        onRenameFolder={handleRenameFolder}
                        onMoveDocument={handleStartMoveDoc}
                        onMoveFolder={handleStartMoveFolder}
                    />
                )}
            </ScrollView>

            {/* 新建文件夹Modal */}
            <Modal transparent visible={showFolderModal} animationType="fade">
                <View className="flex-1 bg-black/40 items-center justify-center px-6">
                    <View className="bg-white dark:bg-zinc-900 rounded-2xl p-6 w-full shadow-xl">
                        <Typography className="text-lg font-bold text-gray-900 dark:text-white mb-4">
                            {folderModalMode === 'create' ? '新建文件夹' : '重命名文件夹'}
                        </Typography>

                        <TextInput
                            value={newFolderName}
                            onChangeText={setNewFolderName}
                            placeholder="输入名称"
                            placeholderTextColor="#94a3b8"
                            className="bg-gray-50 dark:bg-zinc-800 rounded-xl px-4 py-3 text-gray-900 dark:text-white font-semibold mb-6"
                            autoFocus
                        />

                        <View className="flex-row gap-3">
                            <TouchableOpacity
                                onPress={() => {
                                    setShowFolderModal(false);
                                    setNewFolderName('');
                                    setEditingFolderId(null);
                                }}
                                className="flex-1 h-12 bg-gray-100 dark:bg-zinc-800 rounded-xl items-center justify-center"
                            >
                                <Typography className="font-bold text-gray-600 dark:text-gray-400">
                                    取消
                                </Typography>
                            </TouchableOpacity>

                            <TouchableOpacity
                                onPress={handleFolderSubmit}
                                className="flex-1 h-12 bg-indigo-500 rounded-xl items-center justify-center"
                            >
                                <Typography className="font-bold text-white">
                                    {folderModalMode === 'create' ? '创建' : '确定'}
                                </Typography>
                            </TouchableOpacity>
                        </View>
                    </View>
                </View>
            </Modal>

            {/* 移动文档Modal */}
            <Modal transparent visible={showMoveModal} animationType="slide">
                <View className="flex-1 bg-black/40 justify-end">
                    <View className="bg-white dark:bg-zinc-900 rounded-t-3xl p-6 h-[60%]">
                        <View className="flex-row justify-between items-center mb-6">
                            <Typography className="text-xl font-bold text-gray-900 dark:text-white">
                                {movingDocId ? '移动文档到...' : '移动文件夹到...'}
                            </Typography>
                            <TouchableOpacity onPress={() => setShowMoveModal(false)}>
                                <X size={24} color="#94a3b8" />
                            </TouchableOpacity>
                        </View>

                        <ScrollView className="flex-1">
                            {/* 根目录项 */}
                            <TouchableOpacity
                                onPress={() => handleConfirmMove(null)}
                                className="flex-row items-center py-4 border-b border-gray-50 dark:border-zinc-800/50"
                            >
                                <View className="w-10 h-10 rounded-full bg-indigo-50 dark:bg-indigo-900/20 items-center justify-center mr-4">
                                    <Folder size={20} color="#6366f1" />
                                </View>
                                <Typography className="flex-1 font-bold text-gray-900 dark:text-white">
                                    根目录
                                </Typography>
                            </TouchableOpacity>

                            {/* 文件夹列表 (排除自己) */}
                            {folders.filter(f => f.id !== movingFolderId).map(folder => (
                                <TouchableOpacity
                                    key={folder.id}
                                    onPress={() => handleConfirmMove(folder.id)}
                                    className="flex-row items-center py-4 border-b border-gray-50 dark:border-zinc-800/50"
                                >
                                    <View className="w-10 h-10 rounded-full bg-amber-50 dark:bg-amber-900/20 items-center justify-center mr-4">
                                        <Folder size={20} color="#f59e0b" />
                                    </View>
                                    <Typography className="flex-1 font-bold text-gray-900 dark:text-white">
                                        {folder.name}
                                    </Typography>
                                </TouchableOpacity>
                            ))}
                        </ScrollView>
                    </View>
                </View>
            </Modal>

            <ConfirmDialog
                visible={confirmState.visible}
                title={confirmState.title}
                message={confirmState.message}
                isDestructive={confirmState.isDestructive}
                onConfirm={confirmState.onConfirm}
                onCancel={() => setConfirmState(prev => ({ ...prev, visible: false }))}
            />
        </PageLayout>
    );
}
