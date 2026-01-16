import React, { useState } from 'react';
import { View, TouchableOpacity, Image, ActivityIndicator, LayoutChangeEvent, Linking, TextInput } from 'react-native';
import { ScrollView } from 'react-native-gesture-handler';
import Animated, { FadeIn, FadeInUp, FadeOut, FadeOutUp, Layout, withTiming } from 'react-native-reanimated';
import { Typography } from '../ui/Typography';
import {
    ChevronDown,
    ChevronRight,
    Brain,
    Globe,
    Database,
    Image as ImageIcon,
    Terminal,
    Share2,
    X,
    AlertCircle,
    ListTodo,
    Check,
    Send,
    User,
    Hand
} from 'lucide-react-native';
import clsx from 'clsx';
import * as Sharing from 'expo-sharing';
import ImageView from 'react-native-image-viewing';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ExecutionStep } from '../../types/skills';
import { useI18n } from '../../lib/i18n';
import { useTheme } from '../../theme/ThemeProvider';
import { RagReferencesList } from '../../features/chat/components/RagReferences';
import { useChatStore } from '../../store/chat-store';
import * as Haptics from '../../lib/haptics';

interface Props {
    steps: ExecutionStep[];
    isMessageGenerating?: boolean;
    sessionId?: string;
}

const StepIcon = ({ type, toolName }: { type: string, toolName?: string }) => {
    if (type === 'thinking') return <Brain size={16} color="#A0A0A0" />;
    if (type === 'error') return <AlertCircle size={16} color="#FF6B6B" />;
    if (type === 'plan_item') return <ListTodo size={16} color="#A0A0A0" />;
    if (type === 'intervention_required') return <Hand size={16} color="#f59e0b" />;
    if (type === 'intervention_result') return <User size={16} color="#10b981" />;

    // Tool Icons
    if (toolName === 'search_internet') return <Globe size={16} color="#4F8EF7" />;
    if (toolName === 'query_vector_db') return <Database size={16} color="#FF9F43" />;
    if (toolName === 'generate_image') return <ImageIcon size={16} color="#2ED573" />;

    return <Terminal size={16} color="#A0A0A0" />; // Default tool icon
};

// ... (SearchResultsList stays the same)
const SearchResultsList = ({ sources, isDark }: { sources: any[], isDark: boolean }) => {
    if (!sources || sources.length === 0) return null;
    return (
        <View className="mt-2 space-y-2">
            {sources.map((source: any, idx: number) => (
                <View key={idx} className="p-3 rounded-xl border" style={{
                    backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : '#f8f9fa',
                    borderColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)'
                }}>
                    <TouchableOpacity
                        activeOpacity={0.6}
                        onPress={() => source.url && Linking.openURL(source.url)}
                        className="flex-row items-center mb-1"
                    >
                        <Globe size={12} color="#4F8EF7" className="mr-2" />
                        <Typography className="text-xs font-bold text-blue-500 underline" numberOfLines={1}>
                            {source.title || 'Source'}
                        </Typography>
                    </TouchableOpacity>
                    <TouchableOpacity
                        onPress={() => source.url && Linking.openURL(source.url)}
                        activeOpacity={0.6}
                    >
                        <Typography className="text-[10px] opacity-70 mb-1 text-blue-400" numberOfLines={1}>{source.url}</Typography>
                    </TouchableOpacity>
                    <Typography className="text-xs opacity-90" numberOfLines={2}>{source.snippet || source.content}</Typography>
                </View>
            ))}
        </View>
    );
};

const InterventionUI = ({ sessionId, toolName }: { sessionId: string, toolName?: string }) => {
    const [inputValue, setInputValue] = useState('');
    const { isDark } = useTheme();
    const { t } = useI18n();
    const session = useChatStore(s => s.sessions.find(sk => sk.id === sessionId));

    // ✅ CRITICAL FIX: 当不在等待审批状态或 approvalRequest 为空时，不渲染任何内容
    if (!session || session.loopStatus !== 'waiting_for_approval' || !session.approvalRequest) {
        return null;
    }

    const handleApprove = () => {
        setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
        }, 10);
        useChatStore.getState().resumeGeneration(sessionId, true, inputValue.trim() || undefined);
    };

    const handleReject = () => {
        setTimeout(() => {
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
        }, 10);
        useChatStore.getState().resumeGeneration(sessionId, false);
    };

    return (
        <Animated.View
            entering={FadeIn.duration(400)}
            className="mt-3"
        >
            {/* 工具详情展示 */}
            {session?.approvalRequest && (
                <View className="mb-3 p-3 rounded-xl" style={{
                    backgroundColor: isDark ? 'rgba(251, 191, 36, 0.08)' : 'rgba(251, 191, 36, 0.12)',
                    borderWidth: 0,
                }}>
                    <Typography className="text-xs font-bold mb-1" style={{ color: isDark ? '#fbbf24' : '#d97706' }}>
                        {session.approvalRequest.toolName}
                    </Typography>
                    {session.approvalRequest.args && session.approvalRequest.args.length > 0 && (
                        <Typography variant="caption" className="font-mono text-[10px] opacity-60">
                            {JSON.stringify(session.approvalRequest.args, null, 2).slice(0, 150)}
                            {JSON.stringify(session.approvalRequest.args).length > 150 ? '...' : ''}
                        </Typography>
                    )}
                </View>
            )}

            {/* 介入输入框 */}
            <View className="mb-3">
                <Typography variant="caption" className="mb-2 opacity-60 text-xs">
                    {t.agent.manualInterventionHint || "可选：提供修改指令"}
                </Typography>
                <View className="rounded-2xl px-3 py-2" style={{
                    backgroundColor: isDark ? 'rgba(255,255,255,0.04)' : 'rgba(0,0,0,0.03)',
                    borderWidth: 0,
                }}>
                    <TextInput
                        placeholder="例如: '仅写入 /tmp 目录'"
                        placeholderTextColor={isDark ? "rgba(255,255,255,0.3)" : "rgba(0,0,0,0.3)"}
                        className="text-xs"
                        style={{ color: isDark ? '#fff' : '#000', minHeight: 32 }}
                        value={inputValue}
                        onChangeText={setInputValue}
                        multiline
                        numberOfLines={2}
                    />
                </View>
            </View>

            {/* 操作按钮 - 无边界、深度嵌入 */}
            <View className="flex-row gap-3">
                <TouchableOpacity
                    onPress={handleReject}
                    className="flex-1 py-2.5 rounded-xl items-center justify-center"
                    style={{
                        backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.04)',
                        borderWidth: 0,
                    }}
                >
                    <Typography className="text-xs font-semibold" style={{ color: isDark ? 'rgba(255,255,255,0.7)' : 'rgba(0,0,0,0.6)' }}>
                        {t.common.reject}
                    </Typography>
                </TouchableOpacity>

                <TouchableOpacity
                    onPress={handleApprove}
                    className="flex-1 py-2.5 rounded-xl items-center justify-center"
                    style={{
                        backgroundColor: isDark ? 'rgba(251, 191, 36, 0.15)' : 'rgba(251, 191, 36, 0.2)',
                        borderWidth: 0,
                    }}
                >
                    <Typography className="text-xs font-bold" style={{ color: isDark ? '#fbbf24' : '#d97706' }}>
                        {inputValue.trim() ? '携带指令批准' : '批准并执行'}
                    </Typography>
                </TouchableOpacity>
            </View>


        </Animated.View>
    );
};

const TimelineItemComponent = ({ step, isLast, isMessageGenerating, sessionId }: { step: ExecutionStep, isLast: boolean, isMessageGenerating?: boolean, sessionId?: string }) => {
    const [expanded, setExpanded] = useState(false);
    const [viewerVisible, setViewerVisible] = useState(false);
    const [sharing, setSharing] = useState(false);
    const { t } = useI18n();
    const { isDark } = useTheme();
    const insets = useSafeAreaInsets();

    const getTitle = () => {
        const skillName = step.toolName ? (t.skills.names[step.toolName as keyof typeof t.skills.names] || step.toolName) : '';

        switch (step.type) {
            case 'thinking': {
                const isStillThinking = isLast && isMessageGenerating;
                return isStillThinking ? (t.skills.timeline.thinking || 'Thinking...') : (t.skills.timeline.thought || 'Thought');
            }
            case 'plan_item': return t.skills.timeline.plan || 'Plan';
            case 'tool_call': return t.skills.timeline.using.replace('{name}', skillName);
            case 'tool_result': return t.skills.timeline.result.replace('{name}', skillName);
            case 'error': return t.skills.timeline.error;
            case 'intervention_required': return t.agent.interventionRequired || 'Approval Required';
            case 'intervention_result': return t.agent.interventionTaken || 'Intervention Taken';
        }
    };

    React.useEffect(() => {
        if (step.type === 'thinking' || step.type === 'tool_result') {
            setExpanded(false);
        }
    }, [step.type]);

    const getPreview = () => {
        if (step.type === 'tool_call') {
            return JSON.stringify(step.toolArgs).substring(0, 50) + '...';
        }

        if (step.type === 'plan_item' || step.type === 'intervention_result') {
            return step.content || '';
        }

        if (step.type === 'intervention_required') {
            return step.toolName ? `Approve ${step.toolName}?` : 'Pending your decision';
        }

        if (step.type === 'tool_result') {
            if (step.toolName === 'query_vector_db' && step.data?.references) {
                return `${step.data.references.length} result(s)`;
            }
            if (step.toolName === 'search_internet' && step.data?.sources) {
                return `${step.data.sources.length} source(s)`;
            }
        }

        return (step.content || '').substring(0, 60) + '...';
    };

    const getImageUri = () => {
        if (step.toolName === 'generate_image' && step.type === 'tool_result' && step.content) {
            const match = step.content.match(/(file:\/\/\S+|data:image\/\S+;base64,\S+)/);
            return match ? match[1] : null;
        }
        return null;
    };

    const imageUri = getImageUri();

    const handleShare = async () => {
        if (!imageUri) return;
        try {
            setSharing(true);
            if (await Sharing.isAvailableAsync()) {
                await Sharing.shareAsync(imageUri, {
                    mimeType: 'image/png',
                    dialogTitle: 'Share or Save generated image',
                });
            }
        } catch (error) {
            console.error('[Timeline] Share failed:', error);
        } finally {
            setSharing(false);
        }
    };

    const isRagResult = step.toolName === 'query_vector_db' && step.type === 'tool_result' && step.data?.references;
    const isSearchResult = step.toolName === 'search_internet' && step.type === 'tool_result' && step.data?.sources;

    return (
        <Animated.View layout={Layout.springify()} className="flex-row">
            {imageUri && (
                <ImageView
                    images={[{ uri: imageUri }]}
                    imageIndex={0}
                    visible={viewerVisible}
                    onRequestClose={() => setViewerVisible(false)}
                    swipeToCloseEnabled={true}
                    doubleTapToZoomEnabled={true}
                    HeaderComponent={({ imageIndex }) => (
                        <View className="flex-row justify-end px-4" style={{ paddingTop: insets.top + 10 }}>
                            <TouchableOpacity onPress={() => setViewerVisible(false)} className="w-10 h-10 rounded-full bg-black/40 items-center justify-center border border-white/10">
                                <X size={20} color="white" />
                            </TouchableOpacity>
                        </View>
                    )}
                />
            )}

            <Animated.View layout={Layout.springify()} className="items-center mr-3 w-6">
                <View className={clsx(
                    "w-6 h-6 rounded-full items-center justify-center",
                    step.type === 'error' ? "border border-red-500/50 bg-red-500/20" :
                        step.type === 'intervention_required' ? "border border-amber-500/50 bg-amber-500/20" :
                            "bg-black/5 dark:bg-zinc-900/50"
                )}>
                    <StepIcon type={step.type} toolName={step.toolName} />
                </View>
                {!isLast && <Animated.View layout={Layout.springify()} className="w-[1px] flex-1 my-1" style={{ backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.05)' }} />}
            </Animated.View>

            <Animated.View layout={Layout.springify()} className="flex-1 pb-4">
                <TouchableOpacity
                    activeOpacity={0.7}
                    onPress={() => setExpanded(!expanded)}
                    className={clsx(
                        "flex-row items-center justify-between rounded-2xl p-2",
                        step.type === 'intervention_required'
                            ? "bg-amber-500/5 dark:bg-amber-500/10"
                            : "bg-transparent" // ✅ 两种模式下都保持透明，避免边框感
                    )}
                    style={{
                        // 仅在需要注意的状态下显示极其微弱的边框或完全无边框
                        borderWidth: step.type === 'intervention_required' ? 1 : 0,
                        borderColor: step.type === 'intervention_required' ? 'rgba(245, 158, 11, 0.2)' : 'transparent',
                    }}
                >
                    <View className="flex-1 mr-2 px-1">
                        <Typography
                            variant="caption"
                            className="font-bold"
                            style={{
                                color: step.type === 'intervention_required'
                                    ? (isDark ? '#fbbf24' : '#d97706')
                                    : (isDark ? 'rgba(255,255,255,0.8)' : 'rgba(0,0,0,0.8)')
                            }}
                        >
                            {getTitle()}
                        </Typography>
                        {!expanded && (
                            <Typography variant="caption" color="secondary" numberOfLines={1}>
                                {getPreview()}
                            </Typography>
                        )}
                    </View>
                    <View className="flex-row items-center">
                        {imageUri && !expanded && <ImageIcon size={14} color="#2ED573" className="mr-2" />}
                        {expanded ? <ChevronDown size={14} color="#666" /> : <ChevronRight size={14} color="#666" />}
                    </View>
                </TouchableOpacity>

                {expanded && (
                    <Animated.View
                        entering={FadeInUp.springify().damping(20).stiffness(150)}
                        exiting={FadeOutUp.duration(200)}
                        className="mt-2 pl-2 border-l-2"
                        style={{ borderColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.1)' }}
                    >
                        {step.type === 'tool_call' && step.toolArgs && (
                            <Typography variant="caption" className="font-mono text-xs text-blue-300 mb-2">
                                {JSON.stringify(step.toolArgs, null, 2)}
                            </Typography>
                        )}

                        {isRagResult ? (
                            <RagReferencesList references={step.data.references} isDark={isDark} />
                        ) : isSearchResult ? (
                            <SearchResultsList sources={step.data.sources} isDark={isDark} />
                        ) : imageUri ? (
                            <View className="mt-2 rounded-lg overflow-hidden border border-white/10 bg-white/5">
                                <TouchableOpacity activeOpacity={0.9} onPress={() => setViewerVisible(true)}>
                                    <Image source={{ uri: imageUri }} className="w-full aspect-square" resizeMode="cover" />
                                </TouchableOpacity>
                                <View className="bg-black/50 p-2 flex-row items-center justify-between">
                                    <View className="flex-row items-center gap-x-3 ml-auto">
                                        <TouchableOpacity onPress={handleShare} disabled={sharing}>
                                            {sharing ? <ActivityIndicator size="small" color="#AAA" /> : <Share2 size={16} color="#AAA" />}
                                        </TouchableOpacity>
                                    </View>
                                </View>
                            </View>
                        ) : (
                            <Typography variant="body" className="text-sm mt-1" style={{ color: isDark ? 'rgba(255,255,255,0.7)' : 'rgba(0,0,0,0.7)' }}>
                                {step.content}
                            </Typography>
                        )}
                    </Animated.View>
                )}

                {/* Render Intervention UI if this is the active intervention required step */}
                {step.type === 'intervention_required' && isLast && sessionId && (
                    <InterventionUI sessionId={sessionId} toolName={step.toolName} />
                )}
            </Animated.View>
        </Animated.View>
    );
};

const TimelineItem = React.memo(TimelineItemComponent, (prev, next) => {
    if (prev.isLast !== next.isLast) return false;
    if (prev.isMessageGenerating !== next.isMessageGenerating) return false;
    if (prev.step.id !== next.step.id) return false;
    if (prev.step.content !== next.step.content) return false;
    if (prev.step.timestamp !== next.step.timestamp) return false;
    if (prev.sessionId !== next.sessionId) return false;
    return true;
});

const GHScrollView = Animated.createAnimatedComponent(ScrollView);

export const ToolExecutionTimeline: React.FC<Props> = ({ steps, isMessageGenerating, sessionId }) => {
    const scrollViewRef = React.useRef<ScrollView>(null);
    const lastScrollTime = React.useRef(0);

    React.useEffect(() => {
        if ((isMessageGenerating || steps.some(s => s.type === 'intervention_required')) && steps.length > 0) {
            const now = Date.now();
            if (now - lastScrollTime.current > 500) {
                const timer = setTimeout(() => {
                    scrollViewRef.current?.scrollToEnd({ animated: true });
                    lastScrollTime.current = Date.now();
                }, 100);
                return () => clearTimeout(timer);
            }
        }
    }, [steps.length, isMessageGenerating]);

    if (!steps || steps.length === 0) return null;

    return (
        <View className="py-2 my-1 relative">
            <GHScrollView
                ref={scrollViewRef as any}
                nestedScrollEnabled={false}
                showsVerticalScrollIndicator={false}
                fadingEdgeLength={32}
                style={{ maxHeight: 280 }}
                contentContainerStyle={{ paddingVertical: 8, paddingRight: 8 }}
            >
                {steps.filter(s => s.type !== 'plan_item').map((step, index, arr) => (
                    <TimelineItem
                        key={step.id}
                        step={step}
                        isLast={index === arr.length - 1}
                        isMessageGenerating={isMessageGenerating}
                        sessionId={sessionId}
                    />
                ))}
            </GHScrollView>

            {/* 常态化干预输入框：当 Loop 处于活跃状态且这是最后一个消息时渲染 */}
            <LoopActiveIntervention sessionId={sessionId} />
        </View>
    );
};

const LoopActiveIntervention = ({ sessionId }: { sessionId?: string }) => {
    const { isDark } = useTheme();
    const { t } = useI18n();
    const session = useChatStore(s => sessionId ? s.sessions.find(sk => sk.id === sessionId) : null);
    const loopStatus = session?.loopStatus;
    const isWaiting = loopStatus === 'waiting_for_approval';
    const isRunning = loopStatus === 'running';

    // 如果已经在 TimelineItem 中渲染了完整的 InterventionUI (waiting_for_approval 且有 intervention_required step)，
    // 或者进程已结束，则不在此处重复渲染简单输入框。
    // 但是用户要求“常态保持”，我们可以始终在底部提供一个简洁的输入区。

    if (!sessionId || (!isRunning && !isWaiting)) return null;

    // 如果是 waiting 状态，TimelineItem 已经处理了带按钮的 UI，所以这里可以返回 null 避免重复
    // 除非我们想把输入框从 TimelineItem 剥离到全局底部。
    // 基于目前的视觉结构，保留 TimelineItem 中的审批按钮，但在底部常驻一个输入框。

    // 用户反馈：干预输入框不可见。
    // 我们进一步放宽显示条件，只要会话存在且状态不是 'success'，就优先显示。
    // 甚至在 'error' 状态下也保留，以便用户可以尝试通过指令“抢救”或重新触发。
    if (!sessionId) return null;

    return (
        <Animated.View
            entering={FadeIn.duration(400)}
            className="mt-2 px-1"
        >
            <View className="flex-row items-center bg-zinc-100 dark:bg-white/5 rounded-2xl border border-black/5 dark:border-white/5 px-3 py-1.5">
                <TextInput
                    placeholder={t.agent.manualInterventionHint || "Direct agent..."}
                    placeholderTextColor={isDark ? "rgba(255,255,255,0.3)" : "rgba(0,0,0,0.3)"}
                    className="flex-1 text-xs py-1"
                    style={{ color: isDark ? '#fff' : '#000' }}
                    onSubmitEditing={(e) => {
                        const val = e.nativeEvent.text;
                        if (val.trim()) {
                            useChatStore.getState().setPendingIntervention(sessionId, val.trim());
                            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                        }
                    }}
                    returnKeyType="send"
                />
                <View className="p-2 opacity-30">
                    <Send size={14} color={isDark ? "#fff" : "#000"} />
                </View>
            </View>
        </Animated.View>
    );
};
