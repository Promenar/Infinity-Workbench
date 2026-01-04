import React, { useState, useEffect } from 'react';
import { View, Modal, TextInput, TouchableOpacity, Text } from 'react-native';
import { Typography } from '../ui/Typography';
import { useTheme } from '../../theme/ThemeProvider';
import { X, Save, Trash2 } from 'lucide-react-native';
import { graphStore } from '../../lib/rag/graph-store';

interface KGNodeEditModalProps {
    visible: boolean;
    node: { id: string; label: string; group?: string } | null;
    onClose: () => void;
    onSave: () => void;
}

export const KGNodeEditModal: React.FC<KGNodeEditModalProps> = ({
    visible,
    node,
    onClose,
    onSave,
}) => {
    const { isDark } = useTheme();
    const [name, setName] = useState('');
    const [type, setType] = useState('concept');

    useEffect(() => {
        if (node) {
            setName(node.label || '');
            // If group corresponds to type (concept, person, etc.), use it.
            // Typically we map type -> group color, but here we assum group IS type logic.
            // Let's assume default 'concept' if unknown.
            setType(node.group || 'concept');
        }
    }, [node]);

    const handleSave = async () => {
        if (!node || !name.trim()) return;
        try {
            await graphStore.updateNode(node.id, { name: name.trim(), type });
            onSave(); // Trigger refresh
            onClose();
        } catch (e) {
            console.error('Failed to update node:', e);
        }
    };

    const handleDelete = async () => {
        if (!node) return;
        try {
            await graphStore.deleteNode(node.id);
            onSave();
            onClose();
        } catch (e) {
            console.error('Failed to delete node:', e);
        }
    };

    return (
        <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
            <View className="flex-1 bg-black/50 justify-center items-center px-6">
                <View className="bg-white dark:bg-zinc-900 w-full rounded-3xl p-6 shadow-xl border border-gray-100 dark:border-zinc-800">

                    {/* Header */}
                    <View className="flex-row justify-between items-center mb-6">
                        <Typography variant="h3">编辑节点</Typography>
                        <TouchableOpacity onPress={onClose} className="p-2 bg-gray-100 dark:bg-zinc-800 rounded-full">
                            <X size={20} color={isDark ? '#FFF' : '#000'} />
                        </TouchableOpacity>
                    </View>

                    {/* Form */}
                    <View className="gap-4">
                        <View>
                            <Typography variant="label" className="mb-2">名称</Typography>
                            <TextInput
                                value={name}
                                onChangeText={setName}
                                className="bg-gray-50 dark:bg-zinc-800 px-4 py-3 rounded-xl text-base text-gray-900 dark:text-white"
                                placeholder="节点名称"
                                placeholderTextColor={isDark ? '#52525b' : '#a1a1aa'}
                            />
                        </View>

                        <View>
                            <Typography variant="label" className="mb-2">类型 (Tag)</Typography>
                            <TextInput
                                value={type}
                                onChangeText={setType}
                                className="bg-gray-50 dark:bg-zinc-800 px-4 py-3 rounded-xl text-base text-gray-900 dark:text-white"
                                placeholder="例如: Person, Event..."
                                placeholderTextColor={isDark ? '#52525b' : '#a1a1aa'}
                            />
                        </View>
                    </View>

                    {/* Actions */}
                    <View className="flex-row gap-3 mt-8">
                        <TouchableOpacity
                            onPress={handleDelete}
                            className="flex-1 bg-red-50 dark:bg-red-900/20 py-3.5 rounded-xl flex-row justify-center items-center gap-2"
                        >
                            <Trash2 size={18} color="#ef4444" />
                            <Text className="font-bold text-red-600 dark:text-red-400">删除</Text>
                        </TouchableOpacity>

                        <TouchableOpacity
                            onPress={handleSave}
                            className="flex-[2] bg-indigo-600 dark:bg-indigo-500 py-3.5 rounded-xl flex-row justify-center items-center gap-2"
                        >
                            <Save size={18} color="white" />
                            <Text className="font-bold text-white">保存修改</Text>
                        </TouchableOpacity>
                    </View>

                </View>
            </View>
        </Modal>
    );
};
