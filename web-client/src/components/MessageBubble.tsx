import clsx from 'clsx';
import { Bot, User, Copy, ThumbsUp, ThumbsDown, RotateCw } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';
import rehypeRaw from 'rehype-raw';
import type { ChatMessage } from '../types/chat';
import { useState } from 'react';
import { CodeBlock } from './CodeBlock';
import 'katex/dist/katex.min.css'; // Import Katex CSS

interface MessageBubbleProps {
    message: ChatMessage;
    isStreaming?: boolean;
}

export function MessageBubble({ message, isStreaming }: MessageBubbleProps) {
    const isUser = message.role === 'user';
    const [copied, setCopied] = useState(false);

    const handleCopy = () => {
        navigator.clipboard.writeText(message.content);
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
    };

    if (isUser) {
        return (
            <div className="flex w-full mb-8 justify-end animate-fade-in group">
                <div className="flex flex-col items-end max-w-[85%] md:max-w-[75%]">
                    <div className="flex items-end gap-3 justify-end">
                        <div className="relative px-5 py-3.5 bg-[#2d2d2d] text-gray-100 rounded-2xl rounded-tr-sm shadow-sm border border-[#3d3d3d]">
                            <div className="prose prose-sm prose-invert max-w-none wrap-break-word leading-relaxed text-[15px]">
                                {message.content}
                            </div>
                        </div>
                        <div className="shrink-0 w-8 h-8 rounded-full bg-linear-to-tr from-gray-600 to-gray-500 flex items-center justify-center shadow-md">
                            <User size={16} className="text-white/90" />
                        </div>
                    </div>
                </div>
            </div>
        );
    }

    return (
        <div className="flex w-full mb-8 justify-start animate-fade-in group">
            <div className="flex gap-4 max-w-full md:max-w-[90%] w-full">
                {/* Avatar Column */}
                <div className="shrink-0 flex flex-col items-center">
                    <div className="w-8 h-8 rounded-full bg-white border border-gray-200 flex items-center justify-center shadow-sm overflow-hidden p-1">
                        <Bot size={18} className="text-indigo-600" />
                    </div>
                </div>

                {/* Content Column */}
                <div className="flex-1 min-w-0">
                    {/* Header: Name + Time */}
                    <div className="flex items-center gap-2 mb-1">
                        <span className="font-bold text-sm text-gray-200">Nexara AI</span>
                        <span className="text-[11px] text-gray-500 font-medium">
                            {new Date(message.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                        </span>
                        {isStreaming && (
                            <span className="text-[10px] px-1.5 py-0.5 bg-indigo-500/10 text-indigo-400 rounded-full font-bold uppercase tracking-wider animate-pulse flex items-center gap-1">
                                ● Generating
                            </span>
                        )}
                    </div>

                    {/* Message Body */}
                    <div className="prose prose-invert max-w-none leading-7 text-[15px] prose-p:text-gray-300 prose-headings:text-gray-100 prose-strong:text-white prose-code:text-indigo-300 prose-ul:text-gray-300 prose-ol:text-gray-300">
                        <ReactMarkdown
                            remarkPlugins={[remarkGfm, remarkMath]}
                            rehypePlugins={[
                                [rehypeKatex, { strict: false, throwOnError: false, errorColor: '#cc0000' }],
                                rehypeRaw
                            ]}
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

                    {/* Action Bar (Visible on Hover) */}
                    {!isStreaming && (
                        <div className="flex items-center gap-1 mt-2 opacity-0 group-hover:opacity-100 transition-opacity duration-200">
                            <button
                                onClick={handleCopy}
                                className={clsx(
                                    "p-1.5 rounded-lg transition-colors",
                                    copied ? "text-emerald-400 bg-emerald-500/10" : "text-gray-500 hover:text-gray-300 hover:bg-white/5"
                                )}
                                title="Copy"
                            >
                                {copied ? <span className="text-xs font-bold px-1">Copied</span> : <Copy size={14} />}
                            </button>
                            <button className="p-1.5 text-gray-500 hover:text-gray-300 hover:bg-white/5 rounded-lg transition-colors" title="Regenerate">
                                <RotateCw size={14} />
                            </button>
                            <div className="h-4 w-px bg-white/10 mx-1"></div>
                            <button className="p-1.5 text-gray-500 hover:text-gray-300 hover:bg-white/5 rounded-lg transition-colors">
                                <ThumbsUp size={14} />
                            </button>
                            <button className="p-1.5 text-gray-500 hover:text-gray-300 hover:bg-white/5 rounded-lg transition-colors">
                                <ThumbsDown size={14} />
                            </button>
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
}
