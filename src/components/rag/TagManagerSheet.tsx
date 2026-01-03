import React, { useState, useMemo } from 'react';
import { View, Text, TextInput, TouchableOpacity, ScrollView, Modal, KeyboardAvoidingView, Platform, TouchableWithoutFeedback } from 'react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { useRagStore } from '../../store/rag-store';
import { X, Plus, Trash2, Tag as TagIcon } from 'lucide-react-native';
import { TagCapsule } from './TagCapsule';
import { Typography, Button } from '../ui';
import Animated, { FadeIn, SlideInDown } from 'react-native-reanimated';

interface TagManagerSheetProps {
    visible: boolean;
    onClose: () => void;
}

const TAG_colors = [
    '#ef4444', // red
    '#f97316', // orange
    '#eab308', // yellow
    '#22c55e', // green
    '#06b6d4', // cyan
    '#3b82f6', // blue
    '#6366f1', // indigo
    '#a855f7', // purple
    '#ec4899', // pink
    '#64748b', // slate
];

export const TagManagerSheet: React.FC<TagManagerSheetProps> = ({ visible, onClose }) => {
    const { isDark } = useTheme();
    const { availableTags, createTag, deleteTag } = useRagStore();

    const [newTagName, setNewTagName] = useState('');
    const [selectedColor, setSelectedColor] = useState(TAG_colors[6]);
    const [isCreating, setIsCreating] = useState(false);

    const handleCreate = async () => {
        if (!newTagName.trim()) return;
        try {
            await createTag(newTagName.trim(), selectedColor);
            setNewTagName('');
            setIsCreating(false);
        } catch (e) {
            console.error(e);
        }
    };

    const handleDelete = async (id: string) => {
        try {
            await deleteTag(id);
        } catch (e) {
            console.error(e);
        }
    };

    return (
        <Modal
            transparent
            visible={visible}
            animationType="fade"
            onRequestClose={onClose}
        >
            <TouchableWithoutFeedback onPress={onClose}>
                <View className="flex-1 bg-black/50 justify-end">
                    <TouchableWithoutFeedback onPress={e => e.stopPropagation()}>
                        <Animated.View
                            entering={SlideInDown}
                            className={`bg-white dark:bg-zinc-900 rounded-t-3xl h-[70%] w-full overflow-hidden`}
                        >
                            {/* Header */}
                            <View className="flex-row items-center justify-between px-6 py-4 border-b border-gray-100 dark:border-zinc-800">
                                <View className="flex-row items-center gap-2">
                                    <TagIcon size={20} color={isDark ? '#e5e5e5' : '#1f2937'} />
                                    <Typography className="text-lg font-bold text-gray-900 dark:text-white">
                                        标签管理
                                    </Typography>
                                </View>
                                <TouchableOpacity
                                    onPress={onClose}
                                    className="p-2 -mr-2 rounded-full active:bg-gray-100 dark:active:bg-zinc-800"
                                >
                                    <X size={20} color={isDark ? '#9ca3af' : '#6b7280'} />
                                </TouchableOpacity>
                            </View>

                            {/* Content */}
                            <View className="flex-1 p-4">
                                {/* Create New Tag Section */}
                                <View className="mb-6 bg-gray-50 dark:bg-zinc-800/50 p-4 rounded-xl">
                                    <View className="flex-row items-center gap-2 mb-3">
                                        <TextInput
                                            value={newTagName}
                                            onChangeText={setNewTagName}
                                            placeholder="输入新标签名称..."
                                            placeholderTextColor={isDark ? '#52525b' : '#9ca3af'}
                                            className="flex-1 h-12 px-3 py-0 bg-white dark:bg-zinc-800 rounded-lg border border-gray-200 dark:border-zinc-700 text-gray-900 dark:text-white"
                                            style={{ textAlignVertical: 'center' }}
                                        />
                                        <TouchableOpacity
                                            onPress={handleCreate}
                                            disabled={!newTagName.trim()}
                                            className={`h-10 w-10 items-center justify-center rounded-lg ${!newTagName.trim() ? 'bg-gray-300 dark:bg-zinc-700' : 'bg-indigo-600'}`}
                                        >
                                            <Plus size={20} color="white" />
                                        </TouchableOpacity>
                                    </View>

                                    {/* Color Picker */}
                                    <ScrollView horizontal showsHorizontalScrollIndicator={false} className="py-1">
                                        {TAG_colors.map(color => (
                                            <TouchableOpacity
                                                key={color}
                                                onPress={() => setSelectedColor(color)}
                                                className={`w-8 h-8 rounded-full mr-3 items-center justify-center border-2 ${selectedColor === color ? 'border-gray-900 dark:border-white' : 'border-transparent'}`}
                                            >
                                                <View style={{ backgroundColor: color }} className="w-6 h-6 rounded-full" />
                                            </TouchableOpacity>
                                        ))}
                                    </ScrollView>
                                </View>

                                <Typography className="text-sm text-gray-400 font-medium mb-3 px-1">
                                    已有标签 ({availableTags.length})
                                </Typography>

                                <ScrollView className="flex-1">
                                    {availableTags.length === 0 ? (
                                        <View className="items-center justify-center py-10 opacity-50">
                                            <TagIcon size={40} color="#9ca3af" />
                                            <Typography className="mt-2 text-gray-500">暂无标签</Typography>
                                        </View>
                                    ) : (
                                        <View className="flex-row flex-wrap gap-2">
                                            {availableTags.map(tag => (
                                                <View key={tag.id} className="relative group">
                                                    <TagCapsule
                                                        name={tag.name}
                                                        color={tag.color}
                                                        size="md"
                                                    />
                                                    <TouchableOpacity
                                                        onPress={() => handleDelete(tag.id)}
                                                        className="absolute -top-1 -right-1 w-4 h-4 bg-gray-200 dark:bg-zinc-700 rounded-full items-center justify-center border border-white dark:border-zinc-900"
                                                    >
                                                        <X size={10} color={isDark ? '#e5e5e5' : '#4b5563'} />
                                                    </TouchableOpacity>
                                                </View>
                                            ))}
                                        </View>
                                    )}
                                </ScrollView>
                            </View>
                        </Animated.View>
                    </TouchableWithoutFeedback>
                </View>
            </TouchableWithoutFeedback>
        </Modal>
    );
};
