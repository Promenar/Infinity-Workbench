import React from 'react';
import { Sparkles, ChevronDown } from 'lucide-react';
import { useI18n } from '../lib/i18n';

interface ReasoningBlockProps {
    content: string;
    loading?: boolean;
    expanded: boolean;
    onToggle: () => void;
}

export const ReasoningBlock: React.FC<ReasoningBlockProps> = ({
    content,
    loading,
    expanded,
    onToggle,
}) => {
    const { t } = useI18n();

    return (
        <div className="mb-2">
            <button
                onClick={onToggle}
                className={`
                    flex items-center gap-2 px-3 py-1.5 rounded-full border text-xs font-medium transition-all duration-200
                    ${loading || expanded
                        ? 'bg-violet-500/10 border-violet-500/40 text-violet-600 dark:text-violet-400'
                        : 'bg-gray-100 dark:bg-white/5 border-transparent text-gray-500 dark:text-gray-400 hover:bg-gray-200 dark:hover:bg-white/10'
                    }
                `}
            >
                <Sparkles size={12} className={loading ? "animate-pulse" : ""} />
                <span>
                    {loading ? t.status.thinking : expanded ? '已深度思考' : t.chat.thinking}
                </span>
                <ChevronDown
                    size={12}
                    className={`transition-transform duration-200 ${expanded ? 'rotate-180' : ''}`}
                />
            </button>

            {(expanded || loading) && (
                <div className={`
                    mt-2 p-3 pl-4 border-l-2 text-sm text-gray-600 dark:text-gray-300 bg-gray-50/50 dark:bg-zinc-900/30 rounded-r-lg
                    ${loading ? 'border-violet-400/50' : 'border-gray-200 dark:border-zinc-700'}
                `}>
                    <div className="prose dark:prose-invert max-w-none text-xs leading-relaxed whitespace-pre-wrap font-mono">
                        {content}
                    </div>
                </div>
            )}
        </div>
    );
};
