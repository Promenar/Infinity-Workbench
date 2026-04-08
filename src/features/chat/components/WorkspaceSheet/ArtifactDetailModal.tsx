/**
 * ArtifactDetailModal组件
 * 全屏展示Artifact详情，支持渲染各类内容
 */

import React, { useCallback, useState, useEffect } from 'react';
import {
    View,
    Modal,
    StyleSheet,
    ScrollView,
    TouchableOpacity,
    Dimensions,
    Share,
} from 'react-native';
import Animated, { FadeIn, FadeOut, SlideInDown, SlideOutDown } from 'react-native-reanimated';
import * as Haptics from 'expo-haptics';
import {
    X,
    Share2,
    ExternalLink,
    Copy,
    ChevronLeft,
    Clock,
    MessageSquare,
    Tag,
} from 'lucide-react-native';
import { useTheme } from '../../../../theme/ThemeProvider';
import { Typography } from '../../../../components/ui/Typography';
import { Spacing } from '../../../../theme/glass';
import { Artifact } from '../../../../types/artifact';
import { ARTIFACT_TYPE_INFO, getArtifactTypeColor, getArtifactTypeLabel } from '../../../../constants/artifact-config';
import { EChartsRenderer } from '../../../../components/chat/EChartsRenderer';
import { MermaidRenderer } from '../../../../components/chat/MermaidRenderer';
import { MathRenderer } from '../../../../components/chat/MathRenderer';

const { width: SCREEN_WIDTH, height: SCREEN_HEIGHT } = Dimensions.get('window');

interface ArtifactDetailModalProps {
    visible: boolean;
    artifact: Artifact | null;
    onClose: () => void;
    onNavigateToMessage?: (sessionId: string, messageId: string) => void;
}

/**
 * 格式化时间戳
 */
function formatDateTime(timestamp: number): string {
    const date = new Date(timestamp);
    return date.toLocaleString('zh-CN', {
        year: 'numeric',
        month: 'long',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
    });
}

export const ArtifactDetailModal: React.FC<ArtifactDetailModalProps> = ({
    visible,
    artifact,
    onClose,
    onNavigateToMessage,
}) => {
    const { isDark, colors } = useTheme();
    const [copied, setCopied] = useState(false);

    // 重置状态
    useEffect(() => {
        if (!visible) {
            setCopied(false);
        }
    }, [visible]);

    if (!artifact) return null;

    const typeColor = getArtifactTypeColor(artifact.type);
    const typeLabel = getArtifactTypeLabel(artifact.type);

    // 分享
    const handleShare = useCallback(async () => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        try {
            await Share.share({
                message: artifact.content,
                title: artifact.title,
            });
        } catch (e) {
            console.error('[ArtifactDetailModal] Share error:', e);
        }
    }, [artifact]);

    // 复制内容
    const handleCopy = useCallback(() => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        // Clipboard.setString(artifact.content);
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
    }, [artifact]);

    // 跳转到来源消息
    const handleNavigateToMessage = useCallback(() => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        onNavigateToMessage?.(artifact.sessionId, artifact.messageId);
        onClose();
    }, [artifact, onNavigateToMessage, onClose]);

    // 渲染内容
    const renderContent = () => {
        switch (artifact.type) {
            case 'echarts':
                return (
                    <View style={styles.chartContainer}>
                        <EChartsRenderer content={artifact.content} />
                    </View>
                );
            case 'mermaid':
                return (
                    <View style={styles.chartContainer}>
                        <MermaidRenderer content={artifact.content} />
                    </View>
                );
            case 'math':
                return (
                    <View style={styles.contentContainer}>
                        <MathRenderer content={artifact.content} />
                    </View>
                );
            case 'html':
            case 'svg':
                // HTML和SVG使用WebView渲染
                return (
                    <View style={styles.contentContainer}>
                        <Typography style={{ fontSize: 13, color: isDark ? '#a1a1aa' : '#6b7280' }}>
                            HTML/SVG 内容预览需要WebView支持
                        </Typography>
                    </View>
                );
            default:
                return (
                    <View style={styles.contentContainer}>
                        <Typography style={{ fontSize: 14, color: isDark ? '#e4e4e7' : '#27272a' }}>
                            {artifact.content}
                        </Typography>
                    </View>
                );
        }
    };

    return (
        <Modal
            visible={visible}
            transparent
            animationType="fade"
            onRequestClose={onClose}
            statusBarTranslucent
        >
            <View style={styles.modalOverlay}>
                <Animated.View
                    entering={SlideInDown.duration(250)}
                    exiting={SlideOutDown.duration(200)}
                    style={[
                        styles.modalContainer,
                        { backgroundColor: isDark ? '#18181b' : '#ffffff' }
                    ]}
                >
                    {/* 头部 */}
                    <View style={[
                        styles.header,
                        { borderBottomColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)' }
                    ]}>
                        <TouchableOpacity
                            style={styles.backButton}
                            onPress={onClose}
                            activeOpacity={0.7}
                        >
                            <ChevronLeft size={20} color={isDark ? '#a1a1aa' : '#6b7280'} />
                            <Typography style={{ fontSize: 14, color: isDark ? '#a1a1aa' : '#6b7280' }}>
                                返回
                            </Typography>
                        </TouchableOpacity>

                        <View style={styles.headerActions}>
                            <TouchableOpacity
                                style={styles.actionButton}
                                onPress={handleCopy}
                                activeOpacity={0.7}
                            >
                                <Copy size={18} color={isDark ? '#a1a1aa' : '#6b7280'} />
                            </TouchableOpacity>
                            <TouchableOpacity
                                style={styles.actionButton}
                                onPress={handleShare}
                                activeOpacity={0.7}
                            >
                                <Share2 size={18} color={isDark ? '#a1a1aa' : '#6b7280'} />
                            </TouchableOpacity>
                        </View>
                    </View>

                    {/* 标题区 */}
                    <View style={styles.titleSection}>
                        <View style={[styles.typeBadge, { backgroundColor: typeColor + '15' }]}>
                            <Typography style={{ fontSize: 11, color: typeColor, fontWeight: '600' }}>
                                {typeLabel}
                            </Typography>
                        </View>
                        <Typography style={{
                            fontSize: 18,
                            fontWeight: '600',
                            color: isDark ? '#fff' : '#111',
                            marginTop: Spacing[2],
                        }}>
                            {artifact.title}
                        </Typography>
                    </View>

                    {/* 内容区 */}
                    <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>
                        {renderContent()}

                        {/* 元数据 */}
                        <View style={[
                            styles.metaSection,
                            { borderTopColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)' }
                        ]}>
                            <Typography style={{
                                fontSize: 12,
                                fontWeight: '600',
                                color: isDark ? '#71717a' : '#9ca3af',
                                marginBottom: Spacing[3],
                            }}>
                                详细信息
                            </Typography>

                            {/* 创建时间 */}
                            <View style={styles.metaItem}>
                                <Clock size={14} color={isDark ? '#71717a' : '#9ca3af'} />
                                <Typography style={{ fontSize: 13, color: isDark ? '#a1a1aa' : '#6b7280', marginLeft: 8 }}>
                                    创建于 {formatDateTime(artifact.createdAt)}
                                </Typography>
                            </View>

                            {/* 来源消息 */}
                            {onNavigateToMessage && (
                                <TouchableOpacity
                                    style={styles.metaItem}
                                    onPress={handleNavigateToMessage}
                                    activeOpacity={0.7}
                                >
                                    <MessageSquare size={14} color={colors[500]} />
                                    <Typography style={{ fontSize: 13, color: colors[500], marginLeft: 8 }}>
                                        跳转到来源消息
                                    </Typography>
                                </TouchableOpacity>
                            )}

                            {/* 标签 */}
                            {artifact.tags && artifact.tags.length > 0 && (
                                <View style={styles.tagsContainer}>
                                    <Tag size={14} color={isDark ? '#71717a' : '#9ca3af'} />
                                    <View style={styles.tagsList}>
                                        {artifact.tags.map((tag, index) => (
                                            <View
                                                key={index}
                                                style={[
                                                    styles.tag,
                                                    { backgroundColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.03)' }
                                                ]}
                                            >
                                                <Typography style={{ fontSize: 11, color: isDark ? '#a1a1aa' : '#6b7280' }}>
                                                    {tag}
                                                </Typography>
                                            </View>
                                        ))}
                                    </View>
                                </View>
                            )}
                        </View>
                    </ScrollView>
                </Animated.View>
            </View>
        </Modal>
    );
};

const styles = StyleSheet.create({
    modalOverlay: {
        flex: 1,
        backgroundColor: 'rgba(0,0,0,0.5)',
        justifyContent: 'flex-end',
    },
    modalContainer: {
        height: SCREEN_HEIGHT * 0.9,
        borderTopLeftRadius: 24,
        borderTopRightRadius: 24,
    },
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingHorizontal: Spacing[4],
        paddingVertical: Spacing[3],
        borderBottomWidth: 1,
    },
    backButton: {
        flexDirection: 'row',
        alignItems: 'center',
    },
    headerActions: {
        flexDirection: 'row',
        gap: Spacing[2],
    },
    actionButton: {
        width: 36,
        height: 36,
        borderRadius: 18,
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: 'rgba(99, 102, 241, 0.1)',
    },
    titleSection: {
        paddingHorizontal: Spacing[4],
        paddingTop: Spacing[2],
        paddingBottom: Spacing[3],
    },
    typeBadge: {
        paddingHorizontal: 10,
        paddingVertical: 4,
        borderRadius: 8,
        alignSelf: 'flex-start',
    },
    content: {
        flex: 1,
    },
    contentContainer: {
        padding: Spacing[4],
    },
    chartContainer: {
        padding: Spacing[4],
        minHeight: 300,
    },
    metaSection: {
        padding: Spacing[4],
        borderTopWidth: 1,
        marginTop: Spacing[4],
    },
    metaItem: {
        flexDirection: 'row',
        alignItems: 'center',
        marginBottom: Spacing[3],
    },
    tagsContainer: {
        flexDirection: 'row',
        alignItems: 'flex-start',
        marginTop: Spacing[1],
    },
    tagsList: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        marginLeft: 8,
        gap: Spacing[2],
    },
    tag: {
        paddingHorizontal: 10,
        paddingVertical: 4,
        borderRadius: 8,
    },
});
