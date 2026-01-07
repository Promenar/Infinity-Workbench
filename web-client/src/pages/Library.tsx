import { useEffect, useState, useRef } from 'react';
import { workbenchClient } from '../services/WorkbenchClient';
import { Folder, FileText, Upload, Trash2, FolderPlus, ChevronRight, RefreshCw } from 'lucide-react';
import { clsx } from 'clsx';
import { useI18n } from '../lib/i18n';

interface RagDocument {
    id: string;
    title: string;
    type: string;
    fileSize: number;
    vectorized: number;
    folderId?: string;
    createdAt: number;
}

interface RagFolder {
    id: string;
    name: string;
    parentId?: string;
    childCount: number;
}

export const Library = () => {
    const { t } = useI18n();
    const [documents, setDocuments] = useState<RagDocument[]>([]);
    const [folders, setFolders] = useState<RagFolder[]>([]);
    const [loading, setLoading] = useState(true);
    const [uploading, setUploading] = useState(false);
    const [currentFolderId, setCurrentFolderId] = useState<string | undefined>(undefined);

    const fileInputRef = useRef<HTMLInputElement>(null);

    useEffect(() => {
        loadLibrary();
    }, []);

    const loadLibrary = async () => {
        setLoading(true);
        try {
            const data = await workbenchClient.getLibrary();
            setDocuments(data.documents);
            setFolders(data.folders);
        } catch (e) {
            console.error('Failed to load library', e);
        } finally {
            setLoading(false);
        }
    };

    const handleUploadClick = () => {
        fileInputRef.current?.click();
    };

    const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0];
        if (!file) return;

        setUploading(true);
        try {
            await workbenchClient.uploadFile(file, currentFolderId);
            await loadLibrary();
        } catch (error) {
            alert(t.common.error + ': ' + error);
        } finally {
            setUploading(false);
            if (fileInputRef.current) fileInputRef.current.value = '';
        }
    };

    const handleDeleteFile = async (id: string) => {
        if (!window.confirm(t.library.deleteFileConfirm)) return;
        try {
            await workbenchClient.deleteFile(id);
            await loadLibrary();
        } catch (e) {
            alert(t.common.error + ': ' + e);
        }
    };

    const handleCreateFolder = async () => {
        const name = prompt(t.library.newFolder + ':');
        if (!name) return;
        try {
            await workbenchClient.createFolder(name, currentFolderId);
            await loadLibrary();
        } catch (e) {
            alert(t.common.error + ': ' + e);
        }
    };

    const handleDeleteFolder = async (id: string) => {
        if (!window.confirm(t.library.deleteFolderConfirm)) return;
        try {
            await workbenchClient.deleteFolder(id);
            await loadLibrary();
        } catch (e) {
            alert(t.common.error + ': ' + e);
        }
    }

    // Filter view
    const currentDocs = documents.filter(d => d.folderId === currentFolderId);
    const currentSubFolders = folders.filter(f => f.parentId === currentFolderId);

    // Breadcrumbs
    const getBreadcrumbs = () => {
        const crumbs = [{ id: undefined, name: 'Root' }];
        let curr = currentFolderId;
        const path = [];
        while (curr) {
            const f = folders.find(f => f.id === curr);
            if (f) {
                path.unshift({ id: f.id, name: f.name });
                curr = f.parentId;
            } else {
                break;
            }
        }
        return [...crumbs, ...path];
    };

    return (
        <div className="flex flex-col h-full bg-[#09090b] text-white p-6 relative overflow-hidden">
            {/* Ambient Background */}
            <div className="absolute top-0 right-0 w-[500px] h-[500px] bg-emerald-500/5 blur-[120px] pointer-events-none" />

            <header className="flex justify-between items-center mb-6 z-10">
                <div>
                    <h1 className="text-2xl font-bold text-white tracking-tight">
                        {t.library.title}
                    </h1>
                    <p className="text-zinc-400 text-sm mt-1">{t.library.subtitle} ({documents.length} files)</p>
                </div>
                <div className="flex gap-2">
                    <button onClick={loadLibrary} className="p-2.5 bg-white/5 hover:bg-white/10 border border-white/5 rounded-xl transition-colors text-zinc-400 hover:text-white">
                        <RefreshCw size={18} className={loading ? 'animate-spin' : ''} />
                    </button>
                    <button onClick={handleCreateFolder} className="flex items-center gap-2 px-4 py-2.5 bg-white/5 hover:bg-white/10 border border-white/5 rounded-xl transition-colors text-sm font-medium">
                        <FolderPlus size={16} />
                        {t.library.newFolder}
                    </button>
                    <button
                        onClick={handleUploadClick}
                        disabled={uploading}
                        className="flex items-center gap-2 px-4 py-2.5 bg-emerald-600 hover:bg-emerald-500 text-white rounded-xl transition-all shadow-lg shadow-emerald-600/20 active:scale-95 text-sm font-medium disabled:opacity-50"
                    >
                        <Upload size={16} />
                        {uploading ? t.library.uploading : t.library.upload}
                    </button>
                    <input
                        type="file"
                        ref={fileInputRef}
                        className="hidden"
                        accept=".txt,.md,.json,.csv"
                        onChange={handleFileChange}
                    />
                </div>
            </header>

            {/* Breadcrumbs */}
            <div className="flex items-center gap-2 mb-4 text-sm text-zinc-500 z-10">
                {getBreadcrumbs().map((crumb, i) => (
                    <div key={i} className="flex items-center gap-1">
                        {i > 0 && <ChevronRight size={14} className="text-zinc-600" />}
                        <button
                            onClick={() => setCurrentFolderId(crumb.id as string)}
                            className={clsx(
                                "transition-colors hover:text-emerald-400",
                                crumb.id === currentFolderId ? "text-white font-medium" : "text-zinc-500"
                            )}
                        >
                            {crumb.name || 'Root'}
                        </button>
                    </div>
                ))}
            </div>

            {/* Content Area */}
            <div className="flex-1 bg-[#18181b]/40 backdrop-blur-xl rounded-2xl border border-white/5 overflow-hidden flex flex-col shadow-2xl z-10">
                <div className="grid grid-cols-12 gap-4 p-4 border-b border-white/5 text-[10px] font-bold text-zinc-500 uppercase tracking-wider bg-white/2">
                    <div className="col-span-6">{t.library.name}</div>
                    <div className="col-span-2">{t.library.type}</div>
                    <div className="col-span-2">{t.library.size}</div>
                    <div className="col-span-2 text-right">{t.library.actions}</div>
                </div>

                <div className="flex-1 overflow-auto p-2 space-y-1 custom-scrollbar">
                    {/* Folders */}
                    {currentSubFolders.map(folder => (
                        <div
                            key={folder.id}
                            onClick={() => setCurrentFolderId(folder.id)}
                            className="grid grid-cols-12 gap-4 p-3 rounded-xl hover:bg-white/5 cursor-pointer items-center group transition-colors border border-transparent hover:border-white/5"
                        >
                            <div className="col-span-6 flex items-center gap-3 text-zinc-200 font-medium group-hover:text-white transition-colors">
                                <div className="p-1.5 bg-blue-500/10 rounded-lg text-blue-400">
                                    <Folder size={18} className="fill-blue-500/20" />
                                </div>
                                {folder.name}
                            </div>
                            <div className="col-span-2 text-zinc-600 text-xs">-</div>
                            <div className="col-span-2 text-zinc-500 text-xs font-mono">
                                {folder.childCount || 0} items
                            </div>
                            <div className="col-span-2 flex justify-end opacity-0 group-hover:opacity-100">
                                <button
                                    onClick={(e) => { e.stopPropagation(); handleDeleteFolder(folder.id); }}
                                    className="p-1.5 hover:bg-red-500/10 text-zinc-500 hover:text-red-400 rounded-lg transition-colors"
                                >
                                    <Trash2 size={16} />
                                </button>
                            </div>
                        </div>
                    ))}

                    {/* Files */}
                    {currentDocs.map(doc => (
                        <div key={doc.id} className="grid grid-cols-12 gap-4 p-3 rounded-xl hover:bg-white/5 items-center group transition-colors border border-transparent hover:border-white/5">
                            <div className="col-span-6 flex items-center gap-3 text-zinc-300 group-hover:text-zinc-200">
                                <FileText className="text-zinc-500 group-hover:text-emerald-400 transition-colors" size={20} />
                                <span className="truncate">{doc.title}</span>
                            </div>
                            <div className="col-span-2">
                                <span className="px-2 py-0.5 rounded text-[10px] bg-white/5 text-zinc-400 uppercase tracking-wide border border-white/5">
                                    {doc.type}
                                </span>
                            </div>
                            <div className="col-span-2 text-zinc-500 text-xs font-mono">
                                {(doc.fileSize / 1024).toFixed(1)} KB
                            </div>
                            <div className="col-span-2 flex justify-end opacity-0 group-hover:opacity-100 gap-2">
                                <button
                                    onClick={() => handleDeleteFile(doc.id)}
                                    className="p-1.5 hover:bg-red-500/10 text-zinc-500 hover:text-red-400 rounded-lg transition-colors"
                                >
                                    <Trash2 size={16} />
                                </button>
                            </div>
                        </div>
                    ))}

                    {currentDocs.length === 0 && currentSubFolders.length === 0 && (
                        <div className="flex flex-col items-center justify-center h-64 text-zinc-600">
                            <div className="w-16 h-16 rounded-2xl bg-white/5 flex items-center justify-center mb-4">
                                <FolderPlus size={32} className="opacity-50" />
                            </div>
                            <p>{t.library.empty}</p>
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
};
