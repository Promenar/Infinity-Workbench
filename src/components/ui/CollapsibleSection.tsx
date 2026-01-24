import React, { useState } from 'react';
import { View, TouchableOpacity, StyleSheet, LayoutChangeEvent, Text } from 'react-native';
import Animated, {
    useAnimatedStyle,
    useSharedValue,
    withSpring,
    withTiming,
    interpolate,
    useDerivedValue,
    runOnUI,
    measure,
    useAnimatedRef,
} from 'react-native-reanimated';
import { ChevronDown } from 'lucide-react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { Colors } from '../../theme/colors';
import * as Haptics from '../../lib/haptics';

interface CollapsibleSectionProps {
    title: string;
    children: React.ReactNode;
    icon?: React.ReactNode;
    defaultExpanded?: boolean;
    containerStyle?: any;
}

export const CollapsibleSection: React.FC<CollapsibleSectionProps> = ({
    title,
    children,
    icon,
    defaultExpanded = false,
    containerStyle,
}) => {
    const { isDark, colors } = useTheme();
    const [expanded, setExpanded] = useState(defaultExpanded);
    const [bodyHeight, setBodyHeight] = useState(0);

    // Animation values
    const open = useSharedValue(defaultExpanded ? 1 : 0);

    // ⚡ Faster, smooth timing-based animations (no bounce)
    const rotation = useDerivedValue(() => withTiming(open.value * 180, { duration: 250 }));

    const toggle = () => {
        const nextState = !expanded;
        setExpanded(nextState);

        // Add light haptics for consistency with other settings
        setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        }, 10);

        open.value = withTiming(nextState ? 1 : 0, { duration: 250 });
    };

    const animatedBodyStyle = useAnimatedStyle(() => {
        return {
            height: bodyHeight > 0
                ? withTiming(open.value * bodyHeight, { duration: 250 })
                : undefined,
            opacity: withTiming(open.value, { duration: 250 }),
        };
    });

    const animatedChevronStyle = useAnimatedStyle(() => ({
        transform: [{ rotate: `${rotation.value}deg` }],
    }));

    return (
        <View style={[styles.container, containerStyle]}>
            <TouchableOpacity
                onPress={toggle}
                activeOpacity={0.7}
                style={[
                    styles.header,
                    {
                        borderBottomColor: isDark ? 'rgba(99, 102, 241, 0.1)' : 'rgba(0, 0, 0, 0.05)',
                        backgroundColor: isDark ? 'rgba(255,255,255,0.02)' : 'rgba(0,0,0,0.02)',
                        borderBottomWidth: expanded ? 0 : 1,
                    }
                ]}
            >
                <View style={styles.headerLeft}>
                    {icon && <View style={styles.iconContainer}>{icon}</View>}
                    <Text
                        style={{
                            fontSize: 15,
                            fontWeight: '600',
                            letterSpacing: -0.2,
                            color: isDark ? Colors.dark.textPrimary : Colors.light.textPrimary,
                        }}
                    >
                        {title}
                    </Text>
                </View>
                <Animated.View style={animatedChevronStyle}>
                    <ChevronDown size={18} color={isDark ? Colors.dark.textTertiary : Colors.light.textTertiary} />
                </Animated.View>
            </TouchableOpacity>

            <Animated.View style={[styles.bodyContainer, animatedBodyStyle, { overflow: 'hidden' }]}>
                <View onLayout={(e) => setBodyHeight(e.nativeEvent.layout.height)} style={styles.innerBody}>
                    {children}
                </View>
            </Animated.View>
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        overflow: 'hidden',
    },
    header: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        paddingHorizontal: 16,
        paddingVertical: 11,
        minHeight: 44,
    },
    headerLeft: {
        flexDirection: 'row',
        alignItems: 'center',
    },
    iconContainer: {
        width: 22,
        alignItems: 'center',
        justifyContent: 'center',
        marginRight: 14,
    },
    bodyContainer: {
        // Height animated
    },
    innerBody: {
        position: 'absolute',
        width: '100%',
        top: 0,
    }
});

// Fix: The absolute positioning of innerBody might prevent it from taking space if we rely on flow.
// Correct "Accordion" pattern with Reanimated:
// 1. Render content in a wrapper. 
// 2. Wrapper A (Outer) `overflow: hidden`, `height: animated`.
// 3. Wrapper B (Inner) `position: absolute` so it doesn't constrain height? No, Wrapper B should retain height so we can measure it.
// Actually, if Inner is not absolute, it pushes Outer to expand. We want to FORCE Outer height.
// So Inner needs to be `position: absolute` so it doesn't influence layout, BUT we need its height.
// Yes, `position: absolute` + `onLayout` is the way. 
