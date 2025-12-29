import React, { createContext, useContext, useState, useRef, useCallback } from 'react';
import { View, SafeAreaView } from 'react-native';
import Animated, { FadeInDown, FadeOutUp, SlideInDown, FadeInUp } from 'react-native-reanimated';
import { Typography } from './Typography';
import { Check, AlertCircle, Info } from 'lucide-react-native';
import * as Haptics from '../../lib/haptics';

type ToastType = 'success' | 'error' | 'info';

interface ToastContextType {
    showToast: (message: string, type?: ToastType) => void;
}

const ToastContext = createContext<ToastContextType | undefined>(undefined);

/**
 * Lumina 风格的 Toast 通知提供者
 * 采用顶部悬浮胶囊设计，优化了动画曲线，使其更加轻盈专业
 */
export function ToastProvider({ children }: { children: React.ReactNode }) {
    const [toast, setToast] = useState<{ message: string; type: ToastType } | null>(null);
    const timeoutRef = useRef<any>(null);

    const showToast = useCallback((message: string, type: ToastType = 'info') => {
        if (timeoutRef.current) clearTimeout(timeoutRef.current);

        // 触发触感反馈
        if (type === 'success') {
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        } else if (type === 'error') {
            Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
        } else {
            Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        }

        setToast({ message, type });

        // 2.5秒后自动消失（略微缩短停留时间，保持高效感）
        timeoutRef.current = setTimeout(() => {
            setToast(null);
        }, 2500);
    }, []);

    return (
        <ToastContext.Provider value={{ showToast }}>
            {children}
            {toast && (
                <View className="absolute top-24 left-0 right-0 z-[1000] items-center pointer-events-none px-6">
                    <Animated.View
                        entering={FadeInUp.duration(400).springify().damping(18).stiffness(120)}
                        exiting={FadeOutUp.duration(200)}
                        className={`flex-row items-center px-6 py-3.5 rounded-full shadow-[0_25px_60px_rgba(0,0,0,0.3)] border border-white/30 dark:border-white/10
                            ${toast.type === 'success' ? 'bg-indigo-600' : ''}
                            ${toast.type === 'error' ? 'bg-red-600' : ''}
                            ${toast.type === 'info' ? 'bg-zinc-900' : ''}
                        `}
                    >
                        <View className="mr-3 bg-white/20 p-1 rounded-full">
                            {toast.type === 'success' && <Check size={14} color="white" strokeWidth={4} />}
                            {toast.type === 'error' && <AlertCircle size={14} color="white" strokeWidth={4} />}
                            {toast.type === 'info' && <Info size={14} color="white" strokeWidth={4} />}
                        </View>

                        <Typography className="text-white font-black text-[15px] tracking-tight">
                            {toast.message}
                        </Typography>
                    </Animated.View>
                </View>
            )}
        </ToastContext.Provider>
    );
}

export function useToast() {
    const context = useContext(ToastContext);
    if (!context) {
        throw new Error('useToast must be used within a ToastProvider');
    }
    return context;
}
