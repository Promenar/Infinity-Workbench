/**
 * ArtifactFilterBar组件
 * 提供Artifact列表的筛选和搜索功能
 */

import React, { useCallback, useState } from 'react';
import { View, TouchableOpacity, StyleSheet, TextInput } from 'react-native';
import Animated, { FadeIn, FadeOut } from 'react-native-reanimated';
import * as Haptics from 'expo-haptics';
import {
    Search,
    X,
    Filter,
    ChevronDown,
    PieChart,
    GitBranch,
    SquarePi,
    Code,
    Image,
    FileText,
} from 'lucide-react-native';
import { useTheme } from '../../../../theme/ThemeProvider';
import { Typography } from '../../../../components/ui/Typography';
import { Spacing } from '../../../../theme/glass';
import { ArtifactType } from '../../../../types/artifact';
import { ARTIFACT_TYPE_INFO, ARTIFACT_TYPES } from '../../../../constants/artifact-config';

interface ArtifactFilterBarProps {
    onFilterChange: (filter: { type?: ArtifactType; searchQuery?: string }) => void;
    currentFilter?: { type?: ArtifactType; searchQuery?: string };
}

const TYPE_ICONS: Record<ArtifactType | 'all', React.ElementType> = {
    all: FileText,
    echarts: PieChart,
    mermaid: GitBranch,
    math: SquarePi,
    html: Code,
    svg: Image,
};

const TYPE_LABELS: Record<ArtifactType | 'all', string> = {
    all: '全部',
    echarts: '图表',
    mermaid: '流程图',
    math: '公式',
    html: 'HTML',
    svg: 'SVG',
};

export const ArtifactFilterBar: React.FC<ArtifactFilterBarProps> = ({
    onFilterChange,
    currentFilter,
}) => {
    const { isDark } = useTheme();
    const [showTypePicker, setShowTypePicker] = useState(false);
    const [searchText, setSearchText] = useState(currentFilter?.searchQuery || '');

    const selectedType = currentFilter?.type || 'all';

    const handleTypeSelect = useCallback((type: ArtifactType | 'all') => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        onFilterChange({
            ...currentFilter,
            type: type === 'all' ? undefined : type,
        });
        setShowTypePicker(false);
    }, [currentFilter, onFilterChange]);

    const handleSearchChange = useCallback((text: string) => {
        setSearchText(text);
        onFilterChange({
            ...currentFilter,
            searchQuery: text || undefined,
        });
    }, [currentFilter, onFilterChange]);

    const handleClearSearch = useCallback(() => {
        setSearchText('');
        onFilterChange({
            ...currentFilter,
            searchQuery: undefined,
        });
    }, [currentFilter, onFilterChange]);

    const toggleTypePicker = useCallback(() => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        setShowTypePicker(prev => !prev);
    }, []);

    const SelectedIcon = TYPE_ICONS[selectedType] || FileText;
    const selectedLabel = TYPE_LABELS[selectedType] || '全部';

    return (
        <View style={styles.container}>
            {/* 搜索框 */}
            <View style={[
                styles.searchContainer,
                {
                    backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)',
                    borderColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)',
                }
            ]}>
                <Search size={16} color={isDark ? '#71717a' : '#9ca3af'} />
                <TextInput
                    style={[
                        styles.searchInput,
                        { color: isDark ? '#fff' : '#111' }
                    ]}
                    placeholder="搜索工件..."
                    placeholderTextColor={isDark ? '#52525b' : '#9ca3af'}
                    value={searchText}
                    onChangeText={handleSearchChange}
                />
                {searchText.length > 0 && (
                    <TouchableOpacity onPress={handleClearSearch} hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}>
                        <X size={16} color={isDark ? '#71717a' : '#9ca3af'} />
                    </TouchableOpacity>
                )}
            </View>

            {/* 类型筛选 */}
            <TouchableOpacity
                style={[
                    styles.typeButton,
                    {
                        backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)',
                        borderColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)',
                    }
                ]}
                onPress={toggleTypePicker}
                activeOpacity={0.7}
            >
                <SelectedIcon size={16} color={isDark ? '#a1a1aa' : '#6b7280'} />
                <Typography style={{ fontSize: 13, color: isDark ? '#fff' : '#111', marginLeft: 6 }}>
                    {selectedLabel}
                </Typography>
                <ChevronDown size={14} color={isDark ? '#71717a' : '#9ca3af'} />
            </TouchableOpacity>

            {/* 类型选择器下拉 */}
            {showTypePicker && (
                <Animated.View
                    entering={FadeIn.duration(150)}
                    exiting={FadeOut.duration(100)}
                    style={[
                        styles.typePicker,
                        {
                            backgroundColor: isDark ? '#27272a' : '#ffffff',
                            borderColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)',
                        }
                    ]}
                >
                    {(['all', ...ARTIFACT_TYPES] as const).map((type) => {
                        const Icon = TYPE_ICONS[type];
                        const label = TYPE_LABELS[type];
                        const isSelected = selectedType === type;

                        return (
                            <TouchableOpacity
                                key={type}
                                style={[
                                    styles.typePickerItem,
                                    isSelected && {
                                        backgroundColor: isDark ? 'rgba(99, 102, 241, 0.2)' : 'rgba(99, 102, 241, 0.1)',
                                    }
                                ]}
                                onPress={() => handleTypeSelect(type)}
                                activeOpacity={0.7}
                            >
                                <Icon size={16} color={isSelected ? '#6366f1' : (isDark ? '#a1a1aa' : '#6b7280')} />
                                <Typography style={{
                                    fontSize: 13,
                                    color: isSelected ? '#6366f1' : (isDark ? '#fff' : '#111'),
                                    marginLeft: 8,
                                }}>
                                    {label}
                                </Typography>
                            </TouchableOpacity>
                        );
                    })}
                </Animated.View>
            )}
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        flexDirection: 'row',
        paddingHorizontal: Spacing[4],
        paddingVertical: Spacing[2],
        gap: Spacing[2],
    },
    searchContainer: {
        flex: 1,
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: Spacing[3],
        height: 40,
        borderRadius: 12,
        borderWidth: 1,
    },
    searchInput: {
        flex: 1,
        fontSize: 14,
        marginLeft: Spacing[2],
    },
    typeButton: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: Spacing[3],
        height: 40,
        borderRadius: 12,
        borderWidth: 1,
    },
    typePicker: {
        position: 'absolute',
        top: '100%',
        right: Spacing[4],
        borderRadius: 12,
        borderWidth: 1,
        padding: Spacing[2],
        minWidth: 140,
        zIndex: 100,
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 4 },
        shadowOpacity: 0.15,
        shadowRadius: 12,
        elevation: 8,
    },
    typePickerItem: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: Spacing[3],
        paddingVertical: Spacing[2],
        borderRadius: 8,
    },
});
