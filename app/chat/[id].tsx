import React, { useMemo, useRef, useState, useEffect } from 'react';
import {
  View,
  TouchableOpacity,
  ViewStyle,
  Platform,
  TextInput,
  Modal,
  InteractionManager,
  ActivityIndicator,
} from 'react-native';
import { PageLayout, Typography, GlassHeader } from '../../src/components/ui';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { FlatList } from 'react-native'; // 替代 FlashList，详见下方注释
import { ChevronLeft, Settings, ChevronDown } from 'lucide-react-native';
import * as Haptics from '../../src/lib/haptics';
import { useChatStore } from '../../src/store/chat-store';
import { useAgentStore } from '../../src/store/agent-store';
import { useApiStore } from '../../src/store/api-store';
import { ChatBubble } from '../../src/features/chat/components/ChatBubble';
import { ChatInput } from '../../src/features/chat/components/ChatInput';
import { ChatSkeleton } from '../../src/features/chat/components/ChatSkeleton';
import { ExecutionModeSelector } from '../../src/features/chat/components/ExecutionModeSelector';
import { useChat } from '../../src/features/chat/hooks/useChat';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Animated, {
  useSharedValue,
  useAnimatedScrollHandler,
  runOnJS,
  useAnimatedStyle,
  withTiming,
  useAnimatedKeyboard,
} from 'react-native-reanimated';
import { useTheme } from '../../src/theme/ThemeProvider';
import { KeyboardAvoidingView, KeyboardStickyView } from 'react-native-keyboard-controller';
import { ModelPicker } from '../../src/features/settings/ModelPicker';

import { TokenStatsModal } from '../../src/features/chat/components/TokenStatsModal';
import { Message } from '../../src/types/chat';
import { useI18n } from '../../src/lib/i18n';
import { KGExtractionIndicator } from '../../src/components/rag/KGExtractionIndicator';

import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake'; // ✅
import { graphExtractor } from '../../src/lib/rag/graph-extractor'; // ✅
import * as Clipboard from 'expo-clipboard'; // ✅
import { Platform as RNPlatform, Alert } from 'react-native'; // ✅
import { emitToast } from '../../src/lib/utils/toast-emitter'; // ✅
/**
 * 🔑 架构决策：使用 FlatList 而非 FlashList
 * 
 * 原因：FlashList 在复杂 Markdown 内容（表格等）的场景下存在滚动回弹 bug，
 * 这是一个已知的上游问题（@shopify/flash-list）。
 * 
 * 权衡：
 * - FlatList 在文本为主的聊天场景下性能完全足够（100 条消息仅占 ~10MB）
 * - FlashList 的 Cell 回收优势在文本场景下收益有限
 * - 用户体验稳定性优先于理论性能
 * 
 * 相关文档：.agent/docs/archive/2026-02-05-flashlist-deprecation.md
 */
const AnimatedFlatList = Animated.createAnimatedComponent(FlatList) as any;

export default function ChatDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const { isDark } = useTheme();
  const { getAgent } = useAgentStore();
  const { providers } = useApiStore();
  const { t } = useI18n();

  // Find current model config (moved to top level)
  const session = useChatStore((state) => state.getSession(id));
  const agent = useMemo(() => (session ? getAgent(session.agentId) : undefined), [session]);

  const currentModelId = session?.modelId || agent?.defaultModel;
  const { modelConfig, currentProvider } = useMemo(() => {
    if (!currentModelId) return { modelConfig: undefined, currentProvider: undefined };

    // 1. Priority: Exact UUID match (Unique)
    for (const p of providers) {
      const found = p.models.find((m) => m.uuid === currentModelId);
      if (found) return { modelConfig: found, currentProvider: p };
    }

    // 2. Fallback: ID match (Ambiguous - takes first available)
    for (const p of providers) {
      const found = p.models.find((m) => m.id === currentModelId);
      if (found) return { modelConfig: found, currentProvider: p };
    }

    return { modelConfig: undefined, currentProvider: undefined };
  }, [providers, currentModelId]);

  const headerSubtitle = useMemo(() => {
    // ✅ 仅显示助手名称，不显示模型名称
    return agent?.name || '';
  }, [agent]);

  // @ts-ignore
  // @ts-ignore
  const { messages, sendMessage, loading, stop, loadMore, hasMore } = useChat(id);
  const listRef = useRef<any>(null);
  const scrollY = useSharedValue(0);


  // Title & Model editing state
  const [showTitleEditor, setShowTitleEditor] = React.useState(false);
  const [editingTitle, setEditingTitle] = React.useState('');
  const [showModelPicker, setShowModelPicker] = React.useState(false);
  const [showTokenStats, setShowTokenStats] = React.useState(false);

  // ✅ 新增：重发编辑模式状态
  const [editingMessage, setEditingMessage] = React.useState<{
    id: string;
    content: string;
    images?: any[];
  } | null>(null);

  const isAtBottom = useSharedValue(true);
  // 如果有初始滚动位置，则初始认为不在底部，以显示按钮
  React.useEffect(() => {
    if (session?.scrollOffset && session.scrollOffset > 100) {
      isAtBottom.value = false;
    }
  }, [id]);

  // ✅ Auto-toggle tools based on model type (Local vs Cloud)
  useEffect(() => {
    if (!currentProvider || !id) return;

    const isLocal = currentProvider.type === 'local';
    const isCurrentlyEnabled = session?.options?.toolsEnabled !== false;

    if (isLocal && isCurrentlyEnabled) {
      console.log('[ChatDetail] Auto-disabling tools for local model');
      useChatStore.getState().updateSessionOptions(id, { toolsEnabled: false });
    }
  }, [currentProvider?.type, id]);

  const lastMessageCount = useRef(0);
  const scrollOffset = useSharedValue(0);
  const isPositionRestored = useRef(false);
  const listOpacity = useSharedValue(0);
  const isReadyToDisplay = useSharedValue(false);

  // 🔑 用户打断检测
  const userScrolledAway = useSharedValue(false);
  const lastReasoningState = useRef<boolean>(false);
  // 🔑 内容高度追踪（用于精确滚动计算）
  const contentHeightRef = useRef<number>(0);
  const listHeightRef = useRef<number>(0);

  // Loading State
  const [isInitialLoad, setIsInitialLoad] = useState(true);

  // ✅ Keep Awake Logic: Prevent screen sleep during generation
  const isGenerating = useChatStore((state) => state.currentGeneratingSessionId === id);

  useEffect(() => {
    if (isGenerating) {
      activateKeepAwakeAsync();
    } else {
      // 🛡️ 规则 8.1：原生桥接调用必须延迟，避免与状态更新竞态
      setTimeout(() => deactivateKeepAwake(), 10);
    }
    return () => {
      // 🛡️ 卸载时同样延迟原生调用，防止阻塞导航动画起始帧
      setTimeout(() => deactivateKeepAwake(), 10);
    };
  }, [isGenerating]);

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
      transform: [{ scale: withTiming(isVisible ? 1 : 0.5, { duration: 250 }) }],
    };
  });

  const listContainerStyle = useAnimatedStyle(() => ({
    opacity: listOpacity.value,
  }));

  const onScroll = useAnimatedScrollHandler({
    onScroll: (event) => {
      'worklet';
      scrollY.value = event.contentOffset.y;
      scrollOffset.value = event.contentOffset.y;
      const offset = event.contentOffset.y;

      // With inverted list, offset 0 is the bottom.
      // So isAtBottom is true when offset is small.
      isAtBottom.value = offset < 50;
    },
    onBeginDrag: () => {
      'worklet';
      userScrolledAway.value = true;
    },
    onEndDrag: (event) => {
      'worklet';
      const offset = event.contentOffset.y;
      if (offset < 20) {
        userScrolledAway.value = false;
      }
    },
    onMomentumBegin: () => {
      'worklet';
      userScrolledAway.value = true;
    },
    onMomentumEnd: (event) => {
      'worklet';
      const offset = event.contentOffset.y;
      if (offset < 20) {
        userScrolledAway.value = false;
      }
    },
  });

  // 🔑 键盘高度追踪 (Reanimated)
  const { height: reanimatedKeyboardHeight } = useAnimatedKeyboard();

  // 🔑 精确滚动到底部（考虑 paddingBottom + Keyboard）
  const scrollToBottom = (animated = false) => {
    // With inverted list, bottom is offset 0.
    listRef.current?.scrollToOffset({ offset: 0, animated });
  };

  const handleContentSizeChange = (_w: number, h: number) => {
    // For inverted list, logic is simpler.
    // If we are adding message to the "bottom" (index 0), inverted list handles it naturally.
    contentHeightRef.current = h;

    // Auto-scroll if new message arrives (length check)
    if (messages.length > lastMessageCount.current) {
      if (!userScrolledAway.value) {
        scrollToBottom(true);
      }
    }
    lastMessageCount.current = messages.length;
  };

  // 初始化列表显示
  const [isListReady, setIsListReady] = React.useState(false);

  React.useEffect(() => {
    if (isListReady) {
      // Standard List: Scroll to bottom (latest messages) on initial load
      setTimeout(() => {
        scrollToBottom(false);
        listOpacity.value = withTiming(1, { duration: 250 });
        isReadyToDisplay.value = true;

        // Hide loading spinner
        setTimeout(() => {
          setIsInitialLoad(false);
        }, 300);
      }, 50);
    }
  }, [isListReady]);

  // 🔑 Effect A: 仅在生成状态**改变**时触发 (Start/End)
  // 职责：初始化追踪状态，或在生成结束时清理状态
  React.useEffect(() => {
    if (loading) {
      // 🤖 AI开始生成
      // 🔑 仅在开始的那一刻，强制重置打断状态并钉在底部
      userScrolledAway.value = false;
      isAtBottom.value = true;
      scrollToBottom(false);
    } else {
      // 🏁 生成结束
      // 🔑 规则4: 生成结束，重置打断状态（为下一轮做准备）
      if (isAtBottom.value) {
        userScrolledAway.value = false;
      }
      lastReasoningState.current = false;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loading]); // ⚠️ 核心修复：移除 messages 依赖，防止每帧重置状态

  // 🔑 Effect B: 仅在内容更新时触发
  // 职责：处理思维链折叠微调，绝不重置 userScrolledAway
  React.useEffect(() => {
    if (loading && messages.length > 0) {
      const lastMessage = messages[messages.length - 1];
      const hasReasoning = !!lastMessage?.reasoning;

      // Reasoning刚结束，切换到正文
      if (lastReasoningState.current && !hasReasoning) {
        // 只有用户当前乖乖跟着底部时，才执行"微调滚动"
        if (!userScrolledAway.value) {
          scrollToBottom(false);
        }
      }
      lastReasoningState.current = hasReasoning;
    }
  }, [messages, loading]); // 依赖 messages 以检测思维链状态变化

  // 🔑 流式输出追踪：监听消息内容变化
  React.useEffect(() => {
    if (loading && messages.length > 0 && !userScrolledAway.value) {
      // 正在生成中，且用户未打断，持续追踪内容变化
      // 不再检查 isAtBottom.value，原因同上
      const timer = setTimeout(() => {
        // Use animated: false to avoid animation stack lag
        scrollToBottom(false);
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

  // 组件卸载时保存（延迟到导航动画完成后，避免在过渡期增加 JS 线程负载）
  React.useEffect(() => {
    return () => {
      const offset = scrollOffset.value;
      InteractionManager.runAfterInteractions(() => {
        saveScrollPosition(offset);
      });
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
    setTimeout(() => {
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    }, 10);
  };

  const handleDeleteMessage = (messageId: string) => {
    useChatStore.getState().deleteMessage(id, messageId);
    setTimeout(() => {
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    }, 10);
  };

  const handleExtractGraph = async (message: Message) => {
    if (!message.content.trim() || !agent) return;
    const messageContent = message.content;

    try {
      useChatStore.getState().setKGExtractionStatus(id, true);
      setTimeout(() => {
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
      }, 10);

      InteractionManager.runAfterInteractions(async () => {
        try {
          await graphExtractor.extractAndSave(messageContent, undefined, {
            sessionId: id,
            agentId: agent.id,
          });
          console.log('[ManualExtraction] Success');
          if (RNPlatform.OS === 'android') {
            emitToast('知识图谱提取成功', 'success');
          }

        } catch (e) {
          console.warn('[ManualExtraction] Failed', e);
          Alert.alert(t.common.error, 'Extraction failed: ' + (e as Error).message);
        } finally {
          useChatStore.getState().setKGExtractionStatus(id, false);
        }
      });
    } catch (e) {
      console.error(e);
      useChatStore.getState().setKGExtractionStatus(id, false);
    }
  };

  const handleManualVectorize = async (messageId: string) => {
    try {
      setTimeout(() => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      }, 10);
      await useChatStore.getState().vectorizeMessage(id, messageId);
      if (RNPlatform.OS === 'android') {
        emitToast('消息已加入向量库', 'success');
      }

    } catch (e) {
      Alert.alert('Error', 'Vectorization failed');
    }
  };

  const handleManualSummarize = async () => {
    try {
      setTimeout(() => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      }, 10);
      if (RNPlatform.OS === 'android') {
        emitToast('正在生成摘要...', 'info');
      }

      await useChatStore.getState().summarizeSession(id);
    } catch (e) {
      Alert.alert('Error', 'Summarization failed');
    }
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
    <PageLayout safeArea={false}>
      <Stack.Screen
        options={{
          headerShown: false,
        }}
      />

      <View style={{ flex: 1 }}>
        <Animated.View style={[{ flex: 1 }, listContainerStyle]}>
          {(() => {
            // ✅ FIX: Calculate latest assistant message index correctly for Inverted List
            // Inverted means messages are ordered Newest first.
            // So we look for the FIRST assistant message in the reversed array.
            const reversedMessages = useMemo(() => messages.slice().reverse(), [messages]);

            const latestAssistantIndex = useMemo(() => {
              return reversedMessages.findIndex(m => m.role === 'assistant');
            }, [reversedMessages]);

            return (
              <AnimatedFlatList
                ref={listRef}
                inverted={true} // ✅ Switch to inverted
                data={reversedMessages} // ✅ Use memoized reversed data
                renderItem={({ item, index }: { item: any; index: number }) => (
                  <ChatBubble
                    key={item.id}
                    message={item}
                    agentId={agent.id}
                    agentAvatar={agent.avatar}
                    agentColor={agentColor}
                    agentName={agent.name}
                    sessionId={id}
                    // isGenerating: Only show if it's the very latest message (index 0) AND it's assistant
                    isGenerating={(loading || session?.loopStatus === 'waiting_for_approval') && item.role === 'assistant' && index === 0}
                    onDelete={() => handleDeleteMessage(item.id)}
                    onExtractGraph={() => handleExtractGraph(item)}
                    onVectorize={() => handleManualVectorize(item.id)}
                    onSummarize={handleManualSummarize}
                    onResend={
                      item.role === 'user'
                        ? () => {
                          setTimeout(() => {
                            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                            setEditingMessage({
                              id: item.id,
                              content: item.content,
                              images: item.images,
                            });
                          }, 10);
                        }
                        : undefined
                    }
                    onRegenerate={
                      item.role === 'user'
                        ? () => {
                          setTimeout(() => {
                            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                            setEditingMessage({
                              id: item.id,
                              content: item.content,
                              images: item.images,
                            });
                          }, 10);
                        }
                        : async () => {
                          setTimeout(() => {
                            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                          }, 10);
                          await useChatStore.getState().regenerateMessage(id, item.id);
                        }
                    }
                    modelId={session?.modelId}
                    modelName={modelConfig?.name}
                    isLastAssistantMessage={index === latestAssistantIndex} // ✅ Correct logic
                    globalPendingIntervention={session?.pendingIntervention}
                  />
                )}
                keyExtractor={(item: Message) => item.id}
                contentContainerStyle={{
                  paddingTop: insets.bottom + 160, // Reversed: Top is Bottom.
                  paddingBottom: insets.top + 64 + 12, // Reversed: Bottom is Top.
                }}
                onEndReached={() => {
                  if (hasMore) {
                    loadMore();
                  }
                }}
                onEndReachedThreshold={0.5}
                // Footer is now Header (Top of screen)
                ListFooterComponent={
                  hasMore ? (
                    <View style={{ padding: 20, alignItems: 'center' }}>
                      <ActivityIndicator size="small" color={agentColor} />
                    </View>
                  ) : (
                    // Spacer for top (visually)
                    <View style={{ height: 20 }} />
                  )
                }
                // Header is now Footer (Bottom of screen)
                // We don't really need a footer since input is sticky.

                onLayout={(e: { nativeEvent: { layout: { height: number } } }) => {
                  // Simplified layout logic
                  setIsListReady(true);
                }}
                onContentSizeChange={handleContentSizeChange}
                onScroll={onScroll}
                onMomentumScrollEnd={handleScrollEnd}
                onScrollEndDrag={handleScrollEnd}
                overScrollMode="never"
                decelerationRate="normal"
                scrollEventThrottle={16}
                removeClippedSubviews={false}
              />
            );
          })()}
        </Animated.View>

        {/* 🔑 骨架屏加载态 - 提升至 listContainerStyle 动画容器之外，防止受 listOpacity 影响导致闪烁 */}
        {
          isInitialLoad && (
            <ChatSkeleton isDark={isDark} agentColor={agentColor} />
          )
        }
      </View>

      {/* Floating ChatInput - zIndex: 20 高于骨架屏(10)，低于 GlassHeader(50) */}
      <KeyboardStickyView
        offset={{ opened: 0, closed: 0 }}
        style={{
          position: 'absolute',
          bottom: 0,
          left: 0,
          right: 0,
          zIndex: 20,
        }}
      >
        <ChatInput
          isInterventionMode={session.loopStatus === 'running' || session.loopStatus === 'waiting_for_approval'}
          onSendMessage={async (content, options) => {
            if (editingMessage) {
              // ✅ 编辑模式：截断后续消息并重新生成
              const store = useChatStore.getState();
              const currentSession = store.getSession(id);
              if (currentSession) {
                // 找到被编辑消息的索引
                const msgIndex = currentSession.messages.findIndex(m => m.id === editingMessage.id);
                if (msgIndex !== -1) {
                  // 🔑 物理删除 SQLite 中该消息及之后的所有消息，防止幽灵消息
                  const editedMsg = currentSession.messages[msgIndex];
                  await store.deleteMessagesAfter(id, editedMsg.createdAt);

                  // 内存状态同步（过滤掉该索引及之后的消息，因为后面会由 generateMessage 重新发送）
                  const truncatedMessages = currentSession.messages.slice(0, msgIndex);
                  useChatStore.setState(state => ({
                    sessions: state.sessions.map(s =>
                      s.id === id
                        ? { ...s, messages: truncatedMessages }
                        : s
                    )
                  }));
                }
              }
              setEditingMessage(null);


              // 触发新的生成（会创建新的用户消息和 AI 回复）
              await store.generateMessage(id, content, {
                images: options?.images || editingMessage.images,
                files: options?.files, // ✅ Pass files
              });
            } else {
              // 正常发送
              sendMessage(content, options);
            }
            userScrolledAway.value = false;
            isAtBottom.value = true;
            // 🔑 立即触发滚动，不需等待
            scrollToBottom(true);
          }}
          onStop={stop}
          sessionId={id}
          loading={loading}
          agentColor={agent.color}
          currentModel={modelConfig?.name || currentModelId}
          activeModelId={currentModelId}
          onModelPress={() => setShowModelPicker(true)}
          tokenUsage={{
            total: session.stats?.totalTokens || 0,
            last: messages.length > 0 ? messages[messages.length - 1].tokens : undefined,
          }}
          onTokenPress={() => setShowTokenStats(true)}
          // ✅ 新增：编辑模式 props
          editingMessageId={editingMessage?.id}
          initialEditText={editingMessage?.content}
          onCancelEdit={() => setEditingMessage(null)}
          toolsEnabled={session.options?.toolsEnabled ?? true}
          onToggleTools={() => {
            const current = session.options?.toolsEnabled ?? true;
            useChatStore.getState().updateSessionOptions(id, { toolsEnabled: !current });
          }}
        />
      </KeyboardStickyView>

      {/* 浮动"回到底部"按钮 */}
      <Animated.View
        pointerEvents="box-none"
        style={[
          {
            position: 'absolute',
            bottom: insets.bottom + 110,
            right: 30,
            zIndex: 9999,
          },
          scrollButtonStyle,
        ]}
      >
        <TouchableOpacity
          activeOpacity={0.8}
          onPress={() => {
            setTimeout(() => {
              Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
              scrollToBottom(true);
            }, 10);
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
        title={t.agent.conversation.switchModel}
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
            className="w-full max-w-md bg-white dark:bg-zinc-900 rounded-3xl p-6 border border-indigo-50 dark:border-indigo-500/10"
          >
            <Typography className="text-lg font-bold text-gray-900 dark:text-white mb-4">
              {t.agent.conversation.editTitle}
            </Typography>
            <TextInput
              value={editingTitle}
              onChangeText={setEditingTitle}
              placeholder={t.agent.superAssistant.enterTitle}
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
                <Typography className="font-semibold text-white">{t.common.save}</Typography>
              </TouchableOpacity>
            </View>
          </TouchableOpacity>
        </TouchableOpacity>
      </Modal>

      {/* Glass Header */}
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
            const settingsRoute =
              id === 'super_assistant' ? '/chat/super_assistant/settings' : `/chat/${id}/settings`;
            router.push(settingsRoute);
          },
          label: t.common.settings,
        }}
      />


    </PageLayout >
  );
}
