import React, { useState, useRef, useEffect } from 'react';
import { workbenchClient } from '../services/WorkbenchClient';
import { Wifi, WifiOff, ArrowRight, Command } from 'lucide-react';
import clsx from 'clsx';

interface AuthScreenProps {
    onLogin: () => void;
}

export default function AuthScreen({ onLogin }: AuthScreenProps) {
    const [pin, setPin] = useState(['', '', '', '', '', '']);
    const [error, setError] = useState('');
    const [loading, setLoading] = useState(false);

    // Auto-focus refs
    const inputRefs = useRef<(HTMLInputElement | null)[]>([]);

    useEffect(() => {
        // Focus first input on mount with a slight delay for animation
        setTimeout(() => {
            inputRefs.current[0]?.focus();
        }, 500);

        const handleStatus = (status: string) => {
            if (status === 'authenticated') {
                onLogin();
                setLoading(false);
            } else if (status === 'error') {
                setError('Connection Failed. Check Access Code.');
                setLoading(false);
            }
        };

        workbenchClient.on('statusChange', handleStatus);
        return () => {
            workbenchClient.off('statusChange', handleStatus);
        };
    }, [onLogin]);

    const handlePinChange = (index: number, value: string) => {
        if (!/^\d*$/.test(value)) return;

        const newPin = [...pin];
        newPin[index] = value;
        setPin(newPin);
        setError('');

        if (value && index < 5) {
            inputRefs.current[index + 1]?.focus();
        }

        // Auto-submit if full
        if (newPin.every(d => d !== '') && index === 5 && value !== '') {
            // Trigger submit logic
            // We can't easily call handleConnect here because it takes an event
            // But we can trigger the button click or extract the logic.
            // Let's just focus the button or auto-wait
        }
    };

    const handleKeyDown = (index: number, e: React.KeyboardEvent<HTMLInputElement>) => {
        if (e.key === 'Backspace' && !pin[index] && index > 0) {
            inputRefs.current[index - 1]?.focus();
        }
        if (e.key === 'Enter') {
            handleConnect(e as any);
        }
    };

    const handleConnect = async (e: React.FormEvent) => {
        e.preventDefault();
        setLoading(true);
        setError('');

        const fullPin = pin.join('');
        if (fullPin.length !== 6) {
            setError('Enter 6-digit Code');
            setLoading(false);
            return;
        }

        const fullUrl = window.location.origin;

        try {
            const status = workbenchClient.getStatus();
            if (status === 'connected') {
                workbenchClient.login(fullPin);
            } else {
                workbenchClient.connect(fullUrl, fullPin);
            }
        } catch (err) {
            setError('Connection failed');
            setLoading(false);
        }
    };

    return (
        <div className="relative flex flex-col items-center justify-center min-h-screen bg-[#09090b] text-white p-4 overflow-hidden">

            {/* Ambient Background Glow */}
            <div className="absolute top-[-20%] left-[-10%] w-[500px] h-[500px] bg-purple-600/20 rounded-full blur-[120px] pointer-events-none" />
            <div className="absolute bottom-[-20%] right-[-10%] w-[500px] h-[500px] bg-blue-600/20 rounded-full blur-[120px] pointer-events-none" />

            {/* Main Card */}
            <div className="w-full max-w-md glass-panel p-10 rounded-3xl animate-slide-up relative z-10 flex flex-col items-center">

                {/* Logo / Icon */}
                <div className="w-20 h-20 bg-linear-to-br from-indigo-500 to-purple-600 rounded-2xl flex items-center justify-center mb-8 shadow-lg shadow-indigo-500/25">
                    <Command className="text-white" size={32} />
                </div>

                <h1 className="text-3xl font-bold mb-2 text-center tracking-tight">
                    <span className="text-gradient">Welcome Back</span>
                </h1>
                <p className="text-zinc-400 text-sm mb-10 text-center">
                    Enter the access code from your mobile device
                </p>

                {/* Input Grid */}
                <div className="flex gap-3 justify-center mb-8 w-full">
                    {pin.map((digit, i) => (
                        <input
                            key={i}
                            ref={(el) => { inputRefs.current[i] = el; }}
                            type="text"
                            inputMode="numeric"
                            maxLength={1}
                            value={digit}
                            onChange={(e) => handlePinChange(i, e.target.value)}
                            onKeyDown={(e) => handleKeyDown(i, e)}
                            className={clsx(
                                "w-12 h-16 rounded-xl text-center text-2xl font-bold transition-all duration-200 outline-none",
                                "bg-zinc-900/50 border border-zinc-700/50 text-white shadow-inner",
                                "focus:border-indigo-500 focus:bg-zinc-800 focus:scale-110 focus:shadow-[0_0_20px_rgba(99,102,241,0.3)]",
                                digit && "border-zinc-500 bg-zinc-800/80"
                            )}
                        />
                    ))}
                </div>

                {/* Error Message */}
                <div className={clsx(
                    "h-6 mb-6 text-sm font-medium transition-all duration-300",
                    error ? "text-red-400 opacity-100 translate-y-0" : "opacity-0 translate-y-2"
                )}>
                    {error}
                </div>

                {/* Submit Button */}
                <button
                    onClick={handleConnect}
                    disabled={loading}
                    className={clsx(
                        "w-full py-4 rounded-xl font-semibold text-lg transition-all duration-300 flex items-center justify-center gap-2",
                        "bg-gradient-primary shadow-lg shadow-indigo-500/30",
                        "hover:shadow-indigo-500/50 hover:scale-[1.02]",
                        "active:scale-[0.98]",
                        "disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:scale-100"
                    )}
                >
                    {loading ? (
                        <>
                            <div className="w-5 h-5 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                            <span>Connecting...</span>
                        </>
                    ) : (
                        <>
                            <span>Connect</span>
                            <ArrowRight size={20} />
                        </>
                    )}
                </button>
            </div>

            {/* Footer Status */}
            <div className="mt-8 flex items-center gap-2 text-zinc-500 text-xs animate-fade-in" style={{ animationDelay: '0.3s' }}>
                {loading ? <Wifi className="animate-pulse" size={14} /> : <WifiOff size={14} />}
                <span>
                    {loading ? 'Negotiating handshake...' : 'Waiting for connection...'}
                </span>
            </div>
        </div>
    );
}
