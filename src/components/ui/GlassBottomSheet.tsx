import React, { useEffect } from 'react';
import { View, Text, TouchableOpacity, Modal, StyleSheet, Dimensions, DimensionValue } from 'react-native';
import { BlurView } from 'expo-blur';
import { X } from 'lucide-react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { Glass } from '../../theme/glass';
import Animated, {
    FadeIn,
    FadeOut,
    SlideInDown,
    SlideOutDown,
    Easing,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

const { height: SCREEN_HEIGHT } = Dimensions.get('window');

interface GlassBottomSheetProps {
    visible: boolean;
    onClose: () => void;
    title: string;
    subtitle?: string;
    children: React.ReactNode;
    height?: DimensionValue;
}

export const GlassBottomSheet: React.FC<GlassBottomSheetProps> = ({
    visible,
    onClose,
    title,
    subtitle,
    children,
    height = '75%',
}) => {
    const { isDark } = useTheme();
    const insets = useSafeAreaInsets();

    // Reset logic if needed when visible changes? 
    // Usually declarative is enough.

    return (
        <Modal visible={visible} animationType="none" transparent={true} onRequestClose={onClose}>
            <View
                style={{ flex: 1, justifyContent: 'flex-end' }}
            >
                <Animated.View
                    entering={FadeIn.duration(200)}
                    exiting={FadeOut.duration(150)}
                    style={{
                        position: 'absolute',
                        top: 0,
                        left: 0,
                        right: 0,
                        bottom: 0,
                        backgroundColor: isDark ? 'rgba(0,0,0,0.6)' : 'rgba(0,0,0,0.3)',
                    }}
                >
                    <TouchableOpacity activeOpacity={1} style={{ flex: 1 }} onPress={onClose} />
                </Animated.View>

                <Animated.View
                    entering={SlideInDown.duration(280).easing(Easing.out(Easing.quad))}
                    exiting={SlideOutDown.duration(200).easing(Easing.in(Easing.quad))}
                    style={{
                        marginHorizontal: 12,
                        marginBottom: Math.max(insets.bottom, 16),
                        height: height === 'auto' ? undefined : height,
                        minHeight: height === 'auto' ? 100 : undefined,


                        backgroundColor: 'transparent', // ✅ Changed: Removed specific background color
                        borderRadius: 32,
                        overflow: 'hidden',
                        borderWidth: 1,
                        borderColor: isDark ? 'rgba(255, 255, 255, 0.12)' : 'rgba(0, 0, 0, 0.08)',
                        shadowColor: '#000',
                        shadowOffset: { width: 0, height: 10 },
                        shadowOpacity: 0.2,
                        shadowRadius: 20,
                        elevation: 10,
                    }}
                >
                    <BlurView
                        intensity={isDark ? Glass.Header.intensity : Glass.Header.intensity} // ✅ Changed: Use Header intensity (stronger)
                        style={{ flex: height === 'auto' ? undefined : 1, paddingTop: 24 }}
                        tint={isDark ? Glass.Header.tint.dark : Glass.Header.tint.light} // ✅ Changed: Use Header tint
                        experimentalBlurMethod='dimezisBlurView'
                    >
                        {/* ✅ Added: Internal Overlay for consistent "frosted" look */}
                        <View
                            style={{
                                ...StyleSheet.absoluteFillObject,
                                backgroundColor: isDark
                                    ? `rgba(0, 0, 0, ${Glass.Header.opacity.dark})`
                                    : `rgba(255, 255, 255, ${Glass.Header.opacity.light})`,
                            }}
                        />

                        <View
                            style={{
                                flexDirection: 'row',
                                alignItems: 'center',
                                justifyContent: 'space-between',
                                paddingHorizontal: 24,
                                marginBottom: 20,
                            }}
                        >
                            <View>
                                <Text
                                    style={{
                                        fontSize: 22,
                                        fontWeight: '900',
                                        color: isDark ? '#fff' : '#111',
                                        letterSpacing: -0.8,
                                    }}
                                >
                                    {title}
                                </Text>
                                {subtitle && (
                                    <Text
                                        style={{
                                            fontSize: 13,
                                            color: isDark ? '#a1a1aa' : '#71717a',
                                            marginTop: 2,
                                            fontWeight: '500',
                                        }}
                                    >
                                        {subtitle}
                                    </Text>
                                )}
                            </View>
                            <TouchableOpacity
                                onPress={onClose}
                                style={{
                                    width: 36,
                                    height: 36,
                                    alignItems: 'center',
                                    justifyContent: 'center',
                                    backgroundColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.08)',
                                    borderRadius: 18,
                                }}
                            >
                                <X size={18} color={isDark ? '#fff' : '#000'} />
                            </TouchableOpacity>
                        </View>

                        <View style={{ flex: height === 'auto' ? undefined : 1 }}>{children}</View>
                    </BlurView>
                </Animated.View>
            </View>
        </Modal>
    );
};
