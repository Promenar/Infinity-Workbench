import { useEffect, useState, useRef, useMemo } from 'react';
import ForceGraph2D from 'react-force-graph-2d';
import { workbenchClient } from '../services/WorkbenchClient';
import { Share2, ZoomIn, RefreshCw, X } from 'lucide-react';

interface Node {
    id: string;
    name: string;
    type: string;
    val: number; // For visualization size
    color?: string;
    metadata?: any;
}

interface Link {
    source: string | Node;
    target: string | Node;
    relation: string;
    weight?: number;
}

interface GraphData {
    nodes: Node[];
    links: Link[];
}

export function KnowledgeGraph() {
    const [data, setData] = useState<GraphData>({ nodes: [], links: [] });
    const [loading, setLoading] = useState(true);
    const [selectedNode, setSelectedNode] = useState<Node | null>(null);
    const graphRef = useRef<any>(null);
    const containerRef = useRef<HTMLDivElement>(null);
    const [dimensions, setDimensions] = useState({ w: 800, h: 600 });

    const fetchGraph = async () => {
        setLoading(true);
        try {
            const raw = await workbenchClient.getGraph();
            // Transform raw data if necessary, or assume it matches
            // Backend returns { nodes: KGNode[], edges: KGEdge[] }
            // ForceGraph expects { nodes, links }

            const nodes = raw.nodes.map((n: any) => ({
                id: n.id,
                name: n.name,
                type: n.type,
                val: n.type === 'concept' ? 8 : n.type === 'person' ? 10 : 5,
                color: getNodeColor(n.type),
                metadata: n.metadata
            }));

            const links = raw.edges.map((e: any) => ({
                source: e.sourceId,
                target: e.targetId,
                relation: e.relation,
                weight: e.weight
            }));

            setData({ nodes, links });
        } catch (e) {
            console.error("Failed to fetch graph", e);
        } finally {
            setLoading(false);
        }
    };

    const getNodeColor = (type: string) => {
        switch (type) {
            case 'concept': return '#818cf8'; // Indigo 400
            case 'person': return '#34d399'; // Emerald 400
            case 'org': return '#fbbf24'; // Amber 400
            case 'location': return '#f472b6'; // Pink 400
            case 'event': return '#a78bfa'; // Violet 400
            default: return '#9ca3af'; // Gray 400
        }
    };

    useEffect(() => {
        fetchGraph();

        // Responsive resize
        const handleResize = () => {
            if (containerRef.current) {
                setDimensions({
                    w: containerRef.current.offsetWidth,
                    h: containerRef.current.offsetHeight
                });
            }
        };

        window.addEventListener('resize', handleResize);
        handleResize();

        return () => window.removeEventListener('resize', handleResize);
    }, []);

    // Memoize graph to prevent unnecessary re-renders
    const graphComponent = useMemo(() => {
        return (
            <ForceGraph2D
                ref={graphRef}
                width={dimensions.w}
                height={dimensions.h}
                graphData={data}
                nodeLabel="name"
                nodeColor="color"
                nodeRelSize={6}
                linkColor={() => 'rgba(255,255,255,0.1)'}
                linkWidth={1}
                linkDirectionalParticles={1}
                linkDirectionalParticleSpeed={0.005}
                backgroundColor="#09090b"
                onNodeClick={(node) => {
                    setSelectedNode(node as Node);
                    graphRef.current?.centerAt(node.x, node.y, 1000);
                    graphRef.current?.zoom(4, 2000);
                }}
                onBackgroundClick={() => setSelectedNode(null)}
                // Custom Node Rendering for better aesthetic
                nodeCanvasObject={(node: any, ctx, globalScale) => {
                    const label = node.name;
                    const fontSize = 12 / globalScale;
                    ctx.font = `${fontSize}px Sans-Serif`;

                    // Draw node circle
                    const r = node.val;
                    ctx.beginPath();
                    ctx.arc(node.x, node.y, r, 0, 2 * Math.PI, false);
                    ctx.fillStyle = node.color || '#fff';
                    ctx.fill();

                    // Glow effect
                    ctx.shadowBlur = 10;
                    ctx.shadowColor = node.color;

                    // Draw label
                    if (globalScale > 1.5 || selectedNode?.id === node.id) {
                        ctx.textAlign = 'center';
                        ctx.textBaseline = 'middle';
                        ctx.fillStyle = 'rgba(255, 255, 255, 0.8)';
                        ctx.fillText(label, node.x, node.y + r + fontSize);
                    }

                    ctx.shadowBlur = 0; // Reset
                }}
            />
        );
    }, [data, dimensions, selectedNode]);

    return (
        <div className="h-screen w-full relative bg-[#09090b] flex flex-col overflow-hidden">
            {/* Header Overlay */}
            <div className="absolute top-0 left-0 right-0 z-10 p-6 flex justify-between items-start pointer-events-none">
                <div>
                    <h1 className="text-2xl font-bold text-white tracking-tight pointer-events-auto">Knowledge Graph</h1>
                    <p className="text-zinc-400 text-sm pointer-events-auto">Visualizing {data.nodes.length} nodes and {data.links.length} connections</p>
                </div>

                <div className="flex gap-2 pointer-events-auto">
                    <button
                        onClick={fetchGraph}
                        className="p-2 bg-white/5 hover:bg-white/10 text-white rounded-lg backdrop-blur-md border border-white/5 transition-all"
                    >
                        <RefreshCw size={20} className={loading ? "animate-spin" : ""} />
                    </button>
                    <button className="p-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg shadow-lg shadow-indigo-500/20 transition-all">
                        <Share2 size={20} />
                    </button>
                </div>
            </div>

            {/* Graph Canvas */}
            <div ref={containerRef} className="flex-1 w-full h-full">
                {!loading && graphComponent}
            </div>

            {/* Loading State */}
            {loading && (
                <div className="absolute inset-0 flex items-center justify-center bg-[#09090b]/80 backdrop-blur-sm z-20">
                    <div className="flex flex-col items-center gap-4">
                        <div className="w-8 h-8 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin" />
                        <span className="text-zinc-400 text-sm animate-pulse">Constructing Graph...</span>
                    </div>
                </div>
            )}

            {/* Controls Overlay */}
            <div className="absolute bottom-6 right-6 flex flex-col gap-2 z-10 pointer-events-auto">
                <button
                    onClick={() => graphRef.current?.zoomToFit(1000, 50)}
                    className="p-2 bg-zinc-900/80 hover:bg-zinc-800 text-white rounded-lg border border-white/5 backdrop-blur-md"
                    title="Fit to Screen"
                >
                    <ZoomIn size={20} />
                </button>
            </div>

            {/* Node Details Panel */}
            {selectedNode && (
                <div className="absolute top-24 left-6 w-80 bg-[#18181b]/90 backdrop-blur-xl border border-white/10 p-5 rounded-2xl shadow-2xl z-20 animate-slide-up pointer-events-auto">
                    <div className="flex justify-between items-start mb-4">
                        <div>
                            <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full bg-indigo-500/20 text-indigo-300 border border-indigo-500/20">
                                {selectedNode.type}
                            </span>
                            <h2 className="text-xl font-bold text-white mt-2 leading-tight">{selectedNode.name}</h2>
                        </div>
                        <button
                            onClick={() => setSelectedNode(null)}
                            className="text-zinc-500 hover:text-white transition-colors"
                        >
                            <X size={18} />
                        </button>
                    </div>

                    <div className="space-y-3 text-sm text-zinc-400">
                        {selectedNode.metadata && Object.keys(selectedNode.metadata).length > 0 ? (
                            Object.entries(selectedNode.metadata).map(([k, v]) => (
                                <div key={k} className="flex gap-2">
                                    <span className="text-zinc-500 uppercase text-xs font-semibold w-16 shrink-0">{k}:</span>
                                    <span className="text-zinc-300 wrap-break-word flex-1">{String(v)}</span>
                                </div>
                            ))
                        ) : (
                            <p className="italic text-xs opacity-50">No additional metadata</p>
                        )}

                        <div className="pt-4 mt-4 border-t border-white/5 flex gap-2">
                            <button className="flex-1 py-2 bg-white/5 hover:bg-white/10 rounded-lg text-xs font-medium text-white transition-colors border border-white/5">
                                Find Related
                            </button>
                            <button className="flex-1 py-2 bg-indigo-600/20 hover:bg-indigo-600/30 text-indigo-300 border border-indigo-500/30 rounded-lg text-xs font-medium transition-colors">
                                Ask Chat
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
