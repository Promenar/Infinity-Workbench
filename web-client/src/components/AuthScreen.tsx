import React, { useState, useRef, useEffect } from 'react';
import { workbenchClient } from '../services/WorkbenchClient';
interface AuthScreenProps {
    onLogin: () => void;
}

export default function AuthScreen({ onLogin }: AuthScreenProps) {
    const [pin, setPin] = useState(['', '', '', '', '', '']);

    const [error, setError] = useState('');
    const inputRefs = useRef<(HTMLInputElement | null)[]>([]);

    // Focus first input on mount
    useEffect(() => {
        if (inputRefs.current[0]) {
            inputRefs.current[0].focus();
        }

        const handleStatus = (status: string) => {
            if (status === 'authenticated') {
                onLogin();
            } else if (status === 'error') {
                setError('Connection Failed. Check Access Code.');
            }
        };

        workbenchClient.on('statusChange', handleStatus);

        // Auto-connect if token exists
        const token = localStorage.getItem('wb_token');
        if (token) {
            // Use current origin if served from app, or default if dev
            // Note: In dev mode (localhost), origin might be different from API server.
            // But for production (served by app), origin is correct.
            const origin = window.location.origin;
            if (origin.includes('http')) {
                workbenchClient.connect(origin, '');
            }
        }

        return () => {
            workbenchClient.off('statusChange', handleStatus);
        };
    }, [onLogin]);


    const handlePinChange = (index: number, value: string) => {
        if (!/^\d*$/.test(value)) return;

        const newPin = [...pin];
        newPin[index] = value;
        setPin(newPin);

        if (value && index < 5) {
            inputRefs.current[index + 1]?.focus();
        }

        if (newPin.every(d => d !== '')) {
            // Auto submit
        }
    };

    const handleKeyDown = (index: number, e: React.KeyboardEvent<HTMLInputElement>) => {
        if (e.key === 'Backspace' && !pin[index] && index > 0) {
            inputRefs.current[index - 1]?.focus();
        }
    };

    const handleConnect = () => {
        setError('');
        try {
            const fullPin = pin.join('');
            if (fullPin.length !== 6) {
                setError('Please enter a 6-digit Access Code.');
                return;
            }

            // Auto-detect host from current window
            const serverUrl = window.location.origin; // e.g., http://192.168.1.5:3000
            workbenchClient.connect(serverUrl, fullPin);
        } catch (e) {
            setError('Connection Error');
        }
    };

    return (
        <div className="flex flex-col items-center justify-center min-h-screen bg-black text-white p-4">
            <div className="w-full max-w-md bg-zinc-900/50 p-8 rounded-2xl border border-zinc-800 backdrop-blur-xl">
                <div className="text-center mb-8">
                    <div className="w-16 h-16 bg-blue-600/20 rounded-2xl flex items-center justify-center mx-auto mb-4 border border-blue-500/30">
                        <svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect><line x1="8" y1="21" x2="16" y2="21"></line><line x1="12" y1="17" x2="12" y2="21"></line></svg>
                    </div>
                    <h1 className="text-2xl font-bold mb-2">Connect to Nexara</h1>
                    <p className="text-zinc-400 text-sm">Enter the connection details from your mobile device</p>
                </div>

                <div className="space-y-6">


                    <div>
                        <label className="block text-xs font-semibold text-zinc-500 uppercase tracking-wider mb-2">Access Code (PIN)</label>
                        <div className="flex gap-2 justify-between">
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
                                    className="w-12 h-14 bg-black/50 border border-zinc-800 rounded-lg text-center text-xl font-bold focus:outline-none focus:border-blue-500 transition-colors"
                                />
                            ))}
                        </div>
                    </div>

                    {error && (
                        <div className="bg-red-500/10 border border-red-500/20 rounded-lg p-3 text-red-400 text-xs text-center">
                            {error}
                        </div>
                    )}

                    <button
                        onClick={handleConnect}
                        className="w-full bg-blue-600 hover:bg-blue-500 text-white font-semibold py-3.5 rounded-xl transition-all active:scale-[0.98] shadow-[0_0_20px_-5px_rgba(37,99,235,0.3)]"
                    >
                        Connect
                    </button>

                    <p className="text-center text-xs text-zinc-600 mt-4">
                        Make sure both devices are on the same Wi-Fi network
                    </p>
                </div>
            </div>
        </div>
    );
}
