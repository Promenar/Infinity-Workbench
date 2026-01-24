
import React, { useEffect } from 'react';
import { View, Text, TouchableOpacity, Dimensions, StyleSheet } from 'react-native';
import { useRouter } from 'expo-router';
import Svg, { Path, Text as SvgText, Defs, LinearGradient, Stop } from 'react-native-svg';
import Animated, {
    useSharedValue,
    useAnimatedProps,
    withTiming,
    withDelay,
    withSequence,
    withSpring,
    useAnimatedStyle,
    runOnJS,
    Easing,
    interpolate,
} from 'react-native-reanimated';
import { useSettingsStore } from '../src/store/settings-store';
import { Colors } from '../src/theme/colors';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import * as Haptics from 'expo-haptics';

const AnimatedSvgText = Animated.createAnimatedComponent(SvgText);

const { width, height } = Dimensions.get('window');

export default function WelcomeScreen() {
    const router = useRouter();
    const { setHasLaunched, setLanguage } = useSettingsStore();
    // const insets = useSafeAreaInsets(); // Not used currently

    // 动画状态
    const progress = useSharedValue(0); // 书写进度
    const layoutProgress = useSharedValue(0); // 布局变换进度 (0 -> 1)

    // 1. 启动书写动画
    useEffect(() => {
        progress.value = withTiming(1, {
            duration: 2500,
            easing: Easing.inOut(Easing.cubic),
        }, (finished) => {
            if (finished) {
                layoutProgress.value = withDelay(200, withSpring(1, {
                    damping: 20,
                    stiffness: 90,
                }));
            }
        });
    }, []);

    const handleLanguageSelect = (lang: 'zh' | 'en') => {
        // 交互反馈：先震动，延迟设置状态（防御性编程）
        setTimeout(() => {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
            setLanguage(lang);
            setHasLaunched(true);
            router.replace('/(tabs)/chat');
        }, 10);
    };

    // SVG 文字动画属性
    const animatedProps = useAnimatedProps(() => {
        const length = 1000; // 假设文字总路径长度
        return {
            strokeDashoffset: length - progress.value * length,
            strokeDasharray: [length, length],
            fillOpacity: progress.value >= 0.8 ? (progress.value - 0.8) * 5 : 0, // 最后淡入填充
        };
    });

    // 整体容器动画：缩小 + 上移
    const containerStyle = useAnimatedStyle(() => {
        return {
            transform: [
                { translateY: interpolate(layoutProgress.value, [0, 1], [0, -height * 0.15]) },
                { scale: interpolate(layoutProgress.value, [0, 1], [1, 0.8]) }, // 稍微调大最终比例
            ],
        };
    });

    // 语言选择器动画：淡入 + 上浮
    const optionsStyle = useAnimatedStyle(() => {
        return {
            opacity: layoutProgress.value,
            transform: [
                { translateY: interpolate(layoutProgress.value, [0, 1], [50, 0]) },
            ],
        };
    });

    return (
        <View className="flex-1 bg-background justify-center items-center">
            {/* 动画 Logo 区域 */}
            <Animated.View style={[styles.logoContainer, containerStyle]}>
                <Svg height="200" width="350" viewBox="0 0 350 200">
                    <AnimatedSvgText
                        x="50%"
                        y="50%"
                        textAnchor="middle"
                        alignmentBaseline="middle"
                        fontSize="80"
                        fontWeight="bold"
                        stroke="#000000"
                        strokeWidth="2"
                        fill="#000000"
                        animatedProps={animatedProps}
                    >
                        Nexara
                    </AnimatedSvgText>
                </Svg>
            </Animated.View>

            {/* 语言选择区域 */}
            <Animated.View style={[styles.optionsContainer, optionsStyle]}>
                <Text className="text-text-secondary text-lg mb-10 font-medium tracking-wide">
                    Select Language / 选择语言
                </Text>

                <View className="w-full px-12 gap-y-4">
                    <TouchableOpacity
                        activeOpacity={0.7}
                        onPress={() => handleLanguageSelect('zh')}
                        className="bg-surface-secondary w-full py-5 rounded-2xl items-center border border-border-default"
                    >
                        <Text className="text-text-primary text-lg font-bold">中文 (简体)</Text>
                    </TouchableOpacity>

                    <TouchableOpacity
                        activeOpacity={0.7}
                        onPress={() => handleLanguageSelect('en')}
                        className="bg-surface-secondary w-full py-5 rounded-2xl items-center border border-border-default"
                    >
                        <Text className="text-text-primary text-lg font-bold">English</Text>
                    </TouchableOpacity>
                </View>
            </Animated.View>
        </View>
    );
}

const styles = StyleSheet.create({
    logoContainer: {
        justifyContent: 'center',
        alignItems: 'center',
    },
    optionsContainer: {
        position: 'absolute',
        bottom: '15%',
        width: '100%',
        alignItems: 'center',
    },
    shadow: {
        shadowColor: "#000",
        shadowOffset: {
            width: 0,
            height: 2,
        },
        shadowOpacity: 0.1,
        shadowRadius: 3.84,
        elevation: 5,
    }
});
