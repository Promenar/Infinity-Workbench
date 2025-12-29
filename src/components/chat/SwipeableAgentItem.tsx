import React from 'react';
import { View, Animated, StyleSheet, TouchableOpacity } from 'react-native';
import { Swipeable } from 'react-native-gesture-handler';
import { Agent } from '../../types/chat';
import { Typography } from '../ui/Typography';
import { Pin, Trash2, ChevronRight } from 'lucide-react-native';
import * as Haptics from '../../lib/haptics';
import { AgentAvatar } from './AgentAvatar';
import { useChatStore } from '../../store/chat-store';

interface SwipeableAgentItemProps {
    item: Agent;
    onPress: () => void;
    onPin: () => void;
    onDelete: () => void;
    isDark?: boolean;
}

export const SwipeableAgentItem = ({
    item,
    onPress,
    onPin,
    onDelete,
    isDark
}: SwipeableAgentItemProps) => {
    const swipeableRef = React.useRef<Swipeable>(null);

    const isGenerating = useChatStore(state => {
        return state.sessions.some(s => s.agentId === item.id && !!state.activeRequests[s.id]);
    });

    const renderRightActions = (progress: any, dragX: any) => {
        const trans = dragX.interpolate({
            inputRange: [-80, 0],
            outputRange: [0, 80],
            extrapolate: 'clamp',
        });
        return (
            <View style={styles.rightActionContainer}>
                <Animated.View style={[styles.rightAction, { transform: [{ translateX: trans }] }]}>
                    <TouchableOpacity onPress={() => {
                        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Heavy);
                        swipeableRef.current?.close();
                        onDelete();
                    }} style={styles.deleteButton}>
                        <Trash2 size={20} color="white" />
                    </TouchableOpacity>
                </Animated.View>
            </View>
        );
    };

    const renderLeftActions = (progress: any, dragX: any) => {
        const trans = dragX.interpolate({
            inputRange: [0, 80],
            outputRange: [-80, 0],
            extrapolate: 'clamp',
        });
        return (
            <View style={styles.leftActionContainer}>
                <Animated.View style={[styles.leftAction, { transform: [{ translateX: trans }] }]}>
                    <TouchableOpacity onPress={() => {
                        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
                        swipeableRef.current?.close();
                        onPin();
                    }} style={styles.pinButton}>
                        <Pin size={20} color="white" fill={item.isPinned ? "white" : "none"} />
                    </TouchableOpacity>
                </Animated.View>
            </View>
        );
    };

    return (
        <Swipeable
            ref={swipeableRef}
            renderRightActions={renderRightActions}
            renderLeftActions={renderLeftActions}
            containerStyle={styles.swipeContainer}
            useNativeAnimations
            friction={2}
            rightThreshold={40}
            leftThreshold={40}
        >
            <TouchableOpacity
                activeOpacity={0.7}
                className="flex-row items-center px-6 py-4 bg-white dark:bg-black w-full"
                onPress={() => {
                    swipeableRef.current?.close();
                    onPress();
                }}
            >
                {/* Avatar Container */}
                <View className="relative mr-4">
                    <AgentAvatar
                        id={item.id}
                        name={item.name}
                        avatar={item.avatar}
                        color={item.color}
                        size={52}
                    />
                    {item.isPinned && (
                        <View style={{
                            position: 'absolute',
                            bottom: -2,
                            right: -2,
                            backgroundColor: '#f59e0b',
                            padding: 3,
                            borderRadius: 8,
                            borderWidth: 2,
                            borderColor: isDark ? '#000' : '#fff'
                        }}>
                            <Pin size={8} color="white" fill="white" />
                        </View>
                    )}
                </View>

                {/* Content */}
                <View className="flex-1 justify-center py-1">
                    <View className="flex-row justify-between items-baseline mb-1 pr-1">
                        <Typography variant="h3" className="text-[18px] font-bold text-gray-900 dark:text-gray-100 leading-tight">
                            {item.name}
                        </Typography>
                        {item.isPreset && (
                            <View className="bg-indigo-50 dark:bg-indigo-500/10 px-1.5 py-0.5 rounded-md border border-indigo-100 dark:border-indigo-900/30">
                                <Typography className="text-indigo-600 dark:text-indigo-400 font-bold text-[8px] uppercase tracking-tighter">PRESET</Typography>
                            </View>
                        )}
                    </View>
                    <Typography variant="body" className={`leading-5 text-[13px] ${isGenerating ? 'text-indigo-500 dark:text-indigo-400 font-medium italic' : 'text-gray-500'}`} numberOfLines={1}>
                        {isGenerating ? "Thinking..." : item.description}
                    </Typography>
                </View>

                <ChevronRight size={18} color="#cbd5e1" className="ml-2" />
            </TouchableOpacity>
        </Swipeable>
    );
};

const styles = StyleSheet.create({
    swipeContainer: {
        width: '100%',
    },
    leftActionContainer: {
        width: 80,
        justifyContent: 'center',
        alignItems: 'center',
    },
    rightActionContainer: {
        width: 80,
        justifyContent: 'center',
        alignItems: 'center',
        alignSelf: 'flex-end',
    },
    leftAction: {
        flex: 1,
        width: '100%',
        justifyContent: 'center',
        alignItems: 'center',
    },
    rightAction: {
        flex: 1,
        width: '100%',
        justifyContent: 'center',
        alignItems: 'center',
    },
    deleteButton: {
        backgroundColor: '#ef4444',
        width: 48,
        height: 48,
        borderRadius: 24,
        justifyContent: 'center',
        alignItems: 'center',
        shadowColor: "#ef4444",
        shadowOffset: { width: 0, height: 4 },
        shadowOpacity: 0.3,
        shadowRadius: 4.65,
        elevation: 8,
    },
    pinButton: {
        backgroundColor: '#f59e0b',
        width: 48,
        height: 48,
        borderRadius: 24,
        justifyContent: 'center',
        alignItems: 'center',
        shadowColor: "#f59e0b",
        shadowOffset: { width: 0, height: 4 },
        shadowOpacity: 0.3,
        shadowRadius: 4.65,
        elevation: 8,
    }
});
