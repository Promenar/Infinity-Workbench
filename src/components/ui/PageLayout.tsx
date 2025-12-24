import { View, ViewProps } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { twMerge } from 'tailwind-merge';

interface PageLayoutProps extends ViewProps {
    safeArea?: boolean;
}

export function PageLayout({ safeArea = true, className, children, ...props }: PageLayoutProps) {
    const containerClass = twMerge("flex-1 bg-surface-secondary", className);

    if (safeArea) {
        return (
            <SafeAreaView className={containerClass} {...props}>
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
