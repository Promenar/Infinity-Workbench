import React, { memo } from 'react';
import { View, TouchableOpacity } from 'react-native';
import { Typography, ContextMenu } from '../ui';
import { FileText, MoreVertical, Edit2, Share, Trash2, Check } from 'lucide-react-native';
import Animated, { useAnimatedStyle, SharedValue } from 'react-native-reanimated';
import { clsx } from 'clsx';
import { useI18n } from '../../lib/i18n';

export const RagDocItem = memo(({
    item,
    isSelected,
    isSelectionMode,
    selectionProgress,
    onPress,
    onLongPress,
    showToast
}: {
    item: any,
    isSelected: boolean,
    isSelectionMode: boolean,
    selectionProgress: SharedValue<number>,
    onPress: () => void,
    onLongPress: () => void,
    showToast: (m: string, t: any) => void
}) => {
    const { t } = useI18n();
    const contentStyle = useAnimatedStyle(() => ({
        transform: [{ translateX: selectionProgress.value * 42 }]
    }));

    const checkboxStyle = useAnimatedStyle(() => ({
        opacity: selectionProgress.value,
        transform: [{ scale: 0.6 + selectionProgress.value * 0.4 }]
    }));

    return (
        <View className="px-6 mb-2">
            <View
                className={clsx(
                    "flex-row items-center rounded-[22px] border shadow-sm overflow-hidden",
                    isSelected
                        ? "bg-indigo-50/80 dark:bg-indigo-900/40 border-indigo-200 dark:border-indigo-800"
                        : "bg-gray-50 dark:bg-zinc-900 border-gray-100 dark:border-zinc-800"
                )}
            >
                <TouchableOpacity
                    activeOpacity={0.7}
                    onPress={onPress}
                    onLongPress={onLongPress}
                    delayLongPress={200}
                    className="flex-1 overflow-hidden"
                >
                    <Animated.View
                        style={[contentStyle, { marginLeft: -42 }]}
                        className="flex-1 flex-row items-center p-3.5"
                    >
                        <Animated.View style={[checkboxStyle, { width: 42 }]} className="flex-row items-center">
                            <View className={clsx(
                                "w-5 h-5 rounded-full items-center justify-center border",
                                isSelected ? "bg-indigo-500 border-indigo-500" : "bg-white dark:bg-zinc-800 border-gray-100 dark:border-zinc-700"
                            )}>
                                {isSelected && <Check size={12} color="white" strokeWidth={3} />}
                            </View>
                        </Animated.View>

                        <View className={clsx(
                            "w-11 h-11 rounded-xl items-center justify-center mr-3.5 border",
                            isSelected ? "bg-white dark:bg-indigo-900 border-indigo-100" : "bg-white dark:bg-zinc-800 border-gray-50 dark:border-zinc-700"
                        )}>
                            <FileText size={20} color={isSelected ? "#6366f1" : "#94a3b8"} strokeWidth={1.5} />
                        </View>
                        <View className="flex-1">
                            <Typography variant="body" className={clsx("font-bold text-[16px]", isSelected ? "text-indigo-900 dark:text-indigo-100" : "text-gray-900 dark:text-white")}>{item.title}</Typography>
                            <Typography variant="caption" className={clsx("font-medium mt-0.5 text-[11px]", isSelected ? "text-indigo-400" : "text-gray-400")}>{item.size} • {item.date}</Typography>
                        </View>
                    </Animated.View>
                </TouchableOpacity>

                {!isSelectionMode && (
                    <View className="pr-1.5 absolute right-0 top-0 bottom-0 justify-center">
                        <ContextMenu items={[
                            { label: t.library.rename, icon: <Edit2 size={18} />, onPress: () => showToast(t.library.rename, 'info') },
                            { label: t.library.share, icon: <Share size={18} />, onPress: () => showToast(t.library.share, 'success') },
                            { label: t.library.delete, icon: <Trash2 size={18} />, destructive: true, onPress: () => showToast(t.library.delete, 'error') },
                        ]}>
                            <View className="p-3">
                                <MoreVertical size={18} color="#cbd5e1" />
                            </View>
                        </ContextMenu>
                    </View>
                )}
            </View>
        </View>
    );
}, (prev, next) => {
    return (
        prev.isSelected === next.isSelected &&
        prev.isSelectionMode === next.isSelectionMode &&
        prev.item.id === next.item.id
    );
});
