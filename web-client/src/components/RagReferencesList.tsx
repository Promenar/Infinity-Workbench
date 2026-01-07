import React, { useState } from 'react';
import { Library, ChevronDown } from 'lucide-react';
import type { RagReference } from '../types/chat';

interface RagReferencesListProps {
    references: RagReference[];
    expanded?: boolean;
    onToggle?: () => void;
    hideTrigger?: boolean;
}

export const RagReferencesList: React.FC<RagReferencesListProps> = ({ references, expanded, onToggle, hideTrigger }) => {
    const [internalExpanded, setInternalExpanded] = useState(false);
    const isExpanded = expanded !== undefined ? expanded : internalExpanded;
    const handleToggle = onToggle || (() => setInternalExpanded(!internalExpanded));

    if (!references || references.length === 0) return null;

    return (
        <div className="mb-2">
            {!hideTrigger && (
                <button
                    onClick={handleToggle}
                    className={`
                        flex items-center gap-2 px-3 py-1.5 rounded-full border text-xs font-medium transition-all duration-200
                        ${isExpanded
                            ? 'bg-emerald-500/10 border-emerald-500/40 text-emerald-600 dark:text-emerald-400'
                            : 'bg-gray-100 dark:bg-white/5 border-transparent text-gray-500 dark:text-gray-400 hover:bg-gray-200 dark:hover:bg-white/10'
                        }
                    `}
                >
                    <Library size={12} />
                    <span>
                        {references.length} 个知识点
                    </span>
                    <ChevronDown
                        size={12}
                        className={`transition-transform duration-200 ${isExpanded ? 'rotate-180' : ''}`}
                    />
                </button>
            )}

            {isExpanded && (
                <div className="mt-2 space-y-2">
                    {references.map((ref, index) => (
                        <div
                            key={index}
                            className="p-3 rounded-lg bg-gray-50 dark:bg-zinc-800/50 border-l-2 border-l-emerald-500 border border-gray-100 dark:border-zinc-700/50"
                        >
                            <div className="flex items-center justify-between gap-2 mb-1">
                                <span className="text-xs font-bold text-gray-700 dark:text-gray-300 truncate flex-1">
                                    {ref.source || '未命名文档'}
                                </span>
                                {ref.score !== undefined && (
                                    <span className="px-1.5 py-0.5 rounded bg-emerald-50 dark:bg-emerald-900/20 text-emerald-600 dark:text-emerald-400 text-[10px] font-mono font-bold">
                                        {(ref.score * 100).toFixed(0)}%
                                    </span>
                                )}
                            </div>
                            <div className="text-xs text-gray-500 dark:text-gray-400 line-clamp-3 leading-relaxed">
                                {ref.content}
                            </div>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
};
