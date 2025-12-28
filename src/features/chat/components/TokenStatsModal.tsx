import React from 'react';
import { View, Modal, TouchableOpacity, StyleSheet } from 'react-native';
import { BlurView } from 'expo-blur';
import Animated, { FadeIn, FadeOut, SlideInDown, SlideOutDown, Easing } from 'react-native-reanimated';
import { X, Calculator, Zap, Database } from 'lucide-react-native';
import { Typography } from '../../../components/ui/Typography';
import { useTheme } from '../../../theme/ThemeProvider';
import { TokenUsage } from '../../../types/chat';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

interface TokenStatsModalProps {
    visible: boolean;
    onClose: () => void;
    stats: {
        sessionTotal: number;
        lastMessage?: TokenUsage;
    };
}

export const TokenStatsModal: React.FC<TokenStatsModalProps> = ({
    visible,
    onClose,
    stats
}) => {
    const { isDark } = useTheme();
    const insets = useSafeAreaInsets();

    // Total percentage for visualization (example: output usually has higher cost/weight)
    const inTokens = stats.lastMessage?.input || 0;
    const outTokens = stats.lastMessage?.output || 0;
    const totalCurrent = inTokens + outTokens;
    const outPercentage = totalCurrent > 0 ? (outTokens / totalCurrent) * 100 : 0;

    if (!visible) return null;

    return (
        <Modal
            visible={visible}
            transparent
            animationType="none"
            onRequestClose={onClose}
        >
            <View style={[styles.overlay, { paddingBottom: Math.max(insets.bottom, 20) }]}>
                <Animated.View
                    entering={FadeIn.duration(300)}
                    exiting={FadeOut.duration(200)}
                    style={[styles.backdrop, { backgroundColor: 'rgba(0,0,0,0.4)' }]}
                >
                    <TouchableOpacity style={styles.fill} onPress={onClose} activeOpacity={1} />
                </Animated.View>

                <Animated.View
                    entering={SlideInDown.springify().damping(28).stiffness(160)}
                    exiting={SlideOutDown.duration(200)}
                    style={[
                        styles.floatContainer,
                        {
                            backgroundColor: isDark ? 'rgba(24, 24, 27, 0.92)' : 'rgba(255, 255, 255, 0.92)',
                            borderColor: isDark ? 'rgba(255, 255, 255, 0.12)' : 'rgba(0, 0, 0, 0.08)',
                        }
                    ]}
                >
                    <BlurView
                        intensity={isDark ? 30 : 50}
                        tint={isDark ? 'dark' : 'light'}
                        style={styles.blurContent}
                    >
                        <View style={styles.header}>
                            <View>
                                <Typography className="text-2xl font-black" style={{ letterSpacing: -1.5 }}>Token Usage</Typography>
                                <Typography className="text-[10px] text-gray-500 font-bold uppercase tracking-[2px] mt-0.5">Real-time Estimation</Typography>
                            </View>
                            <TouchableOpacity onPress={onClose} style={styles.closeBtn}>
                                <X size={16} color={isDark ? '#fff' : '#000'} />
                            </TouchableOpacity>
                        </View>

                        <View style={styles.visualSection}>
                            <View style={styles.circleContainer}>
                                <View style={[styles.outerCircle, { borderColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.05)' }]}>
                                    <View style={[styles.innerCircle, { backgroundColor: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)' }]}>
                                        <Typography className="text-3xl font-black" style={{ color: isDark ? '#fff' : '#000' }}>{stats.sessionTotal.toLocaleString()}</Typography>
                                        <Typography className="text-[10px] text-gray-400 font-bold mt-1">SESSION TOTAL</Typography>
                                    </View>
                                    <View style={[styles.dot, { backgroundColor: '#8b5cf6', top: '15%', right: '15%' }]} />
                                    <View style={[styles.dot, { backgroundColor: '#f59e0b', bottom: '20%', left: '10%', width: 6, height: 6 }]} />
                                </View>
                            </View>
                        </View>

                        <View style={styles.grid}>
                            <View style={[styles.card, { backgroundColor: isDark ? 'rgba(139, 92, 246, 0.08)' : 'rgba(139, 92, 246, 0.04)' }]}>
                                <Typography className="text-[10px] font-black text-violet-500 uppercase mb-1">Inbound</Typography>
                                <Typography className="text-xl font-black" style={{ color: isDark ? '#fff' : '#111' }}>{inTokens.toLocaleString()}</Typography>
                                <View style={styles.barContainer}>
                                    <View style={[styles.bar, { width: '100%', backgroundColor: '#8b5cf6' }]} />
                                </View>
                            </View>

                            <View style={[styles.card, { backgroundColor: isDark ? 'rgba(245, 158, 11, 0.08)' : 'rgba(245, 158, 11, 0.04)' }]}>
                                <Typography className="text-[10px] font-black text-amber-500 uppercase mb-1">Outbound</Typography>
                                <Typography className="text-xl font-black" style={{ color: isDark ? '#fff' : '#111' }}>{outTokens.toLocaleString()}</Typography>
                                <View style={styles.barContainer}>
                                    <View style={[styles.bar, { width: `${outPercentage}%`, backgroundColor: '#f59e0b' }]} />
                                </View>
                            </View>
                        </View>

                        <View style={styles.footerContainer}>
                            <Typography className="text-[10px] text-gray-500 text-center leading-4">
                                1k tokens ≈ 750 words.{'\n'}Estimation based on model specific pricing.
                            </Typography>
                        </View>
                    </BlurView>
                </Animated.View>
            </View>
        </Modal>
    );
};

const styles = StyleSheet.create({
    overlay: {
        flex: 1,
        justifyContent: 'flex-end',
    },
    backdrop: {
        ...StyleSheet.absoluteFillObject,
        backgroundColor: 'rgba(0,0,0,0.5)',
    },
    fill: {
        flex: 1,
    },
    floatContainer: {
        marginHorizontal: 12,
        borderRadius: 32,
        overflow: 'hidden',
        borderWidth: 1,
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 10 },
        shadowOpacity: 0.2,
        shadowRadius: 20,
        elevation: 10,
    },
    blurContent: {
        padding: 24,
    },
    header: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 20,
    },
    closeBtn: {
        width: 32,
        height: 32,
        borderRadius: 16,
        backgroundColor: 'rgba(255,255,255,0.1)',
        alignItems: 'center',
        justifyContent: 'center',
    },
    visualSection: {
        alignItems: 'center',
        marginBottom: 24,
    },
    circleContainer: {
        width: 140,
        height: 140,
        alignItems: 'center',
        justifyContent: 'center',
    },
    outerCircle: {
        width: 130,
        height: 130,
        borderRadius: 65,
        borderWidth: 3,
        alignItems: 'center',
        justifyContent: 'center',
        borderStyle: 'dashed',
    },
    innerCircle: {
        width: 100,
        height: 100,
        borderRadius: 50,
        alignItems: 'center',
        justifyContent: 'center',
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 4 },
        shadowOpacity: 0.1,
        shadowRadius: 8,
        elevation: 2,
    },
    dot: {
        position: 'absolute',
        width: 8,
        height: 8,
        borderRadius: 4,
    },
    grid: {
        flexDirection: 'row',
        gap: 12,
        marginBottom: 16,
    },
    card: {
        flex: 1,
        padding: 16,
        borderRadius: 20,
    },
    barContainer: {
        height: 3,
        backgroundColor: 'rgba(0,0,0,0.05)',
        borderRadius: 2,
        marginTop: 10,
        overflow: 'hidden',
    },
    bar: {
        height: '100%',
        borderRadius: 2,
    },
    footerContainer: {
        padding: 12,
        borderRadius: 12,
        marginTop: 8,
    }
});
