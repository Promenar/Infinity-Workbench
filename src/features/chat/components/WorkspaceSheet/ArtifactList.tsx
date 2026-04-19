/**
 * ArtifactList组件
 * 显示Artifact列表，集成ArtifactStore和筛选功能
 */

import React, { useState, useCallback, useEffect } from 'react';
import { View, FlatList, RefreshControl, StyleSheet } from 'react-native';
import Animated, { FadeIn, FadeOut } from 'react-native-reanimated';
import { Database } from 'lucide-react-native';
import { useTheme } from '../../../../theme/ThemeProvider';
import { Typography } from '../../../../components/ui/Typography';
import { Spacing } from '../../../../theme/glass';
import { useArtifactStore } from '../../../../store/artifact-store';
import { Artifact, ArtifactType } from '../../../../types/artifact';
import { ArtifactCard } from './ArtifactCard';
import { ArtifactFilterBar } from './ArtifactFilterBar';
import { ArtifactDetailModal } from './ArtifactDetailModal';

interface ArtifactListProps {
    workspacePath?: string;
    onSelectArtifact?: (artifact: Artifact) => void;
    onNavigateToMessage?: (sessionId: string, messageId: string) => void;
}

export const ArtifactList: React.FC<ArtifactListProps> = ({
    workspacePath,
    onSelectArtifact,
    onNavigateToMessage,
}) => {
    const { isDark } = useTheme();
    
    // Store
    const {
        filteredArtifacts,
        filter,
        loading,
        loadArtifacts,
        removeArtifact,
        setFilter,
    } = useArtifactStore();

    // Local state
    const [refreshing, setRefreshing] = useState(false);
    const [selectedArtifact, setSelectedArtifact] = useState<Artifact | null>(null);
    const [detailModalVisible, setDetailModalVisible] = useState(false);

    // 初始加载
    useEffect(() => {
        loadArtifacts();
    }, [loadArtifacts]);

    // ✅ workspacePath 变化时更新筛选条件
    useEffect(() => {
        if (workspacePath) {
            setFilter({ ...filter, workspacePath });
        }
    }, [workspacePath]);

    // 下拉刷新
    const handleRefresh = useCallback(async () => {
        setRefreshing(true);
        await loadArtifacts();
        setRefreshing(false);
    }, [loadArtifacts]);

    // 筛选变化
    const handleFilterChange = useCallback((newFilter: { type?: ArtifactType; searchQuery?: string }) => {
        setFilter(newFilter);
    }, [setFilter]);

    // 点击卡片
    const handleArtifactPress = useCallback((artifact: Artifact) => {
        setSelectedArtifact(artifact);
        setDetailModalVisible(true);
        onSelectArtifact?.(artifact);
    }, [onSelectArtifact]);

    // 删除Artifact
    const handleDeleteArtifact = useCallback(async (id: string) => {
        await removeArtifact(id);
    }, [removeArtifact]);

    // 导出Artifact
    const handleExportArtifact = useCallback((artifact: Artifact) => {
        console.log('[ArtifactList] Export artifact:', artifact.id);
    }, []);

    // 收藏切换
    const handleToggleFavorite = useCallback((id: string) => {
        console.log('[ArtifactList] Toggle favorite:', id);
    }, []);

    // 关闭详情
    const handleCloseDetail = useCallback(() => {
        setDetailModalVisible(false);
        setSelectedArtifact(null);
    }, []);

    // 渲染空状态
    const renderEmpty = () => {
        if (loading) {
            return (
                <View style={styles.emptyContainer}>
                    <Typography style={{ color: isDark ? '#71717a' : '#6b7280', fontSize: 13 }}>
                        加载中...
                    </Typography>
                </View>
            );
        }

        return (
            <Animated.View
                entering={FadeIn.duration(200)}
                exiting={FadeOut.duration(150)}
                style={styles.emptyContainer}
            >
                <View style={[
                    styles.emptyIcon,
                    { backgroundColor: isDark ? 'rgba(255,255,255,0.03)' : '#f3f4f6' }
                ]}>
                    <Database size={32} color={isDark ? '#3f3f46' : '#d1d5db'} />
                </View>
                <Typography style={{
                    color: isDark ? '#71717a' : '#6b7280',
                    marginTop: 16,
                    fontSize: 14,
                    textAlign: 'center'
                }}>
                    暂无工件
                </Typography>
                <Typography style={{
                    color: isDark ? '#52525b' : '#9ca3af',
                    marginTop: 6,
                    fontSize: 12,
                    textAlign: 'center'
                }}>
                    使用工具生成的图表、代码等将保存在这里
                </Typography>
            </Animated.View>
        );
    };

    // 渲染列表项
    const renderItem = ({ item }: { item: Artifact }) => (
        <ArtifactCard
            artifact={item}
            onPress={handleArtifactPress}
            onDelete={handleDeleteArtifact}
            onExport={handleExportArtifact}
            onToggleFavorite={handleToggleFavorite}
        />
    );

    return (
        <View style={styles.container}>
            {/* 筛选栏 */}
            <ArtifactFilterBar
                onFilterChange={handleFilterChange}
                currentFilter={filter}
            />

            {/* 列表 */}
            {filteredArtifacts.length === 0 ? (
                renderEmpty()
            ) : (
                <FlatList
                    data={filteredArtifacts}
                    keyExtractor={(item) => item.id}
                    renderItem={renderItem}
                    contentContainerStyle={styles.list}
                    refreshControl={
                        <RefreshControl
                            refreshing={refreshing}
                            onRefresh={handleRefresh}
                            tintColor={isDark ? '#6366f1' : '#4f46e5'}
                        />
                    }
                    showsVerticalScrollIndicator={false}
                />
            )}

            {/* 详情模态框 */}
            <ArtifactDetailModal
                visible={detailModalVisible}
                artifact={selectedArtifact}
                onClose={handleCloseDetail}
                onNavigateToMessage={onNavigateToMessage}
            />
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        flex: 1,
    },
    list: {
        padding: Spacing[4],
    },
    emptyContainer: {
        flex: 1,
        alignItems: 'center',
        justifyContent: 'center',
        paddingHorizontal: Spacing[8],
        paddingTop: Spacing[16],
    },
    emptyIcon: {
        width: 80,
        height: 80,
        borderRadius: 24,
        alignItems: 'center',
        justifyContent: 'center',
    },
});
