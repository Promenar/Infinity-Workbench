import { View, ViewProps } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { twMerge } from 'tailwind-merge';
import React from 'react';

interface PageLayoutProps extends ViewProps {
  safeArea?: boolean;
}

/**
 * PageLayout - NeuralFlow 统一页面容器 (修复版)
 * 移除了不必要的嵌套View，直接返回SafeAreaView或View
 * 修复了在Tab导航环境下状态重渲染导致的导航上下文错误
 */
export function PageLayout({ safeArea = true, className, children, ...props }: PageLayoutProps) {
  const containerClass = twMerge('flex-1 bg-white dark:bg-black', className);

  if (safeArea) {
    return (
      <SafeAreaView className={containerClass} {...props} edges={['top', 'left', 'right']}>
        {children}
      </SafeAreaView>
    );
  }

  return (
    <View className={containerClass} {...props}>
      {children}
    </View>
  );
}
