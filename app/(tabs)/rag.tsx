import React, { useState, useCallback, useEffect, memo } from 'react';
import { View, TouchableOpacity, Text, BackHandler } from 'react-native';
import { PageLayout, Typography, ContextMenu, useToast } from '../../src/components/ui';
import { FileText, Plus, Folder, MoreVertical, Search, Edit2, Trash2, Share, X, Check, FolderInput, ArrowLeft } from 'lucide-react-native';
import { Stack, useRouter } from 'expo-router';
import { FlashList } from '@shopify/flash-list';
import * as Haptics from 'expo-haptics';
import { MOCK_DOCS, DocItem } from '../../src/data/mock';
import Animated, {
    FadeIn, FadeOut, SlideInDown, SlideOutDown,
    FadeInLeft, FadeOutLeft, LinearTransition,
    ZoomIn, ZoomOut, useAnimatedStyle, useSharedValue, withSpring, withTiming, Easing
} from 'react-native-reanimated';
import { clsx } from 'clsx';
import { useTheme } from '../../src/theme/ThemeProvider';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { RagDocItem } from '../../src/components/rag/RagDocItem';
import { RagFolderCard } from '../../src/components/rag/RagFolderCard';
import { useI18n } from '../../src/lib/i18n';


export default function RagScreen() {
    const router = useRouter();
    const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
    const [isSelectionMode, setIsSelectionMode] = useState(false);
    const { showToast } = useToast();
    const { isDark } = useTheme();
    const insets = useSafeAreaInsets();
    const { t } = useI18n();

    // 全局同步动画动力源：统一驱动所有列表项的平移动画
    const selectionProgress = useSharedValue(0);

    useEffect(() => {
        selectionProgress.value = withTiming(isSelectionMode ? 1 : 0, {
            duration: 300,
            easing: Easing.bezier(0.25, 0.1, 0.25, 1)
        });
    }, [isSelectionMode]);

    const toggleSelection = useCallback((id: string) => {
        setSelectedIds(prev => {
            const next = new Set(prev);
            if (next.has(id)) {
                next.delete(id);
                if (next.size === 0) setIsSelectionMode(false);
            } else {
                next.add(id);
            }
            return next;
        });
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
    }, []);

    const startSelectionMode = useCallback((id: string) => {
        setIsSelectionMode(true);
        setSelectedIds(new Set([id]));
        Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    }, []);

    const cancelSelection = useCallback(() => {
        setIsSelectionMode(false);
        setSelectedIds(new Set());
    }, []);

    useEffect(() => {
        const backAction = () => {
            if (isSelectionMode) {
                cancelSelection();
                return true;
            }
            return false;
        };
        const backHandler = BackHandler.addEventListener('hardwareBackPress', backAction);
        return () => backHandler.remove();
    }, [isSelectionMode, cancelSelection]);

    const handleAction = (action: string) => {
        showToast(`${action} ${selectedIds.size} items`, 'success');
        cancelSelection();
    };

    // 渲染函数：Header
    const renderHeader = () => (
        <View className="mb-4 overflow-hidden">
            <View className="pt-16 px-6 pb-2 bg-white dark:bg-black">
                <View className="flex-col">
                    {/* Top Row: Title or Selection Count - Fixed Height h-14 */}
                    <View className="flex-row justify-between items-center mb-6 h-14">
                        {isSelectionMode ? (
                            <Animated.View
                                entering={FadeIn.duration(300)}
                                exiting={FadeOut.duration(200)}
                                className="flex-row items-center flex-1"
                            >
                                <TouchableOpacity
                                    onPress={cancelSelection}
                                    className="w-10 h-10 items-center justify-center rounded-2xl bg-gray-50 dark:bg-zinc-900 mr-4"
                                >
                                    <X size={20} color="#64748b" />
                                </TouchableOpacity>
                                <View>
                                    <Typography variant="h2" className="text-gray-900 dark:text-white font-black leading-tight">{selectedIds.size} {t.library.selected}</Typography>
                                    <Typography variant="caption" className="text-indigo-500 font-bold uppercase text-[10px] tracking-widest leading-none">{t.library.managementMode}</Typography>
                                </View>
                            </Animated.View>
                        ) : (
                            <Animated.View
                                entering={FadeIn.duration(300)}
                                exiting={FadeOut.duration(200)}
                                className="flex-row justify-between items-center flex-1"
                            >
                                <View>
                                    <Typography variant="h1" className="text-[32px] font-black text-gray-900 dark:text-white tracking-tight leading-none">{t.library.title}</Typography>
                                    <Typography variant="body" className="text-gray-400 font-bold uppercase text-[11px] tracking-widest mt-1 leading-none">{t.library.description}</Typography>
                                </View>
                                <TouchableOpacity
                                    onPress={() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium)}
                                    className="w-12 h-12 bg-gray-50 dark:bg-zinc-900 border border-gray-100 dark:border-zinc-800 rounded-2xl items-center justify-center shadow-sm"
                                >
                                    <Plus size={24} color="#6366f1" strokeWidth={2} />
                                </TouchableOpacity>
                            </Animated.View>
                        )}
                    </View>

                    {/* Bottom Row: Search Bar or Actions - Fixed Height h-12 */}
                    <View className="h-12">
                        {isSelectionMode ? (
                            <Animated.View
                                entering={FadeIn.duration(300)}
                                exiting={FadeOut.duration(200)}
                                className="flex-row items-center justify-between h-full"
                            >
                                <TouchableOpacity
                                    onPress={() => handleAction('Moved')}
                                    className="flex-1 h-full bg-gray-50 dark:bg-zinc-900 rounded-2xl flex-row items-center justify-center mr-2 border border-gray-100 dark:border-zinc-800"
                                >
                                    <FolderInput size={18} color="#6366f1" strokeWidth={2} />
                                    <Typography className="ml-2 text-indigo-500 font-bold text-[12px]">{t.library.move}</Typography>
                                </TouchableOpacity>

                                <TouchableOpacity
                                    onPress={() => handleAction('Shared')}
                                    className="flex-1 h-full bg-gray-50 dark:bg-zinc-900 rounded-2xl flex-row items-center justify-center mr-2 border border-gray-100 dark:border-zinc-800"
                                >
                                    <Share size={18} color="#6366f1" strokeWidth={2} />
                                    <Typography className="ml-2 text-indigo-500 font-bold text-[12px]">{t.library.share}</Typography>
                                </TouchableOpacity>

                                <TouchableOpacity
                                    onPress={() => handleAction('Deleted')}
                                    className="flex-1 h-full bg-red-50 dark:bg-red-500/10 rounded-2xl flex-row items-center justify-center border border-red-100 dark:border-red-900/30"
                                >
                                    <Trash2 size={18} color="#ef4444" strokeWidth={2} />
                                    <Typography className="ml-2 text-red-500 font-bold text-[12px]">{t.library.delete}</Typography>
                                </TouchableOpacity>
                            </Animated.View>
                        ) : (
                            <Animated.View
                                entering={FadeIn.duration(300)}
                                exiting={FadeOut.duration(200)}
                                className="h-full bg-gray-50 dark:bg-zinc-900 border border-gray-100 dark:border-zinc-800 rounded-2xl flex-row items-center px-4"
                            >
                                <Search size={18} color="#94a3b8" strokeWidth={2} />
                                <Typography style={{ color: '#94a3b8', fontWeight: 'bold', marginLeft: 12, fontSize: 16 }}>{t.library.searchPlaceholder}</Typography>
                            </Animated.View>
                        )}
                    </View>
                </View>
            </View>

            <View className="px-6 mt-1">
                <Typography variant="sectionHeader" className="mb-3 text-gray-400 font-bold text-[11px] uppercase tracking-wider">{t.library.collections}</Typography>
                <View className="flex-row">
                    <RagFolderCard
                        label={t.library.work}
                        count={`12 ${t.library.itemsCount}`}
                        iconColor="#6366f1"
                        isSelected={selectedIds.has('folder_work')}
                        isSelectionMode={isSelectionMode}
                        onPress={() => isSelectionMode ? toggleSelection('folder_work') : router.push('/rag/folder_work')}
                        onLongPress={() => !isSelectionMode && startSelectionMode('folder_work')}
                    />
                    <View style={{ width: 12 }} />
                    <RagFolderCard
                        label={t.library.private}
                        count={`4 ${t.library.itemsCount}`}
                        iconColor="#818cf8"
                        isSelected={selectedIds.has('folder_private')}
                        isSelectionMode={isSelectionMode}
                        onPress={() => isSelectionMode ? toggleSelection('folder_private') : router.push('/rag/folder_private')}
                        onLongPress={() => !isSelectionMode && startSelectionMode('folder_private')}
                    />
                </View>
            </View>

            <View className="px-6 mt-8 mb-3">
                <Typography variant="sectionHeader" className="text-gray-400 font-bold text-[11px] uppercase tracking-wider">{t.library.documentsTitle}</Typography>
            </View>
        </View>
    );

    return (
        <PageLayout className="bg-white dark:bg-black" safeArea={false}>
            <Stack.Screen options={{ headerShown: false }} />
            <FlashList<DocItem>
                data={MOCK_DOCS}
                renderItem={({ item }) => (
                    <RagDocItem
                        item={item}
                        isSelected={selectedIds.has(item.id)}
                        isSelectionMode={isSelectionMode}
                        selectionProgress={selectionProgress} // 传递全局进度共享值
                        onPress={() => isSelectionMode ? toggleSelection(item.id) : Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium)}
                        onLongPress={() => !isSelectionMode && startSelectionMode(item.id)}
                        showToast={showToast}
                    />
                )}
                keyExtractor={(item: any) => item.id}
                ListHeaderComponent={renderHeader}
                estimatedItemSize={78}
                contentContainerStyle={{ paddingBottom: 100 }}
                {...({} as any)}
            />

        </PageLayout>
    );
}
