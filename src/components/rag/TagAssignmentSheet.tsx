import React from 'react';
import { View, TouchableOpacity, ScrollView, Modal, TouchableWithoutFeedback } from 'react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { useRagStore } from '../../store/rag-store';
import { X, Tag as TagIcon, Check } from 'lucide-react-native';
import { TagCapsule } from './TagCapsule';
import { Typography } from '../ui';
import Animated, { SlideInDown } from 'react-native-reanimated';

interface TagAssignmentSheetProps {
    visible: boolean;
    docId: string | null;
    onClose: () => void;
    onManageTags: () => void;
}

export const TagAssignmentSheet: React.FC<TagAssignmentSheetProps> = ({ visible, docId, onClose, onManageTags }) => {
    const { isDark } = useTheme();
    const { availableTags, documents, addTagToDocument, removeTagFromDocument } = useRagStore();

    // Find current doc and its tags
    const currentDoc = documents.find(d => d.id === docId);
    const existingTagIds = new Set(currentDoc?.tags?.map(t => t.id) || []);

    const toggleTag = async (tagId: string) => {
        if (!docId) return;

        if (existingTagIds.has(tagId)) {
            await removeTagFromDocument(docId, tagId);
        } else {
            await addTagToDocument(docId, tagId);
        }
    };

    if (!docId) return null;

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
                            className={`bg-white dark:bg-zinc-900 rounded-t-3xl h-[60%] w-full overflow-hidden`}
                        >
                            {/* Header */}
                            <View className="flex-row items-center justify-between px-6 py-4 border-b border-gray-100 dark:border-zinc-800">
                                <View>
                                    <Typography className="text-lg font-bold text-gray-900 dark:text-white">
                                        添加标签
                                    </Typography>
                                    <Typography className="text-xs text-gray-500" numberOfLines={1}>
                                        {currentDoc?.title}
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
                            <ScrollView className="flex-1 p-4">
                                <View className="flex-row flex-wrap gap-2">
                                    {availableTags.map(tag => {
                                        const isSelected = existingTagIds.has(tag.id);
                                        return (
                                            <TouchableOpacity
                                                key={tag.id}
                                                onPress={() => toggleTag(tag.id)}
                                                className={`flex-row items-center px-3 py-2 rounded-full border ${isSelected
                                                    ? 'border-indigo-500 bg-indigo-50 dark:bg-indigo-900/30'
                                                    : 'border-gray-200 dark:border-zinc-700 bg-gray-50 dark:bg-zinc-800'}`}
                                            >
                                                <View className="w-2 h-2 rounded-full mr-2" style={{ backgroundColor: tag.color }} />
                                                <Typography className={`mr-2 ${isSelected ? 'font-bold text-indigo-700 dark:text-indigo-300' : 'text-gray-700 dark:text-gray-300'}`}>
                                                    {tag.name}
                                                </Typography>
                                                {isSelected && <Check size={14} color={isDark ? '#818cf8' : '#4338ca'} />}
                                            </TouchableOpacity>
                                        );
                                    })}

                                    {/* Add New Trigger */}
                                    <TouchableOpacity
                                        onPress={() => {
                                            onClose();
                                            setTimeout(onManageTags, 300);
                                        }}
                                        className="flex-row items-center px-3 py-2 rounded-full border border-dashed border-gray-300 dark:border-zinc-600 bg-transparent"
                                    >
                                        <TagIcon size={14} color={isDark ? '#9ca3af' : '#6b7280'} className="mr-2" />
                                        <Typography className="text-gray-500 dark:text-gray-400">管理标签...</Typography>
                                    </TouchableOpacity>
                                </View>
                            </ScrollView>
                        </Animated.View>
                    </TouchableWithoutFeedback>
                </View>
            </TouchableWithoutFeedback>
        </Modal>
    );
};
