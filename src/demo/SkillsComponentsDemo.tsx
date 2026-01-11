
import React from 'react';
import { ScrollView, View } from 'react-native';
import { PageLayout } from '../components/ui/PageLayout';
import { Typography } from '../components/ui/Typography';
import { CoreMemoryList } from '../components/skills/CoreMemoryList';
import { ToolExecutionTimeline } from '../components/skills/ToolExecutionTimeline';
import { SkillsSettingsPanel } from '../components/settings/SkillsSettingsPanel';

// Mock Execution Data
const MOCK_STEPS = [
    { id: '1', type: 'thinking', content: 'User is asking about the weather in SF.', timestamp: Date.now() },
    { id: '2', type: 'tool_call', toolName: 'search_internet', toolArgs: { query: 'weather in San Francisco' }, timestamp: Date.now() },
    { id: '3', type: 'tool_result', content: 'Currently 65°F and Sunny', timestamp: Date.now() },
    { id: '4', type: 'thinking', content: 'Processing result to answer user.', timestamp: Date.now() },
];

export default function SkillsComponentsDemo() {
    return (
        <PageLayout>
            <ScrollView contentContainerStyle={{ paddingBottom: 100 }}>
                <Typography variant="h2" className="text-white m-4">UI Verification Demo</Typography>

                {/* 1. Timeline */}
                <View className="mb-8">
                    <Typography variant="h3" className="text-white mx-4 mb-2">1. Tool Execution Timeline</Typography>
                    <ToolExecutionTimeline steps={MOCK_STEPS as any} />
                </View>

                {/* 2. Memory List */}
                <View className="mb-8 h-64 bg-white/5 mx-4 rounded-xl overflow-hidden">
                    <Typography variant="h3" className="text-white m-4 mb-2">2. Core Memory List</Typography>
                    <CoreMemoryList />
                </View>

                {/* 3. Settings Panel */}
                <View className="bg-white/5 mx-4 rounded-xl overflow-hidden h-[400px]">
                    <SkillsSettingsPanel />
                </View>

            </ScrollView>
        </PageLayout>
    );
}
