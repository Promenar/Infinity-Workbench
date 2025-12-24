import React from 'react';
import { View, ScrollView } from 'react-native';
import { PageLayout, Typography, Card, Button, Header } from '../../src/components/ui';
import { FileText, Plus } from 'lucide-react-native';
import { useTheme } from '../../src/theme/ThemeProvider';

export default function RagScreen() {
    const { isDark } = useTheme(); // Need theme for icon color
    return (
        <PageLayout className="bg-surface-secondary dark:bg-black" safeArea={false}>
            <View style={{ flex: 1 }}>
                <Header
                    title="Library"
                    rightAction={
                        <Button
                            label="Import"
                            size="sm"
                            variant="secondary"
                            icon={<Plus size={16} color={isDark ? "#f8fafc" : "#0f172a"} />}
                            className="dark:bg-slate-800 dark:border-slate-700"
                            textClassName="dark:text-white"
                        />
                    }
                />

                <ScrollView contentContainerStyle={{ padding: 16, gap: 16 }}>
                    <Card className="p-6 items-center justify-center border-dashed border-2 border-border-default dark:border-slate-800 bg-surface-secondary/50 dark:bg-slate-900/50">
                        <FileText size={48} color="#94a3b8" />
                        <Typography variant="body" className="mt-4 text-center text-text-secondary">
                            Knowledge base is empty.
                            {"\n"}Import documents to start RAG.
                        </Typography>
                    </Card>
                </ScrollView>
            </View>
        </PageLayout>
    );
}
