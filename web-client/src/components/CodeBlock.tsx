import { useState } from 'react';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { Check, Copy } from 'lucide-react';
import clsx from 'clsx';

interface CodeBlockProps {
    language: string;
    value: string;
    inline?: boolean;
    className?: string;
}

export function CodeBlock({ language, value, inline, className }: CodeBlockProps) {
    const [copied, setCopied] = useState(false);

    if (inline) {
        return (
            <code className={clsx("bg-white/10 text-indigo-300 rounded px-1.5 py-0.5 font-mono text-[13px] border border-white/5", className)}>
                {value}
            </code>
        );
    }

    const handleCopy = () => {
        navigator.clipboard.writeText(value);
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
    };

    return (
        <div className="not-prose relative group/code my-4 overflow-hidden rounded-xl border border-white/5 bg-[#161618] shadow-inner">
            {/* Header */}
            <div className="flex items-center justify-between px-4 py-2 bg-white/5 border-b border-white/5">
                <div className="flex items-center gap-2">
                    <div className="flex gap-1.5">
                        <div className="w-2.5 h-2.5 rounded-full bg-red-500/20"></div>
                        <div className="w-2.5 h-2.5 rounded-full bg-yellow-500/20"></div>
                        <div className="w-2.5 h-2.5 rounded-full bg-green-500/20"></div>
                    </div>
                    {language && (
                        <span className="text-xs font-mono text-zinc-500 ml-2 uppercase">
                            {language}
                        </span>
                    )}
                </div>
                <button
                    onClick={handleCopy}
                    className="flex items-center gap-1 text-xs text-zinc-500 hover:text-white transition-colors"
                >
                    {copied ? <Check size={14} className="text-emerald-400" /> : <Copy size={14} />}
                    <span className={clsx("transition-opacity", copied ? "text-emerald-400" : "")}>
                        {copied ? 'Copied' : 'Copy'}
                    </span>
                </button>
            </div>

            {/* Content */}
            <div className="text-sm font-mono overflow-x-auto">
                <SyntaxHighlighter
                    language={language || 'text'}
                    style={oneDark}
                    customStyle={{
                        margin: 0,
                        padding: '1.5rem',
                        background: 'transparent',
                    }}
                    showLineNumbers={true}
                    lineNumberStyle={{ minWidth: '2.5em', paddingRight: '1em', color: '#52525b', textAlign: 'right' }}
                >
                    {value}
                </SyntaxHighlighter>
            </div>
        </div>
    );
}
