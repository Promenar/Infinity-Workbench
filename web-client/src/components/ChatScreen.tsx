import React, { useState, useRef, useEffect } from 'react';
import { Send, LogOut, Bot, User, Cpu } from 'lucide-react';
import type { ChatMessage } from '../hooks/useWebSocket';

interface ChatScreenProps {
    messages: ChatMessage[];
    onSend: (msg: string) => void;
    onDisconnect: () => void;
}

export function ChatScreen({ messages, onSend, onDisconnect }: ChatScreenProps) {
    const [input, setInput] = useState('');
    const bottomRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, [messages]);

    const handleSend = () => {
        if (!input.trim()) return;
        onSend(input);
        setInput('');
    };

    const handleKeyDown = (e: React.KeyboardEvent) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            handleSend();
        }
    };

    return (
        <div className="flex flex-col h-screen bg-neutral-900 text-white">
            {/* Header */}
            <header className="h-16 border-b border-neutral-800 flex items-center justify-between px-6 bg-neutral-900/50 backdrop-blur-md sticky top-0 z-10">
                <div className="flex items-center gap-3">
                    <div className="w-10 h-10 bg-blue-600 rounded-xl flex items-center justify-center">
                        <Cpu className="w-6 h-6 text-white" />
                    </div>
                    <div>
                        <h1 className="font-bold text-lg leading-tight">Nexara Workbench</h1>
                        <span className="text-xs text-green-400 flex items-center gap-1">
                            <span className="w-2 h-2 rounded-full bg-green-500 animate-pulse" />
                            Connected to Local Device
                        </span>
                    </div>
                </div>
                <button
                    onClick={onDisconnect}
                    className="p-2 hover:bg-neutral-800 rounded-lg transition-colors text-neutral-400 hover:text-red-400"
                    title="Disconnect"
                >
                    <LogOut className="w-5 h-5" />
                </button>
            </header>

            {/* Messages */}
            <main className="flex-1 overflow-y-auto p-4 md:p-8 space-y-6">
                {messages.length === 0 && (
                    <div className="flex flex-col items-center justify-center h-full text-neutral-600 opacity-50">
                        <Cpu className="w-16 h-16 mb-4" />
                        <p className="text-lg">Ready to assist.</p>
                    </div>
                )}

                {messages.map((msg) => (
                    <div
                        key={msg.id}
                        className={`flex gap-4 max-w-4xl mx-auto ${msg.role === 'user' ? 'justify-end' : ''}`}
                    >
                        {msg.role === 'assistant' && (
                            <div className="w-8 h-8 rounded-lg bg-blue-600 flex-shrink-0 flex items-center justify-center mt-1">
                                <Bot className="w-5 h-5 text-white" />
                            </div>
                        )}

                        <div className={`rounded-2xl px-5 py-3 max-w-[80%] shadow-sm whitespace-pre-wrap leading-relaxed ${msg.role === 'user'
                            ? 'bg-neutral-800 text-neutral-100 border border-neutral-700'
                            : 'bg-transparent text-neutral-200'
                            }`}>
                            {msg.content}
                        </div>

                        {msg.role === 'user' && (
                            <div className="w-8 h-8 rounded-lg bg-neutral-700 flex-shrink-0 flex items-center justify-center mt-1">
                                <User className="w-5 h-5 text-neutral-400" />
                            </div>
                        )}
                    </div>
                ))}
                <div ref={bottomRef} />
            </main>

            {/* Input Area */}
            <div className="p-4 bg-neutral-900 border-t border-neutral-800">
                <div className="max-w-4xl mx-auto relative group">
                    <textarea
                        className="w-full bg-neutral-800 border border-neutral-700 rounded-xl pl-4 pr-12 py-3 min-h-[56px] max-h-48 resize-none focus:outline-none focus:ring-2 focus:ring-blue-500/50 focus:border-blue-500 transition-all text-sm md:text-base placeholder-neutral-500"
                        placeholder="Type a message... (Shift+Enter for new line)"
                        value={input}
                        onChange={(e) => setInput(e.target.value)}
                        onKeyDown={handleKeyDown}
                        rows={1}
                    />
                    <button
                        onClick={handleSend}
                        disabled={!input.trim()}
                        className="absolute right-2 bottom-2 p-2 bg-blue-600 hover:bg-blue-500 disabled:bg-neutral-700 disabled:text-neutral-500 text-white rounded-lg transition-colors shadow-lg"
                    >
                        <Send className="w-4 h-4" />
                    </button>
                </div>
                <div className="text-center mt-2 text-xs text-neutral-600">
                    Powered by Nexara Local Intelligence
                </div>
            </div>
        </div>
    );
}
