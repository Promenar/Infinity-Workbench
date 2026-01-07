import React from 'react';
import { Globe, ChevronDown, ExternalLink } from 'lucide-react';
import { useI18n } from '../lib/i18n';

interface SearchSourcesBlockProps {
    citations: { title: string; url: string; source?: string }[];
    expanded: boolean;
    onToggle: () => void;
}

export const SearchSourcesBlock: React.FC<SearchSourcesBlockProps> = ({
    citations,
    expanded,
    onToggle,
}) => {
    const { t } = useI18n();

    if (!citations || citations.length === 0) return null;

    return (
        <div className="mb-2">
            <button
                onClick={onToggle}
                className={`
                    flex items-center gap-2 px-3 py-1.5 rounded-full border text-xs font-medium transition-all duration-200
                    ${expanded
                        ? 'bg-indigo-500/10 border-indigo-500/40 text-indigo-600 dark:text-indigo-400'
                        : 'bg-gray-100 dark:bg-white/5 border-transparent text-gray-500 dark:text-gray-400 hover:bg-gray-200 dark:hover:bg-white/10'
                    }
                `}
            >
                <Globe size={12} />
                <span>
                    {citations.length} {t.chat.search}
                </span>
                <ChevronDown
                    size={12}
                    className={`transition-transform duration-200 ${expanded ? 'rotate-180' : ''}`}
                />
            </button>

            {expanded && (
                <div className="mt-2 grid grid-cols-1 sm:grid-cols-2 gap-2">
                    {citations.map((cite, index) => (
                        <a
                            key={index}
                            href={cite.url}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="flex items-start gap-2 p-2 rounded-lg bg-gray-50 dark:bg-zinc-800/50 hover:bg-gray-100 dark:hover:bg-zinc-800 transition-colors border border-gray-100 dark:border-zinc-700/50 group"
                        >
                            <div className="mt-0.5 shrink-0 w-4 h-4 rounded bg-gray-200 dark:bg-zinc-700 flex items-center justify-center text-[10px] text-gray-500 dark:text-gray-400 font-mono">
                                {index + 1}
                            </div>
                            <div className="flex-1 min-w-0">
                                <div className="text-xs font-medium text-gray-700 dark:text-gray-300 truncate group-hover:text-indigo-500 dark:group-hover:text-indigo-400 transition-colors">
                                    {cite.title || cite.url}
                                </div>
                                <div className="text-[10px] text-gray-400 dark:text-gray-500 truncate flex items-center gap-1">
                                    {cite.source || new URL(cite.url).hostname}
                                    <ExternalLink size={8} />
                                </div>
                            </div>
                        </a>
                    ))}
                </div>
            )}
        </div>
    );
};
