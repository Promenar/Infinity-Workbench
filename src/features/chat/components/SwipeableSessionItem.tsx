import React from 'react';
import { View, Animated as RNAnimated, StyleSheet, TouchableOpacity } from 'react-native';
import { Swipeable } from 'react-native-gesture-handler';
import Animated, { FadeIn } from 'react-native-reanimated';
import { Session } from '../../../types/chat';
import { Typography } from '../../../components/ui/Typography';
import { Pin, Trash2, ChevronRight } from 'lucide-react-native';
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
  isDark,
}: SwipeableSessionItemProps) => {
  const isGenerating = useChatStore((state) => !!state.activeRequests[item.id]);

  let subtitleText = item.lastMessage;
  let subtitleStyle = 'text-gray-500 dark:text-gray-400';
  let isDraft = false;

  if (isGenerating) {
    subtitleText = 'Thinking...';
    subtitleStyle = 'text-indigo-500 dark:text-indigo-400 font-medium italic';
  } else if (item.draft) {
    subtitleText = item.draft;
    isDraft = true;
    subtitleStyle = 'text-gray-500 dark:text-gray-400';
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
        <RNAnimated.View style={[styles.rightAction, { transform: [{ translateX: trans }] }]}>
          <TouchableOpacity
            onPress={() => {
              Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Heavy);
              swipeableRef.current?.close();
              onDelete();
            }}
            style={styles.deleteButton}
          >
            <Trash2 size={20} color="white" />
          </TouchableOpacity>
        </RNAnimated.View>
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
        <RNAnimated.View style={[styles.leftAction, { transform: [{ translateX: trans }] }]}>
          <TouchableOpacity
            onPress={() => {
              Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
              swipeableRef.current?.close();
              onPin();
            }}
            style={styles.pinButton}
          >
            <Pin size={20} color="white" fill={item.isPinned ? 'white' : 'none'} />
          </TouchableOpacity>
        </RNAnimated.View>
      </View>
    );
  };

  return (
    <Animated.View entering={FadeIn.duration(200)}>
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
        style={{
          backgroundColor: isDark ? '#000' : '#fff',
          flexDirection: 'row',
          alignItems: 'center',
          paddingHorizontal: 16,
          paddingVertical: 13,
          width: '100%',
        }}
        onPress={() => {
          swipeableRef.current?.close();
          onPress();
        }}
      >
        <View className="mr-3">
          <AgentAvatar
            id={agentId}
            name={item.title}
            avatar={agentAvatar}
            color={agentColor}
            size={46} // Reduced to match agent list
          />
        </View>

        <View className="flex-1 justify-center py-1">
          <View className="flex-row justify-between items-baseline mb-0.5 pr-1">
            <View style={{ flexDirection: 'row', flex: 1, alignItems: 'center' }}>
              {item.isPinned && (
                <View
                  style={{
                    marginRight: 6,
                    backgroundColor: `${agentColor}20`,
                    padding: 2,
                    borderRadius: 4,
                  }}
                >
                  <Pin size={10} color={agentColor} fill={agentColor} />
                </View>
              )}
              <Typography
                variant="h3"
                style={{
                  fontSize: 16.5,
                  fontWeight: 'bold',
                  lineHeight: 20,
                  color: isDark ? '#ffffff' : '#111827',
                }}
                numberOfLines={1}
              >
                {item.title}
              </Typography>
            </View>
            <Typography
              variant="caption"
              className="text-gray-400 text-[10px] font-bold uppercase tracking-tighter mt-1"
            >
              {item.time}
            </Typography>
          </View>
          <Typography variant="body" className={subtitleStyle} numberOfLines={1}>
            {isDraft && <Typography className="text-red-500 font-bold">[Draft] </Typography>}
            {subtitleText}
          </Typography>
        </View>

        <ChevronRight size={18} color={isDark ? '#52525b' : '#9ca3af'} style={{ marginLeft: 8 }} />
      </TouchableOpacity>
      </Swipeable>
    </Animated.View>
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
    shadowColor: '#ef4444',
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
    shadowColor: '#f59e0b',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 4.65,
    elevation: 8,
  },
});
