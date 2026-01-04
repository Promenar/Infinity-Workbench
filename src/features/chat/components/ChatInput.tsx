import React, { useState, useEffect } from 'react';
import {
  View,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  Platform,
  Image,
  ScrollView,
} from 'react-native';
import {
  ArrowUp,
  Plus,
  Cpu,
  Square,
  Calculator,
  Globe,
  BrainCircuit,
  X,
  Image as ImageIcon,
  Camera,
} from 'lucide-react-native';
import * as Haptics from '../../../lib/haptics';
import { useI18n } from '../../../lib/i18n';
import * as ImagePicker from 'expo-image-picker';
import * as FileSystem from 'expo-file-system/legacy';
import { BlurView } from 'expo-blur';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withRepeat,
  withTiming,
  Easing,
  cancelAnimation,
} from 'react-native-reanimated';
import { useTheme } from '../../../theme/ThemeProvider';
import { Typography, ConfirmDialog } from '../../../components/ui';
import Svg, { Circle } from 'react-native-svg';
import { TokenUsage } from '../../../types/chat';
import { formatTokenCount } from '../utils/token-counter';
import { useChatStore } from '../../../store/chat-store';
import { isForcedReasoningModel } from '../../../lib/llm/model-utils';
import { useApiStore } from '../../../store/api-store';
import { ANIMATION_DURATION } from '../../../theme/animations';
import { SummaryIndicator } from './SummaryIndicator'; // ✅ 导入摘要指示器

interface ChatInputProps {
  onSendMessage: (
    text: string,
    options?: { webSearch?: boolean; reasoning?: boolean; images?: string[] },
  ) => void;
  onStop?: () => void;
  sessionId: string;
  disabled?: boolean;
  loading?: boolean;
  agentColor?: string;
  currentModel?: string;
  onModelPress?: () => void;
  tokenUsage?: {
    total: number;
    last?: TokenUsage;
  };
  onTokenPress?: () => void;
}

const AnimatedCircle = Animated.createAnimatedComponent(Circle);

export function ChatInput({
  onSendMessage,
  onStop,
  sessionId,
  disabled,
  loading,
  agentColor = '#6366f1',
  currentModel,
  onModelPress,
  tokenUsage,
  onTokenPress,
}: ChatInputProps) {
  const { t } = useI18n();
  const { isDark } = useTheme();
  const rotation = useSharedValue(0);
  const [text, setText] = useState('');
  const [selectedImages, setSelectedImages] = useState<string[]>([]);
  const [showAttachmentMenu, setShowAttachmentMenu] = useState(false);

  // 确认弹窗状态
  const [confirmState, setConfirmState] = useState<{
    visible: boolean;
    title: string;
    message: string;
    onConfirm: () => void;
  }>({
    visible: false,
    title: '',
    message: '',
    onConfirm: () => {},
  });

  // Access store directly to persist toggles
  const session = useChatStore((state) => state.getSession(sessionId));
  const updateSessionOptions = useChatStore((state) => state.updateSessionOptions);
  const updateSessionDraft = useChatStore((state) => state.updateSessionDraft);

  const webSearchEnabled = session?.options?.webSearch ?? false;
  const reasoningEnabled = session?.options?.reasoning ?? false;

  // Load draft on mount
  useEffect(() => {
    if (session?.draft) {
      setText(session.draft);
    }
  }, [sessionId]); // Only run when sessionId changes (effectively on mount for this component instance)

  // Save draft on text change (debounced)
  useEffect(() => {
    const timer = setTimeout(() => {
      if (session && text !== session.draft) {
        // Only update if changed to avoid loop
        // Don't save empty string if it was already undefined/empty to avoid unnecessary writes
        if (text || session.draft) {
          updateSessionDraft(sessionId, text || undefined);
        }
      }
    }, 500);

    return () => clearTimeout(timer);
  }, [text, sessionId]);

  // 检测是否为强制推理模型（无法关闭推理）
  const providers = useApiStore((state) => state.providers);
  const modelId = session?.modelId;
  const currentModelConfig = modelId
    ? providers.flatMap((p) => p.models).find((m) => m.uuid === modelId || m.id === modelId)
    : undefined;
  const isModelForcedReasoning = currentModelConfig
    ? isForcedReasoningModel(currentModelConfig.id)
    : false;

  useEffect(() => {
    if (loading) {
      rotation.value = withRepeat(
        withTiming(360, { duration: ANIMATION_DURATION.ROTATION_SLOW, easing: Easing.linear }),
        -1,
      );
    } else {
      cancelAnimation(rotation);
      rotation.value = 0;
    }
  }, [loading]);

  const animatedCircleStyle = useAnimatedStyle(() => {
    return {
      transform: [{ rotate: `${rotation.value}deg` }],
    };
  });

  const handleSend = () => {
    if (loading && onStop) {
      setTimeout(() => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
        onStop();
      }, 0);
      return;
    }

    if ((!text.trim() && selectedImages.length === 0) || disabled || loading) return;

    setTimeout(() => {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      // Pass the current persistent options
      onSendMessage(text, {
        webSearch: webSearchEnabled,
        reasoning: reasoningEnabled,
        images: selectedImages.length > 0 ? selectedImages : undefined,
      });
      setText('');
      updateSessionDraft(sessionId, undefined); // Clear draft immediately
      setSelectedImages([]);
    }, 0);
  };

  const handlePickImage = async (source: 'camera' | 'library') => {
    setShowAttachmentMenu(false);
    try {
      let result;
      if (source === 'camera') {
        const { status } = await ImagePicker.requestCameraPermissionsAsync();
        if (status !== 'granted') {
          setConfirmState({
            visible: true,
            title: t.chat.cameraPermission,
            message: t.chat.cameraPermissionMessage,
            onConfirm: () => setConfirmState((prev) => ({ ...prev, visible: false })),
          });
          return;
        }
        result = await ImagePicker.launchCameraAsync({
          mediaTypes: ImagePicker.MediaTypeOptions.Images,
          allowsEditing: false,
          quality: 0.7,
        });
      } else {
        const { status } = await ImagePicker.requestMediaLibraryPermissionsAsync();
        if (status !== 'granted') {
          setConfirmState({
            visible: true,
            title: t.chat.galleryPermission,
            message: t.chat.galleryPermissionMessage,
            onConfirm: () => setConfirmState((prev) => ({ ...prev, visible: false })),
          });
          return;
        }
        result = await ImagePicker.launchImageLibraryAsync({
          mediaTypes: ImagePicker.MediaTypeOptions.Images,
          allowsMultipleSelection: true,
          quality: 0.7,
        });
      }

      if (!result.canceled) {
        const newImages: string[] = [];
        // Ensure directory exists - using makeDirectoryAsync with intermediates: true handles existence check
        const imgDir = (FileSystem.documentDirectory || '') + 'images/';
        await FileSystem.makeDirectoryAsync(imgDir, { intermediates: true });

        for (const asset of result.assets) {
          const filename = `img_${Date.now()}_${Math.random().toString(36).slice(2)}.jpg`;
          const dest = imgDir + filename;
          await FileSystem.copyAsync({
            from: asset.uri,
            to: dest,
          });
          newImages.push(dest);
        }

        if (newImages.length > 0) {
          setSelectedImages((prev) => [...prev, ...newImages]);
          Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        }
      }
    } catch (e) {
      console.error('Image picker error:', e);
      setConfirmState({
        visible: true,
        title: t.chat.imageSelectionError,
        message: t.chat.imageSelectionErrorMessage,
        onConfirm: () => setConfirmState((prev) => ({ ...prev, visible: false })),
      });
    }
  };

  const removeImage = (index: number) => {
    setSelectedImages((prev) => prev.filter((_, i) => i !== index));
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
  };

  const handleModelPress = () => {
    if (onModelPress) {
      setTimeout(() => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        onModelPress();
      }, 0);
    }
  };

  const handleTokenPress = () => {
    if (onTokenPress) {
      setTimeout(() => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        onTokenPress();
      }, 0);
    }
  };

  return (
    <View
      style={[
        styles.outerContainer,
        Platform.select({
          ios: {
            shadowColor: '#000',
            shadowOffset: { width: 0, height: 8 },
            shadowOpacity: 0.15,
            shadowRadius: 16,
          },
          android: {
            // elevation 需要背景色，但为了透出毛玻璃，使用极低透明度的背景
            backgroundColor: isDark ? 'rgba(0, 0, 0, 0.01)' : 'rgba(255, 255, 255, 0.01)',
            borderRadius: 24,
            elevation: 12,
            margin: 2,
          },
        }),
      ]}
    >
      <BlurView
        intensity={120}
        tint={isDark ? 'dark' : 'light'}
        style={[
          styles.blurContainer,
          {
            borderColor: isDark ? 'rgba(255, 255, 255, 0.12)' : 'rgba(0, 0, 0, 0.08)',
            borderWidth: 1.5,
          },
        ]}
      >
        <View
          style={[
            styles.overlay,
            {
              backgroundColor: isDark ? 'rgba(0, 0, 0, 0.3)' : 'rgba(255, 255, 255, 0.3)',
            },
          ]}
        />

        <View style={styles.topBar}>
          {currentModel && (
            <TouchableOpacity
              onPress={handleModelPress}
              activeOpacity={0.6}
              style={styles.modelBar}
            >
              <Cpu size={10} color={agentColor} />
              <Typography className="text-[9px] font-black ml-1 uppercase tracking-tight text-gray-400 dark:text-gray-500">
                {currentModel}
              </Typography>
            </TouchableOpacity>
          )}

          {tokenUsage && (
            <TouchableOpacity
              onPress={handleTokenPress}
              activeOpacity={0.6}
              style={styles.tokenBar}
            >
              <Calculator size={10} color={isDark ? '#52525b' : '#a1a1aa'} />
              <Typography className="text-[9px] font-bold ml-1 text-gray-400 dark:text-zinc-600">
                {formatTokenCount(tokenUsage.total)} tok
              </Typography>
            </TouchableOpacity>
          )}

          {/* ✅ 全局摘要指示器 */}
          <SummaryIndicator sessionId={sessionId} isDark={isDark} />

          <View style={{ flex: 1 }} />

          <View style={{ flexDirection: 'row', gap: 8, paddingRight: 12 }}>
            <TouchableOpacity
              onPress={() => {
                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                updateSessionOptions(sessionId, { webSearch: !webSearchEnabled });
              }}
              activeOpacity={0.7}
              style={[
                styles.toggleButton,
                webSearchEnabled && {
                  backgroundColor: isDark ? 'rgba(59, 130, 246, 0.2)' : 'rgba(59, 130, 246, 0.1)',
                },
              ]}
            >
              <Globe
                size={14}
                color={webSearchEnabled ? '#3b82f6' : isDark ? '#52525b' : '#a1a1aa'}
                strokeWidth={webSearchEnabled ? 2.5 : 2}
              />
            </TouchableOpacity>

            <TouchableOpacity
              onPress={() => {
                if (!isModelForcedReasoning) {
                  Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                  updateSessionOptions(sessionId, { reasoning: !reasoningEnabled });
                }
              }}
              activeOpacity={isModelForcedReasoning ? 1 : 0.7}
              style={[
                styles.toggleButton,
                (reasoningEnabled || isModelForcedReasoning) && {
                  backgroundColor: isDark ? 'rgba(139, 92, 246, 0.2)' : 'rgba(139, 92, 246, 0.1)',
                },
                isModelForcedReasoning && { opacity: 0.6 },
              ]}
            >
              <BrainCircuit
                size={14}
                color={
                  reasoningEnabled || isModelForcedReasoning
                    ? '#8b5cf6'
                    : isDark
                      ? '#52525b'
                      : '#a1a1aa'
                }
                strokeWidth={reasoningEnabled || isModelForcedReasoning ? 2.5 : 2}
              />
            </TouchableOpacity>
          </View>
        </View>

        <View style={styles.contentContainer}>
          <TouchableOpacity
            onPress={() => {
              setTimeout(() => {
                Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                setShowAttachmentMenu(!showAttachmentMenu);
              }, 10);
            }}
            style={[styles.iconButton, { backgroundColor: isDark ? '#27272a' : '#f4f4f5' }]}
          >
            <Plus size={20} color="#64748b" />
          </TouchableOpacity>

          <View style={{ flex: 1 }}>
            {selectedImages.length > 0 && (
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                style={styles.previewContainer}
                contentContainerStyle={{ paddingHorizontal: 4 }}
              >
                {selectedImages.map((uri, index) => (
                  <View key={uri} style={styles.previewItem}>
                    <Image source={{ uri }} style={styles.previewImage} />
                    <TouchableOpacity
                      style={styles.removeButton}
                      onPress={() => removeImage(index)}
                    >
                      <X size={12} color="white" />
                    </TouchableOpacity>
                  </View>
                ))}
              </ScrollView>
            )}
            <View style={styles.inputWrapper}>
              <TextInput
                style={[
                  styles.input,
                  { color: isDark ? '#fff' : '#000', backgroundColor: 'transparent' },
                ]}
                placeholder={selectedImages.length > 0 ? 'Add a caption...' : 'Message...'}
                placeholderTextColor="#94a3b8"
                underlineColorAndroid="transparent"
                multiline
                value={text}
                onChangeText={setText}
                editable={!disabled && !loading}
              />
            </View>
          </View>

          <View style={styles.buttonContainer}>
            {loading && (
              <Animated.View style={[styles.svgContainer, animatedCircleStyle]}>
                <Svg width="40" height="40" viewBox="0 0 40 40">
                  <Circle
                    cx="20"
                    cy="20"
                    r="18"
                    stroke={isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)'}
                    strokeWidth="2"
                    fill="none"
                  />
                  <Circle
                    cx="20"
                    cy="20"
                    r="18"
                    stroke={agentColor}
                    strokeWidth="2"
                    strokeDasharray="25, 100"
                    strokeLinecap="round"
                    fill="none"
                  />
                </Svg>
              </Animated.View>
            )}
            <TouchableOpacity
              onPress={handleSend}
              disabled={!loading && ((!text.trim() && selectedImages.length === 0) || disabled)}
              style={[
                styles.sendButton,
                {
                  backgroundColor:
                    text.trim() || selectedImages.length > 0 || loading ? agentColor : '#cbd5e1',
                  opacity: text.trim() || selectedImages.length > 0 || loading ? 1 : 0.4,
                },
              ]}
            >
              {loading ? (
                <Square size={14} color="white" fill="white" />
              ) : (
                <ArrowUp size={20} color="white" strokeWidth={2.5} />
              )}
            </TouchableOpacity>
          </View>
        </View>
      </BlurView>

      {/* Attachment Menu - Moved outside BlurView to prevent clipping due to overflow:hidden */}
      {showAttachmentMenu && (
        <View
          style={[
            styles.attachmentMenu,
            {
              backgroundColor: isDark ? 'rgba(39, 39, 42, 0.95)' : 'rgba(255, 255, 255, 0.95)',
              borderColor: isDark ? 'rgba(255, 255, 255, 0.1)' : 'rgba(0, 0, 0, 0.1)',
            },
          ]}
        >
          <TouchableOpacity
            style={styles.attachmentMenuItem}
            onPress={() => handlePickImage('camera')}
          >
            <Camera size={20} color={isDark ? '#e4e4e7' : '#4b5563'} />
            <Typography className="ml-3 text-sm font-medium text-gray-700 dark:text-gray-200">
              {t.chat.takePhoto}
            </Typography>
          </TouchableOpacity>
          <View style={{ height: 1, backgroundColor: isDark ? '#3f3f46' : '#e5e7eb' }} />
          <TouchableOpacity
            style={styles.attachmentMenuItem}
            onPress={() => handlePickImage('library')}
          >
            <ImageIcon size={20} color={isDark ? '#e4e4e7' : '#4b5563'} />
            <Typography className="ml-3 text-sm font-medium text-gray-700 dark:text-gray-200">
              {t.chat.selectFromGallery}
            </Typography>
          </TouchableOpacity>
        </View>
      )}

      <ConfirmDialog
        visible={confirmState.visible}
        title={confirmState.title}
        message={confirmState.message}
        onConfirm={confirmState.onConfirm}
        onCancel={() => setConfirmState((prev) => ({ ...prev, visible: false }))}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  outerContainer: {
    marginHorizontal: 20,
    marginBottom: 12,
    // 外部容器取消 iOS 阴影以防冲突，主要由内层处理
    ...Platform.select({
      ios: {
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 6 },
        shadowOpacity: 0.12,
        shadowRadius: 12,
      },
    }),
  },
  blurContainer: {
    borderRadius: 24,
    overflow: 'hidden',
    borderWidth: 1.5,
    // Android elevation
    ...Platform.select({
      android: {
        elevation: 10,
      },
    }),
  },
  overlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
  },
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingLeft: 12,
    paddingTop: 6,
    marginBottom: 2,
  },
  modelBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 10,
    backgroundColor: 'rgba(0,0,0,0.03)',
    marginRight: 6,
  },
  tokenBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 10,
    backgroundColor: 'rgba(0,0,0,0.02)',
  },
  toggleButton: {
    width: 24,
    height: 24,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  contentContainer: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    paddingHorizontal: 8,
    paddingVertical: 6,
  },
  iconButton: {
    width: 36,
    height: 36,
    borderRadius: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  inputWrapper: {
    // flex: 1, // Removed to allow parent to control layout
    minHeight: 36,
    justifyContent: 'center',
    paddingHorizontal: 8,
  },
  input: {
    fontSize: 16,
    maxHeight: 120,
    paddingVertical: 6,
  },
  previewContainer: {
    marginBottom: 8,
    height: 64,
  },
  previewItem: {
    width: 60,
    height: 60,
    marginRight: 8,
    borderRadius: 8,
    overflow: 'hidden',
    position: 'relative',
  },
  previewImage: {
    width: '100%',
    height: '100%',
    borderRadius: 8,
  },
  removeButton: {
    position: 'absolute',
    top: 2,
    right: 2,
    backgroundColor: 'rgba(0,0,0,0.6)',
    borderRadius: 10,
    width: 18,
    height: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonContainer: {
    width: 40,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 2,
  },
  svgContainer: {
    position: 'absolute',
    top: 0,
    left: 0,
    width: 40,
    height: 40,
  },
  sendButton: {
    width: 32,
    height: 32,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 1,
  },
  attachmentMenu: {
    position: 'absolute',
    bottom: 60,
    left: 12,
    width: 160,
    borderRadius: 16,
    borderWidth: 1,
    overflow: 'hidden',
    // Shadow
    ...Platform.select({
      ios: {
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 4 },
        shadowOpacity: 0.15,
        shadowRadius: 12,
      },
      android: {
        elevation: 8,
      },
    }),
  },
  attachmentMenuItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    paddingHorizontal: 16,
  },
});
