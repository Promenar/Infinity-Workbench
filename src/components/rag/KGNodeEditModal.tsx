import React, { useState, useEffect } from 'react';
import { View, Modal, TextInput, TouchableOpacity, Text, ActivityIndicator, StyleSheet } from 'react-native';
import { KeyboardAvoidingView } from 'react-native-keyboard-controller';
import { BlurView } from 'expo-blur';
import { Typography } from '../ui/Typography';
import { useTheme } from '../../theme/ThemeProvider';
import { useI18n } from '../../lib/i18n';
import { X, Save, Trash2 } from 'lucide-react-native';
import { graphStore } from '../../lib/rag/graph-store';
import { GlassAlert } from '../ui/GlassAlert';
import { Glass } from '../../theme/glass';

interface KGNodeEditModalProps {
    visible: boolean;
    node: { id: string; label: string; group?: string } | null;
    onClose: () => void;
    onSave: () => void;
    sessionId?: string;
    agentId?: string;
}

interface AlertConfig {
    visible: boolean;
    title: string;
    message: string;
    confirmText?: string;
    cancelText?: string;
    showCancel?: boolean;
    onConfirm?: () => void;
    isDestructive?: boolean;
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
    const [saving, setSaving] = useState(false);

    // Generic Alert State
    const [alertConfig, setAlertConfig] = useState<AlertConfig>({
        visible: false,
        title: '',
        message: '',
    });

    useEffect(() => {
        if (node) {
            setName(node.label || '');
            setType(node.group || 'concept');
        } else {
            setName('');
            setType('concept');
        }
    }, [node]);

    const hideAlert = () => setAlertConfig(prev => ({ ...prev, visible: false }));

    const handleMerge = async () => {
        try {
            if (node) {
                hideAlert();
                setSaving(true);
                await graphStore.mergeNodes(node.id, name.trim());
                onSave();
                onClose();
            }
        } catch (mergeError: any) {
            console.error('Merge failed:', mergeError);
            setAlertConfig({
                visible: true,
                title: t.common.error,
                message: `${t.rag.kg.mergeFailed || 'Merge Failed'}: ${mergeError.message}`,
                confirmText: 'OK',
            });
        } finally {
            setSaving(false);
        }
    };

    const handleSave = async () => {
        if (!name.trim()) {
            setAlertConfig({
                visible: true,
                title: t.common.error,
                message: t.rag.kg.nodeNameRequired || 'Node name is required',
                confirmText: 'OK',
            });
            return;
        }
        setSaving(true);
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
            onSave();
            onClose();
        } catch (e: any) {
            // Check for UNIQUE constraint violation (Node exists)
            // Error logged by graphStore is suppressed now if handled
            if (e.message && (e.message.includes('UNIQUE constraint failed') || e.message.includes('SQLITE_CONSTRAINT'))) {
                console.warn('[KGNodeEditModal] Handled UNIQUE constraint violation. Prompting merge.');
                setSaving(false);

                setAlertConfig({
                    visible: true,
                    title: t.rag.kg.nodeExists || 'Node Exists',
                    message: (t.rag.kg.nodeMergeConfirm || "Node '{name}' already exists. Do you want to merge this node into it?").replace('{name}', name.trim()),
                    confirmText: t.rag.kg.merge || 'Merge',
                    cancelText: t.common.cancel,
                    showCancel: true,
                    onConfirm: handleMerge,
                });
                return;
            }

            console.error('Failed to save node:', e);
            setSaving(false);
            setAlertConfig({
                visible: true,
                title: t.common.error,
                message: `${t.rag.kg.saveFailed || 'Failed to save node'}: ${e.message || 'Unknown error'}`,
                confirmText: 'OK',
            });
        }
    };

    const handleDelete = async () => {
        if (!node) return;
        setAlertConfig({
            visible: true,
            title: t.common.confirm,
            message: t.rag.kg.deleteConfirm || 'Are you sure you want to delete this node?',
            confirmText: t.common.delete,
            cancelText: t.common.cancel,
            showCancel: true,
            isDestructive: true,
            onConfirm: async () => {
                hideAlert();
                setSaving(true);
                try {
                    await graphStore.deleteNode(node.id);
                    onSave();
                    onClose();
                } catch (e: any) {
                    console.error('Failed to delete node:', e);
                    setSaving(false);
                    // Add small delay to allow modal transition
                    setTimeout(() => {
                        setAlertConfig({
                            visible: true,
                            title: t.common.error,
                            message: `${t.rag.kg.deleteFailed || 'Failed to delete node'}: ${e.message}`,
                            confirmText: 'OK',
                        });
                    }, 300);
                }
            }
        });
    };

    // Glass constants - Matching GlassBottomSheet exactly
    const glassIntensity = Glass.Header.intensity;
    // Use Header opacity (0.15/0.25) to match the toolbox transparency
    const glassOpacity = isDark ? Glass.Header.opacity.dark : Glass.Header.opacity.light;
    const glassTint = isDark ? Glass.Header.tint.dark : Glass.Header.tint.light;

    return (
        <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
            <KeyboardAvoidingView behavior="padding" className="flex-1">
            <View className="flex-1 justify-center items-center px-6">
                {/* Backdrop - consistent dim */}
                <View style={{ ...StyleSheet.absoluteFillObject, backgroundColor: isDark ? 'rgba(0,0,0,0.6)' : 'rgba(0,0,0,0.3)' }} />

                {/* Glass Container */}
                <View
                    style={{
                        width: '100%',
                        borderRadius: 24,
                        overflow: 'hidden',
                        borderWidth: 1,
                        // Match GlassBottomSheet border colors
                        borderColor: isDark ? 'rgba(255, 255, 255, 0.12)' : 'rgba(0, 0, 0, 0.08)',
                        backgroundColor: 'transparent',
                        shadowColor: '#000',
                        shadowOffset: { width: 0, height: 10 },
                        shadowOpacity: 0.2,
                        shadowRadius: 20,
                        elevation: 10,
                    }}
                >
                    <BlurView
                        intensity={glassIntensity}
                        tint={glassTint as any}
                        experimentalBlurMethod='dimezisBlurView' // Enable experimental if available for better effect
                        style={{ padding: 24 }}
                    >
                        {/* Color Overlay */}
                        <View
                            style={{
                                ...StyleSheet.absoluteFillObject,
                                backgroundColor: isDark
                                    ? `rgba(0, 0, 0, ${glassOpacity})`
                                    : `rgba(255, 255, 255, ${glassOpacity})`
                            }}
                        />

                        {/* Content */}
                        <View>
                            {/* Header */}
                            <View className="flex-row justify-between items-center mb-6">
                                <Typography variant="h3" className="text-gray-900 dark:text-white font-bold">
                                    {node ? t.rag.kg.nodeEdit : t.rag.kg.nodeCreate}
                                </Typography>
                                <TouchableOpacity
                                    onPress={onClose}
                                    disabled={saving}
                                    className="p-2 rounded-full"
                                    style={{ backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)' }}
                                >
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
                                        style={{
                                            backgroundColor: isDark ? 'rgba(0,0,0,0.2)' : 'rgba(0,0,0,0.05)',
                                            color: isDark ? '#FFF' : '#000'
                                        }}
                                        className="px-4 py-3 rounded-xl text-base"
                                        placeholder={t.rag.kg.nodeName}
                                        placeholderTextColor={isDark ? '#52525b' : '#a1a1aa'}
                                        editable={!saving}
                                    />
                                </View>
                                <View>
                                    <Typography variant="label" className="mb-2 text-gray-700 dark:text-gray-300">
                                        {t.rag.kg.nodeGroup}
                                    </Typography>
                                    <TextInput
                                        value={type}
                                        onChangeText={setType}
                                        style={{
                                            backgroundColor: isDark ? 'rgba(0,0,0,0.2)' : 'rgba(0,0,0,0.05)',
                                            color: isDark ? '#FFF' : '#000'
                                        }}
                                        className="px-4 py-3 rounded-xl text-base"
                                        placeholder={t.rag.kg.nodeGroup}
                                        placeholderTextColor={isDark ? '#52525b' : '#a1a1aa'}
                                        editable={!saving}
                                    />
                                </View>
                            </View>

                            {/* Actions */}
                            <View className="flex-row gap-3 mt-8">
                                {node && (
                                    <TouchableOpacity
                                        onPress={handleDelete}
                                        disabled={saving}
                                        className="flex-1 py-3.5 rounded-xl flex-row justify-center items-center gap-2"
                                        style={{
                                            backgroundColor: isDark ? 'rgba(239, 68, 68, 0.2)' : 'rgba(254, 226, 226, 0.7)',
                                            opacity: saving ? 0.5 : 1
                                        }}
                                    >
                                        <Trash2 size={18} color="#ef4444" />
                                        <Text className="font-bold text-red-600 dark:text-red-400">{t.rag.kg.deleteNode}</Text>
                                    </TouchableOpacity>
                                )}

                                <TouchableOpacity
                                    onPress={handleSave}
                                    disabled={saving}
                                    style={{ backgroundColor: colors[500], opacity: saving ? 0.7 : 1 }}
                                    className="flex-[2] py-3.5 rounded-xl flex-row justify-center items-center gap-2"
                                >
                                    {saving ? (
                                        <ActivityIndicator color="white" size="small" />
                                    ) : (
                                        <>
                                            <Save size={18} color="white" />
                                            <Text className="font-bold text-white">{node ? t.rag.kg.saveChanges : t.rag.kg.createNow}</Text>
                                        </>
                                    )}
                                </TouchableOpacity>
                            </View>
                        </View>
                    </BlurView>
                </View>
            </View>
            </KeyboardAvoidingView>

            {/* Glass Alert for all interactions */}
            <GlassAlert
                visible={alertConfig.visible}
                title={alertConfig.title}
                message={alertConfig.message}
                confirmText={alertConfig.confirmText}
                cancelText={alertConfig.cancelText}
                showCancel={alertConfig.showCancel}
                isDestructive={alertConfig.isDestructive}
                onConfirm={alertConfig.onConfirm ? alertConfig.onConfirm : hideAlert}
                onCancel={hideAlert}
            />
        </Modal>
    );
};
