import React, { createContext, useContext, useState, useRef, useCallback } from 'react';
import { View } from 'react-native';
import Animated, { FadeInUp, FadeOutUp } from 'react-native-reanimated';
import { Typography } from './Typography';
import { Check, AlertCircle, Info } from 'lucide-react-native';

type ToastType = 'success' | 'error' | 'info';

interface ToastContextType {
    showToast: (message: string, type?: ToastType) => void;
}

const ToastContext = createContext<ToastContextType | undefined>(undefined);

export function ToastProvider({ children }: { children: React.ReactNode }) {
    const [toast, setToast] = useState<{ message: string; type: ToastType } | null>(null);
    const timeoutRef = useRef<ReturnType<typeof setTimeout>>();

    const showToast = useCallback((message: string, type: ToastType = 'info') => {
        if (timeoutRef.current) clearTimeout(timeoutRef.current);

        setToast({ message, type });

        // Auto hide after 3 seconds
        timeoutRef.current = setTimeout(() => {
            setToast(null);
        }, 3000);
    }, []);

    return (
        <ToastContext.Provider value={{ showToast }}>
            {children}
            {toast && (
                <View className="absolute top-12 left-4 right-4 z-50 items-center pointer-events-none">
                    <Animated.View
                        entering={FadeInUp.springify()}
                        exiting={FadeOutUp}
                        className={`flex-row items-center px-4 py-3 rounded-full shadow-lg border border-white/10
              ${toast.type === 'success' ? 'bg-green-600' : ''}
              ${toast.type === 'error' ? 'bg-red-600' : ''}
              ${toast.type === 'info' ? 'bg-zinc-800' : ''}
            `}
                    >
                        {toast.type === 'success' && <Check size={18} color="white" className="mr-2" />}
                        {toast.type === 'error' && <AlertCircle size={18} color="white" className="mr-2" />}
                        {toast.type === 'info' && <Info size={18} color="white" className="mr-2" />}

                        <Typography variant="body" className="text-white font-medium text-sm">
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
