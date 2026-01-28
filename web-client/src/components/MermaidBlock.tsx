import React, { useEffect, useRef, useState } from 'react';
import mermaid from 'mermaid';
import { Maximize2, X } from 'lucide-react';

interface MermaidBlockProps {
    chart: string;
}

export const MermaidBlock: React.FC<MermaidBlockProps> = ({ chart }) => {
    const containerRef = useRef<HTMLDivElement>(null);
    const [svg, setSvg] = useState<string>('');
    const [error, setError] = useState<string | null>(null);
    const [isFullscreen, setIsFullscreen] = useState(false);

    useEffect(() => {
        // Initialize mermaid
        mermaid.initialize({
            startOnLoad: false,
            theme: document.documentElement.classList.contains('dark') ? 'dark' : 'default',
            securityLevel: 'loose',
        });

        const renderChart = async () => {
            if (!containerRef.current) return;
            try {
                const id = `mermaid-${Math.random().toString(36).substr(2, 9)}`;
                const { svg } = await mermaid.render(id, chart);
                setSvg(svg);
                setError(null);
            } catch (err) {
                console.error("Mermaid failed to render", err);
                setError("Failed to render diagram");
                // Mermaid leaves parsing errors in the DOM, clean up
                const errorElement = document.querySelector(`#${containerRef.current.id}`);
                if (errorElement) errorElement.innerHTML = '';
            }
        };

        renderChart();
    }, [chart]);

    if (error) {
        return (
            <div className="p-4 border border-red-200 dark:border-red-900/50 bg-red-50 dark:bg-red-900/20 rounded-lg text-red-600 dark:text-red-400 text-sm">
                Mermaid Error: {error}
            </div>
        );
    }

    return (
        <div className="my-4 relative group" ref={containerRef}>
            <div className="relative border border-gray-200 dark:border-white/10 rounded-xl bg-white dark:bg-black/20 overflow-hidden">
                <div className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity z-10">
                    <button
                        onClick={() => setIsFullscreen(true)}
                        className="p-1.5 bg-gray-100 dark:bg-white/10 rounded-lg hover:bg-gray-200 dark:hover:bg-white/20 text-gray-500 dark:text-gray-400"
                    >
                        <Maximize2 size={16} />
                    </button>
                </div>
                <div
                    className="p-4 flex justify-center items-center overflow-x-auto min-h-[100px]"
                    dangerouslySetInnerHTML={{ __html: svg }}
                />
            </div>

            {/* Fullscreen Viewer */}
            {isFullscreen && (
                <div className="fixed inset-0 z-50 bg-white dark:bg-zinc-900 flex flex-col animate-in fade-in duration-200">
                    <div className="flex justify-end p-4 border-b border-gray-100 dark:border-zinc-800">
                        <button
                            onClick={() => setIsFullscreen(false)}
                            className="p-2 rounded-full hover:bg-gray-100 dark:hover:bg-zinc-800 text-gray-500 dark:text-zinc-400"
                        >
                            <X size={24} />
                        </button>
                    </div>
                    <div className="flex-1 overflow-auto flex items-center justify-center p-8 bg-dots-pattern">
                        <div
                            className="w-full max-w-6xl"
                            dangerouslySetInnerHTML={{ __html: svg }}
                        />
                    </div>
                </div>
            )}
        </div>
    );
};
