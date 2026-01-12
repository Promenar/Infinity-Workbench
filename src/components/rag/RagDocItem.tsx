import React, { memo } from 'react';
import { View, TouchableOpacity } from 'react-native';
import { Typography, ContextMenu } from '../ui';
import { FileText, MoreVertical, Edit2, Share, Trash2, Check } from 'lucide-react-native';
import Animated, { useAnimatedStyle, SharedValue } from 'react-native-reanimated';
import { clsx } from 'clsx';
import { useI18n } from '../../lib/i18n';
import { useTheme } from '../../theme/ThemeProvider';

export const RagDocItem = memo(
  ({
    item,
    isSelected,
    isSelectionMode,
    selectionProgress,
    onPress,
    onLongPress,
    onDelete,
    showToast,
  }: {
    item: any;
    isSelected: boolean;
    isSelectionMode: boolean;
    selectionProgress: SharedValue<number>;
    onPress: () => void;
    onLongPress: () => void;
    onDelete: () => void;
    showToast: (m: string, t: any) => void;
  }) => {
    const { t } = useI18n();
    const { isDark, colors } = useTheme();
    const contentStyle = useAnimatedStyle(() => ({
      transform: [{ translateX: selectionProgress.value * 42 }],
    }));

    const checkboxStyle = useAnimatedStyle(() => ({
      opacity: selectionProgress.value,
      transform: [{ scale: 0.6 + selectionProgress.value * 0.4 }],
    }));

    return (
      <View className="px-6 mb-2">
        <View
          style={isSelected ? { backgroundColor: isDark ? colors.opacity20 : colors.opacity10, borderColor: colors.opacity30 } : {}}
          className={clsx(
            'flex-row items-center p-4 mb-3 rounded-2xl border transition-all duration-300',
            !isSelected && 'bg-white dark:bg-zinc-900 border-indigo-50 dark:border-indigo-500/10',
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
              className="flex-1 flex-row items-center py-2.5 px-3"
            >
              <Animated.View
                style={[checkboxStyle, { width: 42 }]}
                className="flex-row items-center"
              >
                <View
                  style={isSelected ? { backgroundColor: colors[500], borderColor: colors[500] } : {}}
                  className={clsx(
                    'w-5 h-5 rounded-full border-2 items-center justify-center mr-3',
                    !isSelected && 'border-gray-300 dark:border-zinc-700',
                  )}
                >
                  {isSelected && <Check size={12} color="white" strokeWidth={3} />}
                </View>
              </Animated.View>

              <View
                className={clsx(
                  'w-11 h-11 rounded-xl items-center justify-center mr-3.5 border',
                  isSelected
                    ? 'bg-white dark:bg-indigo-900 border-indigo-100'
                    : 'bg-white dark:bg-zinc-800 border-gray-50 dark:border-zinc-700',
                )}
              >
                <FileText size={20} color={isSelected ? colors[500] : '#94a3b8'} strokeWidth={1.5} />
              </View>
              <View className="flex-1">
                <Typography
                  variant="body"
                  numberOfLines={1}
                  className={clsx(
                    'font-bold text-[15px] leading-6',
                    isSelected
                      ? 'text-indigo-900 dark:text-indigo-100'
                      : 'text-gray-900 dark:text-gray-100',
                  )}
                >
                  {item.title}
                </Typography>
                <Typography
                  variant="caption"
                  style={isSelected ? { color: colors[400] } : {}}
                  className={clsx(
                    'text-[10px] mt-0.5',
                    !isSelected && 'text-gray-400',
                  )}
                >
                  {item.size} • {item.date}
                </Typography>
              </View>
            </Animated.View>
          </TouchableOpacity>

          <View className="pr-1 justify-center">
            <ContextMenu
              items={[
                // { label: t.library.rename, icon: <Edit2 size={16} />, onPress: () => showToast(t.library.rename, 'info') }, // TODO
                {
                  label: t.library.delete,
                  icon: <Trash2 size={16} />,
                  destructive: true,
                  onPress: onDelete,
                },
              ]}
            >
              <View className="p-3 opacity-60">
                <MoreVertical size={16} color={isSelected ? '#6366f1' : '#94a3b8'} />
              </View>
            </ContextMenu>
          </View>
        </View>
      </View>
    );
  },
);
