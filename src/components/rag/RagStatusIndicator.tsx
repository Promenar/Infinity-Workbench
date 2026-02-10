import React, { useEffect, useState, useRef } from 'react';
import { View, TouchableOpacity, StyleSheet } from 'react-native';
import Animated, {
  FadeInDown,
  FadeOutDown,
  Layout,
  useAnimatedStyle,
  withSpring,
  withTiming,
  useSharedValue,
  withRepeat,
  Easing
} from 'react-native-reanimated';
import { useRagStore } from '../../store/rag-store';
import { Typography } from '../ui/Typography';
import {
  Database,
  Network,
  AlertTriangle,
  FileText,
  Loader2,
  Save,
  Scissors,
  CheckCircle2,
  AlertCircle,
  X,
  Zap
} from 'lucide-react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { BlurView } from 'expo-blur';

export function RagStatusIndicator() {
  const { currentTask, vectorizationQueue } = useRagStore();
  const { isDark, colors } = useTheme();
  const [visible, setVisible] = useState(false);

  const progressWidth = useSharedValue(0);
  const glowOpacity = useSharedValue(0.4);

  // 呼吸灯效果
  useEffect(() => {
    glowOpacity.value = withRepeat(
      withTiming(1, { duration: 1500, easing: Easing.inOut(Easing.ease) }),
      -1,
      true
    );
  }, []);

  // 同步进度条
  useEffect(() => {
    if (currentTask?.progress) {
      progressWidth.value = withSpring(currentTask.progress, { damping: 20, stiffness: 90 });
    } else if (!currentTask) {
      progressWidth.value = withTiming(0);
    }
  }, [currentTask?.progress]);

  // 延迟隐藏逻辑
  useEffect(() => {
    if (currentTask || vectorizationQueue.length > 0) {
      setVisible(true);
    } else {
      const timer = setTimeout(() => {
        setVisible(false);
      }, 3000);
      return () => clearTimeout(timer);
    }
  }, [currentTask, vectorizationQueue.length]);

  // 状态映射
  const getStatusConfig = () => {
    if (!currentTask) {
      return {
        icon: <CheckCircle2 size={16} color="#10b981" />,
        text: '处理已完成',
        color: '#10b981',
        glow: 'rgba(16, 185, 129, 0.4)',
      };
    }

    switch (currentTask.status) {
      case 'pending':
        return {
          icon: <Loader2 size={16} color={colors[500]} />,
          text: '等待队列中',
          color: colors[500],
          glow: 'rgba(99, 102, 241, 0.4)',
        };
      case 'reader':
      case 'chunking':
        return {
          icon: <Scissors size={16} color="#f59e0b" />,
          text: '解析并切分中',
          color: '#f59e0b',
          glow: 'rgba(245, 158, 11, 0.4)',
        };
      case 'vectorizing':
        return {
          icon: <Database size={16} color="#3b82f6" />,
          text: currentTask.subStatus || '生成向量嵌入',
          color: '#3b82f6',
          glow: 'rgba(59, 130, 246, 0.4)',
        };
      case 'saving':
        return {
          icon: <Save size={16} color="#8b5cf6" />,
          text: '同步至向量库',
          color: '#8b5cf6',
          glow: 'rgba(139, 92, 246, 0.4)',
        };
      case 'failed':
        return {
          icon: <AlertCircle size={16} color="#ef4444" />,
          text: '执行出错',
          color: '#ef4444',
          glow: 'rgba(239, 68, 68, 0.4)',
        };
      case 'extracting':
        return {
          icon: <Network size={16} color="#8b5cf6" />,
          text: currentTask.subStatus || '构建知识图谱',
          color: '#8b5cf6',
          glow: 'rgba(139, 92, 246, 0.4)',
        };
      case 'warning':
        return {
          icon: <AlertTriangle size={16} color="#f59e0b" />,
          text: currentTask.subStatus || '处理完成（部分警告）',
          color: '#f59e0b',
          glow: 'rgba(245, 158, 11, 0.4)',
        };
      default:
        return {
          icon: <Zap size={16} color={colors[500]} />,
          text: '异步处理中',
          color: colors[500],
          glow: 'rgba(99, 102, 241, 0.4)',
        };
    }
  };

  const config = getStatusConfig();
  const queueCount = vectorizationQueue.length;

  const progressStyle = useAnimatedStyle(() => ({
    width: `${progressWidth.value}%`,
    backgroundColor: config.color,
  }));

  const glowStyle = useAnimatedStyle(() => ({
    borderColor: config.color,
    opacity: glowOpacity.value,
    shadowColor: config.color,
    shadowRadius: 10,
    shadowOpacity: glowOpacity.value * 0.5,
  }));

  if (!visible && !currentTask) return null;

  return (
    <Animated.View
      entering={FadeInDown.springify().damping(15)}
      exiting={FadeOutDown.duration(400)}
      layout={Layout.springify()}
      style={{
        position: 'absolute',
        bottom: 100, // 稍微上移避免遮挡 TabBar 的发光
        left: 20,
        right: 20,
        zIndex: 1000,
        pointerEvents: 'box-none',
      }}
    >
      <View className="overflow-hidden rounded-[24px] border border-white/20 dark:border-white/10 shadow-2xl">
        <BlurView
          intensity={isDark ? 40 : 80}
          tint={isDark ? 'dark' : 'light'}
          style={styles.blurContainer}
        >
          {/* 发光外边框 (模拟 Neon 效果) */}
          <Animated.View
            style={[StyleSheet.absoluteFill, styles.neonBorder, glowStyle]}
            pointerEvents="none"
          />

          <View className="flex-row items-center px-4 py-3">
            {/* 核心图标底座 */}
            <View
              style={{ backgroundColor: `${config.color}20` }}
              className="w-10 h-10 rounded-2xl items-center justify-center mr-4"
            >
              {config.icon}
            </View>

            <View className="flex-1 mr-4">
              <Typography
                className="text-sm font-black text-gray-900 dark:text-white tracking-tight"
                numberOfLines={1}
              >
                {currentTask ? currentTask.docTitle : '所有任务已就绪'}
              </Typography>
              <View className="flex-row items-center mt-0.5">
                <Typography className="text-[10px] text-gray-400 font-bold uppercase tracking-widest mr-2">
                  {config.text}
                </Typography>
                {currentTask && (currentTask.progress ?? 0) > 0 && (
                  <Typography style={{ color: config.color }} className="text-[10px] font-black">
                    {Math.round(currentTask.progress ?? 0)}%
                  </Typography>
                )}
              </View>
            </View>

            {/* 队列控制器 */}
            <View className="flex-row items-center gap-2">
              {queueCount > 0 && (
                <TouchableOpacity
                  onPress={() => {
                    const { clearVectorizationQueue } = useRagStore.getState();
                    clearVectorizationQueue();
                  }}
                  className="w-8 h-8 rounded-full bg-black/5 dark:bg-white/5 items-center justify-center"
                >
                  <X size={14} color={isDark ? '#fff' : '#000'} opacity={0.5} />
                </TouchableOpacity>
              )}

              {queueCount > 1 && (
                <View className="bg-indigo-500/20 px-2 py-0.5 rounded-lg border border-indigo-500/30">
                  <Typography className="text-[10px] font-black text-indigo-500">
                    +{queueCount - 1}
                  </Typography>
                </View>
              )}
            </View>
          </View>

          {/* 底部能量进度条 */}
          {currentTask && (
            <View className="h-1 w-full bg-black/5 dark:bg-white/5 overflow-hidden">
              <Animated.View style={[StyleSheet.absoluteFill, progressStyle]} />
              {/* 进度头部的微光 */}
              <Animated.View
                style={[
                  styles.progressGlow,
                  { left: `${progressWidth.value}%`, backgroundColor: config.color }
                ]}
              />
            </View>
          )}
        </BlurView>
      </View>
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  blurContainer: {
    width: '100%',
    backgroundColor: 'transparent',
  },
  neonBorder: {
    borderWidth: 1.5,
    borderRadius: 24,
  },
  progressGlow: {
    position: 'absolute',
    width: 20,
    height: 4,
    top: 0,
    marginLeft: -20,
    shadowRadius: 5,
    shadowOpacity: 0.8,
    elevation: 5,
  }
});
