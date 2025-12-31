import React, { useEffect } from 'react';
import { View, TouchableOpacity, ScrollView, Platform } from 'react-native';
import { Typography } from '../../../components/ui';
import { RagReference } from '../../../types/chat';
import { ChevronDown, FileText, Library } from 'lucide-react-native';
import Animated, {
    useAnimatedStyle,
    useSharedValue,
    withRepeat,
    withTiming,
    withSequence,
    FadeInUp,
    FadeOutUp
} from 'react-native-reanimated';

import { RagProgress } from '../../../types/chat';

interface RagReferencesChipProps {
    references: RagReference[];
    isDark: boolean;
    expanded: boolean;
    onToggle: () => void;
    loading?: boolean;
    progress?: RagProgress; // ✅ 新增：进度对象
}

/**
 * RAG 状态胶囊按钮 - 支持进度条显示
 */
export const RagReferencesChip: React.FC<RagReferencesChipProps> = ({
    references,
    isDark,
    expanded,
    onToggle,
    loading,
    progress
}) => {
    const opacity = useSharedValue(1);

    useEffect(() => {
        // 只有在 loading 且没有具体进度时才呼吸，或者一直呼吸？
        // 如果有进度条，可能不需要呼吸，而是进度条动画。
        // 保留呼吸效果作为背景活跃指示
        if (loading) {
            opacity.value = withRepeat(
                withSequence(
                    withTiming(0.6, { duration: 800 }),
                    withTiming(1, { duration: 800 })
                ),
                -1,
                true
            );
        } else {
            opacity.value = 1;
        }
    }, [loading]);

    const animatedStyle = useAnimatedStyle(() => ({
        opacity: opacity.value
    }));

    if (!loading && (!references || references.length === 0)) return null;

    // 映射阶段到中文描述
    const getStageLabel = (stage?: string) => {
        switch (stage) {
            case 'rewriting': return '优化查询...';
            case 'embedding': return '向量化...';
            case 'searching': return '检索记忆...';
            case 'reranking': return 'Rerank精排...';
            case 'done': return '检索完成';
            default: return '检索知识库...';
        }
    };

    return (
        <Animated.View style={animatedStyle}>
            <TouchableOpacity
                activeOpacity={0.7}
                onPress={loading ? undefined : onToggle}
                disabled={loading}
                style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    backgroundColor: isDark ? 'rgba(255, 255, 255, 0.05)' : 'rgba(0, 0, 0, 0.03)',
                    paddingHorizontal: 10,
                    paddingVertical: 5,
                    borderRadius: 20,
                    borderWidth: 1,
                    borderColor: loading
                        ? (isDark ? 'rgba(16, 185, 129, 0.6)' : '#10b981')
                        : (expanded
                            ? (isDark ? 'rgba(52, 211, 153, 0.4)' : '#059669')
                            : (isDark ? 'rgba(255, 255, 255, 0.05)' : 'rgba(0,0,0,0.05)')),
                    gap: 6,
                    minWidth: 120 // 保证进度条有空间
                }}
            >
                <Library size={12} color={loading ? '#34d399' : (isDark ? (expanded ? '#34d399' : '#a1a1aa') : (expanded ? '#059669' : '#64748b'))} />

                <View>
                    <Typography style={{
                        fontSize: 11,
                        fontWeight: '600',
                        color: loading ? '#34d399' : (isDark ? (expanded ? '#34d399' : '#a1a1aa') : (expanded ? '#047857' : '#4b5563'))
                    }}>
                        {loading && progress ? getStageLabel(progress.stage) : (loading ? '检索知识库...' : `${references.length} 个知识点`)}
                    </Typography>

                    {/* 进度条 (仅在 loading 且有进度时显示) */}
                    {loading && progress && (
                        <View style={{
                            height: 2,
                            backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.1)',
                            marginTop: 2,
                            borderRadius: 1,
                            width: '100%',
                            overflow: 'hidden'
                        }}>
                            <Animated.View style={{
                                height: '100%',
                                backgroundColor: '#34d399',
                                width: `${progress.percentage}%`
                            }} />
                        </View>
                    )}
                </View>

                {!loading && (
                    <Animated.View style={{ transform: [{ rotate: expanded ? '180deg' : '0deg' }] }}>
                        <ChevronDown size={11} color={isDark ? '#71717a' : '#94a3b8'} />
                    </Animated.View>
                )}
            </TouchableOpacity>
        </Animated.View>
    );
};

interface RagReferencesListProps {
    references: RagReference[];
    isDark: boolean;
}

/**
 * RAG 引用详情列表 - 优化视觉排版
 */
export const RagReferencesList: React.FC<RagReferencesListProps> = ({
    references,
    isDark
}) => {
    if (!references || references.length === 0) return null;

    return (
        <Animated.View
            entering={FadeInUp.duration(300)}
            exiting={FadeOutUp.duration(200)}
            className="mb-4 space-y-2"
        >
            {references.map((ref, index) => (
                <View
                    key={ref.id || index}
                    style={{
                        padding: 12,
                        borderRadius: 12,
                        marginBottom: 10,
                        backgroundColor: isDark ? 'rgba(24, 24, 27, 0.6)' : '#f9fafb',
                        borderLeftWidth: 3,
                        borderLeftColor: isDark ? '#10b981' : '#059669',
                        borderWidth: Platform.OS === 'android' ? 1 : 0, // Android 辅助轮廓
                        borderColor: isDark ? '#27272a' : '#f3f4f6',
                    }}
                >
                    <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 8, gap: 6 }}>
                        <Typography
                            className="text-xs font-bold flex-1"
                            style={{ color: isDark ? '#f4f4f5' : '#18181b', opacity: 0.9 }}
                            numberOfLines={1}
                        >
                            {ref.source || '未命名文档'}
                        </Typography>
                        {ref.similarity && (
                            <View style={{
                                paddingHorizontal: 6,
                                paddingVertical: 2,
                                borderRadius: 8,
                                backgroundColor: isDark ? 'rgba(16, 185, 129, 0.1)' : '#ecfdf5'
                            }}>
                                <Typography style={{
                                    fontSize: 9,
                                    fontWeight: '700',
                                    color: isDark ? '#34d399' : '#059669'
                                }}>
                                    {(ref.similarity * 100).toFixed(0)}%
                                </Typography>
                            </View>
                        )}
                    </View>

                    <Typography
                        style={{
                            fontSize: 12,
                            lineHeight: 18,
                            color: isDark ? '#a1a1aa' : '#4b5563',
                        }}
                        numberOfLines={5}
                    >
                        {ref.content}
                    </Typography>
                </View>
            ))}
        </Animated.View>
    );
};
