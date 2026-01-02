import React, { useMemo, useRef, useState, useEffect } from 'react';
import { View, TouchableOpacity, ViewStyle, Platform, TextInput, Modal, InteractionManager, ActivityIndicator } from 'react-native';
import { PageLayout, Typography, GlassHeader } from '../../src/components/ui';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { FlashList } from '@shopify/flash-list';
import { ChevronLeft, Settings, ChevronDown } from 'lucide-react-native';
import * as Haptics from '../../src/lib/haptics';
import { useChatStore } from '../../src/store/chat-store';
import { useAgentStore } from '../../src/store/agent-store';
import { useApiStore } from '../../src/store/api-store';
import { ChatBubble } from '../../src/features/chat/components/ChatBubble';
import { ChatInput } from '../../src/features/chat/components/ChatInput';
import { useChat } from '../../src/features/chat/hooks/useChat';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Animated, {
    useSharedValue,
    useAnimatedScrollHandler,
    runOnJS,
    useAnimatedStyle,
    withTiming,
} from 'react-native-reanimated';
import { useTheme } from '../../src/theme/ThemeProvider';
import { KeyboardAvoidingView } from 'react-native-keyboard-controller';
import { ModelPicker } from '../../src/features/settings/ModelPicker';

import { TokenStatsModal } from '../../src/features/chat/components/TokenStatsModal';
import { Message } from '../../src/types/chat';
import { useI18n } from '../../src/lib/i18n';

// Create animated version of FlashList
const AnimatedFlashList = Animated.createAnimatedComponent(FlashList) as any;

export default function ChatDetailScreen() {
    const { id } = useLocalSearchParams<{ id: string }>();
    const router = useRouter();
    const insets = useSafeAreaInsets();
    const { isDark } = useTheme();
    const { getAgent } = useAgentStore();
    const { providers } = useApiStore();
    const { t } = useI18n();

    // Find current model config (moved to top level)
    const session = useChatStore(state => state.getSession(id));
    const agent = useMemo(() => session ? getAgent(session.agentId) : undefined, [session]);

    const currentModelId = session?.modelId || agent?.defaultModel;
    const modelConfig = useMemo(() => {
        if (!currentModelId) return undefined;
        for (const p of providers) {
            const found = p.models.find(m => m.uuid === currentModelId || m.id === currentModelId);
            if (found) return found;
        }
        return undefined;
    }, [providers, currentModelId]);

    const headerSubtitle = useMemo(() => {
        if (!agent) return '';
        let text = agent.name;
        if (modelConfig) {
            text += ` • ${modelConfig.name}`;
            const caps = [];
            if (modelConfig.capabilities?.vision) caps.push('Vision');
            if (modelConfig.capabilities?.internet) caps.push('Web');
            if (modelConfig.capabilities?.reasoning) caps.push('Reasoning');
            if (caps.length > 0) text += ` • ${caps.join(' ')}`;
        }
        return text;
    }, [agent, modelConfig]);

    // @ts-ignore
    const { messages, sendMessage, loading, stop } = useChat(id);
    const listRef = useRef<any>(null);
    const scrollY = useSharedValue(0);

    // Title & Model editing state
    const [showTitleEditor, setShowTitleEditor] = React.useState(false);
    const [editingTitle, setEditingTitle] = React.useState('');
    const [showModelPicker, setShowModelPicker] = React.useState(false);
    const [showTokenStats, setShowTokenStats] = React.useState(false);

    const isAtBottom = useSharedValue(true);
    // 如果有初始滚动位置，则初始认为不在底部，以显示按钮
    React.useEffect(() => {
        if (session?.scrollOffset && session.scrollOffset > 100) {
            isAtBottom.value = false;
        }
    }, [id]);

    const lastMessageCount = useRef(0);
    const scrollOffset = useSharedValue(0);
    const isPositionRestored = useRef(false);
    const listOpacity = useSharedValue(0);
    const isReadyToDisplay = useSharedValue(false);

    // 🔑 用户打断检测
    const userScrolledAway = useSharedValue(false);
    const lastReasoningState = useRef<boolean>(false);

    // Loading State
    const [isInitialLoad, setIsInitialLoad] = useState(true);

    useEffect(() => {
        const task = InteractionManager.runAfterInteractions(() => {
            // Don't hide loading here - wait for list to be ready
        });
        return () => task.cancel();
    }, []);

    // 动画控制按钮显示
    const scrollButtonStyle = useAnimatedStyle(() => {
        const isVisible = !isAtBottom.value && isReadyToDisplay.value;
        return {
            opacity: withTiming(isVisible ? 1 : 0, { duration: 250 }),
            transform: [{ scale: withTiming(isVisible ? 1 : 0.5, { duration: 250 }) }]
        };
    });

    const listContainerStyle = useAnimatedStyle(() => ({
        opacity: listOpacity.value
    }));

    const onScroll = useAnimatedScrollHandler({
        onScroll: (event) => {
            'worklet';
            scrollY.value = event.contentOffset.y;
            scrollOffset.value = event.contentOffset.y;
            const offset = event.contentOffset.y;
            const visibleHeight = event.layoutMeasurement.height;
            const totalHeight = event.contentSize.height;

            // Standard List: Bottom is when offset + visible >= total
            const isBottom = totalHeight - (offset + visibleHeight) < 50;
            isAtBottom.value = isBottom;

            // 🔑 自动恢复追踪：如果用户手动滚回到底部，恢复自动追踪
            if (isBottom) {
                userScrolledAway.value = false;
            }

            // ❌ 移除 onScroll 中的打断检测
            // 之前的逻辑会导致：当内容快速增长时（流式输出），contentSize变大但scroll还没到底，
            // 导致 isAtBottom 暂时为 false，从而误判为用户打断，停止了自动滚动。
            // 现在完全依靠 onBeginDrag 来判断用户主动打断。
        },
        onBeginDrag: () => {
            'worklet';
            // 用户开始拖动时，标记为打断
            userScrolledAway.value = true;
        },
        onMomentumBegin: () => {
            'worklet';
            // 惯性滑动开始时，也标记为打断
            userScrolledAway.value = true;
        }
    });

    const handleContentSizeChange = () => {
        // 检测是否有新消息
        if (messages.length > lastMessageCount.current) {
            const lastMessage = messages[messages.length - 1];
            const isNewUserMessage = lastMessage?.role === 'user';

            if (isNewUserMessage) {
                // 用户发送新消息，强制滚动到底部
                userScrolledAway.value = false;
                isAtBottom.value = true;
                listRef.current?.scrollToEnd({ animated: true });
            } else if (!userScrolledAway.value) {
                // 🔑 AI生成的第一帧：如果用户之前就在底部（未打断），则开始追踪
                if (isAtBottom.value || loading) {
                    listRef.current?.scrollToEnd({ animated: true });
                }
            }
        } else if (loading && !userScrolledAway.value) {
            // 🔑 内容持续更新：只要没打断，就强制追踪
            // 不再检查 isAtBottom.value，因为流式输出时它可能因为渲染延迟而暂时为 false
            listRef.current?.scrollToEnd({ animated: true });
        }
        lastMessageCount.current = messages.length;
    };

    // 初始化列表显示
    const [isListReady, setIsListReady] = React.useState(false);

    React.useEffect(() => {
        if (isListReady) {
            // Standard List: Scroll to bottom (latest messages) on initial load
            setTimeout(() => {
                listRef.current?.scrollToEnd({ animated: false });
                listOpacity.value = withTiming(1, { duration: 250 });
                isReadyToDisplay.value = true;

                // Hide loading spinner
                setTimeout(() => {
                    setIsInitialLoad(false);
                }, 300);
            }, 50);
        }
    }, [isListReady]);

    // 🔑 规则5: Reasoning追踪 + 生成结束重置打断状态
    React.useEffect(() => {
        if (loading) {
            // 🤖 AI开始生成/正在生成

            // 🆕 如果是刚开始生成（或者恢复生成），且当前在底部，强制重置打断状态
            // 这解决了"开始流式输出后没有自动追踪"的问题
            if (isAtBottom.value) {
                userScrolledAway.value = false;
            }

            if (messages.length > 0) {
                const lastMessage = messages[messages.length - 1];
                const hasReasoning = !!lastMessage?.reasoning;

                // Reasoning刚结束，切换到正文
                if (lastReasoningState.current && !hasReasoning) {
                    if (!userScrolledAway.value) {
                        // 折叠reasoning并滚动到正文
                        listRef.current?.scrollToEnd({ animated: true });
                    }
                }
                lastReasoningState.current = hasReasoning;
            }
        } else {
            // 🏁 生成结束
            // 🔑 规则4: 生成结束，重置打断状态（为下一轮做准备）
            // 注意：不强制滚动到底部，尊重用户位置
            if (isAtBottom.value) {
                userScrolledAway.value = false;
            }
            lastReasoningState.current = false;
        }
    }, [loading, messages, isListReady]);

    // 🔑 流式输出追踪：监听消息内容变化
    React.useEffect(() => {
        if (loading && messages.length > 0 && !userScrolledAway.value) {
            // 正在生成中，且用户未打断，持续追踪内容变化
            // 不再检查 isAtBottom.value，原因同上
            const timer = setTimeout(() => {
                listRef.current?.scrollToEnd({ animated: true });
            }, 50);
            return () => clearTimeout(timer);
        }
    }, [loading, messages, userScrolledAway]);

    // 持久化滚动位置
    const saveScrollPosition = (offset: number) => {
        if (offset > 0) {
            useChatStore.getState().updateSessionScrollOffset(id, offset);
        }
    };

    // 组件卸载时保存
    React.useEffect(() => {
        return () => {
            saveScrollPosition(scrollOffset.value);
        };
    }, [id]);

    // 滚动停止时也保存，确保“进度自动更新”
    const handleScrollEnd = () => {
        saveScrollPosition(scrollOffset.value);
    };

    const handleTitleEdit = () => {
        if (!session) return;
        setEditingTitle(session.title);
        setShowTitleEditor(true);
    };

    const handleTitleSave = () => {
        if (!id || !editingTitle.trim()) return;
        useChatStore.getState().updateSessionTitle(id, editingTitle.trim());
        setShowTitleEditor(false);
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    };

    const handleDeleteMessage = () => {
        // Functionality moved to native selection/actions if needed
    };

    // const reversedMessages = useMemo(() => [...messages].reverse(), [messages]);

    if (!session || !agent) {
        return (
            <PageLayout>
                <Typography>Conversation not found</Typography>
            </PageLayout>
        );
    }

    const agentColor = agent.color || '#6366f1';

    return (
        <PageLayout>
            <Stack.Screen
                options={{
                    headerShown: false,
                }}
            />

            <Animated.View style={[{ flex: 1 }, listContainerStyle]}>
                <AnimatedFlashList
                    ref={listRef}
                    inverted={false}
                    data={messages}
                    renderItem={({ item, index }: { item: any, index: number }) => (
                        <ChatBubble
                            message={item}
                            agentId={agent.id}
                            agentAvatar={agent.avatar}
                            agentColor={agentColor}
                            agentName={agent.name}
                            sessionId={id}
                            isGenerating={loading && index === messages.length - 1}
                            onDelete={() => {
                                useChatStore.getState().deleteMessage(id, item.id);
                                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                            }}
                            onLongPress={(message) => {
                                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                                // Handle long press
                            }}
                            onResend={item.role === 'user' ? () => {
                                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                                sendMessage(item.content);
                            } : undefined}
                            onRegenerate={item.role === 'assistant' ? () => {
                                // 找到上一条用户消息
                                const currentIndex = messages.findIndex(m => m.id === item.id);
                                if (currentIndex > 0) {
                                    const prevMsg = messages[currentIndex - 1];
                                    if (prevMsg.role === 'user') {
                                        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                                        // 删除当前 AI 消息 (可选，取决于产品逻辑，这里选择保留历史记录，追加新回答，或者覆盖)
                                        // 这里选择直接触发新生成，复用上一条 prompt
                                        sendMessage(prevMsg.content);
                                    }
                                }
                            } : undefined}
                            modelId={session?.modelId}
                            isDark={isDark}
                        />
                    )}
                    estimatedItemSize={200}
                    removeClippedSubviews={Platform.OS === 'android'}
                    drawDistance={2000}
                    overflowSize={500}
                    getItemType={(item: any) => item.role}
                    contentContainerStyle={{
                        paddingTop: insets.top + 70,
                        paddingBottom: insets.bottom + 100,
                    }}
                    onLayout={() => setIsListReady(true)}
                    onContentSizeChange={handleContentSizeChange}
                    onScroll={onScroll}
                    onMomentumScrollEnd={handleScrollEnd}
                    onScrollEndDrag={handleScrollEnd}
                    overScrollMode="never"
                    decelerationRate="normal"
                    scrollEventThrottle={16}
                />

                {/* Loading Overlay - Only covers message list area */}
                {isInitialLoad && (
                    <View style={{
                        position: 'absolute',
                        top: 0,
                        left: 0,
                        right: 0,
                        bottom: 0,
                        backgroundColor: isDark ? '#000' : '#fff',
                        alignItems: 'center',
                        justifyContent: 'center',
                        zIndex: 999,
                        opacity: 1
                    }}>
                        <ActivityIndicator size="large" color={agentColor} />
                    </View>
                )}
            </Animated.View>

            {/* Floating ChatInput with keyboard avoidance */}
            <KeyboardAvoidingView
                behavior={Platform.OS === 'ios' ? 'padding' : 'padding'}
                keyboardVerticalOffset={0}
                style={{
                    position: 'absolute',
                    bottom: 0,
                    left: 0,
                    right: 0,
                }}
            >
                <ChatInput
                    onSendMessage={(content, options) => {
                        // 🔑 规则3: 发送消息后立即滚动到底部
                        sendMessage(content, options);
                        userScrolledAway.value = false;
                        isAtBottom.value = true;
                        setTimeout(() => {
                            listRef.current?.scrollToEnd({ animated: true });
                        }, 100);
                    }}
                    onStop={stop}
                    sessionId={id}
                    loading={loading}
                    agentColor={agent.color}
                    currentModel={modelConfig?.name || currentModelId}
                    onModelPress={() => setShowModelPicker(true)}
                    tokenUsage={{
                        total: session.stats?.totalTokens || 0,
                        last: messages.length > 0 ? messages[messages.length - 1].tokens : undefined
                    }}
                    onTokenPress={() => setShowTokenStats(true)}
                />
            </KeyboardAvoidingView>

            {/* 浮动"回到底部"按钮 */}
            <Animated.View
                pointerEvents="box-none"
                style={[{
                    position: 'absolute',
                    bottom: insets.bottom + 110,
                    right: 30,
                    zIndex: 9999, // 极高优先级
                }, scrollButtonStyle]}
            >
                <TouchableOpacity
                    activeOpacity={0.8}
                    onPress={() => {
                        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                        listRef.current?.scrollToEnd({ animated: true });
                    }}
                    style={{
                        width: 36,
                        height: 36,
                        borderRadius: 18,
                        backgroundColor: agentColor,
                        alignItems: 'center',
                        justifyContent: 'center',
                        shadowColor: '#000',
                        shadowOffset: { width: 0, height: 4 },
                        shadowOpacity: 0.25,
                        shadowRadius: 6,
                        elevation: 6,
                    }}
                >
                    <ChevronDown size={20} color="#ffffff" strokeWidth={3} />
                </TouchableOpacity>
            </Animated.View>

            {/* Token Stats Modal */}
            <TokenStatsModal
                visible={showTokenStats}
                onClose={() => setShowTokenStats(false)}
                session={session}
            />

            {/* Model Picker */}
            <ModelPicker
                visible={showModelPicker}
                title={t.conversation.switchModel}
                selectedUuid={session.modelId || agent.defaultModel}
                filterType="chat"
                onSelect={(uuid) => {
                    useChatStore.getState().updateSessionModel(id, uuid);
                    setTimeout(() => {
                        setShowModelPicker(false);
                        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
                    }, 10);
                }}
                onClose={() => setShowModelPicker(false)}
            />

            {/* Title Editor Modal */}
            <Modal
                visible={showTitleEditor}
                animationType="fade"
                transparent={true}
                onRequestClose={() => setShowTitleEditor(false)}
            >
                <TouchableOpacity
                    activeOpacity={1}
                    onPress={() => setShowTitleEditor(false)}
                    className="flex-1 bg-black/50 items-center justify-center p-6"
                >
                    <TouchableOpacity
                        activeOpacity={1}
                        onPress={(e) => e.stopPropagation()}
                        className="w-full max-w-md bg-white dark:bg-zinc-900 rounded-3xl p-6 border border-gray-100 dark:border-zinc-800"
                    >
                        <Typography className="text-lg font-bold text-gray-900 dark:text-white mb-4">
                            {t.conversation.editTitle}
                        </Typography>
                        <TextInput
                            value={editingTitle}
                            onChangeText={setEditingTitle}
                            placeholder={t.superAssistant.enterTitle}
                            placeholderTextColor="#9ca3af"
                            autoFocus
                            className="bg-gray-50 dark:bg-black p-4 rounded-xl border border-gray-200 dark:border-zinc-800 text-gray-900 dark:text-white mb-4"
                        />
                        <View className="flex-row gap-3">
                            <TouchableOpacity
                                onPress={() => setShowTitleEditor(false)}
                                className="flex-1 py-3 rounded-xl bg-gray-100 dark:bg-zinc-800 items-center"
                            >
                                <Typography className="font-semibold text-gray-700 dark:text-gray-300">
                                    {t.common.cancel}
                                </Typography>
                            </TouchableOpacity>
                            <TouchableOpacity
                                onPress={handleTitleSave}
                                className="flex-1 py-3 rounded-xl bg-indigo-600 dark:bg-indigo-500 items-center"
                            >
                                <Typography className="font-semibold text-white">
                                    {t.common.save}
                                </Typography>
                            </TouchableOpacity>
                        </View>
                    </TouchableOpacity>
                </TouchableOpacity>
            </Modal>

            {/* Glass Header - Moved to end for stacking context */}
            <GlassHeader
                title={session.title}
                subtitle={headerSubtitle}
                leftAction={{
                    icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
                    onPress: () => router.back(),
                    label: t.common.back,
                }}
                rightAction={{
                    icon: <Settings size={20} color={isDark ? '#fff' : '#000'} />,
                    onPress: () => {
                        // Route to dedicated settings page for Super Assistant
                        const settingsRoute = id === 'super_assistant'
                            ? '/chat/super_assistant/settings'
                            : `/chat/${id}/settings`;
                        router.push(settingsRoute);
                    },
                    label: t.common.settings,
                }}
            />
        </PageLayout >
    );
}
