import React, { useEffect, useState, useMemo, useCallback } from 'react';
import {
    View,
    TextInput,
    Text,
    Platform,
    StyleSheet,
    ActivityIndicator,
    ScrollView,
    Dimensions,
    TouchableOpacity,
    KeyboardAvoidingView,
    Alert,
} from 'react-native';
import { useLocalSearchParams, useRouter, Stack } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import SyntaxHighlighter from 'react-native-syntax-highlighter';
// @ts-ignore
import { docco, atomOneDark } from 'react-syntax-highlighter/dist/cjs/styles/hljs';

import { GlassHeader } from '../../src/components/ui/GlassHeader';
import { PageLayout } from '../../src/components/ui/PageLayout';
import { useTheme } from '../../src/theme/ThemeProvider';
import { useI18n } from '../../src/lib/i18n';
import { Check, ChevronLeft, Eye, Edit2, AlertTriangle } from 'lucide-react-native';
import { useRagStore } from '../../src/store/rag-store';
import { useToast } from '../../src/components/ui/Toast';
import { Typography } from '../../src/components/ui/Typography';

const LARGE_FILE_THRESHOLD = 100000; // 100KB
const VERY_LARGE_FILE_THRESHOLD = 500000; // 500KB

export default function DocumentEditorScreen() {
    const { isDark, colors } = useTheme();
    const insets = useSafeAreaInsets();
    const router = useRouter();
    const params = useLocalSearchParams<{ docId: string; title: string, initialContent?: string }>();
    const { t } = useI18n();
    const { showToast } = useToast();

    const { getDocumentContent, updateDocumentContent } = useRagStore.getState();

    const [content, setContent] = useState('');
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [isPreviewMode, setIsPreviewMode] = useState(false);
    const [fileSize, setFileSize] = useState(0);
    const [showSizeWarning, setShowSizeWarning] = useState(false);

    const docTitle = params.title || 'Untitled';
    const docId = params.docId;

    const isLargeFile = fileSize > LARGE_FILE_THRESHOLD;
    const isVeryLargeFile = fileSize > VERY_LARGE_FILE_THRESHOLD;

    useEffect(() => {
        const load = async () => {
            if (!docId) return;
            setLoading(true);
            try {
                const text = await getDocumentContent(docId);
                const size = text?.length || 0;
                setFileSize(size);
                setContent(text || '');

                if (size > LARGE_FILE_THRESHOLD) {
                    setShowSizeWarning(true);
                }
            } catch (e) {
                console.error(e);
                showToast(t.common.error, 'error');
            } finally {
                setLoading(false);
            }
        };
        load();
    }, [docId]);

    const language = useMemo(() => {
        const ext = docTitle.split('.').pop()?.toLowerCase();
        switch (ext) {
            case 'js':
            case 'jsx':
            case 'ts':
            case 'tsx': return 'javascript';
            case 'json': return 'json';
            case 'md': return 'markdown';
            case 'py': return 'python';
            case 'java': return 'java';
            case 'html': return 'xml';
            case 'css': return 'css';
            case 'sh': return 'bash';
            default: return 'text';
        }
    }, [docTitle]);

    const handleSave = async () => {
        if (!docId) return;
        setSaving(true);
        try {
            await updateDocumentContent(docId, content);
            showToast(t.common.save + t.common.success, 'success');
            router.back();
        } catch (e) {
            showToast(t.common.save + t.common.error, 'error');
        } finally {
            setSaving(false);
        }
    };

    const handleTogglePreview = useCallback(() => {
        if (isVeryLargeFile) {
            showToast('文件过大，无法使用语法高亮预览', 'error');
            return;
        }
        setIsPreviewMode(!isPreviewMode);
    }, [isPreviewMode, isVeryLargeFile]);

    const formatFileSize = (bytes: number): string => {
        if (bytes < 1024) return `${bytes} B`;
        if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
        return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
    };

    const syntaxTheme = isDark ? atomOneDark : docco;

    const renderContent = () => {
        if (isPreviewMode && !isLargeFile) {
            return (
                <ScrollView horizontal contentContainerStyle={{ flexGrow: 1 }}>
                    <SyntaxHighlighter
                        language={language}
                        style={syntaxTheme}
                        highlighter={'hljs'}
                        PreTag={View}
                        CodeTag={Text}
                        customStyle={{
                            backgroundColor: 'transparent',
                            padding: 0,
                            margin: 0,
                        }}
                        fontSize={14}
                        fontFamily={Platform.OS === 'ios' ? 'Menlo' : 'monospace'}
                    >
                        {content}
                    </SyntaxHighlighter>
                </ScrollView>
            );
        }

        return (
            <TextInput
                value={content}
                onChangeText={setContent}
                multiline
                textAlignVertical="top"
                autoCapitalize="none"
                autoCorrect={false}
                style={{
                    fontFamily: language !== 'text' ? (Platform.OS === 'ios' ? 'Menlo' : 'monospace') : undefined,
                    fontSize: language !== 'text' ? 14 : 16,
                    lineHeight: language !== 'text' ? 22 : 24,
                    color: isDark ? (language !== 'text' ? '#d4d4d4' : '#ffffff') : (language !== 'text' ? '#1f2937' : '#000000'),
                    minHeight: 500,
                }}
                placeholder="输入内容..."
                placeholderTextColor="#9ca3af"
            />
        );
    };

    return (
        <PageLayout safeArea={false}>
            <Stack.Screen options={{ headerShown: false }} />

            <GlassHeader
                title={docTitle}
                subtitle={loading ? '加载中...' : `${formatFileSize(fileSize)}${isLargeFile ? ' (大文件)' : ''}`}
                leftAction={{
                    icon: <ChevronLeft size={24} color={isDark ? '#fff' : '#000'} />,
                    onPress: () => router.back()
                }}
                rightAction={{
                    icon: saving ? <ActivityIndicator size="small" color={isDark ? '#fff' : '#000'} /> : (
                        <Check size={24} color={isDark ? '#fff' : '#000'} />
                    ),
                    onPress: handleSave
                }}
                headerRight={
                    language !== 'text' ? (
                        <TouchableOpacity
                            onPress={handleTogglePreview}
                            style={{
                                padding: 8,
                                marginRight: 4,
                                backgroundColor: isVeryLargeFile 
                                    ? 'rgba(239, 68, 68, 0.2)' 
                                    : isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)',
                                borderRadius: 8,
                                opacity: isVeryLargeFile ? 0.6 : 1,
                            }}
                        >
                            {isPreviewMode ? (
                                <Edit2 size={20} color={isDark ? '#fff' : '#000'} />
                            ) : (
                                <Eye size={20} color={isVeryLargeFile ? '#ef4444' : isDark ? '#fff' : '#000'} />
                            )}
                        </TouchableOpacity>
                    ) : undefined
                }
            />

            {loading ? (
                <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
                    <ActivityIndicator size="large" color={colors[500]} />
                </View>
            ) : (
                <KeyboardAvoidingView
                    behavior={Platform.OS === 'ios' ? 'padding' : undefined}
                    style={{ flex: 1 }}
                >
                    {/* 大文件警告横幅 */}
                    {showSizeWarning && (
                        <View style={{
                            backgroundColor: isDark ? 'rgba(251, 191, 36, 0.15)' : '#FEF3C7',
                            paddingHorizontal: 16,
                            paddingVertical: 10,
                            flexDirection: 'row',
                            alignItems: 'center',
                            gap: 8,
                            marginTop: 60 + insets.top,
                        }}>
                            <AlertTriangle size={18} color="#F59E0B" />
                            <Typography className="flex-1 text-xs" style={{ color: isDark ? '#FCD34D' : '#92400E' }}>
                                {isVeryLargeFile 
                                    ? `文件过大 (${formatFileSize(fileSize)})，已禁用语法高亮。编辑大文件可能影响性能。`
                                    : `大文件 (${formatFileSize(fileSize)})，语法高亮可能影响性能。`
                                }
                            </Typography>
                            <TouchableOpacity onPress={() => setShowSizeWarning(false)}>
                                <Typography className="text-xs font-bold" style={{ color: colors[500] }}>
                                    知道了
                                </Typography>
                            </TouchableOpacity>
                        </View>
                    )}

                    <ScrollView
                        style={{ flex: 1 }}
                        contentContainerStyle={{
                            paddingTop: showSizeWarning ? 16 : (70 + insets.top),
                            paddingBottom: insets.bottom + 40,
                            paddingHorizontal: 0,
                            flexGrow: 1,
                        }}
                        keyboardDismissMode="interactive"
                        keyboardShouldPersistTaps="handled"
                    >
                        <View style={{ paddingHorizontal: 16, flex: 1 }}>
                            {renderContent()}
                        </View>
                    </ScrollView>
                </KeyboardAvoidingView>
            )}
        </PageLayout>
    );
}
