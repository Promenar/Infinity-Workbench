/**
 * ChatSkeleton - 聊天界面骨架屏组件
 *
 * 模拟 2 轮对话气泡（用户右对齐 + AI 左对齐+头像占位），
 * 使用 Reanimated opacity 脉冲动画，配合 FadeOut 退场过渡。
 * 替代原有全屏 ActivityIndicator，降低感知延迟。
 */
import React, { useEffect } from 'react';
import { View, StyleSheet } from 'react-native';
import Animated, {
    useSharedValue,
    useAnimatedStyle,
    withRepeat,
    withTiming,
    FadeOut,
} from 'react-native-reanimated';

interface ChatSkeletonProps {
    isDark: boolean;
    agentColor?: string;
}

// 单个骨架条 - 带 shimmer 脉冲
const SkeletonBar: React.FC<{
    width: number | string;
    height: number;
    isDark: boolean;
    delay?: number;
    borderRadius?: number;
}> = ({ width, height, isDark, delay = 0, borderRadius = 8 }) => {
    const opacity = useSharedValue(0.3);

    useEffect(() => {
        // 延迟启动，制造错落感
        const timer = setTimeout(() => {
            opacity.value = withRepeat(
                withTiming(0.7, { duration: 1200 }),
                -1,
                true, // 自动反转
            );
        }, delay);
        return () => clearTimeout(timer);
    }, []);

    const animatedStyle = useAnimatedStyle(() => ({
        opacity: opacity.value,
    }));

    return (
        <Animated.View
            style={[
                {
                    width: width as any,
                    height,
                    borderRadius,
                    backgroundColor: isDark ? '#3f3f46' : '#e4e4e7',
                },
                animatedStyle,
            ]}
        />
    );
};

// AI 气泡骨架 - 左对齐，带头像占位
const AssistantBubbleSkeleton: React.FC<{
    isDark: boolean;
    agentColor?: string;
    delayBase?: number;
    lines?: number[];
}> = ({ isDark, agentColor, delayBase = 0, lines = [85, 70] }) => (
    <View style={styles.assistantRow}>
        {/* 头像占位 */}
        <View
            style={[
                styles.avatar,
                {
                    backgroundColor: agentColor
                        ? `${agentColor}30`
                        : isDark
                            ? '#3f3f46'
                            : '#e4e4e7',
                },
            ]}
        />
        {/* 内容区骨架 */}
        <View
            style={[
                styles.assistantBubble,
                {
                    backgroundColor: isDark
                        ? 'rgba(39, 39, 42, 0.4)'
                        : 'rgba(244, 244, 245, 0.6)',
                },
            ]}
        >
            {lines.map((widthPercent, i) => (
                <SkeletonBar
                    key={i}
                    width={`${widthPercent}%`}
                    height={12}
                    isDark={isDark}
                    delay={delayBase + i * 150}
                />
            ))}
        </View>
    </View>
);

// 用户气泡骨架 - 右对齐
const UserBubbleSkeleton: React.FC<{
    isDark: boolean;
    delayBase?: number;
    width?: number;
}> = ({ isDark, delayBase = 0, width = 55 }) => (
    <View style={styles.userRow}>
        <View
            style={[
                styles.userBubble,
                {
                    backgroundColor: isDark
                        ? 'rgba(39, 39, 42, 0.4)'
                        : 'rgba(244, 244, 245, 0.6)',
                },
            ]}
        >
            <SkeletonBar
                width={`${width}%`}
                height={12}
                isDark={isDark}
                delay={delayBase}
            />
        </View>
    </View>
);

/**
 * ChatSkeleton - 主组件
 * 模拟 2 轮对话，自底向上排列（与 inverted FlatList 视觉一致）
 */
export const ChatSkeleton: React.FC<ChatSkeletonProps> = ({
    isDark,
    agentColor,
}) => {
    return (
        <Animated.View
            exiting={FadeOut.duration(300)}
            style={[
                styles.container,
                { backgroundColor: isDark ? '#000' : '#fff' },
            ]}
        >
            {/* 自底向上布局：使用 justifyContent: flex-end */}
            <View style={styles.content}>
                {/* 第 1 轮：AI 回复 */}
                <AssistantBubbleSkeleton
                    isDark={isDark}
                    agentColor={agentColor}
                    delayBase={0}
                    lines={[90, 75, 50]}
                />

                {/* 第 1 轮：用户消息 */}
                <UserBubbleSkeleton isDark={isDark} delayBase={200} width={60} />

                {/* 第 2 轮：AI 回复 */}
                <AssistantBubbleSkeleton
                    isDark={isDark}
                    agentColor={agentColor}
                    delayBase={400}
                    lines={[85, 65]}
                />

                {/* 第 2 轮：用户消息 */}
                <UserBubbleSkeleton isDark={isDark} delayBase={550} width={45} />
            </View>
        </Animated.View>
    );
};

const styles = StyleSheet.create({
    container: {
        position: 'absolute',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        zIndex: 999,
    },
    content: {
        flex: 1,
        justifyContent: 'flex-end',
        paddingHorizontal: 16,
        paddingBottom: 100, // 为底部 ChatInput 留空间
        gap: 16,
    },
    // AI 气泡行
    assistantRow: {
        flexDirection: 'row',
        alignItems: 'flex-start',
        gap: 10,
    },
    avatar: {
        width: 28,
        height: 28,
        borderRadius: 14,
        marginTop: 2,
    },
    assistantBubble: {
        flex: 1,
        borderRadius: 16,
        borderTopLeftRadius: 4,
        padding: 16,
        gap: 10,
    },
    // 用户气泡行
    userRow: {
        flexDirection: 'row',
        justifyContent: 'flex-end',
    },
    userBubble: {
        maxWidth: '75%',
        minWidth: '40%',
        borderRadius: 16,
        borderTopRightRadius: 4,
        padding: 16,
        alignItems: 'flex-end',
    },
});
