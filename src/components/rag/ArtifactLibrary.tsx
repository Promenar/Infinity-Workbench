/**
 * ArtifactLibrary - 全局 Artifact 库页面
 *
 * 支持跨会话搜索、类型筛选、时间排序和卡片预览。
 */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
    View,
    Text,
    StyleSheet,
    TextInput,
    TouchableOpacity,
    FlatList,
    ActivityIndicator,
} from 'react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { useArtifactStore } from '../../store/artifact-store';
import { Artifact, ArtifactType, ArtifactFilter } from '../../types/artifact';
import { ARTIFACT_TYPE_INFO } from '../../constants/artifact-config';
import { Search, Filter, X, ChevronDown } from 'lucide-react-native';
import { useDebounce } from '../../hooks/useDebounce';

// 类型筛选选项
const TYPE_FILTERS: { label: string; value: ArtifactType | 'all' }[] = [
    { label: '全部', value: 'all' },
    { label: '图表', value: 'echarts' },
    { label: '流程图', value: 'mermaid' },
    { label: '公式', value: 'math' },
    { label: 'HTML', value: 'html' },
    { label: 'SVG', value: 'svg' },
];

// 排序选项
const SORT_OPTIONS: { label: string; value: string }[] = [
    { label: '最新创建', value: 'createdAt_desc' },
    { label: '最早创建', value: 'createdAt_asc' },
    { label: '最近更新', value: 'updatedAt_desc' },
    { label: '按标题', value: 'title_asc' },
];

interface ArtifactLibraryProps {
    onSelectArtifact?: (artifact: Artifact) => void;
}

export const ArtifactLibrary: React.FC<ArtifactLibraryProps> = ({ onSelectArtifact }) => {
    const { isDark, colors } = useTheme();
    const { filteredArtifacts, loading, loadArtifacts, setFilter, clearFilter, searchArtifacts } = useArtifactStore();

    const [searchQuery, setSearchQuery] = useState('');
    const [activeTypeFilter, setActiveTypeFilter] = useState<ArtifactType | 'all'>('all');
    const [activeSort, setActiveSort] = useState('createdAt_desc');
    const [showSortPicker, setShowSortPicker] = useState(false);
    const [searchResults, setSearchResults] = useState<Artifact[]>([]);
    const [isSearching, setIsSearching] = useState(false);

    const debouncedQuery = useDebounce(searchQuery, 300);

    // 加载初始数据
    useEffect(() => {
        loadArtifacts();
    }, []);

    // 搜索逻辑
    useEffect(() => {
        if (debouncedQuery.trim()) {
            setIsSearching(true);
            searchArtifacts(debouncedQuery).then((results: Artifact[]) => {
                setSearchResults(results);
                setIsSearching(false);
            });
        } else {
            setSearchResults([]);
        }
    }, [debouncedQuery]);

    // 筛选逻辑
    const displayArtifacts = useMemo(() => {
        let items = debouncedQuery.trim() ? searchResults : filteredArtifacts;

        if (activeTypeFilter !== 'all') {
            items = items.filter((a: Artifact) => a.type === activeTypeFilter);
        }

        return items;
    }, [filteredArtifacts, searchResults, activeTypeFilter, debouncedQuery]);

    // 排序逻辑
    const sortedArtifacts = useMemo(() => {
        const [field, order] = activeSort.split('_');
        const sorted = [...displayArtifacts].sort((a, b) => {
            let cmp = 0;
            if (field === 'title') {
                cmp = a.title.localeCompare(b.title);
            } else if (field === 'updatedAt') {
                cmp = a.updatedAt - b.updatedAt;
            } else {
                cmp = a.createdAt - b.createdAt;
            }
            return order === 'desc' ? -cmp : cmp;
        });
        return sorted;
    }, [displayArtifacts, activeSort]);

    const handleTypeFilter = useCallback((type: ArtifactType | 'all') => {
        setActiveTypeFilter(type);
        if (type === 'all') {
            clearFilter();
        } else {
            setFilter({ type });
        }
    }, [setFilter, clearFilter]);

    const formatDate = (timestamp: number) => {
        const date = new Date(timestamp);
        return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
    };

    const renderArtifactCard = ({ item }: { item: Artifact }) => {
        const typeInfo = ARTIFACT_TYPE_INFO[item.type];
        const typeColor = typeInfo?.color || '#64748b';
        const typeLabel = typeInfo?.label || item.type;

        return (
            <TouchableOpacity
                style={[styles.card, {
                    backgroundColor: isDark ? '#1c1c1e' : '#fff',
                    borderColor: isDark ? '#2c2c2e' : '#e5e7eb',
                }]}
                onPress={() => onSelectArtifact?.(item)}
                activeOpacity={0.7}
                accessibilityRole="button"
                accessibilityLabel={`${typeLabel}: ${item.title}`}
            >
                <View style={styles.cardHeader}>
                    <View style={[styles.typeBadge, { backgroundColor: typeColor + '20' }]}>
                        <Text style={[styles.typeBadgeText, { color: typeColor }]}>
                            {typeLabel}
                        </Text>
                    </View>
                    <Text style={[styles.cardDate, { color: isDark ? '#71717a' : '#9ca3af' }]}>
                        {formatDate(item.createdAt)}
                    </Text>
                </View>
                <Text style={[styles.cardTitle, { color: isDark ? '#f4f4f5' : '#111827' }]} numberOfLines={2}>
                    {item.title}
                </Text>
                {item.tags && item.tags.length > 0 && (
                    <View style={styles.tagsContainer}>
                        {item.tags.slice(0, 3).map((tag: string, i: number) => (
                            <View key={i} style={[styles.tag, { backgroundColor: isDark ? '#27272a' : '#f3f4f6' }]}>
                                <Text style={[styles.tagText, { color: isDark ? '#a1a1aa' : '#6b7280' }]}>{tag}</Text>
                            </View>
                        ))}
                    </View>
                )}
            </TouchableOpacity>
        );
    };

    return (
        <View style={[styles.container, { backgroundColor: isDark ? '#000' : '#f9fafb' }]}>
            {/* 搜索栏 */}
            <View style={[styles.searchBar, { backgroundColor: isDark ? '#1c1c1e' : '#fff', borderColor: isDark ? '#2c2c2e' : '#e5e7eb' }]}>
                <Search size={18} color={isDark ? '#71717a' : '#9ca3af'} />
                <TextInput
                    style={[styles.searchInput, { color: isDark ? '#f4f4f5' : '#111827' }]}
                    placeholder="搜索 Artifact..."
                    placeholderTextColor={isDark ? '#52525b' : '#9ca3af'}
                    value={searchQuery}
                    onChangeText={setSearchQuery}
                    returnKeyType="search"
                />
                {searchQuery.length > 0 && (
                    <TouchableOpacity onPress={() => setSearchQuery('')}>
                        <X size={16} color={isDark ? '#71717a' : '#9ca3af'} />
                    </TouchableOpacity>
                )}
            </View>

            {/* 类型筛选 */}
            <View style={styles.filterRow}>
                <FlatList
                    horizontal
                    showsHorizontalScrollIndicator={false}
                    data={TYPE_FILTERS}
                    keyExtractor={item => item.value}
                    renderItem={({ item }) => (
                        <TouchableOpacity
                            style={[styles.filterChip, {
                                backgroundColor: activeTypeFilter === item.value
                                    ? (colors?.[500] || '#6366f1')
                                    : (isDark ? '#27272a' : '#f3f4f6'),
                            }]}
                            onPress={() => handleTypeFilter(item.value)}
                        >
                            <Text style={[styles.filterChipText, {
                                color: activeTypeFilter === item.value ? '#fff' : (isDark ? '#a1a1aa' : '#6b7280'),
                            }]}>
                                {item.label}
                            </Text>
                        </TouchableOpacity>
                    )}
                    contentContainerStyle={styles.filterList}
                />
            </View>

            {/* 排序 */}
            <View style={styles.sortRow}>
                <TouchableOpacity
                    style={styles.sortButton}
                    onPress={() => setShowSortPicker(!showSortPicker)}
                >
                    <Text style={[styles.sortLabel, { color: isDark ? '#71717a' : '#6b7280' }]}>
                        排序: {SORT_OPTIONS.find(s => s.value === activeSort)?.label}
                    </Text>
                    <ChevronDown size={14} color={isDark ? '#71717a' : '#6b7280'} />
                </TouchableOpacity>
                <Text style={[styles.countText, { color: isDark ? '#52525b' : '#9ca3af' }]}>
                    {sortedArtifacts.length} 个结果
                </Text>
            </View>

            {showSortPicker && (
                <View style={[styles.sortPicker, { backgroundColor: isDark ? '#1c1c1e' : '#fff', borderColor: isDark ? '#2c2c2e' : '#e5e7eb' }]}>
                    {SORT_OPTIONS.map(option => (
                        <TouchableOpacity
                            key={option.value}
                            style={[styles.sortOption, activeSort === option.value && styles.sortOptionActive]}
                            onPress={() => { setActiveSort(option.value); setShowSortPicker(false); }}
                        >
                            <Text style={[styles.sortOptionText, {
                                color: activeSort === option.value ? (colors?.[500] || '#6366f1') : (isDark ? '#a1a1aa' : '#6b7280'),
                                fontWeight: activeSort === option.value ? '600' : '400',
                            }]}>
                                {option.label}
                            </Text>
                        </TouchableOpacity>
                    ))}
                </View>
            )}

            {/* 列表 */}
            {loading || isSearching ? (
                <View style={styles.loadingContainer}>
                    <ActivityIndicator size="large" color={colors?.[500] || '#6366f1'} />
                </View>
            ) : sortedArtifacts.length === 0 ? (
                <View style={styles.emptyContainer}>
                    <Text style={[styles.emptyText, { color: isDark ? '#52525b' : '#9ca3af' }]}>
                        {debouncedQuery ? '未找到匹配的 Artifact' : '暂无 Artifact'}
                    </Text>
                </View>
            ) : (
                <FlatList
                    data={sortedArtifacts}
                    keyExtractor={item => item.id}
                    renderItem={renderArtifactCard}
                    contentContainerStyle={styles.listContent}
                />
            )}
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        flex: 1,
    },
    searchBar: {
        flexDirection: 'row',
        alignItems: 'center',
        marginHorizontal: 16,
        marginTop: 12,
        paddingHorizontal: 12,
        height: 44,
        borderRadius: 12,
        borderWidth: 1,
    },
    searchInput: {
        flex: 1,
        marginLeft: 8,
        fontSize: 15,
        paddingVertical: 0,
    },
    filterRow: {
        marginTop: 12,
    },
    filterList: {
        paddingHorizontal: 16,
        gap: 8,
    },
    filterChip: {
        paddingHorizontal: 14,
        paddingVertical: 6,
        borderRadius: 20,
        marginRight: 8,
    },
    filterChipText: {
        fontSize: 13,
        fontWeight: '600',
    },
    sortRow: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        paddingHorizontal: 16,
        marginTop: 12,
        marginBottom: 4,
    },
    sortButton: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 4,
    },
    sortLabel: {
        fontSize: 12,
    },
    countText: {
        fontSize: 12,
    },
    sortPicker: {
        marginHorizontal: 16,
        borderRadius: 12,
        borderWidth: 1,
        overflow: 'hidden',
    },
    sortOption: {
        paddingHorizontal: 16,
        paddingVertical: 10,
    },
    sortOptionActive: {
        // Visual emphasis applied via text color
    },
    sortOptionText: {
        fontSize: 14,
    },
    loadingContainer: {
        flex: 1,
        justifyContent: 'center',
        alignItems: 'center',
    },
    emptyContainer: {
        flex: 1,
        justifyContent: 'center',
        alignItems: 'center',
        paddingVertical: 60,
    },
    emptyText: {
        fontSize: 15,
    },
    listContent: {
        paddingHorizontal: 16,
        paddingBottom: 20,
    },
    card: {
        borderRadius: 12,
        borderWidth: 1,
        padding: 12,
        marginTop: 8,
    },
    cardHeader: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 8,
    },
    typeBadge: {
        paddingHorizontal: 8,
        paddingVertical: 2,
        borderRadius: 6,
    },
    typeBadgeText: {
        fontSize: 11,
        fontWeight: '700',
    },
    cardDate: {
        fontSize: 11,
    },
    cardTitle: {
        fontSize: 15,
        fontWeight: '600',
        lineHeight: 20,
    },
    tagsContainer: {
        flexDirection: 'row',
        marginTop: 8,
        gap: 4,
    },
    tag: {
        paddingHorizontal: 8,
        paddingVertical: 2,
        borderRadius: 4,
    },
    tagText: {
        fontSize: 11,
    },
});
