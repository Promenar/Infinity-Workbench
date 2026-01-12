import React, { useEffect, useState, useMemo } from 'react';
import {
    Modal,
    View,
    TouchableOpacity,
    TextInput,
    Platform,
    TouchableWithoutFeedback,
    Keyboard,
    Dimensions,
} from 'react-native';
import Animated, {
    useSharedValue,
    useAnimatedStyle,
    withTiming,
    interpolate,
    Extrapolate,
} from 'react-native-reanimated';
import { BlurView } from 'expo-blur';
import { StyleSheet } from 'react-native';
import { useKeyboardHandler } from 'react-native-keyboard-controller';
import { Typography } from './Typography';
import { useTheme } from '../../theme/ThemeProvider';
import { X, Check, AlertTriangle } from 'lucide-react-native';

interface FloatingTextEditorModalProps {
    visible: boolean;
    initialContent: string;
    title: string;
    placeholder?: string;
    warningMessage?: string;
    onSave: (content: string) => void;
    onClose: () => void;
    multiline?: boolean;
}

const { height: SCREEN_HEIGHT, width: SCREEN_WIDTH } = Dimensions.get('window');
const COLLAPSED_HEIGHT = SCREEN_HEIGHT * 0.8;
const MODAL_WIDTH = SCREEN_WIDTH * 0.94;

export const FloatingTextEditorModal: React.FC<FloatingTextEditorModalProps> = ({
    visible,
    initialContent,
    title,
    placeholder,
    warningMessage,
    onSave,
    onClose,
    multiline = true,
}) => {
    const { isDark, colors } = useTheme();
    const [content, setContent] = useState(initialContent);
    const keyboardHeight = useSharedValue(0);

    // Initial value for selection to ensure it shows the top
    const [selection, setSelection] = useState<{ start: number; end: number } | undefined>(undefined);

    useEffect(() => {
        if (visible) {
            setContent(initialContent);
            // Scroll to top by setting selection
            setSelection({ start: 0, end: 0 });
            // Reset after a short delay to allow normal interaction
            const timer = setTimeout(() => setSelection(undefined), 100);
            return () => clearTimeout(timer);
        }
    }, [visible, initialContent]);

    useKeyboardHandler({
        onMove: (e) => {
            'worklet';
            keyboardHeight.value = e.height;
        },
        onEnd: (e) => {
            'worklet';
            keyboardHeight.value = e.height;
        },
    });

    const animatedStyle = useAnimatedStyle(() => {
        // We want to shrink the modal from the bottom
        // Instead of moving up, we decrease height
        const currentKeyboardHeight = Math.max(0, keyboardHeight.value);

        // Calculate how much safe space we need from bottom
        // On Android/iOS, the modal is centered.
        // Bottom edge of modal is (SCREEN_HEIGHT + COLLAPSED_HEIGHT) / 2
        // We need to move the bottom edge up if it overlaps with keyboard
        const modalBottom = (SCREEN_HEIGHT + COLLAPSED_HEIGHT) / 2;
        const keyboardTop = SCREEN_HEIGHT - currentKeyboardHeight;

        const overlap = Math.max(0, modalBottom - keyboardTop + 10); // 10px buffer

        // Remove withTiming here for 1:1 synchronization with the keyboard movement
        return {
            height: COLLAPSED_HEIGHT - overlap,
        };
    });

    if (!visible) return null;

    return (
        <Modal
            visible={visible}
            transparent={true}
            animationType="fade"
            statusBarTranslucent={true}
            onRequestClose={onClose}
        >
            <TouchableWithoutFeedback onPress={onClose}>
                <View style={{
                    flex: 1,
                    backgroundColor: 'rgba(0,0,0,0.6)',
                    justifyContent: 'flex-start', // Important: Pin to top/start to avoid "squeezing" from both ends
                    alignItems: 'center',
                }}>
                    <TouchableWithoutFeedback onPress={Keyboard.dismiss}>
                        <Animated.View
                            style={[
                                {
                                    backgroundColor: isDark ? 'rgba(15, 17, 26, 0.7)' : 'rgba(255, 255, 255, 0.8)',
                                    borderRadius: 16,
                                    padding: 24,
                                    width: MODAL_WIDTH,
                                    shadowColor: isDark ? '#6366f1' : '#000',
                                    shadowOffset: { width: 0, height: 4 },
                                    shadowOpacity: 0.1,
                                    shadowRadius: 15,
                                    elevation: isDark ? 0 : 20,
                                    overflow: 'hidden',
                                    marginTop: (SCREEN_HEIGHT - COLLAPSED_HEIGHT) / 2,
                                    borderColor: isDark ? 'rgba(99, 102, 241, 0.2)' : 'rgba(0,0,0,0.05)',
                                    borderWidth: 1,
                                },
                                animatedStyle
                            ]}
                        >
                            <BlurView
                                intensity={isDark ? 30 : 60}
                                tint={isDark ? 'dark' : 'light'}
                                style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}
                            />

                            {/* Header */}
                            <View className="flex-row items-center justify-between mb-4">
                                <TouchableOpacity
                                    onPress={onClose}
                                    className="p-2 -ml-2 rounded-full"
                                    style={{ backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : '#f3f4f6' }}
                                >
                                    <X size={20} color={isDark ? '#a1a1aa' : '#6b7280'} />
                                </TouchableOpacity>

                                <Typography
                                    className="text-lg font-bold flex-1 text-center mx-2"
                                    numberOfLines={1}
                                    style={{ color: isDark ? '#ffffff' : '#000000' }}
                                >
                                    {title}
                                </Typography>

                                <TouchableOpacity
                                    onPress={() => onSave(content)}
                                    className="p-2 -mr-2 rounded-full"
                                    style={{ backgroundColor: colors[500] }}
                                >
                                    <Check size={20} color="#ffffff" />
                                </TouchableOpacity>
                            </View>

                            {/* Warning Alert */}
                            {warningMessage && (
                                <View
                                    className={`p-3 mb-4 rounded-xl border ${isDark
                                        ? 'bg-yellow-900/20 border-yellow-800'
                                        : 'bg-yellow-50 border-yellow-200'
                                        }`}
                                >
                                    <Typography
                                        style={{
                                            color: isDark ? '#fde047' : '#854d0e',
                                            fontSize: 13,
                                            lineHeight: 20,
                                        }}
                                    >
                                        {warningMessage}
                                    </Typography>
                                </View>
                            )}

                            {/* Editor Area */}
                            <View
                                className={`flex-1 rounded-xl border ${isDark ? 'bg-zinc-900 border-zinc-700' : 'bg-gray-50 border-gray-200'
                                    }`}
                            >
                                <TextInput
                                    value={content}
                                    onChangeText={setContent}
                                    placeholder={placeholder}
                                    placeholderTextColor={isDark ? '#52525b' : '#9ca3af'}
                                    multiline={multiline}
                                    selection={selection}
                                    onSelectionChange={(e) => {
                                        if (selection) setSelection(undefined);
                                    }}
                                    style={{
                                        flex: 1,
                                        padding: 16,
                                        color: isDark ? '#ffffff' : '#000000',
                                        fontSize: 14,
                                        lineHeight: 24,
                                        textAlignVertical: 'top',
                                        fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
                                    }}
                                    autoFocus
                                />

                                {/* Character Count / Helper */}
                                <View className="p-2 items-end border-t border-gray-100 dark:border-zinc-800">
                                    <Typography variant="caption" style={{ color: isDark ? '#52525b' : '#9ca3af' }}>
                                        {content.length} chars
                                    </Typography>
                                </View>
                            </View>
                        </Animated.View>
                    </TouchableWithoutFeedback>
                </View>
            </TouchableWithoutFeedback>
        </Modal>
    );
};

