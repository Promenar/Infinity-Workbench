import React, { useRef, useState, useEffect } from 'react';
import { Download, Upload, CheckCircle, AlertCircle, HardDrive, Cloud, Server, User, Key, Globe, Eye, EyeOff } from 'lucide-react';
import { GlassCard } from '../../components/ui/glass-card';
import { useI18n } from '../../lib/i18n';
import { workbenchClient } from '../../services/WorkbenchClient';
import clsx from 'clsx';

interface WebDavConfig {
    url?: string;
    username?: string;
    password?: string;
    enabled?: boolean;
    autoBackup?: boolean;
}

export const BackupSection = () => {
    const { t } = useI18n();
    const fileInputRef = useRef<HTMLInputElement>(null);
    const [importStatus, setImportStatus] = useState<'idle' | 'success' | 'error'>('idle');
    const [config, setConfig] = useState<WebDavConfig>({
        url: '',
        username: '',
        password: '',
        enabled: false,
        autoBackup: false
    });

    const [showPassword, setShowPassword] = useState(false);
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        loadConfig();
    }, []);

    const loadConfig = async () => {
        try {
            const data = await workbenchClient.getWebDavConfig();
            setConfig((prev: WebDavConfig) => ({ ...prev, ...data }));
        } catch (e) {
            console.error('Failed to load WebDAV config', e);
        }
    };

    const handleSave = async (newConfig: any) => {
        setSaving(true);
        try {
            await workbenchClient.updateWebDavConfig(newConfig);
            setConfig(newConfig);
        } catch (e) {
            console.error('Failed to save WebDAV config', e);
            alert(t.common.error);
        } finally {
            setSaving(false);
        }
    };

    const handleChange = (key: string, value: any) => {
        const newConfig = { ...config, [key]: value };
        setConfig(newConfig);
        // Auto-save on toggle? or Add Save button? 
        // For text fields, usually wait for blur or explicit save.
        // For switches, auto-save.
        if (key === 'enabled' || key === 'autoBackup') {
            handleSave(newConfig);
        }
    };

    const handleExport = () => {
        const data = {
            version: '1.0',
            timestamp: Date.now(),
            // We can't easily export full App state from Web Client side unless we fetch it all.
            // But per previous implementation, we were exporting 'config'. 
            // Ideally we should call a backend command to generate the export, but for now let's keep it simple:
            // Just export the *Web* client knowledge or requesting `workbenchClient.getConfig()`
            config: config,
        };
        // NOTE: Real full backup requires backend command. 
        // Just exporting local config for now as placeholder for "Client Side Config Backup".

        const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `nexara_web_config_${new Date().toISOString().split('T')[0]}.json`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    };

    const handleImport = (event: React.ChangeEvent<HTMLInputElement>) => {
        const file = event.target.files?.[0];
        if (!file) return;

        const reader = new FileReader();
        reader.onload = async (e) => {
            try {
                const content = e.target?.result as string;
                const data = JSON.parse(content);

                if (data.config) {
                    await workbenchClient.updateWebDavConfig(data.config); // Only restoring WebDAV config for now
                    // Updating global config if available...
                    setImportStatus('success');
                    setTimeout(() => setImportStatus('idle'), 3000);
                    loadConfig();
                } else {
                    throw new Error('Invalid backup format');
                }
            } catch (err) {
                console.error('Import failed', err);
                setImportStatus('error');
                setTimeout(() => setImportStatus('idle'), 3000);
            }
        };
        reader.readAsText(file);
    };

    return (
        <div className="space-y-6">
            <div className="flex items-center gap-3">
                <div className="p-2 bg-emerald-500/10 rounded-lg text-emerald-400">
                    <HardDrive size={24} />
                </div>
                <div>
                    <h2 className="text-xl font-bold text-white">{t.settings.backup.title}</h2>
                    <p className="text-sm text-zinc-400">{t.settings.backup.description}</p>
                </div>
            </div>

            {/* Local Backup */}
            <GlassCard className="p-8">
                <h3 className="text-lg font-bold text-white mb-6 flex items-center gap-2">
                    <Download size={18} />
                    {t.settings.backup.localBackup}
                </h3>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                    <div className="flex flex-col items-center justify-center p-6 rounded-2xl bg-white/5 border border-white/5 hover:bg-white/10 transition-colors group text-center cursor-pointer"
                        onClick={handleExport}>
                        <div className="w-12 h-12 rounded-full bg-indigo-500/20 flex items-center justify-center mb-4 group-hover:scale-110 transition-transform">
                            <Download size={24} className="text-indigo-400" />
                        </div>
                        <h3 className="text-sm font-bold text-white mb-1">{t.settings.backup.export}</h3>
                        <p className="text-xs text-zinc-500">JSON Format</p>
                    </div>

                    <div className="flex flex-col items-center justify-center p-6 rounded-2xl bg-white/5 border border-white/5 hover:bg-white/10 transition-colors group text-center cursor-pointer relative overflow-hidden"
                        onClick={() => fileInputRef.current?.click()}>
                        <input
                            type="file"
                            ref={fileInputRef}
                            onChange={handleImport}
                            accept=".json"
                            className="hidden"
                        />
                        <div className="w-12 h-12 rounded-full bg-pink-500/20 flex items-center justify-center mb-4 group-hover:scale-110 transition-transform">
                            <Upload size={24} className="text-pink-400" />
                        </div>
                        <h3 className="text-sm font-bold text-white mb-1">{t.settings.backup.import}</h3>
                        <p className="text-xs text-zinc-500">Restore Config</p>

                        {importStatus === 'success' && (
                            <div className="absolute inset-0 bg-emerald-500/20 backdrop-blur-sm flex items-center justify-center flex-col animate-in fade-in">
                                <CheckCircle size={32} className="text-emerald-400 mb-2" />
                            </div>
                        )}
                        {importStatus === 'error' && (
                            <div className="absolute inset-0 bg-red-500/20 backdrop-blur-sm flex items-center justify-center flex-col animate-in fade-in">
                                <AlertCircle size={32} className="text-red-400 mb-2" />
                            </div>
                        )}
                    </div>
                </div>
            </GlassCard>

            {/* WebDAV Cloud */}
            <GlassCard className="p-8">
                <div className="flex items-center justify-between mb-6">
                    <h3 className="text-lg font-bold text-white flex items-center gap-2">
                        <Cloud size={18} className="text-sky-400" />
                        {t.settings.backup.webdav.title}
                    </h3>
                    <label className="relative inline-flex items-center cursor-pointer">
                        <input
                            type="checkbox"
                            checked={config.enabled ?? false}
                            onChange={(e) => handleChange('enabled', e.target.checked)}
                            className="sr-only peer"
                        />
                        <div className="w-9 h-5 bg-zinc-700 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-sky-500"></div>
                    </label>
                </div>

                <div className={clsx("space-y-4 transition-all", !config.enabled && "opacity-50 pointer-events-none")}>
                    {/* URL */}
                    <div className="space-y-2">
                        <label className="text-xs font-bold text-zinc-400 uppercase tracking-wider flex items-center gap-2">
                            <Globe size={14} /> {t.settings.backup.webdav.server}
                        </label>
                        <div className="relative">
                            <input
                                type="text"
                                value={config.url}
                                onChange={(e) => handleChange('url', e.target.value)}
                                placeholder="https://dav.example.com/remote.php/webdav/"
                                className="w-full bg-black/20 border border-white/10 rounded-xl px-4 py-3 pl-10 text-white focus:border-sky-500 outline-none transition-colors"
                            />
                            <Server size={16} className="absolute left-3 top-3.5 text-zinc-500" />
                        </div>
                    </div>

                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        {/* Username */}
                        <div className="space-y-2">
                            <label className="text-xs font-bold text-zinc-400 uppercase tracking-wider flex items-center gap-2">
                                <User size={14} /> {t.settings.backup.webdav.username}
                            </label>
                            <input
                                type="text"
                                value={config.username}
                                onChange={(e) => handleChange('username', e.target.value)}
                                className="w-full bg-black/20 border border-white/10 rounded-xl px-4 py-3 text-white focus:border-sky-500 outline-none transition-colors"
                            />
                        </div>

                        {/* Password */}
                        <div className="space-y-2">
                            <label className="text-xs font-bold text-zinc-400 uppercase tracking-wider flex items-center gap-2">
                                <Key size={14} /> {t.settings.backup.webdav.password}
                            </label>
                            <div className="relative">
                                <input
                                    type={showPassword ? "text" : "password"}
                                    value={config.password}
                                    onChange={(e) => handleChange('password', e.target.value)}
                                    className="w-full bg-black/20 border border-white/10 rounded-xl px-4 py-3 pr-10 text-white focus:border-sky-500 outline-none transition-colors"
                                />
                                <button
                                    onClick={() => setShowPassword(!showPassword)}
                                    className="absolute right-3 top-3.5 text-zinc-500 hover:text-zinc-300"
                                >
                                    {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                                </button>
                            </div>
                        </div>
                    </div>

                    {/* Auto Backup Toggle */}
                    <label className="flex items-center gap-3 p-3 rounded-xl bg-white/5 border border-white/5 cursor-pointer hover:bg-white/10 transition-colors">
                        <input
                            type="checkbox"
                            checked={config.autoBackup ?? false}
                            onChange={(e) => handleChange('autoBackup', e.target.checked)}
                            className="rounded border-zinc-600 bg-zinc-800 text-sky-500 focus:ring-0"
                        />
                        <span className="text-sm font-medium text-zinc-300">{t.settings.backup.webdav.autoBackup}</span>
                    </label>

                    {/* Save Button */}
                    <div className="pt-4 flex justify-end">
                        <button
                            onClick={() => handleSave(config)}
                            disabled={saving}
                            className="px-6 py-2.5 rounded-xl bg-sky-600 hover:bg-sky-500 text-white font-bold transition-all disabled:opacity-50"
                        >
                            {saving ? t.settings.backup.webdav.saving : t.settings.backup.webdav.save}
                        </button>
                    </div>
                </div>
            </GlassCard>
        </div>
    );
};
