import React, { useMemo, useState } from 'react';
import ReactECharts from 'echarts-for-react';
import { Maximize2, X, AlertCircle } from 'lucide-react';

interface EChartsBlockProps {
    config: string;
}

export const EChartsBlock: React.FC<EChartsBlockProps> = ({ config }) => {
    const [isFullscreen, setIsFullscreen] = useState(false);

    const { option, error } = useMemo(() => {
        try {
            // Clean code block markers
            const cleanConfig = config.replace(/^```echarts\n?/, '').replace(/```$/, '').trim();
            const parsed = JSON.parse(cleanConfig);
            return { option: parsed, error: null };
        } catch (e) {
            return { option: null, error: "Invalid JSON Configuration" };
        }
    }, [config]);

    const isDark = document.documentElement.classList.contains('dark');

    if (error) {
        return (
            <div className="p-4 border border-blue-100 dark:border-blue-900/50 bg-blue-50 dark:bg-blue-900/20 rounded-lg flex items-center gap-2 text-blue-600 dark:text-blue-400">
                <span className="text-sm font-medium">
                    {config.length > 20 ? "Generating chart..." : "..."}
                </span>
            </div>
        );
    }

    return (
        <div className="my-4 relative group">
            <div className="relative border border-gray-200 dark:border-white/10 rounded-xl bg-white dark:bg-black/20 overflow-hidden">
                <div className="flex justify-between items-center p-3 border-b border-gray-100 dark:border-white/5 bg-gray-50/50 dark:bg-white/5">
                    <span className="text-xs font-semibold text-gray-400 uppercase tracking-wider">Chart</span>
                    <button
                        onClick={() => setIsFullscreen(true)}
                        className="p-1.5 hover:bg-gray-200 dark:hover:bg-white/10 rounded text-gray-500 dark:text-gray-400 transition-colors"
                    >
                        <Maximize2 size={14} />
                    </button>
                </div>
                <div className="p-4" style={{ height: 400 }}>
                    <ReactECharts
                        option={{
                            ...option,
                            backgroundColor: 'transparent',
                        }}
                        theme={isDark ? 'dark' : undefined}
                        style={{ height: '100%', width: '100%' }}
                    />
                </div>
            </div>

            {/* Fullscreen Viewer */}
            {isFullscreen && (
                <div className="fixed inset-0 z-50 bg-white dark:bg-zinc-900 flex flex-col animate-in fade-in duration-200">
                    <div className="flex justify-between items-center p-4 border-b border-gray-100 dark:border-zinc-800">
                        <span className="text-sm font-semibold text-gray-500 uppercase">Interactive Chart</span>
                        <button
                            onClick={() => setIsFullscreen(false)}
                            className="p-2 rounded-full hover:bg-gray-100 dark:hover:bg-zinc-800 text-gray-500 dark:text-zinc-400"
                        >
                            <X size={24} />
                        </button>
                    </div>
                    <div className="flex-1 p-8 bg-gray-50 dark:bg-zinc-950/50">
                        <div className="bg-white dark:bg-zinc-900 rounded-2xl shadow-xl p-8 h-full w-full border border-gray-200 dark:border-zinc-800">
                            <ReactECharts
                                option={option}
                                theme={isDark ? 'dark' : undefined}
                                style={{ height: '100%', width: '100%' }}
                            />
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};
