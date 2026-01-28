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
    BackHandler,
    Text,
} from 'react-native';
import Animated, {
    useSharedValue,
    useAnimatedStyle,
} from 'react-native-reanimated';
import { BlurView } from 'expo-blur';
import { useKeyboardHandler } from 'react-native-keyboard-controller';
import { Typography } from './Typography';
import { useTheme } from '../../theme/ThemeProvider';
import { X, Check } from 'lucide-react-native';

interface FloatingCodeEditorModalProps {
    visible: boolean;
    initialContent: string;
    title: string;
    placeholder?: string;
    warningMessage?: string;
    onSave: (content: string) => void;
    onClose: () => void;
}

const { height: SCREEN_HEIGHT, width: SCREEN_WIDTH } = Dimensions.get('window');
const COLLAPSED_HEIGHT = SCREEN_HEIGHT * 0.85;
const MODAL_WIDTH = SCREEN_WIDTH * 0.96;

export const FloatingCodeEditorModal: React.FC<FloatingCodeEditorModalProps> = ({
    visible,
    initialContent,
    title,
    placeholder,
    warningMessage,
    onSave,
    onClose,
}) => {
    const { isDark, colors } = useTheme();
    const [content, setContent] = useState(initialContent);
    const keyboardHeight = useSharedValue(0);

    const [selection, setSelection] = useState<{ start: number; end: number } | undefined>(undefined);

    useEffect(() => {
        if (visible) {
            setContent(initialContent);
            setSelection({ start: 0, end: 0 });
            const timer = setTimeout(() => setSelection(undefined), 100);
            return () => clearTimeout(timer);
        }
    }, [visible, initialContent]);

    useEffect(() => {
        const onBackPress = () => {
            if (visible) {
                onClose();
                return true;
            }
            return false;
        };

        const subscription = BackHandler.addEventListener('hardwareBackPress', onBackPress);
        return () => subscription.remove();
    }, [visible, onClose]);

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
        const currentKeyboardHeight = Math.max(0, keyboardHeight.value);
        const modalBottom = (SCREEN_HEIGHT + COLLAPSED_HEIGHT) / 2;
        const keyboardTop = SCREEN_HEIGHT - currentKeyboardHeight;
        const overlap = Math.max(0, modalBottom - keyboardTop + 10);

        return {
            height: COLLAPSED_HEIGHT - overlap,
        };
    });

    // 🔢 生成行号
    const lineNumbers = useMemo(() => {
        const lines = content.split('\n').length;
        return Array.from({ length: Math.max(1, lines) }, (_, i) => i + 1).join('\n');
    }, [content]);

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
                    justifyContent: 'flex-start',
                    alignItems: 'center',
                }}>
                    <TouchableWithoutFeedback onPress={Keyboard.dismiss}>
                        <Animated.View
                            style={[
                                {
                                    backgroundColor: isDark ? 'rgba(15, 17, 26, 0.85)' : 'rgba(255, 255, 255, 0.9)',
                                    borderRadius: 16,
                                    padding: 20,
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
                                intensity={isDark ? 50 : 80}
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

                            {/* Code Editor Area */}
                            <View
                                className={`flex-1 flex-row rounded-xl border overflow-hidden ${isDark ? 'bg-[#1e1e1e] border-zinc-700' : 'bg-gray-50 border-gray-200'
                                    }`}
                            >
                                {/* Line Numbers Column */}
                                <View
                                    style={{
                                        width: 40,
                                        backgroundColor: isDark ? '#252526' : '#e5e7eb',
                                        paddingVertical: 16,
                                        alignItems: 'flex-end',
                                        paddingRight: 8,
                                        borderRightWidth: 1,
                                        borderColor: isDark ? '#333' : '#d1d5db'
                                    }}
                                >
                                    <Text style={{
                                        fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
                                        fontSize: 13,
                                        lineHeight: 22, // Match TextInput lineHeight
                                        color: isDark ? '#6e7681' : '#9ca3af',
                                        textAlign: 'right'
                                    }}>
                                        {lineNumbers}
                                    </Text>
                                </View>

                                {/* Code Input */}
                                <TextInput
                                    value={content}
                                    onChangeText={setContent}
                                    placeholder={placeholder}
                                    placeholderTextColor={isDark ? '#52525b' : '#9ca3af'}
                                    multiline={true}
                                    selection={selection}
                                    onSelectionChange={(e) => {
                                        if (selection) setSelection(undefined);
                                    }}
                                    style={{
                                        flex: 1,
                                        padding: 16,
                                        paddingTop: 16, // Match Line Number padding
                                        color: isDark ? '#d4d4d4' : '#1f2937',
                                        fontSize: 13,
                                        lineHeight: 22,
                                        textAlignVertical: 'top',
                                        fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
                                    }}
                                    autoCapitalize="none"
                                    autoCorrect={false}
                                />
                            </View>

                            {/* Footer Info */}
                            <View className="p-2 items-end">
                                <Typography variant="caption" style={{ color: isDark ? '#52525b' : '#9ca3af' }}>
                                    JS / JSON • {content.length} chars
                                </Typography>
                            </View>

                        </Animated.View>
                    </TouchableWithoutFeedback>
                </View>
            </TouchableWithoutFeedback>
        </Modal>
    );
};
