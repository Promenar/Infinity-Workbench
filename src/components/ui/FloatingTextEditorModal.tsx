import React, { useEffect, useState } from 'react';
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
import { KeyboardAvoidingView } from 'react-native-keyboard-controller';
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
    const { isDark } = useTheme();
    const [content, setContent] = useState(initialContent);
    const screenHeight = Dimensions.get('window').height;

    useEffect(() => {
        if (visible) {
            setContent(initialContent);
        }
    }, [visible, initialContent]);

    if (!visible) return null;

    return (
        <Modal
            visible={visible}
            transparent={true}
            animationType="fade"
            statusBarTranslucent
            onRequestClose={onClose}
        >
            <TouchableWithoutFeedback onPress={onClose}>
                <View style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.5)', justifyContent: 'flex-end' }}>
                    <KeyboardAvoidingView
                        behavior={Platform.OS === 'ios' ? 'padding' : 'padding'}
                        style={{ width: '100%' }}
                    >
                        <TouchableWithoutFeedback onPress={Keyboard.dismiss}>
                            <View
                                style={{
                                    backgroundColor: isDark ? '#18181b' : '#ffffff',
                                    borderTopLeftRadius: 24,
                                    borderTopRightRadius: 24,
                                    padding: 24,
                                    paddingBottom: Platform.OS === 'ios' ? 40 : 24,
                                    height: screenHeight * 0.55, // Fixed height based on full screen
                                    shadowColor: '#000',
                                    shadowOffset: { width: 0, height: -2 },
                                    shadowOpacity: 0.1,
                                    shadowRadius: 10,
                                    elevation: 10,
                                }}
                            >
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
                                        className="p-2 -mr-2 rounded-full bg-indigo-500"
                                    >
                                        <Check size={20} color="#ffffff" />
                                    </TouchableOpacity>
                                </View>

                                {/* Warning Alert */}
                                {warningMessage && (
                                    <View
                                        className={`flex-row items-start p-3 mb-4 rounded-xl border ${isDark
                                            ? 'bg-yellow-900/20 border-yellow-800'
                                            : 'bg-yellow-50 border-yellow-200'
                                            }`}
                                    >
                                        <AlertTriangle
                                            size={16}
                                            color="#eab308"
                                            style={{ marginTop: 2, marginRight: 8 }}
                                        />
                                        <Typography
                                            style={{
                                                flex: 1,
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
                                        style={{
                                            flex: 1,
                                            padding: 16,
                                            color: isDark ? '#ffffff' : '#000000',
                                            fontSize: 15,
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
                            </View>
                        </TouchableWithoutFeedback>
                    </KeyboardAvoidingView>
                </View>
            </TouchableWithoutFeedback>
        </Modal>
    );
};
