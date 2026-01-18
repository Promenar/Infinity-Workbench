import React, { memo, useCallback } from 'react';
import { View, Text, TouchableOpacity, StyleSheet, ViewStyle } from 'react-native';
import { Server, Edit2, Trash2, Cpu } from 'lucide-react-native';
import { Card } from '../../../components/ui/Card';
import { Marquee } from '../../../components/ui/Marquee';
import { useTheme } from '../../../theme/ThemeProvider';
import { ProviderConfig } from '../../../store/api-store';
import * as Haptics from '../../../lib/haptics';
import { useI18n } from '../../../lib/i18n';
import { Colors } from '../../../theme/colors';

interface ProviderListProps {
    providers: ProviderConfig[];
    onEdit: (provider: ProviderConfig) => void;
    onDelete: (id: string) => void;
    onManageModels: (provider: ProviderConfig) => void;
    style?: ViewStyle;
}

/**
 * ProviderListItem
 * 
 * Memoized item to prevent re-renders of the entire list when only one item updates
 * or when parent state (like egg count) changes.
 */
const ProviderListItem = memo(({
    provider,
    onEdit,
    onDelete,
    onManageModels
}: {
    provider: ProviderConfig;
    onEdit: (p: ProviderConfig) => void;
    onDelete: (id: string) => void;
    onManageModels: (p: ProviderConfig) => void;
}) => {
    const { isDark, colors } = useTheme();
    const { t } = useI18n();

    const handleEdit = useCallback(() => {
        setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
            onEdit(provider);
        }, 10);
    }, [provider, onEdit]);

    const handleDelete = useCallback(() => {
        setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
            onDelete(provider.id);
        }, 10);
    }, [provider.id, onDelete]);

    const handleManageModels = useCallback(() => {
        setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
            onManageModels(provider);
        }, 10);
    }, [provider, onManageModels]);

    return (
        <Card variant="glass" style={styles.card}>
            <View style={styles.cardContent}>
                <View style={styles.headerRow}>
                    <View style={styles.providerInfo}>
                        <View style={[
                            styles.iconContainer,
                            { backgroundColor: isDark ? 'rgba(39, 39, 42, 0.6)' : 'rgba(0, 0, 0, 0.05)' }
                        ]}>
                            <Server size={20} color={colors[500]} />
                        </View>
                        <View style={styles.textContainer}>
                            <Marquee
                                text={provider.name}
                                className="font-bold text-[15px]"
                            />
                            <Text style={[
                                styles.urlText,
                                { color: isDark ? Colors.dark.textSecondary : '#666' }
                            ]}>
                                {provider.baseUrl}
                            </Text>
                        </View>
                    </View>

                    <View style={styles.actionsContainer}>
                        <TouchableOpacity onPress={handleEdit} style={styles.actionButton}>
                            <Edit2 size={18} color={colors[500]} />
                        </TouchableOpacity>
                        <TouchableOpacity onPress={handleDelete} style={styles.actionButton}>
                            <Trash2 size={18} color="#ef4444" />
                        </TouchableOpacity>
                    </View>
                </View>

                <TouchableOpacity
                    onPress={handleManageModels}
                    style={[
                        styles.modelButton,
                        { backgroundColor: isDark ? 'rgba(39, 39, 42, 0.6)' : 'rgba(0, 0, 0, 0.05)' }
                    ]}
                >
                    <Cpu size={14} color={colors[500]} style={styles.modelIcon} />
                    <Text style={[styles.modelButtonText, { color: colors[500] }]}>
                        {t.settings.modelSettings.title}
                    </Text>
                </TouchableOpacity>
            </View>
        </Card>
    );
});

/**
 * ProviderList
 * 
 * Performance-optimized list component.
 * Features:
 * - Memoized List Items
 * - No inline functions in render loop where possible
 * - Native Styles (No ClassName)
 */
export const ProviderList = memo(({
    providers,
    onEdit,
    onDelete,
    onManageModels,
    style
}: ProviderListProps) => {
    const { isDark } = useTheme();
    const { t } = useI18n();

    if (providers.length === 0) {
        return (
            <View style={styles.emptyContainer}>
                <Text style={[
                    styles.emptyTitle,
                    { color: isDark ? Colors.dark.textSecondary : '#9ca3af' }
                ]}>
                    {t?.settings?.noProviders || 'No Providers'}
                </Text>
                <Text style={[
                    styles.emptyDesc,
                    { color: isDark ? Colors.dark.textTertiary : '#d1d5db' }
                ]}>
                    {t?.settings?.noProvidersDesc || 'Add your first provider to get started'}
                </Text>
            </View>
        );
    }

    return (
        <View style={[styles.listContainer, style]}>
            {providers.map((provider) => (
                <ProviderListItem
                    key={provider.id}
                    provider={provider}
                    onEdit={onEdit}
                    onDelete={onDelete}
                    onManageModels={onManageModels}
                />
            ))}
        </View>
    );
});

const styles = StyleSheet.create({
    listContainer: {
        gap: 10, // Reduced from 16
    },
    card: {
        marginBottom: 2, // Reduced from 8
    },
    cardContent: {
        padding: 10, // Reduced from 12
    },
    headerRow: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        marginBottom: 8, // Significantly reduced from 16
    },
    providerInfo: {
        flexDirection: 'row',
        alignItems: 'center',
        flex: 1,
    },
    iconContainer: {
        width: 36, // Reduced from 40
        height: 36, // Reduced from 40
        borderRadius: 12, // More compact radius
        alignItems: 'center',
        justifyContent: 'center',
    },
    textContainer: {
        marginLeft: 10, // Reduced from 12
        flex: 1,
    },
    nameText: {
        fontSize: 15, // Slightly smaller from 16
        fontWeight: '700',
    },
    urlText: {
        fontSize: 11, // Reduced from 12
        marginTop: 0, // Tighter spacing
        opacity: 0.8,
    },
    actionsContainer: {
        flexDirection: 'row',
        gap: 4, // Reduced from 8
    },
    actionButton: {
        padding: 6, // Reduced from 8
    },
    modelButton: {
        flexDirection: 'row',
        alignItems: 'center',
        alignSelf: 'flex-start',
        marginTop: 0, // Remove top margin since headerRow has marginBottom
        paddingVertical: 6, // Reduced from 8
        paddingHorizontal: 12, // Reduced from 16
        borderRadius: 10, // Tighter radius
        borderWidth: 1, // Add border instead of heavy background
    },
    modelIcon: {
        marginRight: 4, // Reduced from 6
    },
    modelButtonText: {
        fontSize: 12, // Reduced from 13
        fontWeight: '700',
    },
    emptyContainer: {
        alignItems: 'center',
        paddingVertical: 60,
    },
    emptyTitle: {
        fontSize: 16,
        marginBottom: 8,
    },
    emptyDesc: {
        fontSize: 14,
    }
});
