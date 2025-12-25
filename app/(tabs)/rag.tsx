import React, { useState, useCallback, useEffect } from 'react';
import { View, TouchableOpacity, Text, BackHandler } from 'react-native';
import { PageLayout, Typography, ContextMenu, useToast } from '../../src/components/ui';
import { FileText, Plus, Folder, MoreVertical, Search, Edit2, Trash2, Share, X, Check, FolderInput } from 'lucide-react-native';
import { Stack } from 'expo-router';
import { FlashList } from '@shopify/flash-list';
import * as Haptics from 'expo-haptics';
import { MOCK_DOCS } from '../../src/data/mock';
import Animated, { FadeIn, FadeOut, SlideInDown, SlideOutDown, FadeInLeft, FadeOutLeft, LinearTransition } from 'react-native-reanimated';
import { clsx } from 'clsx';
import { useTheme } from '../../src/theme/ThemeProvider';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

/**
 * 文件夹卡片组件
 */
const RagFolderCard = ({
    label,
    count,
    iconColor,
    isSelected,
    isSelectionMode,
    onPress,
    onLongPress
}: {
    label: string,
    count: string,
    iconColor: string,
    isSelected: boolean,
    isSelectionMode: boolean,
    onPress: () => void,
    onLongPress: () => void
}) => {
    return (
        <TouchableOpacity
            activeOpacity={0.7}
            onPress={onPress}
            onLongPress={onLongPress}
            delayLongPress={200}
            className={clsx(
                "p-5 rounded-[32px] flex-1 h-44 justify-between border shadow-sm relative overflow-hidden",
                isSelected
                    ? "bg-indigo-50/80 dark:bg-indigo-900/40 border-indigo-200 dark:border-indigo-800"
                    : "bg-gray-50 dark:bg-zinc-900 border-gray-100 dark:border-zinc-800"
            )}
        >
            <View className="flex-row justify-between items-start">
                <View className={clsx(
                    "w-12 h-12 rounded-2xl items-center justify-center border",
                    isSelected
                        ? "bg-white dark:bg-indigo-900 border-indigo-100"
                        : "bg-white dark:bg-zinc-800 border-gray-100 dark:border-zinc-700"
                )}>
                    <Folder size={24} color={isSelected ? "#6366f1" : iconColor} strokeWidth={1.5} />
                </View>

                {isSelectionMode ? (
                    <Animated.View entering={FadeIn} className={clsx(
                        "w-6 h-6 rounded-full items-center justify-center border",
                        isSelected ? "bg-indigo-500 border-indigo-500" : "bg-white dark:bg-zinc-800 border-gray-200 dark:border-zinc-700"
                    )}>
                        {isSelected && <Check size={14} color="white" strokeWidth={3} />}
                    </Animated.View>
                ) : (
                    <View className="p-1">
                        <MoreVertical size={18} color="#94a3b8" />
                    </View>
                )}
            </View>

            <View>
                <Typography variant="h3" className={clsx(
                    "font-black text-[19px] mb-0.5",
                    isSelected ? "text-indigo-900 dark:text-indigo-100" : "text-gray-900 dark:text-white"
                )}>{label}</Typography>
                <Typography variant="caption" className={clsx(
                    "font-bold uppercase text-[10px] tracking-widest",
                    isSelected ? "text-indigo-400" : "text-gray-400"
                )}>{count}</Typography>
            </View>
        </TouchableOpacity>
    );
};

export default function RagScreen() {
    const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
    const [isSelectionMode, setIsSelectionMode] = useState(false);
    const { showToast } = useToast();
    const { isDark } = useTheme();
    const insets = useSafeAreaInsets();

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
        <Animated.View layout={LinearTransition.duration(400)} className="mb-6 overflow-hidden">
            <View className="pt-16 px-6 pb-4 bg-white dark:bg-black">
                {isSelectionMode ? (
                    <Animated.View
                        layout={LinearTransition}
                        entering={FadeIn.duration(400)}
                        exiting={FadeOut.duration(100)}
                        className="flex-row justify-between items-center h-12 mb-6"
                    >
                        <View className="flex-row items-center">
                            <TouchableOpacity
                                onPress={cancelSelection}
                                className="w-10 h-10 items-center justify-center rounded-2xl bg-gray-50 dark:bg-zinc-900 mr-4"
                            >
                                <X size={20} color="#64748b" />
                            </TouchableOpacity>
                            <View>
                                <Typography variant="h2" className="text-gray-900 dark:text-white font-black">{selectedIds.size} Selected</Typography>
                                <Typography variant="caption" className="text-indigo-500 font-bold uppercase text-[10px] tracking-widest leading-none">Management Mode</Typography>
                            </View>
                        </View>
                        <TouchableOpacity onPress={() => handleAction('Shared')}>
                            <Share size={22} color="#6366f1" strokeWidth={2} />
                        </TouchableOpacity>
                    </Animated.View>
                ) : (
                    <Animated.View
                        layout={LinearTransition}
                        entering={FadeIn.duration(400)}
                        exiting={FadeOut.duration(100)}
                    >
                        <View className="flex-row justify-between items-center mb-6">
                            <View>
                                <Typography variant="h1" className="text-[32px] font-black text-gray-900 dark:text-white tracking-tight">Library</Typography>
                                <Typography variant="body" className="text-gray-400 font-bold uppercase text-[11px] tracking-widest mt-1">Knowledge Base</Typography>
                            </View>
                            <TouchableOpacity
                                onPress={() => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium)}
                                className="w-12 h-12 bg-gray-50 dark:bg-zinc-900 border border-gray-100 dark:border-zinc-800 rounded-2xl items-center justify-center shadow-sm"
                            >
                                <Plus size={24} color="#6366f1" strokeWidth={2} />
                            </TouchableOpacity>
                        </View>
                        <View className="h-12 bg-gray-50 dark:bg-zinc-900 border border-gray-100 dark:border-zinc-800 rounded-2xl flex-row items-center px-4">
                            <Search size={18} color="#94a3b8" strokeWidth={2} />
                            <Text style={{ color: '#94a3b8', fontWeight: 'bold', marginLeft: 12, fontSize: 16 }}>Search library...</Text>
                        </View>
                    </Animated.View>
                )}
            </View>

            <View className="px-6 mt-2">
                <Typography variant="sectionHeader" className="mb-4 text-gray-400 font-bold text-[11px] uppercase tracking-wider">Collections</Typography>
                <Animated.View layout={LinearTransition} className="flex-row">
                    <RagFolderCard
                        label="Work"
                        count="12 items"
                        iconColor="#6366f1"
                        isSelected={selectedIds.has('folder_work')}
                        isSelectionMode={isSelectionMode}
                        onPress={() => isSelectionMode ? toggleSelection('folder_work') : {}}
                        onLongPress={() => !isSelectionMode && startSelectionMode('folder_work')}
                    />
                    <View style={{ width: 16 }} />
                    <RagFolderCard
                        label="Private"
                        count="4 items"
                        iconColor="#818cf8"
                        isSelected={selectedIds.has('folder_private')}
                        isSelectionMode={isSelectionMode}
                        onPress={() => isSelectionMode ? toggleSelection('folder_private') : {}}
                        onLongPress={() => !isSelectionMode && startSelectionMode('folder_private')}
                    />
                </Animated.View>
            </View>

            <Animated.View layout={LinearTransition} className="px-6 mt-12 mb-4">
                <Typography variant="sectionHeader" className="text-gray-400 font-bold text-[11px] uppercase tracking-wider">Documents</Typography>
            </Animated.View>
        </Animated.View>
    );

    // 渲染函数：Item
    const renderDocItem = ({ item }: { item: any }) => {
        const isSelected = selectedIds.has(item.id);
        return (
            <Animated.View layout={LinearTransition} className="px-6 mb-3">
                <View
                    className={clsx(
                        "flex-row items-center rounded-[28px] border shadow-sm overflow-hidden",
                        isSelected
                            ? "bg-indigo-50/80 dark:bg-indigo-900/40 border-indigo-200 dark:border-indigo-800"
                            : "bg-gray-50 dark:bg-zinc-900 border-gray-100 dark:border-zinc-800"
                    )}
                >
                    <TouchableOpacity
                        activeOpacity={0.7}
                        onPress={() => isSelectionMode ? toggleSelection(item.id) : Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium)}
                        onLongPress={() => !isSelectionMode && startSelectionMode(item.id)}
                        delayLongPress={200}
                        className="flex-1 flex-row items-center p-5"
                    >
                        {isSelectionMode && (
                            <Animated.View entering={FadeInLeft.duration(250)} className="mr-4">
                                <View className={clsx(
                                    "w-6 h-6 rounded-full items-center justify-center border",
                                    isSelected ? "bg-indigo-500 border-indigo-500" : "bg-white dark:bg-zinc-800 border-gray-100 dark:border-zinc-700"
                                )}>
                                    {isSelected && <Check size={14} color="white" strokeWidth={3} />}
                                </View>
                            </Animated.View>
                        )}
                        <View className={clsx(
                            "w-14 h-14 rounded-2xl items-center justify-center mr-4 border",
                            isSelected ? "bg-white dark:bg-indigo-900 border-indigo-100" : "bg-white dark:bg-zinc-800 border-gray-50 dark:border-zinc-700"
                        )}>
                            <FileText size={24} color={isSelected ? "#6366f1" : "#94a3b8"} strokeWidth={1.5} />
                        </View>
                        <View className="flex-1">
                            <Typography variant="body" className={clsx("font-bold text-[17px]", isSelected ? "text-indigo-900 dark:text-indigo-100" : "text-gray-900 dark:text-white")}>{item.title}</Typography>
                            <Typography variant="caption" className={clsx("font-medium mt-0.5 text-[12px]", isSelected ? "text-indigo-400" : "text-gray-400")}>{item.size} • {item.date}</Typography>
                        </View>
                    </TouchableOpacity>

                    {!isSelectionMode && (
                        <View className="pr-3">
                            <ContextMenu items={[
                                { label: 'Rename', icon: <Edit2 size={18} />, onPress: () => showToast('Rename', 'info') },
                                { label: 'Share', icon: <Share size={18} />, onPress: () => showToast('Shared', 'success') },
                                { label: 'Delete', icon: <Trash2 size={18} />, destructive: true, onPress: () => showToast('Deleted', 'error') },
                            ]}>
                                <View className="p-3">
                                    <MoreVertical size={20} color="#cbd5e1" />
                                </View>
                            </ContextMenu>
                        </View>
                    )}
                </View>
            </Animated.View>
        );
    };

    return (
        <PageLayout className="bg-white dark:bg-black" safeArea={false}>
            <Stack.Screen options={{ headerShown: false }} />
            <FlashList
                data={MOCK_DOCS}
                renderItem={renderDocItem}
                keyExtractor={(item: any) => item.id}
                ListHeaderComponent={renderHeader}
                estimatedItemSize={100}
                contentContainerStyle={{ paddingBottom: isSelectionMode ? 180 : 80 }}
                {...({} as any)}
            />

            {isSelectionMode && (
                <View style={{ bottom: insets.bottom + 74 }} className="absolute left-6 right-6 z-50">
                    <Animated.View
                        entering={SlideInDown.duration(300)}
                        exiting={SlideOutDown.duration(200)}
                        className="bg-white dark:bg-zinc-900 rounded-[30px] p-5 flex-row justify-around items-center shadow-2xl border border-gray-100 dark:border-white/10"
                        style={{ elevation: 15, shadowColor: "#000", shadowOffset: { width: 0, height: 15 }, shadowOpacity: 0.15, shadowRadius: 30 }}
                    >
                        <TouchableOpacity className="items-center" onPress={() => handleAction('Moved')}>
                            <View className="w-12 h-12 rounded-2xl bg-gray-50 dark:bg-white/10 items-center justify-center mb-1">
                                <FolderInput size={22} color={isDark ? "#a5b4fc" : "#6366f1"} />
                            </View>
                            <Typography className="text-gray-900 dark:text-white text-[11px] font-bold">MOVE</Typography>
                        </TouchableOpacity>
                        <TouchableOpacity className="items-center" onPress={() => handleAction('Shared')}>
                            <View className="w-12 h-12 rounded-2xl bg-gray-50 dark:bg-white/10 items-center justify-center mb-1">
                                <Share size={22} color={isDark ? "#a5b4fc" : "#6366f1"} />
                            </View>
                            <Typography className="text-gray-900 dark:text-white text-[11px] font-bold">SHARE</Typography>
                        </TouchableOpacity>
                        <TouchableOpacity className="items-center" onPress={() => handleAction('Deleted')}>
                            <View className="w-12 h-12 rounded-2xl bg-red-50 dark:bg-red-500/20 items-center justify-center mb-1">
                                <Trash2 size={22} color="#ef4444" />
                            </View>
                            <Typography className="text-red-500 text-[11px] font-bold">DELETE</Typography>
                        </TouchableOpacity>
                    </Animated.View>
                </View>
            )}
        </PageLayout>
    );
}
