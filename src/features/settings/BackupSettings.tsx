
import React, { useState, useEffect } from 'react';
import { View, Text, Switch, TouchableOpacity, ScrollView, Alert, TextInput, Modal, ActivityIndicator, FlatList, Platform } from 'react-native';
import { KeyboardAvoidingView } from 'react-native-keyboard-controller';
import { useI18n } from '../../lib/i18n';
import { useTheme } from '../../theme/ThemeProvider';
import { Cloud, Upload, Download, Save, RefreshCw, X, Folder, FileJson, CheckCircle } from 'lucide-react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as DocumentPicker from 'expo-document-picker';
import * as FileSystem from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { BackupManager, BackupData } from '../../lib/backup/BackupManager';
import { WebDavClient, WebDavFile } from '../../lib/backup/WebDavClient';

export function BackupSettings() {
    const { isDark } = useTheme();
    const { t } = useI18n();
    const [loading, setLoading] = useState(false);
    const [status, setStatus] = useState<string>('');

    // WebDAV Config
    const [webDavConfig, setWebDavConfig] = useState({
        url: '',
        username: '',
        password: '',
        enabled: false
    });
    const [showWebDavModal, setShowWebDavModal] = useState(false);
    const [remoteFiles, setRemoteFiles] = useState<WebDavFile[]>([]);
    const [showRemoteFilesModal, setShowRemoteFilesModal] = useState(false);

    useEffect(() => {
        loadConfig();
    }, []);

    const loadConfig = async () => {
        const json = await AsyncStorage.getItem('backup_config');
        if (json) {
            setWebDavConfig(JSON.parse(json));
        }
    };

    const saveConfig = async (newConfig: any) => {
        setWebDavConfig(newConfig);
        await AsyncStorage.setItem('backup_config', JSON.stringify(newConfig));
    };

    // --- Local Operations ---

    const handleLocalExport = async () => {
        setLoading(true);
        setStatus(t.settings.backup.generating);
        try {
            const data = await BackupManager.exportData();
            const json = JSON.stringify(data, null, 2);

            const filename = `nexara_backup_${new Date().toISOString().split('T')[0]}.json`;
            const fileUri = ((FileSystem as any).documentDirectory || '') + filename;

            await FileSystem.writeAsStringAsync(fileUri, json);

            setStatus(t.settings.backup.backupCreated);

            if (await Sharing.isAvailableAsync()) {
                await Sharing.shareAsync(fileUri);
            } else {
                Alert.alert(t.settings.backup.backupCreated, `Saved to ${fileUri}`);
            }
        } catch (e: any) {
            Alert.alert(t.settings.backup.exportFailed, e.message);
        } finally {
            setLoading(false);
            setStatus('');
        }
    };

    const handleLocalImport = async () => {
        try {
            const result = await DocumentPicker.getDocumentAsync({
                type: 'application/json',
                copyToCacheDirectory: true
            });

            if (result.canceled) return;

            Alert.alert(
                t.settings.backup.restoreTitle,
                t.settings.backup.restoreWarning,
                [
                    { text: t.common.cancel, style: 'cancel' },
                    {
                        text: t.settings.backup.restoreAction,
                        style: 'destructive',
                        onPress: async () => {
                            setLoading(true);
                            setStatus(t.settings.backup.restoring);
                            try {
                                const content = await FileSystem.readAsStringAsync(result.assets[0].uri);
                                const backup: BackupData = JSON.parse(content);
                                await BackupManager.importData(backup);
                                Alert.alert(t.settings.backup.successTitle, t.settings.backup.restoreSuccess);
                            } catch (e: any) {
                                Alert.alert(t.settings.backup.importFailed, e.message);
                            } finally {
                                setLoading(false);
                                setStatus('');
                            }
                        }
                    }
                ]
            );

        } catch (e: any) {
            Alert.alert('Error', e.message);
        }
    };

    // --- WebDAV Operations ---

    const getClient = () => {
        if (!webDavConfig.url) throw new Error('WebDAV URL not configured');
        return new WebDavClient({
            url: webDavConfig.url,
            username: webDavConfig.username,
            password: webDavConfig.password
        });
    };

    const handleWebDavTest = async () => {
        setLoading(true);
        try {
            const client = getClient();
            await client.checkConnection();
            Alert.alert(t.settings.backup.connectionSuccess, t.settings.backup.connectionSuccessDesc);
        } catch (e: any) {
            Alert.alert(t.settings.backup.connectionFailed, e.message);
        } finally {
            setLoading(false);
        }
    };

    const handleWebDavUpload = async () => {
        if (!webDavConfig.enabled) {
            Alert.alert(t.settings.backup.webDavDisabled, t.settings.backup.webDavDisabledDesc);
            return;
        }

        setLoading(true);
        setStatus(t.settings.backup.uploading);
        try {
            const client = getClient();
            const data = await BackupManager.exportData();
            const json = JSON.stringify(data);
            const filename = `nexara_backup_${new Date().toISOString().replace(/[:.]/g, '-')}.json`;

            await client.uploadFile(filename, json);
            Alert.alert(t.settings.backup.successTitle, t.settings.backup.uploadSuccess);
        } catch (e: any) {
            Alert.alert(t.settings.backup.uploadFailed, e.message);
        } finally {
            setLoading(false);
            setStatus('');
        }
    };

    const handleWebDavRestore = async () => {
        if (!webDavConfig.enabled) {
            Alert.alert(t.settings.backup.webDavDisabled, t.settings.backup.webDavDisabledDesc);
            return;
        }

        setLoading(true);
        setStatus(t.settings.backup.fetchingList);
        try {
            const client = getClient();
            const files = await client.listFiles('/'); // List root
            // Filter for JSON files, sort by date desc
            const backups = files
                .filter(f => f.filename.endsWith('.json') && f.filename.includes('nexara'))
                .sort((a, b) => new Date(b.lastModified).getTime() - new Date(a.lastModified).getTime());

            setRemoteFiles(backups);
            setShowRemoteFilesModal(true);
        } catch (e: any) {
            Alert.alert(t.common.error, e.message);
        } finally {
            setLoading(false);
            setStatus('');
        }
    };

    const confirmRemoteRestore = (file: WebDavFile) => {
        setShowRemoteFilesModal(false);
        Alert.alert(
            t.settings.backup.confirmCloudRestore,
            t.settings.backup.confirmCloudRestoreDesc.replace('{filename}', file.filename),
            [
                { text: t.common.cancel, style: 'cancel' },
                {
                    text: t.settings.backup.restoreAction,
                    style: 'destructive',
                    onPress: async () => {
                        setLoading(true);
                        setStatus(t.settings.backup.downloading.replace('{filename}', file.filename));
                        try {
                            const client = getClient();
                            const content = await client.downloadFile(file.filename);
                            setStatus(t.settings.backup.restoring);
                            const backup: BackupData = JSON.parse(content);
                            await BackupManager.importData(backup);
                            Alert.alert(t.settings.backup.successTitle, t.settings.backup.restoreSuccess);
                        } catch (e: any) {
                            Alert.alert(t.settings.backup.importFailed, e.message);
                        } finally {
                            setLoading(false);
                            setStatus('');
                        }
                    }
                }
            ]
        );
    }

    return (
        <View className="mb-6">
            <Text className={`text-sm font-bold mb-4 uppercase tracking-wider ${isDark ? 'text-gray-400' : 'text-gray-500'}`}>
                {t.settings.backup.backupHeader}
            </Text>

            <View className={`rounded-xl overflow-hidden border ${isDark ? 'bg-zinc-900 border-zinc-800' : 'bg-white border-gray-200'}`}>

                {/* Local Actions */}
                <View className="p-4 border-b border-gray-100 dark:border-zinc-800">
                    <Text className={`font-medium mb-3 ${isDark ? 'text-gray-200' : 'text-gray-900'}`}>{t.settings.backup.localStorage}</Text>
                    <View className="flex-row gap-3">
                        <TouchableOpacity
                            onPress={handleLocalExport}
                            disabled={loading}
                            className={`flex-1 flex-row items-center justify-center p-3 rounded-lg ${isDark ? 'bg-zinc-800' : 'bg-gray-100'}`}
                        >
                            <Upload size={18} color={isDark ? '#fff' : '#000'} />
                            <Text className={`ml-2 font-medium ${isDark ? 'text-white' : 'text-black'}`}>{t.settings.backup.export}</Text>
                        </TouchableOpacity>
                        <TouchableOpacity
                            onPress={handleLocalImport}
                            disabled={loading}
                            className={`flex-1 flex-row items-center justify-center p-3 rounded-lg ${isDark ? 'bg-zinc-800' : 'bg-gray-100'}`}
                        >
                            <Download size={18} color={isDark ? '#fff' : '#000'} />
                            <Text className={`ml-2 font-medium ${isDark ? 'text-white' : 'text-black'}`}>{t.settings.backup.import}</Text>
                        </TouchableOpacity>
                    </View>
                </View>

                {/* WebDAV Settings */}
                <View className="p-4">
                    <View className="flex-row items-center justify-between mb-3">
                        <View className="flex-row items-center">
                            <Cloud size={20} color="#6366f1" />
                            <Text className={`ml-2 font-medium ${isDark ? 'text-gray-200' : 'text-gray-900'}`}>{t.settings.backup.webDavCloud}</Text>
                        </View>
                        <Switch
                            value={webDavConfig.enabled}
                            onValueChange={(v) => {
                                if (v && !webDavConfig.url) setShowWebDavModal(true);
                                saveConfig({ ...webDavConfig, enabled: v });
                            }}
                            trackColor={{ false: '#767577', true: '#6366f1' }}
                        />
                    </View>

                    {webDavConfig.enabled && (
                        <View>
                            <View className="flex-row items-center justify-between mb-3 border-b border-gray-100 dark:border-zinc-800 pb-3">
                                <Text className={`text-sm ${isDark ? 'text-gray-400' : 'text-gray-600'}`}>{t.settings.backup.autoBackup}</Text>
                                <Switch
                                    value={(webDavConfig as any).autoBackup || false}
                                    onValueChange={(v) => saveConfig({ ...webDavConfig, autoBackup: v })}
                                    trackColor={{ false: '#767577', true: '#6366f1' }}
                                />
                            </View>

                            <View className="flex-row gap-3 mb-3">
                                <TouchableOpacity
                                    onPress={handleWebDavUpload}
                                    disabled={loading}
                                    className={`flex-1 flex-row items-center justify-center p-3 rounded-lg ${isDark ? 'bg-zinc-800' : 'bg-gray-100'}`}
                                >
                                    <Upload size={18} color={isDark ? '#fff' : '#000'} />
                                    <Text className={`ml-2 font-medium ${isDark ? 'text-white' : 'text-black'}`}>{t.settings.backup.backupToCloud}</Text>
                                </TouchableOpacity>
                                <TouchableOpacity
                                    onPress={handleWebDavRestore}
                                    disabled={loading}
                                    className={`flex-1 flex-row items-center justify-center p-3 rounded-lg ${isDark ? 'bg-zinc-800' : 'bg-gray-100'}`}
                                >
                                    <Download size={18} color={isDark ? '#fff' : '#000'} />
                                    <Text className={`ml-2 font-medium ${isDark ? 'text-white' : 'text-black'}`}>{t.settings.backup.restoreFromCloud}</Text>
                                </TouchableOpacity>
                            </View>
                            <TouchableOpacity
                                onPress={() => setShowWebDavModal(true)}
                                className={`p-3 rounded-lg border ${isDark ? 'border-zinc-700 bg-zinc-800' : 'border-gray-200 bg-gray-50'} items-center`}
                            >
                                <Text className={`font-medium ${isDark ? 'text-gray-300' : 'text-gray-700'}`}>{t.settings.backup.configureServer}</Text>
                            </TouchableOpacity>
                        </View>
                    )}
                </View>

                {loading && (
                    <View className="absolute inset-0 bg-black/50 items-center justify-center z-50">
                        <View className={`p-4 rounded-xl items-center ${isDark ? 'bg-zinc-800' : 'bg-white'}`}>
                            <ActivityIndicator size="large" color="#6366f1" />
                            <Text className={`mt-2 font-medium ${isDark ? 'text-white' : 'text-black'}`}>{status}</Text>
                        </View>
                    </View>
                )}
            </View>

            {/* WebDAV Configuration Modal - Using Layout-Based Keyboard Avoidance Pattern */}
            <Modal visible={showWebDavModal} transparent animationType="slide">
                <View className="flex-1 justify-end bg-black/50">
                    <KeyboardAvoidingView
                        behavior={Platform.OS === 'ios' ? 'padding' : 'padding'}
                        keyboardVerticalOffset={Platform.OS === 'ios' ? 0 : 0}
                        style={{ width: '100%' }}
                    >
                        <View className={`rounded-t-3xl p-6 ${isDark ? 'bg-zinc-900' : 'bg-white'}`}>
                            <View className="flex-row justify-between items-center mb-6">
                                <Text className={`text-xl font-bold ${isDark ? 'text-white' : 'text-black'}`}>{t.settings.backup.settingsTitle}</Text>
                                <TouchableOpacity onPress={() => setShowWebDavModal(false)}>
                                    <X size={24} color={isDark ? '#fff' : '#000'} />
                                </TouchableOpacity>
                            </View>

                            <Text className={`text-sm mb-1 ${isDark ? 'text-gray-400' : 'text-gray-600'}`}>{t.settings.backup.serverUrl}</Text>
                            <TextInput
                                value={webDavConfig.url}
                                onChangeText={t => setWebDavConfig(prev => ({ ...prev, url: t.trim() }))}
                                className={`p-3 rounded-lg mb-4 border ${isDark ? 'bg-zinc-800 border-zinc-700 text-white' : 'bg-gray-50 border-gray-200 text-black'}`}
                                placeholder="https://..."
                                placeholderTextColor={isDark ? '#666' : '#999'}
                                autoCapitalize="none"
                            />

                            <Text className={`text-sm mb-1 ${isDark ? 'text-gray-400' : 'text-gray-600'}`}>{t.settings.backup.username}</Text>
                            <TextInput
                                value={webDavConfig.username}
                                onChangeText={t => setWebDavConfig(prev => ({ ...prev, username: t.trim() }))}
                                className={`p-3 rounded-lg mb-4 border ${isDark ? 'bg-zinc-800 border-zinc-700 text-white' : 'bg-gray-50 border-gray-200 text-black'}`}
                                autoCapitalize="none"
                            />

                            <Text className={`text-sm mb-1 ${isDark ? 'text-gray-400' : 'text-gray-600'}`}>{t.settings.backup.password}</Text>
                            <TextInput
                                value={webDavConfig.password}
                                onChangeText={t => setWebDavConfig(prev => ({ ...prev, password: t.trim() }))}
                                className={`p-3 rounded-lg mb-6 border ${isDark ? 'bg-zinc-800 border-zinc-700 text-white' : 'bg-gray-50 border-gray-200 text-black'}`}
                                secureTextEntry
                            />
                            <Text className={`text-xs mb-6 ${isDark ? 'text-gray-500' : 'text-gray-400'}`}>
                                {t.settings.backup.appPasswordHint || 'Note: If using 2FA/Nutstore, use an App Password.'}
                            </Text>

                            <View className="flex-row gap-3">
                                <TouchableOpacity
                                    onPress={handleWebDavTest}
                                    className={`flex-1 p-4 rounded-xl items-center border ${isDark ? 'border-zinc-700' : 'border-gray-300'}`}
                                >
                                    <Text className={`font-semibold ${isDark ? 'text-white' : 'text-black'}`}>{t.settings.backup.testConnection}</Text>
                                </TouchableOpacity>
                                <TouchableOpacity
                                    onPress={() => {
                                        saveConfig(webDavConfig);
                                        setShowWebDavModal(false);
                                    }}
                                    className="flex-1 bg-indigo-600 p-4 rounded-xl items-center"
                                >
                                    <Text className="text-white font-bold">{t.settings.backup.save}</Text>
                                </TouchableOpacity>
                            </View>
                        </View>
                    </KeyboardAvoidingView>
                </View>
            </Modal>

            {/* Remote Files List Modal */}
            <Modal visible={showRemoteFilesModal} transparent animationType="slide">
                <View className="flex-1 bg-black/50">
                    <View className={`flex-1 mt-20 rounded-t-3xl ${isDark ? 'bg-zinc-900' : 'bg-white'}`}>
                        <View className="p-4 border-b border-gray-100 dark:border-zinc-800 flex-row justify-between items-center">
                            <Text className={`text-lg font-bold ${isDark ? 'text-white' : 'text-black'}`}>{t.settings.backup.selectBackupTitle}</Text>
                            <TouchableOpacity onPress={() => setShowRemoteFilesModal(false)}>
                                <X size={24} color={isDark ? '#fff' : '#000'} />
                            </TouchableOpacity>
                        </View>

                        <FlatList
                            data={remoteFiles}
                            keyExtractor={item => item.filename}
                            contentContainerStyle={{ padding: 16 }}
                            renderItem={({ item }) => (
                                <TouchableOpacity
                                    onPress={() => confirmRemoteRestore(item)}
                                    className={`flex-row items-center p-4 mb-3 rounded-xl border ${isDark ? 'bg-zinc-800 border-zinc-700' : 'bg-gray-50 border-gray-200'}`}
                                >
                                    <FileJson size={24} color="#6366f1" />
                                    <View className="ml-3 flex-1">
                                        <Text className={`font-medium ${isDark ? 'text-white' : 'text-black'}`}>{item.filename}</Text>
                                        <Text className="text-xs text-gray-500 mt-1">
                                            {new Date(item.lastModified).toLocaleString()} • {(item.size / 1024 / 1024).toFixed(2)} MB
                                        </Text>
                                    </View>
                                </TouchableOpacity>
                            )}
                            ListEmptyComponent={
                                <View className="items-center py-10">
                                    <Text className="text-gray-500">{t.settings.backup.noBackupsFound}</Text>
                                </View>
                            }
                        />
                    </View>
                </View>
            </Modal>
        </View>
    );
}
