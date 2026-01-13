import React, { useEffect } from 'react';
import { View, Text, StyleSheet, TouchableOpacity } from 'react-native';
import { useRagStore } from '../../../store/rag-store';
import { useTheme } from '../../../theme/ThemeProvider';
import {
    Zap,
    Search,
    Network,
    Database,
    BrainCircuit,
    Library,
    ChevronDown,
    ChevronUp,
    Check,
    Brain,
    Loader2
} from 'lucide-react-native';
import { Colors } from '../../../theme/colors';
import Animated, {
    useAnimatedStyle,
    useSharedValue,
    withTiming,
    withRepeat,
    withSequence,
    LinearTransition,
    FadeIn,
    FadeOut
} from 'react-native-reanimated';

interface RagOmniIndicatorProps {
    messageId: string;
    isGenerating?: boolean;
    referencesCount?: number;
    isExpanded?: boolean;
    onToggle?: () => void;
}

/**
 * RagOmniIndicator - 全能 RAG 指示器 (全面集成版)
 * 1. 深度嵌入设计，无边界透明。
 * 2. 对齐头像中心线 (32px 容器)。
 * 3. 集成：RAG 检索、图谱抽取、向量化归档的全流量监控。
 */
export const RagOmniIndicator: React.FC<RagOmniIndicatorProps> = ({
    messageId,
    isGenerating = false,
    referencesCount = 0,
    isExpanded = false,
    onToggle
}) => {
    const { isDark, colors } = useTheme();
    const { processingState, processingHistory } = useRagStore();

    // 状态判定逻辑
    const isActiveRetrieval = processingState.activeMessageId === messageId && processingState.stage !== undefined && processingState.status === 'retrieving';
    const isActiveKG = processingState.activeMessageId === messageId && processingState.kgStatus === 'extracting';
    const isActiveArchive = processingState.activeMessageId === messageId && (
        (processingState.status as string) === 'chunking' ||
        (processingState.status as string) === 'summarizing' ||
        (processingState.status as string) === 'vectorizing'
    );

    const history = messageId ? processingHistory[messageId] : undefined;
    // 🔑 增强判定：即使 history 丢失（如由于持久化清理或旧消息），只要有引用数就视为已完成
    const isCompleted = history !== undefined || referencesCount > 0;

    // 动画状态
    const progressWidth = useSharedValue(0);
    const pulseScale = useSharedValue(1);

    // 1. 同步进度条 (优先展示 RAG 检索进度 -> 其次后台进度)
    useEffect(() => {
        let targetProgress = 0;
        if (isActiveRetrieval) {
            targetProgress = processingState.progress || 0;
        } else if (isActiveKG) {
            targetProgress = processingState.kgProgress || 0;
        } else if (isActiveArchive) {
            targetProgress = processingState.progress || 0;
        } else if (isCompleted) {
            targetProgress = 100;
        }

        progressWidth.value = withTiming(targetProgress, { duration: 500 });
    }, [isActiveRetrieval, isActiveKG, isActiveArchive, isCompleted, processingState.progress, processingState.kgProgress]);

    // 2. 呼吸脉冲效果
    useEffect(() => {
        const needsPulse = isActiveRetrieval || isActiveKG || isActiveArchive || (processingState.activeMessageId === messageId && processingState.pulseActive);

        if (needsPulse) {
            pulseScale.value = withRepeat(
                withSequence(withTiming(1.15, { duration: 800 }), withTiming(1, { duration: 800 })),
                -1,
                true
            );
        } else {
            pulseScale.value = 1;
        }
    }, [isActiveRetrieval, isActiveKG, isActiveArchive, processingState.pulseActive, messageId]);

    const animatedProgressStyle = useAnimatedStyle(() => ({
        width: `${progressWidth.value}%`,
        opacity: progressWidth.value > 0 && progressWidth.value < 100 ? 1 : 0,
    }));

    const animatedPulseStyle = useAnimatedStyle(() => ({
        transform: [{ scale: pulseScale.value }],
    }));

    const getStatusContent = () => {
        // 优先级：活动检索 > 图谱抽取 > 后台归档 > 历史记录 > 生成中保活

        // 1. 活动检索 (Retrieval)
        if (isActiveRetrieval) {
            const dict: Record<string, string> = {
                'INTENT': '语义意图识别...',
                'API_TX': '上推请求数据...',
                'API_WAIT': '模型推理中...',
                'API_RX': '接收 RAG 响应...',
                'KG_SCAN': '知识图谱：关系溯源...',
            };
            const label = (processingState.subStage && dict[processingState.subStage]) ||
                (processingState.networkStats && !processingState.networkStats.rxBytes ? '模型推理中...' : '知识库检索中...');
            return { label, icon: <Search size={13} color={colors[500]} />, color: colors[500] };
        }

        // 2. 知识图谱抽取 (KG Extraction)
        if (isActiveKG) {
            const kgLabels: Record<string, string> = {
                'ENTITY_PARSE': '图谱：实体识别...',
                'GRAPH_WALK': '图谱：构建逻辑链...',
            };
            return {
                label: (processingState.subStage && kgLabels[processingState.subStage]) || '全域知识同步...',
                icon: <BrainCircuit size={13} color="#60a5fa" />,
                color: '#60a5fa'
            };
        }

        // 3. 后台归档集成 (Archiving)
        if (isActiveArchive) {
            const label = processingState.status === 'chunking' ? '记忆：语义切片...' :
                processingState.status === 'vectorizing' ? '记忆：向量化存储...' : '记忆：智能摘要...';
            return {
                label,
                icon: <Database size={13} color="#34d399" />,
                color: '#34d399'
            };
        }

        // 4. 完成状态 (History/Completed)
        if (isCompleted) {
            const isSummarized = history?.type === 'summarized';
            const isRetrieved = history?.type === 'retrieved';

            const kpLabel = `已关联 ${referencesCount} 个知识点`;

            // 语义区分：如果是刚检索完（生成中），显示“已关联”或“知识就绪”
            // 如果是真正的后台任务完成，显示“已归档”或“已摘要”
            let statusLabel = referencesCount > 0 ? kpLabel : '未匹配相关知识';
            if (isRetrieved) {
                statusLabel = referencesCount > 0 ? `${kpLabel} (就绪)` : '检索完成 (无匹配)';
            } else if (history) {
                const typeLabel = history?.type === 'summarized' ? '已摘要' : '已归档';
                statusLabel = referencesCount > 0 ? `${kpLabel} (${typeLabel})` : `处理完成 (${typeLabel})`;
            } else {
                // 兜底方案：只有引用数但无历史记录（旧消息或持久化丢失）
                statusLabel = kpLabel;
            }

            // 修正 Chunks 计数获取逻辑
            const chunkCount = history?.chunkCount || (processingState.activeMessageId === messageId ? processingState.chunks.length : 0);

            // 🔑 修正 2: 移除左侧图标的 Check 模式，统一显示功能类别图标
            // 归档状态左侧显示 Database/Brain，检索状态显示 Library/Zap
            const leftIcon = isRetrieved ?
                <Library size={13} color={colors[500]} /> :
                (history ? (history.type === 'summarized' ? <Brain size={13} color="#34d399" /> : <Database size={13} color="#34d399" />) : <Library size={13} color={colors[500]} />);

            return {
                label: statusLabel,
                icon: leftIcon,
                color: isDark ? Colors.dark.textSecondary : 'rgba(0,0,0,0.6)',
                showCaret: referencesCount > 0,
                // 只在真正归档后显示右侧绿勾
                showCheck: !isRetrieved,
                chunkCount: chunkCount
            };
        }

        // 5. 生成中保活 (Failsafe during generation)
        // 如果 RAG 检索已开始过（activeMessageId 匹配），虽然没有 history 且状态已 idle（检索刚结束微秒内）
        // 但只要模型还在生成，就显示“知识就绪”以防止闪烁
        if (isGenerating && processingState.activeMessageId === messageId) {
            const hasStartedRag = processingState.status !== 'idle' || referencesCount > 0 || history;
            const isSyncing = isActiveKG || isActiveArchive;

            return {
                label: referencesCount > 0
                    ? `已关联 ${referencesCount} 个知识点`
                    : (isSyncing ? '全域知识同步...' : (hasStartedRag ? '知识同步完成' : '模型思考中...')),
                icon: isSyncing ? <BrainCircuit size={13} color="#60a5fa" /> : <Zap size={13} color={colors[500]} />,
                color: isSyncing ? '#60a5fa' : colors[500]
            };
        }

        return null;
    };

    const status = getStatusContent();
    if (!status) return null;

    const netStats = processingState.networkStats;

    const ContentBody = (
        <View style={styles.contentWrapper}>
            <View style={styles.leftGroup}>
                <Animated.View style={animatedPulseStyle}>
                    {status.icon}
                </Animated.View>
                <Text style={[styles.statusText, { color: status.color }]}>
                    {status.label}
                </Text>
                {status.showCheck && <Check size={12} color="#10b981" style={{ marginLeft: -2 }} />}
                {status.showCaret && (
                    isExpanded ? <ChevronUp size={12} color={isDark ? '#71717a' : '#94a3b8'} /> : <ChevronDown size={12} color={isDark ? '#71717a' : '#94a3b8'} />
                )}
            </View>

            {/* 流量统计 & 状态指示 */}
            <View style={styles.rightGroup}>
                {isActiveRetrieval && netStats && (
                    <View style={styles.statsGroup}>
                        {netStats.txBytes !== undefined && <Text style={styles.miniStat}>↑{Math.round(netStats.txBytes / 1024)}K</Text>}
                        {netStats.rxBytes !== undefined && <Text style={styles.miniStat}>↓{Math.round(netStats.rxBytes / 1024)}K</Text>}
                    </View>
                )}

                {(!isActiveRetrieval && isCompleted) && (
                    <Text style={styles.miniStat}>{(history?.chunkCount || 0)} Chunks</Text>
                )}
            </View>
        </View>
    );

    return (
        <Animated.View
            layout={LinearTransition}
            entering={FadeIn}
            exiting={FadeOut}
            style={styles.container}
        >
            <View style={styles.mainWrapper}>
                {isCompleted ? (
                    <TouchableOpacity activeOpacity={0.6} onPress={onToggle} style={styles.touchable}>
                        {ContentBody}
                    </TouchableOpacity>
                ) : (
                    <View style={styles.touchable}>
                        {ContentBody}
                    </View>
                )}
            </View>

            {/* 极其微弱的进度导轨 */}
            <View style={styles.progressRail}>
                <Animated.View
                    style={[
                        styles.progressBar,
                        { backgroundColor: status.color || colors[500] },
                        animatedProgressStyle
                    ]}
                />
            </View>
        </Animated.View>
    );
};

const styles = StyleSheet.create({
    container: {
        width: '100%',
        height: 34,
        position: 'relative',
    },
    mainWrapper: {
        flex: 1,
        justifyContent: 'center',
    },
    touchable: {
        height: '100%',
        justifyContent: 'center',
    },
    contentWrapper: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        paddingHorizontal: 0,
    },
    leftGroup: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 6,
    },
    rightGroup: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 8,
    },
    statusText: {
        fontSize: 12,
        fontWeight: '600',
        lineHeight: 16,
        includeFontPadding: false, // 🔑 移除 Android 默认内边距
        textAlignVertical: 'center', // 🔑 强制文本在 line box 中居中
        transform: [{ translateY: 0.5 }], // 🔑 正向补偿，使文字重心下沉至真实中心
    },
    statsGroup: {
        flexDirection: 'row',
        gap: 6,
        alignItems: 'center',
    },
    miniStat: {
        fontSize: 10,
        fontFamily: 'monospace',
        color: '#94a3b8',
        fontWeight: '500',
    },
    progressRail: {
        position: 'absolute',
        bottom: 0,
        left: 0,
        right: 0,
        height: 1.5,
        backgroundColor: 'transparent',
    },
    progressBar: {
        height: '100%',
        borderRadius: 1,
    },
});
