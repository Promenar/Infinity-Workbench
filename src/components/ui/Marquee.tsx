import React, { useEffect, useRef, useState } from 'react';
import { View, ScrollView, Animated, Easing, LayoutChangeEvent, TextProps } from 'react-native';
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
    const scrollValue = useRef(new Animated.Value(0)).current;

    const isLongText = textWidth > containerWidth && containerWidth > 0;
    const GAP = 80; // 文本循环之间的间距

    useEffect(() => {
        scrollValue.setValue(0);

        if (isLongText) {
            // 滚动一个完整的“文本+间距”周期
            const scrollDistance = textWidth + GAP;

            const animation = Animated.loop(
                Animated.timing(scrollValue, {
                    toValue: -scrollDistance,
                    duration: textWidth * 35, // 保持恒定速度，而不是恒定时间
                    easing: Easing.linear,
                    useNativeDriver: true,
                })
            );

            // 首次执行前稍微延迟一下，给用户时间阅读开头
            const timer = setTimeout(() => {
                animation.start();
            }, delay);

            return () => {
                clearTimeout(timer);
                animation.stop();
            };
        }
    }, [isLongText, textWidth, containerWidth, text, delay]);

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
                style={{
                    flexDirection: 'row',
                    transform: [{ translateX: scrollValue }],
                    // 渲染两个重复的文本块以实现无缝对接
                    width: isLongText ? (textWidth + GAP) * 2 : '100%',
                }}
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
