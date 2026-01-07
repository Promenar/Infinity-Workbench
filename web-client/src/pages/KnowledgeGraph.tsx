import { useEffect, useState, useRef, useMemo } from 'react';
import ForceGraph2D from 'react-force-graph-2d';
import { workbenchClient } from '../services/WorkbenchClient';
import { Share2, ZoomIn, RefreshCw, X, Search, Network } from 'lucide-react';
import { useI18n } from '../lib/i18n';
import { clsx } from 'clsx';
import { AnimatePresence, motion } from 'framer-motion';

interface Node {
    id: string;
    name: string;
    type: string;
    val: number; // For visualization size
    color?: string;
    metadata?: any;
    x?: number;
    y?: number;
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
    const { t } = useI18n();
    const [data, setData] = useState<GraphData>({ nodes: [], links: [] });
    const [loading, setLoading] = useState(false); // Initial loading handled by effects
    const [selectedNode, setSelectedNode] = useState<Node | null>(null);
    const [searchQuery, setSearchQuery] = useState('');
    const [filteredData, setFilteredData] = useState<GraphData>({ nodes: [], links: [] });
    const [activeTypes, setActiveTypes] = useState<Set<string>>(new Set());

    // Browser State
    const [activeTab, setActiveTab] = useState<'library' | 'sessions'>('library');
    const [libraryDocs, setLibraryDocs] = useState<any[]>([]);
    const [libraryFolders, setLibraryFolders] = useState<any[]>([]);
    const [sessions, setSessions] = useState<any[]>([]);
    const [selectedFolderId, setSelectedFolderId] = useState<string | null>(null);
    const [selectedDocIds, setSelectedDocIds] = useState<Set<string>>(new Set());
    const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null);

    const graphRef = useRef<any>(null);
    const containerRef = useRef<HTMLDivElement>(null);
    const [dimensions, setDimensions] = useState({ w: 800, h: 600 });
    const [showLegend, setShowLegend] = useState(false);

    // Initial Data Fetch
    useEffect(() => {
        const init = async () => {
            setLoading(true);
            try {
                const [libData, sessData] = await Promise.all([
                    workbenchClient.getLibrary(),
                    workbenchClient.getSessions()
                ]);
                setLibraryDocs(libData.documents);
                setLibraryFolders(libData.folders);
                setSessions(sessData);

                // Fetch full graph by default
                await fetchGraph({});
            } catch (e) {
                console.error("Failed to init KG page", e);
            } finally {
                setLoading(false);
            }
        };
        init();
    }, []);

    const fetchGraph = async (filters: { docIds?: string[], sessionId?: string }) => {
        setLoading(true);
        try {
            const raw = await workbenchClient.getGraph(filters);

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
            setFilteredData({ nodes, links });

            const types = new Set<string>();
            nodes.forEach((n: any) => types.add(n.type));
            setActiveTypes(types);
        } catch (e) {
            console.error("Failed to fetch graph", e);
        } finally {
            setLoading(false);
        }
    };

    // Scoped Fetch Triggers
    useEffect(() => {
        if (activeTab === 'library') {
            if (selectedDocIds.size > 0) {
                fetchGraph({ docIds: Array.from(selectedDocIds) });
            } else {
                fetchGraph({}); // All if none selected? Or empty? Defaulting to all for now.
            }
        }
    }, [selectedDocIds, activeTab]);

    useEffect(() => {
        if (activeTab === 'sessions' && selectedSessionId) {
            fetchGraph({ sessionId: selectedSessionId });
        }
    }, [selectedSessionId, activeTab]);


    const getNodeColor = (type: string) => {
        switch (type) {
            case 'concept': return '#818cf8';
            case 'person': return '#34d399';
            case 'org': return '#fbbf24';
            case 'location': return '#f472b6';
            case 'event': return '#a78bfa';
            default: return '#9ca3af';
        }
    };

    const handleResize = () => {
        if (containerRef.current) {
            setDimensions({
                w: containerRef.current.offsetWidth,
                h: containerRef.current.offsetHeight
            });
        }
    };

    useEffect(() => {
        window.addEventListener('resize', handleResize);
        handleResize(); // Initial
        // Delay slightly for layout settle
        setTimeout(handleResize, 100);
        return () => window.removeEventListener('resize', handleResize);
    }, [activeTab]); // Re-calc on tab switch change layout


    // Filter Logic (Client Side)
    useEffect(() => {
        if (!data.nodes.length) {
            setFilteredData({ nodes: [], links: [] });
            return;
        }

        let nodes = data.nodes;
        let links = data.links;

        nodes = nodes.filter(n => activeTypes.has(n.type));

        if (searchQuery.trim()) {
            const lower = searchQuery.toLowerCase();
            nodes = nodes.filter(n => n.name.toLowerCase().includes(lower) || n.type.includes(lower));
        }

        const nodeIds = new Set(nodes.map(n => n.id));
        links = links.filter(l => {
            const s = typeof l.source === 'object' ? (l.source as any).id : l.source;
            const t = typeof l.target === 'object' ? (l.target as any).id : l.target;
            return nodeIds.has(s) && nodeIds.has(t);
        });

        setFilteredData({ nodes, links });
    }, [data, searchQuery, activeTypes]);

    const handleNodeClick = (node: Node) => {
        setSelectedNode(node);
        graphRef.current?.centerAt(node.x, node.y, 1000);
        graphRef.current?.zoom(4, 2000);
    };

    const toggleType = (type: string) => {
        const next = new Set(activeTypes);
        if (next.has(type)) next.delete(type);
        else next.add(type);
        setActiveTypes(next);
    };

    // --- Browser UI Helpers ---
    const renderLibraryTree = () => {
        const currentFiles = libraryDocs.filter(d => d.folderId === (selectedFolderId || undefined)); // API uses undefined for root
        const currentSubFolders = libraryFolders.filter(f => f.parentId === (selectedFolderId || undefined));

        // Back navigation
        const currentFolder = libraryFolders.find(f => f.id === selectedFolderId);

        return (
            <div className="flex flex-col h-full">
                {selectedFolderId && (
                    <button
                        onClick={() => setSelectedFolderId(currentFolder?.parentId || null)}
                        className="flex items-center gap-2 p-2 text-zinc-400 hover:text-white hover:bg-white/5 rounded-lg mb-2 text-sm transition-colors"
                    >
                        <div className="rotate-180"><div className="w-0 h-0 border-l-[6px] border-l-transparent border-r-[6px] border-r-transparent border-b-8 border-b-current" /></div> {/* Hacky Back Icon or use lucide */}
                        Back to {currentFolder?.parentId ? 'Parent' : 'Root'}
                    </button>
                )}

                <div className="space-y-1 flex-1 overflow-y-auto custom-scrollbar pr-2">
                    {/* Folders */}
                    {currentSubFolders.map(folder => (
                        <div
                            key={folder.id}
                            onClick={() => setSelectedFolderId(folder.id)}
                            className="flex items-center gap-2 p-2 rounded-lg hover:bg-white/5 cursor-pointer group transition-colors"
                        >
                            <div className="text-blue-400/80 group-hover:text-blue-400"><Network size={16} /></div> {/* Reusing icons */}
                            <span className="text-sm text-zinc-300 group-hover:text-white truncate">{folder.name}</span>
                        </div>
                    ))}

                    {/* Files - Selection based */}
                    {currentFiles.map(doc => {
                        const isSelected = selectedDocIds.has(doc.id);
                        return (
                            <div
                                key={doc.id}
                                onClick={() => {
                                    const next = new Set(selectedDocIds);
                                    if (next.has(doc.id)) next.delete(doc.id);
                                    else next.add(doc.id);
                                    setSelectedDocIds(next);
                                }}
                                className={clsx(
                                    "flex items-center gap-2 p-2 rounded-lg cursor-pointer transition-all border",
                                    isSelected
                                        ? "bg-indigo-500/10 border-indigo-500/30"
                                        : "hover:bg-white/5 border-transparent"
                                )}
                            >
                                <div className={clsx("w-3 h-3 rounded-full border flex items-center justify-center", isSelected ? "border-indigo-500 bg-indigo-500" : "border-zinc-600")}>
                                    {isSelected && <div className="w-1.5 h-1.5 bg-white rounded-full" />}
                                </div>
                                <span className={clsx("text-sm truncate flex-1", isSelected ? "text-indigo-200" : "text-zinc-400")}>{doc.title}</span>
                            </div>
                        )
                    })}

                    {currentFiles.length === 0 && currentSubFolders.length === 0 && (
                        <div className="text-center py-8 text-zinc-600 text-xs italic">{t.graph.browser.empty}</div>
                    )}
                </div>

                {/* Action: Clear Selection */}
                {selectedDocIds.size > 0 && (
                    <button
                        onClick={() => setSelectedDocIds(new Set())}
                        className="mt-4 w-full py-2 bg-white/5 hover:bg-white/10 border border-white/5 rounded-lg text-xs text-white transition-colors"
                    >
                        Show All Files
                    </button>
                )}
            </div>
        );
    };

    const renderSessionsList = () => {
        return (
            <div className="flex flex-col h-full space-y-1 overflow-y-auto custom-scrollbar pr-2">
                {sessions.map(s => (
                    <div
                        key={s.id}
                        onClick={() => setSelectedSessionId(s.id)}
                        className={clsx(
                            "p-3 rounded-lg cursor-pointer transition-all border",
                            selectedSessionId === s.id
                                ? "bg-emerald-500/10 border-emerald-500/30"
                                : "hover:bg-white/5 border-transparent"
                        )}
                    >
                        <h4 className={clsx("text-sm font-medium mb-1 truncate", selectedSessionId === s.id ? "text-emerald-300" : "text-zinc-300")}>
                            {s.title || 'United Session'}
                        </h4>
                        <p className="text-[10px] text-zinc-500">{new Date(s.lastUpdated).toLocaleDateString()}</p>
                    </div>
                ))}
                {sessions.length === 0 && <div className="text-center py-8 text-zinc-600 text-xs italic">{t.graph.browser.empty}</div>}
            </div>
        )
    }

    const graphComponent = useMemo(() => (
        <ForceGraph2D
            ref={graphRef}
            width={dimensions.w}
            height={dimensions.h}
            graphData={filteredData}
            nodeLabel="name"
            nodeColor="color"
            nodeRelSize={6}
            linkColor={() => 'rgba(255,255,255,0.1)'}
            linkWidth={1}
            linkDirectionalParticles={1}
            linkDirectionalParticleSpeed={0.005}
            backgroundColor="#09090b"
            onNodeClick={(node) => handleNodeClick(node as Node)}
            onBackgroundClick={() => setSelectedNode(null)}
            nodeCanvasObject={(node: any, ctx, globalScale) => {
                const label = node.name;
                const fontSize = 12 / globalScale;
                ctx.font = `${fontSize}px Sans-Serif`;
                const r = node.val;
                ctx.beginPath();
                ctx.arc(node.x, node.y, r, 0, 2 * Math.PI, false);
                ctx.fillStyle = node.color || '#fff';
                ctx.fill();
                ctx.shadowBlur = 10;
                ctx.shadowColor = node.color;
                if (globalScale > 1.5 || selectedNode?.id === node.id) {
                    ctx.textAlign = 'center';
                    ctx.textBaseline = 'middle';
                    ctx.fillStyle = 'rgba(255, 255, 255, 0.8)';
                    ctx.fillText(label, node.x, node.y + r + fontSize);
                }
                ctx.shadowBlur = 0;
            }}
        />
    ), [filteredData, dimensions, selectedNode, activeTypes]);

    return (
        <div className="h-screen w-full flex bg-[#09090b] overflow-hidden">
            {/* Sidebar Browser */}
            <div className="w-80 border-r border-white/5 flex flex-col bg-[#09090b]">
                <div className="p-4 border-b border-white/5">
                    <h2 className="text-sm font-bold text-zinc-400 uppercase tracking-wider mb-4 px-1">{t.graph.title}</h2>
                    {/* Tab Switcher */}
                    <div className="flex bg-white/5 rounded-lg p-1">
                        <button
                            onClick={() => setActiveTab('library')}
                            className={clsx("flex-1 py-1.5 text-xs font-medium rounded-md transition-all", activeTab === 'library' ? "bg-zinc-700 text-white shadow-sm" : "text-zinc-400 hover:text-zinc-200")}
                        >
                            {t.graph.browser.tabs.library}
                        </button>
                        <button
                            onClick={() => setActiveTab('sessions')}
                            className={clsx("flex-1 py-1.5 text-xs font-medium rounded-md transition-all", activeTab === 'sessions' ? "bg-zinc-700 text-white shadow-sm" : "text-zinc-400 hover:text-zinc-200")}
                        >
                            {t.graph.browser.tabs.sessions}
                        </button>
                    </div>
                </div>

                <div className="flex-1 overflow-hidden p-4">
                    {activeTab === 'library' ? renderLibraryTree() : renderSessionsList()}
                </div>
            </div>

            {/* Main Content */}
            <div className="flex-1 relative flex flex-col h-full bg-[#09090b]">
                {/* Header Overlay */}
                <div className="absolute top-0 left-0 right-0 z-10 p-6 flex justify-between items-start pointer-events-none">
                    <div className="pointer-events-auto">
                        {/* Title removed, kept sidebar title. Showing stats here maybe? */}
                        <div className="flex items-center gap-2">
                            <span className="text-xs font-medium text-zinc-500 bg-black/40 backdrop-blur px-3 py-1 rounded-full border border-white/10">
                                {filteredData.nodes.length} Nodes · {filteredData.links.length} Edges
                            </span>
                        </div>
                    </div>

                    <div className="flex gap-2 pointer-events-auto">
                        <div className="relative group">
                            <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                                <Search size={14} className="text-zinc-500" />
                            </div>
                            <input
                                type="text"
                                value={searchQuery}
                                onChange={(e) => setSearchQuery(e.target.value)}
                                placeholder={t.graph.searchPlaceholder}
                                className="bg-black/40 backdrop-blur border border-white/10 rounded-xl pl-9 pr-3 py-2 text-sm text-white focus:border-indigo-500 outline-none w-64 transition-all"
                            />
                        </div>

                        <button onClick={() => fetchGraph({})} className="p-2 bg-white/5 hover:bg-white/10 text-white rounded-lg backdrop-blur-md border border-white/5 transition-all" title="Reset & Refresh">
                            <RefreshCw size={20} className={loading ? "animate-spin" : ""} />
                        </button>
                        <button onClick={() => setShowLegend(!showLegend)} className={clsx("p-2 rounded-lg backdrop-blur-md border border-white/5 transition-all", showLegend ? "bg-indigo-600/20 text-indigo-400 border-indigo-500/30" : "bg-white/5 text-white hover:bg-white/10")} title={t.graph.legend.tooltip}>
                            <Share2 size={20} />
                        </button>
                    </div>
                </div>

                {/* Graph Canvas */}
                <div ref={containerRef} className="flex-1 w-full h-full relative">
                    {!loading && graphComponent}

                    {/* Controls Overlay (Zoom) */}
                    <div className="absolute bottom-6 right-6 flex flex-col gap-2 z-10 pointer-events-auto">
                        <button onClick={() => graphRef.current?.zoomToFit(1000, 50)} className="p-2 bg-zinc-900/80 hover:bg-zinc-800 text-white rounded-lg border border-white/5 backdrop-blur-md" title="Reset Zoom">
                            <ZoomIn size={20} />
                        </button>
                    </div>
                </div>

                {/* Loading State - Overlay within Main Content */}
                {loading && (
                    <div className="absolute inset-0 flex items-center justify-center bg-[#09090b]/80 backdrop-blur-sm z-20 pointer-events-auto">
                        <div className="flex flex-col items-center gap-4">
                            <div className="w-8 h-8 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin" />
                            <span className="text-zinc-400 text-sm animate-pulse">Computing Graph...</span>
                        </div>
                    </div>
                )}

                {/* Empty State */}
                {!loading && data.nodes.length === 0 && (
                    <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
                        <div className="bg-zinc-900/90 backdrop-blur border border-white/10 p-6 rounded-2xl max-w-sm text-center">
                            <Network className="w-12 h-12 text-zinc-600 mx-auto mb-4" />
                            <p className="text-zinc-400 text-sm">{t.graph.noData || 'No graph data in this scope.'}</p>
                        </div>
                    </div>
                )}

                {/* Legend */}
                <AnimatePresence>
                    {showLegend && (
                        <div className="absolute top-20 right-6 w-48 bg-[#18181b]/90 backdrop-blur-xl border border-white/10 p-4 rounded-xl shadow-2xl z-20 pointer-events-auto">
                            <h3 className="text-xs font-bold text-zinc-400 uppercase mb-3">{t.graph.legend.title}</h3>
                            <div className="space-y-2">
                                {Array.from(new Set(data.nodes.map(n => n.type))).map(type => (
                                    <button key={type} onClick={() => toggleType(type)} className="flex items-center gap-2 w-full hover:bg-white/5 p-1.5 rounded-lg transition-colors group">
                                        <div className={clsx("w-3 h-3 rounded-full transition-opacity", !activeTypes.has(type) && "opacity-20")} style={{ backgroundColor: getNodeColor(type) }} />
                                        <span className={clsx("text-sm capitalize transition-colors", activeTypes.has(type) ? "text-zinc-300" : "text-zinc-600")}>{type}</span>
                                    </button>
                                ))}
                            </div>
                        </div>
                    )}
                </AnimatePresence>

                {/* Node Details Panel */}
                <AnimatePresence>
                    {selectedNode && (
                        <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 20 }} className="absolute bottom-6 left-6 w-80 bg-[#18181b]/90 backdrop-blur-xl border border-white/10 p-5 rounded-2xl shadow-2xl z-20 pointer-events-auto">
                            <div className="flex justify-between items-start mb-4">
                                <div>
                                    <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full border bg-white/5" style={{ borderColor: selectedNode.color, color: selectedNode.color }}>{selectedNode.type}</span>
                                    <h2 className="text-xl font-bold text-white mt-2 leading-tight">{selectedNode.name}</h2>
                                </div>
                                <button onClick={() => setSelectedNode(null)} className="text-zinc-500 hover:text-white transition-colors"><X size={18} /></button>
                            </div>
                            <div className="space-y-3 text-sm text-zinc-400 max-h-64 overflow-y-auto custom-scrollbar">
                                {selectedNode.metadata && Object.keys(selectedNode.metadata).length > 0 ? Object.entries(selectedNode.metadata).map(([k, v]) => (
                                    <div key={k} className="flex flex-col gap-1">
                                        <span className="text-zinc-500 uppercase text-[10px] font-bold">{k}</span>
                                        <span className="text-zinc-300 break-all bg-black/20 p-2 rounded-lg">{String(v)}</span>
                                    </div>
                                )) : <p className="italic text-xs opacity-50">No additional details</p>}
                            </div>
                        </motion.div>
                    )}
                </AnimatePresence>
            </div>
        </div>
    );
}
