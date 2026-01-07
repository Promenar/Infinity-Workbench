import React from 'react';
import { View, } from 'react-native';
import { Network } from 'lucide-react-native';
import Animated, {
    useSharedValue,
    useAnimatedStyle,
    withRepeat,
    withTiming,
    Easing,
    ZoomIn,
    ZoomOut
} from 'react-native-reanimated';
import { Typography } from '../ui/Typography';
import { useI18n } from '../../lib/i18n';

interface KGExtractionIndicatorProps {
    isExtracting: boolean;
}

export const KGExtractionIndicator = React.memo(({ isExtracting }: KGExtractionIndicatorProps) => {
    const { t } = useI18n();

    // Animations
    const glowScale = useSharedValue(1);
    const glowOpacity = useSharedValue(0.5);

    React.useEffect(() => {
        if (isExtracting) {
            // Breathing animation
            glowScale.value = withRepeat(
                withTiming(1.6, { duration: 1200, easing: Easing.inOut(Easing.ease) }),
                -1,
                true,
            );
            glowOpacity.value = withRepeat(
                withTiming(0.1, { duration: 1200, easing: Easing.inOut(Easing.ease) }),
                -1,
                true,
            );
        } else {
            glowScale.value = 1;
            glowOpacity.value = 0;
        }
    }, [isExtracting]);

    const glowStyle = useAnimatedStyle(() => ({
        transform: [{ scale: glowScale.value }],
        opacity: glowOpacity.value,
    }));

    if (!isExtracting) return null;

    const accentColor = "#6366f1"; // Indigo-500

    return (
        <Animated.View
            entering={ZoomIn.duration(300)}
            exiting={ZoomOut.duration(300)}
            style={{
                flexDirection: 'row',
                alignItems: 'center',
                paddingHorizontal: 6,
                paddingVertical: 2,
                borderRadius: 10,
                backgroundColor: 'rgba(99, 102, 241, 0.05)',
                // marginRight: 6 // Let parent handle gap
            }}
        >
            <View style={{ position: 'relative', alignItems: 'center', justifyContent: 'center' }}>
                <Animated.View
                    style={[
                        glowStyle,
                        {
                            position: 'absolute',
                            width: 10, // Match icon size
                            height: 10,
                            borderRadius: 5,
                            backgroundColor: accentColor,
                        }
                    ]}
                />
                <Network size={10} color={accentColor} />
            </View>
            <Typography className="font-black ml-1 text-[9px] uppercase tracking-tighter" style={{ color: accentColor }}>
                GRAPHING...
            </Typography>
        </Animated.View>
    );
});
