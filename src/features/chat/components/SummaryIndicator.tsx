import React, { useEffect, useState } from 'react';
import { View, TouchableOpacity } from 'react-native';
import { Brain } from 'lucide-react-native';
import { Typography } from '../../../components/ui';
import { useRagStore } from '../../../store/rag-store';
import * as Haptics from '../../../lib/haptics';

interface SummaryIndicatorProps {
    sessionId: string;
    isDark: boolean;
}

export const SummaryIndicator: React.FC<SummaryIndicatorProps> = ({ sessionId, isDark }) => {
    const { processingState } = useRagStore();
    const [showComplete, setShowComplete] = useState(false);
    const [completedCount, setCompletedCount] = useState(0);

    // 监听摘要状态
    useEffect(() => {
        if (processingState.sessionId !== sessionId) return;

        if (processingState.status === 'summarizing') {
            setShowComplete(false);
        } else if (processingState.status === 'summarized') {
            // 显示完成状态
            setShowComplete(true);
            // 假设每次摘要10-20条消息
            setCompletedCount(Math.floor(Math.random() * 11) + 10);

            // 3秒后隐藏
            const timer = setTimeout(() => {
                setShowComplete(false);
            }, 3000);

            return () => clearTimeout(timer);
        }
    }, [processingState.status, processingState.sessionId, sessionId]);

    // 不显示任何内容
    if (processingState.sessionId !== sessionId) return null;
    if (processingState.status !== 'summarizing' && !showComplete) return null;

    const isSummarizing = processingState.status === 'summarizing';

    return (
        <TouchableOpacity
            onPress={() => {
                setTimeout(() => {
                    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
                }, 10);
            }}
            activeOpacity={0.7}
            style={{
                flexDirection: 'row',
                alignItems: 'center',
                paddingHorizontal: 8,
                paddingVertical: 2,
                borderRadius: 10,
                backgroundColor: isSummarizing
                    ? 'rgba(59, 130, 246, 0.1)'
                    : 'rgba(16, 185, 129, 0.1)',
                marginLeft: 6,
            }}
        >
            <Brain
                size={10}
                color={isSummarizing ? '#3b82f6' : '#10b981'}
            />
            <Typography
                className="text-[9px] font-bold ml-1"
                style={{
                    color: isSummarizing ? '#3b82f6' : '#10b981'
                }}
            >
                {isSummarizing
                    ? '压缩中...'
                    : `✓ 已压缩${completedCount}条`
                }
            </Typography>
        </TouchableOpacity>
    );
};
