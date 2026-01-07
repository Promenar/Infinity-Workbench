import React, { useState, useEffect } from 'react';
import { Bot, User, Copy, Check, RotateCw, Trash2, Share2, Loader2 } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';
import clsx from 'clsx';
import type { ChatMessage } from '../types/chat';
import { CodeBlock } from './CodeBlock';
import { ReasoningBlock } from './ReasoningBlock';
import { SearchSourcesBlock } from './SearchSourcesBlock';
import { RagReferencesList } from './RagReferencesList';
import { useI18n } from '../lib/i18n';
import 'katex/dist/katex.min.css';

interface MessageBubbleProps {
    message: ChatMessage;
    isStreaming?: boolean;
    onRegenerate?: (messageId: string) => void;
    onDelete?: (messageId: string) => void;
}

export const MessageBubble: React.FC<MessageBubbleProps> = ({
    message,
    isStreaming,
    onRegenerate,
    onDelete
}) => {
    const { t } = useI18n();
    const isUser = message.role === 'user';
    const [copied, setCopied] = useState(false);
    const [isReasoningExpanded, setIsReasoningExpanded] = useState(false);
    const [isSourcesExpanded, setIsSourcesExpanded] = useState(false);

    // Auto-expand reasoning when streaming starts
    useEffect(() => {
        if (isStreaming && message.content === '' && message.reasoning && !isReasoningExpanded) {
            setIsReasoningExpanded(true);
        }
    }, [isStreaming, message.reasoning]);

    const handleCopy = () => {
        navigator.clipboard.writeText(message.content);
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
    };

    const handleShare = async () => {
        if (navigator.share) {
            try {
                await navigator.share({ text: message.content });
            } catch (err) {
                console.debug('Share failed/cancelled', err);
            }
        } else {
            handleCopy();
        }
    };

    // Derive vectorization status
    const status = message.vectorizationStatus;
    const showProcessing = !isUser && !isStreaming && status === 'processing';
    const showSuccess = !isUser && !isStreaming && status === 'success';
    const showError = !isUser && !isStreaming && status === 'error';

    return (
        <div className={clsx(
            "flex gap-4 p-4 hover:bg-white/5 transition-colors group rounded-xl",
            isStreaming && "bg-white/5"
        )}>
            {/* Avatar */}
            <div className={clsx(
                "w-8 h-8 rounded-lg flex items-center justify-center shrink-0",
                isUser ? "bg-indigo-500/20 text-indigo-400" : "bg-emerald-500/20 text-emerald-400"
            )}>
                {isUser ? <User size={18} /> : <Bot size={18} />}
            </div>

            {/* Content Column */}
            <div className="flex-1 min-w-0 space-y-2">
                {/* Header (Role Name - Optional, maybe just timestamp or hidden) */}
                <div className="flex items-center gap-2">
                    <span className="font-medium text-sm text-gray-300">
                        {isUser ? 'You' : 'AI Assistant'}
                    </span>
                    <span className="text-xs text-gray-600">
                        {new Date(message.createdAt).toLocaleTimeString()}
                    </span>
                </div>

                {/* Reasoning Block */}
                {message.reasoning && (
                    <ReasoningBlock
                        content={message.reasoning}
                        expanded={isReasoningExpanded}
                        onToggle={() => setIsReasoningExpanded(!isReasoningExpanded)}
                        loading={isStreaming && !message.content}
                    />
                )}

                {/* Main Content */}
                <div className="prose prose-invert max-w-none text-gray-300 leading-relaxed">
                    <ReactMarkdown
                        remarkPlugins={[remarkGfm, remarkMath]}
                        rehypePlugins={[rehypeKatex]}
                        components={{
                            code: ({ inline, className, children }: any) => {
                                const match = /language-(\w+)/.exec(className || '');
                                const language = match ? match[1] : '';
                                const content = String(children).replace(/\n$/, '');

                                if (!inline) {
                                    if (language === 'svg') {
                                        return (
                                            <div className="my-4 p-4 bg-white/5 rounded-xl border border-white/10 overflow-x-auto flex justify-center">
                                                <div dangerouslySetInnerHTML={{ __html: content }} />
                                            </div>
                                        );
                                    }

                                    return (
                                        <CodeBlock
                                            language={language}
                                            value={content}
                                        />
                                    );
                                }

                                return (
                                    <CodeBlock
                                        language="text"
                                        value={String(children)}
                                        inline={true}
                                    />
                                );
                            },
                            table: (props: any) => (
                                <div className="overflow-x-auto my-4 border border-white/10 rounded-lg">
                                    <table className="min-w-full divide-y divide-white/10 text-sm text-left" {...props} />
                                </div>
                            ),
                            thead: (props: any) => <thead className="bg-white/5 text-gray-200" {...props} />,
                            th: (props: any) => <th className="px-4 py-3 font-semibold text-xs uppercase tracking-wider" {...props} />,
                            td: (props: any) => <td className="px-4 py-3 whitespace-nowrap text-gray-300 border-t border-white/5" {...props} />,
                            blockquote: (props: any) => (
                                <blockquote className="border-l-4 border-indigo-500/50 bg-white/5 rounded-r-lg pl-4 pr-2 py-1 my-4 italic text-gray-400" {...props} />
                            ),
                            ul: (props: any) => <ul className="list-disc pl-6 space-y-1 my-4 marker:text-gray-500" {...props} />,
                            ol: (props: any) => <ol className="list-decimal pl-6 space-y-1 my-4 marker:text-gray-500" {...props} />,
                            a: (props: any) => (
                                <a
                                    className="text-indigo-400 hover:text-indigo-300 underline decoration-indigo-500/30 hover:decoration-indigo-500/80 transition-all font-medium"
                                    target="_blank"
                                    rel="noopener noreferrer"
                                    {...props}
                                />
                            )
                        }}
                    >
                        {message.content || (isStreaming ? 'Thinking...' : '')}
                    </ReactMarkdown>
                </div>

                {/* Search Sources */}
                {message.citations && message.citations.length > 0 && (
                    <SearchSourcesBlock
                        citations={message.citations}
                        expanded={isSourcesExpanded}
                        onToggle={() => setIsSourcesExpanded(!isSourcesExpanded)}
                    />
                )}

                {/* RAG References */}
                {message.ragReferences && message.ragReferences.length > 0 && (
                    <div className="mt-4 pt-4 border-t border-white/5">
                        <RagReferencesList references={message.ragReferences} />
                    </div>
                )}

                {/* Footer Actions & Status */}
                <div className="flex items-center justify-between pt-2">
                    {/* Status Indicators */}
                    <div className="flex items-center gap-4 h-6">
                        {showProcessing && (
                            <span className="text-[10px] flex items-center gap-1.5 text-amber-500 font-medium animate-pulse bg-amber-500/10 px-2 py-0.5 rounded-full">
                                <Loader2 size={10} className="animate-spin" />
                                {t.chat.processing}
                            </span>
                        )}
                        {showSuccess && (
                            <span className="text-[10px] flex items-center gap-1.5 text-emerald-500 font-medium animate-in fade-in zoom-in bg-emerald-500/10 px-2 py-0.5 rounded-full">
                                <Check size={10} />
                                {t.chat.memorized}
                            </span>
                        )}
                        {showError && (
                            <span className="text-[10px] flex items-center gap-1.5 text-red-500 font-medium bg-red-500/10 px-2 py-0.5 rounded-full">
                                {t.chat.failed}
                            </span>
                        )}
                    </div>

                    {/* Action Buttons */}
                    {!isStreaming && (
                        <div className="flex items-center gap-1 transition-opacity duration-200">
                            <button
                                onClick={handleCopy}
                                className={clsx(
                                    "p-1.5 rounded-lg transition-colors",
                                    copied ? "text-emerald-400 bg-emerald-500/10" : "text-gray-500 hover:text-gray-300 hover:bg-white/5"
                                )}
                                title="Copy"
                            >
                                {copied ? <span className="text-xs font-bold px-1">{t.common.copied}</span> : <Copy size={14} />}
                            </button>

                            <button
                                onClick={handleShare}
                                className="p-1.5 text-gray-500 hover:text-gray-300 hover:bg-white/5 rounded-lg transition-colors"
                                title="Share"
                            >
                                <Share2 size={14} />
                            </button>

                            {(onRegenerate && !isUser) && (
                                <button
                                    onClick={() => onRegenerate && onRegenerate(message.id)}
                                    className="p-1.5 text-gray-500 hover:text-gray-300 hover:bg-white/5 rounded-lg transition-colors"
                                    title="Regenerate"
                                >
                                    <RotateCw size={14} />
                                </button>
                            )}

                            {onDelete && (
                                <>
                                    <div className="h-4 w-px bg-white/10 mx-1"></div>
                                    <button
                                        onClick={() => {
                                            if (window.confirm(t.chat.deleteMessageConfirm)) {
                                                onDelete(message.id);
                                            }
                                        }}
                                        className="p-1.5 text-gray-500 hover:text-red-400 hover:bg-red-500/10 rounded-lg transition-colors"
                                        title="Delete"
                                    >
                                        <Trash2 size={14} />
                                    </button>
                                </>
                            )}
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
};
