import React, { useState, useEffect } from 'react';
import { View, Modal, TextInput, TouchableOpacity, Text, BackHandler } from 'react-native';
import { Typography } from '../ui/Typography';
import { useTheme } from '../../theme/ThemeProvider';
import { useI18n } from '../../lib/i18n';
import { X, Save, Trash2 } from 'lucide-react-native';
import { graphStore } from '../../lib/rag/graph-store';

interface KGNodeEditModalProps {
    visible: boolean;
    node?: { id: string; label: string; group?: string } | null;
    onClose: () => void;
    onSave: () => void;
    sessionId?: string;
    agentId?: string;
}

export const KGNodeEditModal: React.FC<KGNodeEditModalProps> = ({
    visible,
    node,
    onClose,
    onSave,
    sessionId,
    agentId,
}) => {
    const { isDark, colors } = useTheme();
    const { t } = useI18n();
    const [name, setName] = useState('');
    const [type, setType] = useState('concept');

    useEffect(() => {
        if (visible) {
            if (node) {
                setName(node.label || '');
                setType(node.group || 'concept');
            } else {
                setName('');
                setType('concept');
            }
        }
    }, [visible, node]);

    useEffect(() => {
        const onBackPress = () => {
            if (visible) {
                onClose();
                return true;
            }
            return false;
        };

        const subscription = BackHandler.addEventListener('hardwareBackPress', onBackPress);
        return () => subscription.remove();
    }, [visible, onClose]);

    const handleSave = async () => {
        if (!name.trim()) return;
        try {
            if (node) {
                // Update
                await graphStore.updateNode(node.id, { name: name.trim(), type });
            } else {
                // Create
                await graphStore.upsertNode(
                    name.trim(),
                    type,
                    {},
                    { sessionId, agentId }
                );
            }
            onSave(); // Trigger refresh
            onClose();
        } catch (e) {
            console.error('Failed to save node:', e);
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

    const isEditing = !!node;

    return (
        <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
            <View className="flex-1 bg-black/50 justify-center items-center px-6">
                <View className="bg-white/80 dark:bg-zinc-900/60 w-full rounded-3xl p-6 shadow-xl border border-indigo-50 dark:border-indigo-500/10">

                    {/* Header */}
                    <View className="flex-row justify-between items-center mb-6">
                        <Typography variant="h3" className="text-gray-900 dark:text-white font-bold">
                            {isEditing ? t.rag.kg.nodeEdit : t.rag.kg.nodeCreate}
                        </Typography>
                        <TouchableOpacity onPress={onClose} className="p-2 bg-gray-100 dark:bg-zinc-800 rounded-full">
                            <X size={20} color={isDark ? '#FFF' : '#000'} />
                        </TouchableOpacity>
                    </View>

                    {/* Form */}
                    <View className="gap-4">
                        <View>
                            <Typography variant="label" className="mb-2 text-gray-700 dark:text-gray-300">
                                {t.rag.kg.nodeName}
                            </Typography>
                            <TextInput
                                value={name}
                                onChangeText={setName}
                                className="bg-gray-50 dark:bg-zinc-800 px-4 py-3 rounded-xl text-base text-gray-900 dark:text-white"
                                placeholder={t.rag.kg.nodeName}
                                placeholderTextColor={isDark ? '#52525b' : '#a1a1aa'}
                            />
                        </View>

                        <View>
                            <Typography variant="label" className="mb-2 text-gray-700 dark:text-gray-300">
                                {t.rag.kg.nodeGroup}
                            </Typography>
                            <TextInput
                                value={type}
                                onChangeText={setType}
                                className="bg-gray-50 dark:bg-zinc-800 px-4 py-3 rounded-xl text-base text-gray-900 dark:text-white"
                                placeholder="e.g. Person, Event..."
                                placeholderTextColor={isDark ? '#52525b' : '#a1a1aa'}
                            />
                        </View>
                    </View>

                    {/* Actions */}
                    <View className="flex-row gap-3 mt-8">
                        {isEditing && (
                            <TouchableOpacity
                                onPress={handleDelete}
                                className="flex-1 bg-red-50 dark:bg-red-900/20 py-3.5 rounded-xl flex-row justify-center items-center gap-2"
                            >
                                <Trash2 size={18} color="#ef4444" />
                                <Text className="font-bold text-red-600 dark:text-red-400">{t.rag.kg.deleteNode}</Text>
                            </TouchableOpacity>
                        )}

                        <TouchableOpacity
                            onPress={handleSave}
                            style={{ backgroundColor: colors[500] }}
                            className={`flex-[2] py-3.5 rounded-xl flex-row justify-center items-center gap-2 ${!isEditing ? 'flex-1' : ''}`}
                        >
                            <Save size={18} color="white" />
                            <Text className="font-bold text-white">{isEditing ? t.rag.kg.saveChanges : t.rag.kg.createNow}</Text>
                        </TouchableOpacity>
                    </View>

                </View>
            </View>
        </Modal>
    );
};
