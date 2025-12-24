import React, { useState } from 'react';
import { View, TextInput, TouchableOpacity } from 'react-native';
import { ArrowUp, Plus } from 'lucide-react-native';
import { clsx } from 'clsx';

interface ChatInputProps {
    onSend: (text: string) => void;
    disabled?: boolean;
}

export function ChatInput({ onSend, disabled }: ChatInputProps) {
    const [text, setText] = useState('');

    const handleSend = () => {
        if (!text.trim()) return;
        onSend(text);
        setText('');
    };

    return (
        <View className="px-4 py-2 bg-surface-primary dark:bg-black border-t border-border-default dark:border-slate-800 flex-row items-end gap-2">
            <TouchableOpacity
                className="w-10 h-10 items-center justify-center rounded-full bg-surface-secondary dark:bg-slate-800 active:bg-surface-tertiary"
                onPress={() => { }} // TODO: Attachment picker
            >
                <Plus size={24} color="#64748b" />
            </TouchableOpacity>

            <View className="flex-1 bg-surface-secondary dark:bg-slate-900 rounded-2xl border border-transparent focus:border-primary-500/50 min-h-[44px] justify-center px-4 py-2">
                <TextInput
                    className="text-text-primary dark:text-white text-base max-h-32"
                    placeholder="Type a message..."
                    placeholderTextColor="#94a3b8"
                    multiline
                    value={text}
                    onChangeText={setText}
                    editable={!disabled}
                />
            </View>

            <TouchableOpacity
                className={clsx(
                    "w-10 h-10 items-center justify-center rounded-full transition-colors",
                    text.trim() ? "bg-primary-600" : "bg-surface-tertiary dark:bg-slate-800"
                )}
                onPress={handleSend}
                disabled={!text.trim() || disabled}
            >
                <ArrowUp size={20} color={text.trim() ? "white" : "#94a3b8"} />
            </TouchableOpacity>
        </View>
    );
}
