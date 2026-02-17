import React, { useEffect, useState } from 'react';
import { View, LayoutChangeEvent, TextProps } from 'react-native';
import Animated, {
    useSharedValue,
    withTiming,
    withRepeat,
    withDelay,
    Easing,
    useAnimatedStyle,
    cancelAnimation,
} from 'react-native-reanimated';
import { Typography } from './Typography';

interface MarqueeProps {
    text: string;
    className?: string;
    duration?: number;
    delay?: number;
    style?: any;
    textProps?: TextProps;
}

export function Marquee({ text, className, duration = 3000, delay = 2000, style, textProps }: MarqueeProps) {
    const [containerWidth, setContainerWidth] = useState(0);
    const [textWidth, setTextWidth] = useState(0);
    const scrollValue = useSharedValue(0);

    const isLongText = textWidth > containerWidth && containerWidth > 0;
    const GAP = 80;

    useEffect(() => {
        scrollValue.value = 0;

        if (isLongText) {
            const scrollDistance = textWidth + GAP;
            const animationDuration = textWidth * 35;

            scrollValue.value = withDelay(
                delay,
                withRepeat(
                    withTiming(-scrollDistance, {
                        duration: animationDuration,
                        easing: Easing.linear,
                    }),
                    -1,
                    false
                )
            );
        }

        return () => {
            cancelAnimation(scrollValue);
        };
    }, [isLongText, textWidth, containerWidth, text, delay]);

    const animatedStyle = useAnimatedStyle(() => {
        'worklet';
        return {
            transform: [{ translateX: scrollValue.value }],
        };
    });

    return (
        <View
            onLayout={(e) => setContainerWidth(e.nativeEvent.layout.width)}
            style={[{ overflow: 'hidden', flex: 1, height: 22, justifyContent: 'center' }, style]}
        >
            {/* 隐藏的测量层 */}
            <View style={{ position: 'absolute', opacity: 0, width: 5000, flexDirection: 'row', top: -1000 }} pointerEvents="none">
                <Typography
                    className={className}
                    onLayout={(e) => {
                        if (e.nativeEvent.layout.width > 0) {
                            setTextWidth(e.nativeEvent.layout.width);
                        }
                    }}
                    numberOfLines={0}
                    style={[{ flexShrink: 0 }, textProps?.style]}
                    {...textProps}
                >
                    {text}
                </Typography>
            </View>

            {/* 可见的渲染层 */}
            <Animated.View
                style={[
                    {
                        flexDirection: 'row',
                        width: isLongText ? (textWidth + GAP) * 2 : '100%',
                    },
                    animatedStyle,
                ]}
            >
                <Typography
                    className={className}
                    numberOfLines={isLongText ? 0 : 1}
                    ellipsizeMode={isLongText ? 'clip' : 'tail'}
                    style={[{ flexShrink: 0 }, textProps?.style]}
                    {...textProps}
                >
                    {text}
                </Typography>

                {isLongText && (
                    <>
                        <View style={{ width: GAP }} />
                        <Typography
                            className={className}
                            numberOfLines={0}
                            ellipsizeMode="clip"
                            style={[{ flexShrink: 0 }, textProps?.style]}
                            {...textProps}
                        >
                            {text}
                        </Typography>
                    </>
                )}
            </Animated.View>
        </View>
    );
}
