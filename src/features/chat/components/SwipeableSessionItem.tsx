import React from 'react';
import { View, Animated, StyleSheet, TouchableOpacity } from 'react-native';
import { Swipeable } from 'react-native-gesture-handler';
import { Session } from '../../../types/chat';
import { Typography } from '../../../components/ui/Typography';
import { Pin, Trash2 } from 'lucide-react-native';
import * as Haptics from '../../../lib/haptics';
import { AgentAvatar } from '../../../components/chat/AgentAvatar';
import { useChatStore } from '../../../store/chat-store';

interface SwipeableSessionItemProps {
    item: Session;
    onPress: () => void;
    onPin: () => void;
    onDelete: () => void;
    agentId: string;
    agentAvatar?: string;
    agentColor: string;
    isDark?: boolean;
}

export const SwipeableSessionItem = ({
    item,
    onPress,
    onPin,
    onDelete,
    agentId,
    agentAvatar,
    agentColor,
    isDark
}: SwipeableSessionItemProps) => {
    // Get active requests to check for generating status
    const isGenerating = useChatStore(state => !!state.activeRequests[item.id]);

    // Determine subtitle text and style
    let subtitleText = item.lastMessage;
    let subtitleStyle = "text-gray-500 dark:text-gray-400";
    let isDraft = false;

    if (isGenerating) {
        subtitleText = "Thinking...";
        subtitleStyle = "text-indigo-500 dark:text-indigo-400 font-medium italic";
    } else if (item.draft) {
        subtitleText = item.draft;
        isDraft = true;
        subtitleStyle = "text-gray-500 dark:text-gray-400";
    }

    const swipeableRef = React.useRef<Swipeable>(null);

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
                activeOpacity={0.9} // Better feedback
                className="flex-row items-start p-4 bg-gray-50/50 dark:bg-zinc-900/40 rounded-3xl border border-gray-100/50 dark:border-zinc-800/50"
                style={{ backgroundColor: isDark ? '#18181b' : '#f9fafb' }}
                onPress={() => {
                    swipeableRef.current?.close();
                    onPress();
                }}
            >
                <View className="mr-4 mt-1">
                    <AgentAvatar
                        id={agentId}
                        name={item.title}
                        avatar={agentAvatar}
                        color={agentColor}
                        size={48}
                    />
                </View>

                <View className="flex-1 py-1">
                    <View className="flex-row justify-between items-start mb-1">
                        <View style={{ flexDirection: 'row', flex: 1, alignItems: 'center' }}>
                            {item.isPinned && (
                                <View style={{ marginRight: 6, backgroundColor: `${agentColor}20`, padding: 2, borderRadius: 4 }}>
                                    <Pin size={10} color={agentColor} fill={agentColor} />
                                </View>
                            )}
                            <Typography
                                variant="h3"
                                className="text-[17px] font-black text-gray-900 dark:text-gray-100 leading-tight mr-2"
                                numberOfLines={1}
                            >
                                {item.title}
                            </Typography>
                        </View>
                        <Typography variant="caption" className="text-gray-400 text-[10px] font-bold uppercase tracking-tighter mt-1">
                            {item.time}
                        </Typography>
                    </View>
                    <Typography variant="body" className={subtitleStyle} numberOfLines={2}>
                        {isDraft && <Typography className="text-red-500 font-bold">[Draft] </Typography>}
                        {subtitleText}
                    </Typography>
                </View>
            </TouchableOpacity>
        </Swipeable>
    );
};

const styles = StyleSheet.create({
    swipeContainer: {
        marginBottom: 12,
        marginHorizontal: 16,
    },
    leftActionContainer: {
        width: 80,
        justifyContent: 'center',
        alignItems: 'center',
        marginLeft: 16, // Offset to match margin
    },
    rightActionContainer: {
        width: 80,
        justifyContent: 'center',
        alignItems: 'center',
        marginRight: 16, // Offset to match margin
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
