import React from 'react';
import { ScrollView } from 'react-native';
import { PageLayout } from '../../../src/components/ui';
import { Stack } from 'expo-router';
import { AdvancedRetrievalPanel } from '../../../src/features/settings/components/AdvancedRetrievalPanel';

export default function AdvancedRetrievalScreen() {
    return (
        <>
            <Stack.Screen
                options={{
                    title: '高级检索配置',
                    headerShown: true,
                }}
            />
            <PageLayout>
                <ScrollView className="flex-1" showsVerticalScrollIndicator={false}>
                    <AdvancedRetrievalPanel />
                </ScrollView>
            </PageLayout>
        </>
    );
}
