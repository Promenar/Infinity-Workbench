import React, { useMemo, useRef } from 'react';
import { View, TouchableOpacity, ViewStyle, Platform, TextInput, Modal } from 'react-native';
import { PageLayout, Typography, GlassHeader } from '../../src/components/ui';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { FlashList } from '@shopify/flash-list';
import { ChevronLeft, Settings, ChevronDown } from 'lucide-react-native';
import * as Haptics from 'expo-haptics';
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

// Create animated version of FlashList
const AnimatedFlashList = Animated.createAnimatedComponent(FlashList) as any;

export default function ChatDetailScreen() {
    const { id } = useLocalSearchParams<{ id: string }>();
    const router = useRouter();
    const insets = useSafeAreaInsets();
    const { isDark } = useTheme();
    const { getAgent } = useAgentStore();
    const { providers } = useApiStore();

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

            // Use a smaller threshold for precision
            isAtBottom.value = totalHeight - (offset + visibleHeight) < 50;
        }
    });

    // 移除 handleScrollEnd，改用动画样式逻辑

    const handleContentSizeChange = () => {
        // 如果用户处于底部，且正在生成内容，则自动追踪滚动
        if (isAtBottom.value && loading) {
            listRef.current?.scrollToEnd({ animated: true });
        }

        // 专门处理新消息到达时的自动定位 (如果是用户发送的消息，强制滚到底)
        const hasNewMessage = messages.length > lastMessageCount.current;
        if (hasNewMessage) {
            const lastMessage = messages[messages.length - 1];
            if (lastMessage.role === 'user' || isAtBottom.value) {
                listRef.current?.scrollToEnd({ animated: true });
            }
        }

        lastMessageCount.current = messages.length;
    };

    // 初始化时恢复上次滚动位置
    const [isListReady, setIsListReady] = React.useState(false);

    React.useEffect(() => {
        if (isListReady && messages.length > 0) {
            // 补救性滚动（以防 initialScrollOffset 没对上）
            if (session?.scrollOffset && !isPositionRestored.current) {
                isPositionRestored.current = true;
                listRef.current?.scrollToOffset({
                    offset: session.scrollOffset,
                    animated: false
                });
            }

            // 确保位置设置好后再显示列表和按钮
            const timer = setTimeout(() => {
                listOpacity.value = withTiming(1, { duration: 250 });
                isReadyToDisplay.value = true;
            }, 80);
            return () => clearTimeout(timer);
        } else if (isListReady && messages.length === 0) {
            listOpacity.value = withTiming(1, { duration: 250 });
        }
    }, [isListReady, id, (messages.length > 0)]);

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




            {/* Glass Header */}
            <GlassHeader
                title={session.title}
                subtitle={headerSubtitle}
                leftAction={{
                    icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
                    onPress: () => router.back(),
                    label: 'Back',
                }}
                rightAction={{
                    icon: <Settings size={20} color={isDark ? '#fff' : '#000'} />,
                    onPress: () => router.push(`/chat/${id}/settings`),
                    label: 'Settings',
                }}
            />

            {/* Message List */}
            <Animated.View style={[{ flex: 1 }, listContainerStyle]}>
                <AnimatedFlashList
                    ref={listRef}
                    data={messages}
                    renderItem={({ item, index }: { item: any, index: number }) => (
                        <ChatBubble
                            message={item}
                            agentId={agent.id}
                            agentAvatar={agent.avatar}
                            agentColor={agentColor}
                            agentName={agent.name}
                            isGenerating={loading && index === messages.length - 1}
                            onDelete={() => {
                                useChatStore.getState().deleteMessage(id, item.id);
                                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                            }}
                        />
                    )}
                    estimatedItemSize={200}
                    removeClippedSubviews={false}
                    initialScrollOffset={session?.scrollOffset || 0} // 使用这个彻底修复跳变
                    drawDistance={2500}
                    getItemType={(item: any) => item.role}
                    contentContainerStyle={{
                        paddingTop: insets.top + 70,
                        paddingBottom: insets.bottom + 100,
                        paddingHorizontal: 16,
                    }}
                    onLayout={() => setIsListReady(true)}
                    onContentSizeChange={handleContentSizeChange}
                    onScroll={onScroll}
                    onMomentumScrollEnd={handleScrollEnd}
                    onScrollEndDrag={handleScrollEnd}
                    scrollEventThrottle={16}
                />
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
                    onSendMessage={sendMessage}
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
                    right: 20,
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
                        width: 52,
                        height: 52,
                        borderRadius: 26,
                        backgroundColor: agentColor,
                        alignItems: 'center',
                        justifyContent: 'center',
                        shadowColor: '#000',
                        shadowOffset: { width: 0, height: 6 },
                        shadowOpacity: 0.35,
                        shadowRadius: 8,
                        elevation: 10,
                    }}
                >
                    <ChevronDown size={32} color="#ffffff" strokeWidth={3} />
                </TouchableOpacity>
            </Animated.View>

            {/* Token Stats Modal */}
            <TokenStatsModal
                visible={showTokenStats}
                onClose={() => setShowTokenStats(false)}
                stats={{
                    sessionTotal: session.stats?.totalTokens || 0,
                    lastMessage: messages.length > 0 ? messages[messages.length - 1].tokens : undefined
                }}
            />

            {/* Model Picker */}
            <ModelPicker
                visible={showModelPicker}
                title="切换会话模型"
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
                            编辑会话标题
                        </Typography>
                        <TextInput
                            value={editingTitle}
                            onChangeText={setEditingTitle}
                            placeholder="输入会话标题"
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
                                    取消
                                </Typography>
                            </TouchableOpacity>
                            <TouchableOpacity
                                onPress={handleTitleSave}
                                className="flex-1 py-3 rounded-xl bg-indigo-600 dark:bg-indigo-500 items-center"
                            >
                                <Typography className="font-semibold text-white">
                                    保存
                                </Typography>
                            </TouchableOpacity>
                        </View>
                    </TouchableOpacity>
                </TouchableOpacity>
            </Modal>
        </PageLayout>
    );
}
