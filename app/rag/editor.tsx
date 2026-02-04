import React, { useEffect, useState, useMemo } from 'react';
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
import { Check, ChevronLeft, Eye, Edit2 } from 'lucide-react-native';
import { useRagStore } from '../../src/store/rag-store';
import { useToast } from '../../src/components/ui/Toast';

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

    const docTitle = params.title || 'Untitled';
    const docId = params.docId;

    // Load content
    useEffect(() => {
        const load = async () => {
            if (!docId) return;
            // If passed via params (small files), use it. Else fetch.
            // Params limitation: Avoid passing huge strings. Prefer fetching.
            setLoading(true);
            try {
                const text = await getDocumentContent(docId);
                setContent(text || '');
            } catch (e) {
                console.error(e);
                showToast(t.common.error, 'error');
            } finally {
                setLoading(false);
            }
        };
        load();
    }, [docId]);

    // Detect language
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

    // Editor Styles
    const syntaxTheme = isDark ? atomOneDark : docco;

    return (
        <PageLayout safeArea={false}>
            <Stack.Screen options={{ headerShown: false }} />

            <GlassHeader
                title={docTitle}
                subtitle={loading ? '加载中...' : (isPreviewMode ? '预览模式' : '编辑文档')}
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
                            onPress={() => setIsPreviewMode(!isPreviewMode)}
                            style={{
                                padding: 8,
                                marginRight: 4,
                                backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)',
                                borderRadius: 8,
                            }}
                        >
                            {isPreviewMode ? (
                                <Edit2 size={20} color={isDark ? '#fff' : '#000'} />
                            ) : (
                                <Eye size={20} color={isDark ? '#fff' : '#000'} />
                            )}
                        </TouchableOpacity>
                    ) : undefined
                }
            />

            {/* Content Area */}
            {loading ? (
                <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
                    <ActivityIndicator size="large" color={colors[500]} />
                </View>
            ) : (
                <KeyboardAvoidingView
                    behavior={Platform.OS === 'ios' ? 'padding' : undefined}
                    style={{ flex: 1 }}
                >
                    <ScrollView
                        style={{ flex: 1 }}
                        contentContainerStyle={{
                            paddingTop: 70 + insets.top, // Standard offset
                            paddingBottom: insets.bottom + 40,
                            paddingHorizontal: 0,
                            flexGrow: 1,
                        }}
                        keyboardDismissMode="interactive"
                        keyboardShouldPersistTaps="handled"
                    >
                        <View style={{ paddingHorizontal: 16, flex: 1 }}>
                            {isPreviewMode ? (
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
                            ) : (
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
                            )}
                        </View>
                    </ScrollView>
                </KeyboardAvoidingView>
            )}
        </PageLayout>
    );
}
