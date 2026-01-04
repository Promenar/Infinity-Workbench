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
    const rotation = useSharedValue(0);

    React.useEffect(() => {
        if (isExtracting) {
            rotation.value = withRepeat(
                withTiming(360, { duration: 2000, easing: Easing.linear }),
                -1,
                false
            );
        } else {
            rotation.value = 0;
        }
    }, [isExtracting]);

    const animatedStyle = useAnimatedStyle(() => {
        return {
            transform: [{ rotate: `${rotation.value}deg` }],
        };
    });

    if (!isExtracting) return null;

    return (
        <Animated.View
            entering={ZoomIn.duration(300)}
            exiting={ZoomOut.duration(300)}
            style={{
                flexDirection: 'row',
                alignItems: 'center',
                paddingHorizontal: 8,
                paddingVertical: 2,
                borderRadius: 10,
                backgroundColor: 'rgba(0,0,0,0.03)',
                marginRight: 6
            }}
        >
            <Animated.View style={[animatedStyle, { marginRight: 6 }]}>
                <Network size={12} color="#6366f1" />
            </Animated.View>
            <Typography variant="caption" className="text-indigo-600 dark:text-indigo-300 font-medium text-[10px]">
                更新图谱中
            </Typography>
        </Animated.View>
    );
});
