
import React, { useState } from 'react';
import { View } from 'react-native';
import { FlashList } from '@shopify/flash-list';
import { Card } from '../ui/Card';
import { Typography } from '../ui/Typography';
import { Button } from '../ui/Button';
import { Trash2, Brain } from 'lucide-react-native';

interface CoreMemory {
    id: string;
    content: string;
    category: 'preference' | 'fact' | 'context';
    createdAt: number;
}

// Mock Data
const MOCK_MEMORIES: CoreMemory[] = [
    { id: '1', content: 'User prefers dark mode', category: 'preference', createdAt: Date.now() },
    { id: '2', content: 'User is a React Native developer', category: 'context', createdAt: Date.now() - 100000 },
    { id: '3', content: 'Birthday is Jan 1st', category: 'fact', createdAt: Date.now() - 200000 },
];

export const CoreMemoryList: React.FC = () => {
    const [memories, setMemories] = useState<CoreMemory[]>(MOCK_MEMORIES);

    const handleDelete = (id: string) => {
        setMemories(prev => prev.filter(m => m.id !== id));
    };

    const renderItem = ({ item }: { item: CoreMemory }) => (
        <Card className="mb-3 p-4 flex-row justify-between items-center bg-card/80 border-white/5">
            <View className="flex-1 mr-4">
                <View className="flex-row items-center mb-1">
                    <Brain size={14} color="#A0A0A0" />
                    <Typography variant="caption" color="secondary" className="ml-1 uppercase">
                        {item.category}
                    </Typography>
                </View>
                <Typography variant="body" className="text-white/90">
                    {item.content}
                </Typography>
            </View>
            <Button
                variant="ghost"
                size="sm"
                label=""
                onPress={() => handleDelete(item.id)}
                className="opacity-70 active:opacity-100"
            >
                <Trash2 size={18} color="#FF6B6B" />
            </Button>
        </Card>
    );

    return (
        <View className="flex-1">
            <FlashList
                data={memories}
                renderItem={renderItem}

                contentContainerStyle={{ padding: 16 }}
                ListEmptyComponent={
                    <View className="items-center justify-center py-10">
                        <Typography variant="body" color="secondary">
                            No core memories saved yet.
                        </Typography>
                    </View>
                }
            />
        </View>
    );
};
