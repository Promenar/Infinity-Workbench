import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  ScrollView,
  TextInput,
  Modal,
  ActivityIndicator,
  FlatList,
  Platform,
  StyleSheet,
} from 'react-native';
import { Switch, CollapsibleSection } from '../../components/ui';
import { KeyboardAvoidingView } from 'react-native-keyboard-controller';
import { useI18n } from '../../lib/i18n';
import { useTheme } from '../../theme/ThemeProvider';
import { useToast } from '../../components/ui/Toast';
import {
  Cloud,
  Upload,
  Download,
  Save,
  RefreshCw,
  X,
  Folder,
  FileJson,
  CheckCircle,
} from 'lucide-react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as DocumentPicker from 'expo-document-picker';
import * as FileSystem from 'expo-file-system/legacy';
import * as Sharing from 'expo-sharing';
import { BackupManager, BackupData } from '../../lib/backup/BackupManager';
import { WebDavClient, WebDavFile } from '../../lib/backup/WebDavClient';

import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { SettingsSection } from './components/SettingsSection';
import { SettingsItem } from './components/SettingsItem';
import { GlassBottomSheet } from '../../components/ui/GlassBottomSheet';
import { Colors } from '../../theme/colors';

export function BackupSettings() {
  const { isDark, colors } = useTheme();
  const { t } = useI18n();
  const { showToast } = useToast();
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState<string>('');

  // Confirm Dialog State
  const [confirmDialog, setConfirmDialog] = useState({
    visible: false,
    title: '',
    message: '',
    confirmText: '',
    isDestructive: false,
    onConfirm: () => { },
  });

  const showConfirm = (
    title: string,
    message: string,
    onConfirm: () => void,
    isDestructive = false,
  ) => {
    setConfirmDialog({
      visible: true,
      title,
      message,
      confirmText: t.common.confirm || 'Confirm',
      isDestructive,
      onConfirm: () => {
        setConfirmDialog((prev) => ({ ...prev, visible: false }));
        onConfirm();
      },
    });
  };

  // WebDAV Config
  const [webDavConfig, setWebDavConfig] = useState({
    url: '',
    username: '',
    password: '',
    enabled: false,
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

  // Backup Options State
  const [backupOptions, setBackupOptions] = useState({
    includeSessions: true,
    includeKnowledgeBase: true,
    includeFiles: true,
    includeSettings: true,
    includeSecrets: true,
  });

  const toggleOption = (key: keyof typeof backupOptions) => {
    setBackupOptions(prev => ({ ...prev, [key]: !prev[key] }));
  };

  // --- Local Operations ---

  const handleLocalExport = async () => {
    setLoading(true);
    setStatus(t.settings.backup.generating);
    try {
      const data = await BackupManager.exportData(backupOptions);
      const json = JSON.stringify(data, null, 2);

      const filename = `nexara_backup_${new Date().toISOString().split('T')[0]}.json`;
      const fileUri = ((FileSystem as any).documentDirectory || '') + filename;

      await FileSystem.writeAsStringAsync(fileUri, json);

      setStatus(t.settings.backup.backupCreated);

      if (await Sharing.isAvailableAsync()) {
        await Sharing.shareAsync(fileUri);
      } else {
        showToast(t.settings.backup.backupCreated, 'success');
      }
    } catch (e: any) {
      showToast(e.message, 'error');
    } finally {
      setLoading(false);
      setStatus('');
    }
  };

  const handleLocalImport = async () => {
    try {
      const result = await DocumentPicker.getDocumentAsync({
        type: 'application/json',
        copyToCacheDirectory: true,
      });

      if (result.canceled) return;

      showConfirm(
        t.settings.backup.restoreTitle,
        t.settings.backup.restoreWarning,
        async () => {
          setLoading(true);
          setStatus(t.settings.backup.restoring);
          try {
            const content = await FileSystem.readAsStringAsync(result.assets[0].uri);
            const backup: BackupData = JSON.parse(content);
            await BackupManager.importData(backup);
            showToast(t.settings.backup.restoreSuccess, 'success');
          } catch (e: any) {
            showToast(e.message, 'error');
          } finally {
            setLoading(false);
            setStatus('');
          }
        },
        true,
      );
    } catch (e: any) {
      showToast(e.message, 'error');
    }
  };

  // --- WebDAV Operations ---

  const getClient = () => {
    if (!webDavConfig.url) throw new Error('WebDAV URL not configured');
    return new WebDavClient({
      url: webDavConfig.url,
      username: webDavConfig.username,
      password: webDavConfig.password,
    });
  };

  const handleWebDavTest = async () => {
    setLoading(true);
    try {
      const client = getClient();
      await client.checkConnection();
      showToast(t.settings.backup.connectionSuccess, 'success');
    } catch (e: any) {
      showToast(e.message, 'error');
    } finally {
      setLoading(false);
    }
  };
  const handleWebDavUpload = async () => {
    if (!webDavConfig.enabled) {
      showToast(t.settings.backup.webDavDisabled, 'info');
      return;
    }

    setLoading(true);
    setStatus(t.settings.backup.uploading);
    try {
      const client = getClient();
      const data = await BackupManager.exportData(backupOptions);
      const json = JSON.stringify(data);
      const filename = `nexara_backup_${new Date().toISOString().replace(/[:.]/g, '-')}.json`;

      await client.uploadFile(filename, json);
      showToast(t.settings.backup.uploadSuccess, 'success');
    } catch (e: any) {
      showToast(e.message, 'error');
    } finally {
      setLoading(false);
      setStatus('');
    }
  };

  const handleWebDavRestore = async () => {
    if (!webDavConfig.enabled) {
      showToast(t.settings.backup.webDavDisabled, 'info');
      return;
    }

    setLoading(true);
    setStatus(t.settings.backup.fetchingList);
    try {
      const client = getClient();
      const files = await client.listFiles('/'); // List root
      // Filter for JSON files, sort by date desc
      const backups = files
        .filter((f) => f.filename.endsWith('.json') && f.filename.includes('nexara'))
        .sort((a, b) => new Date(b.lastModified).getTime() - new Date(a.lastModified).getTime());

      setRemoteFiles(backups);
      setShowRemoteFilesModal(true);
    } catch (e: any) {
      showToast(e.message, 'error');
    } finally {
      setLoading(false);
      setStatus('');
    }
  };

  const confirmRemoteRestore = (file: WebDavFile) => {
    setShowRemoteFilesModal(false);
    showConfirm(
      t.settings.backup.confirmCloudRestore,
      t.settings.backup.confirmCloudRestoreDesc.replace('{filename}', file.filename),
      async () => {
        setLoading(true);
        setStatus(t.settings.backup.downloading.replace('{filename}', file.filename));
        try {
          const client = getClient();
          const content = await client.downloadFile(file.filename);
          setStatus(t.settings.backup.restoring);
          const backup: BackupData = JSON.parse(content);
          await BackupManager.importData(backup);
          showToast(t.settings.backup.restoreSuccess, 'success');
        } catch (e: any) {
          showToast(e.message, 'error');
        } finally {
          setLoading(false);
          setStatus('');
        }
      },
      true,
    );
  };

  const OptionRow = ({ label, value, onToggle, desc }: { label: string; value: boolean; onToggle: () => void; desc?: string }) => (
    <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 12, borderBottomWidth: 1, borderBottomColor: isDark ? Colors.dark.borderDefault : '#e5e7eb' }}>
      <View style={{ flex: 1, marginRight: 16 }}>
        <Text style={{ fontSize: 14, fontWeight: '500', color: isDark ? Colors.dark.textPrimary : '#1f2937' }}>{label}</Text>
        {desc && <Text style={{ fontSize: 11, color: isDark ? Colors.dark.textSecondary : '#6b7280', marginTop: 2 }}>{desc}</Text>}
      </View>
      <Switch value={value} onValueChange={onToggle} />
    </View>
  );

  return (
    <>
      <SettingsSection title={t.settings.backup.backupHeader}>
        <CollapsibleSection
          title={t.settings.backup.contentSelection}
          icon={<Folder size={18} color={colors[500]} />}
        >
          <View style={{ paddingHorizontal: 16, paddingBottom: 8 }}>
            <OptionRow
              label={t.settings.backup.options.sessions}
              desc={t.settings.backup.options.sessionsDesc}
              value={backupOptions.includeSessions}
              onToggle={() => toggleOption('includeSessions')}
            />
            <OptionRow
              label={t.settings.backup.options.knowledge}
              desc={t.settings.backup.options.knowledgeDesc}
              value={backupOptions.includeKnowledgeBase}
              onToggle={() => toggleOption('includeKnowledgeBase')}
            />
            <OptionRow
              label={t.settings.backup.options.files}
              desc={t.settings.backup.options.filesDesc}
              value={backupOptions.includeFiles}
              onToggle={() => toggleOption('includeFiles')}
            />
            <OptionRow
              label={t.settings.backup.options.settings}
              desc={t.settings.backup.options.settingsDesc}
              value={backupOptions.includeSettings}
              onToggle={() => toggleOption('includeSettings')}
            />
            <OptionRow
              label={t.settings.backup.options.secrets}
              desc={t.settings.backup.options.secretsDesc}
              value={backupOptions.includeSecrets}
              onToggle={() => toggleOption('includeSecrets')}
            />
          </View>
        </CollapsibleSection>

        {/* Local Actions */}
        <ConfirmDialog
          visible={confirmDialog.visible}
          title={confirmDialog.title}
          message={confirmDialog.message}
          confirmText={confirmDialog.confirmText}
          isDestructive={confirmDialog.isDestructive}
          onConfirm={confirmDialog.onConfirm}
          onCancel={() => setConfirmDialog((prev) => ({ ...prev, visible: false }))}
        />
        <View
          style={{
            borderBottomWidth: 1,
            borderBottomColor: isDark ? Colors.dark.borderDefault : '#eee',
            padding: 16,
          }}
        >
          <Text
            style={{
              fontWeight: '600',
              marginBottom: 12,
              color: isDark ? Colors.dark.textPrimary : '#111',
            }}
          >
            {t.settings.backup.localStorage}
          </Text>
          <View style={{ flexDirection: 'row', gap: 12 }}>
            <TouchableOpacity
              onPress={handleLocalExport}
              disabled={loading}
              style={{
                flex: 1,
                flexDirection: 'row',
                alignItems: 'center',
                justifyContent: 'center',
                padding: 12,
                borderRadius: 12,
                backgroundColor: isDark ? Colors.dark.surfaceTertiary : '#f1f5f9',
              }}
            >
              <Upload size={18} color={isDark ? '#fff' : '#000'} />
              <Text style={{ marginLeft: 8, fontWeight: '600', color: isDark ? '#fff' : '#000' }}>
                {t.settings.backup.export}
              </Text>
            </TouchableOpacity>
            <TouchableOpacity
              onPress={handleLocalImport}
              disabled={loading}
              style={{
                flex: 1,
                flexDirection: 'row',
                alignItems: 'center',
                justifyContent: 'center',
                padding: 12,
                borderRadius: 12,
                backgroundColor: isDark ? Colors.dark.surfaceTertiary : '#f1f5f9',
              }}
            >
              <Download size={18} color={isDark ? '#fff' : '#000'} />
              <Text style={{ marginLeft: 8, fontWeight: '600', color: isDark ? '#fff' : '#000' }}>
                {t.settings.backup.import}
              </Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* WebDAV Settings */}
        <View style={{ padding: 16 }}>
          <View
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'space-between',
              marginBottom: 12,
            }}
          >
            <View style={{ flexDirection: 'row', alignItems: 'center' }}>
              <Cloud size={20} color={colors[500]} />
              <Text
                style={{
                  marginLeft: 8,
                  fontWeight: '600',
                  color: isDark ? Colors.dark.textPrimary : '#111',
                }}
              >
                {t.settings.backup.webDavCloud}
              </Text>
            </View>
            <Switch
              value={webDavConfig.enabled}
              onValueChange={(v) => {
                if (v && !webDavConfig.url) setShowWebDavModal(true);
                saveConfig({ ...webDavConfig, enabled: v });
              }}
            />
          </View>

          {webDavConfig.enabled && (
            <View>
              <View
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  marginBottom: 16,
                  borderBottomWidth: 1,
                  borderBottomColor: isDark ? Colors.dark.borderDefault : '#eee',
                  paddingBottom: 12,
                }}
              >
                <Text
                  style={{ fontSize: 14, color: isDark ? Colors.dark.textSecondary : '#4b5563' }}
                >
                  {t.settings.backup.autoBackup}
                </Text>
                <Switch
                  value={(webDavConfig as any).autoBackup || false}
                  onValueChange={(v) => saveConfig({ ...webDavConfig, autoBackup: v })}
                />
              </View>

              <View style={{ flexDirection: 'row', gap: 12, marginBottom: 12 }}>
                <TouchableOpacity
                  onPress={handleWebDavUpload}
                  disabled={loading}
                  style={{
                    flex: 1,
                    flexDirection: 'row',
                    alignItems: 'center',
                    justifyContent: 'center',
                    padding: 12,
                    borderRadius: 12,
                    backgroundColor: isDark ? Colors.dark.surfaceTertiary : '#f1f5f9',
                  }}
                >
                  <Upload size={18} color={isDark ? '#fff' : '#000'} />
                  <Text
                    style={{ marginLeft: 8, fontWeight: '600', color: isDark ? '#fff' : '#000' }}
                  >
                    {t.settings.backup.backupToCloud}
                  </Text>
                </TouchableOpacity>
                <TouchableOpacity
                  onPress={handleWebDavRestore}
                  disabled={loading}
                  style={{
                    flex: 1,
                    flexDirection: 'row',
                    alignItems: 'center',
                    justifyContent: 'center',
                    padding: 12,
                    borderRadius: 12,
                    backgroundColor: isDark ? Colors.dark.surfaceTertiary : '#f1f5f9',
                  }}
                >
                  <Download size={18} color={isDark ? '#fff' : '#000'} />
                  <Text
                    style={{ marginLeft: 8, fontWeight: '600', color: isDark ? '#fff' : '#000' }}
                  >
                    {t.settings.backup.restoreFromCloud}
                  </Text>
                </TouchableOpacity>
              </View>
              <TouchableOpacity
                onPress={() => setShowWebDavModal(true)}
                style={{
                  padding: 12,
                  borderRadius: 12,
                  borderWidth: 1,
                  borderColor: isDark ? Colors.dark.borderDefault : '#e5e7eb',
                  backgroundColor: isDark ? Colors.dark.surfaceTertiary : '#f9fafb',
                  alignItems: 'center',
                }}
              >
                <Text
                  style={{
                    fontWeight: '600',
                    color: isDark ? Colors.dark.textSecondary : '#4b5563',
                  }}
                >
                  {t.settings.backup.configureServer}
                </Text>
              </TouchableOpacity>
            </View>
          )}
        </View>

        {loading && (
          <View
            style={[
              StyleSheet.absoluteFill,
              {
                backgroundColor: 'rgba(0,0,0,0.5)',
                alignItems: 'center',
                justifyContent: 'center',
                zIndex: 50,
                borderRadius: 24,
              },
            ]}
          >
            <View
              style={{
                padding: 24,
                borderRadius: 24,
                alignItems: 'center',
                backgroundColor: isDark ? Colors.dark.surfaceTertiary : '#fff',
              }}
            >
              <ActivityIndicator size="large" color={colors[500]} />
              <Text style={{ marginTop: 12, fontWeight: '600', color: isDark ? '#fff' : '#000' }}>
                {status}
              </Text>
            </View>
          </View>
        )}
      </SettingsSection>

      {/* WebDAV Configuration Modal */}
      {/* WebDAV Configuration Modal */}
      <GlassBottomSheet
        visible={showWebDavModal}
        onClose={() => setShowWebDavModal(false)}
        title={t.settings.backup.settingsTitle}
      >
        <KeyboardAvoidingView
          behavior={Platform.OS === 'ios' ? 'padding' : 'padding'}
          keyboardVerticalOffset={Platform.OS === 'ios' ? 100 : 0}
          style={{ flex: 1 }}
        >
          <ScrollView contentContainerStyle={{ paddingHorizontal: 24, paddingBottom: 40 }}>
            <Text className={`text-sm font-medium mb-2 ${isDark ? 'text-gray-400' : 'text-gray-600'}`}>
              {t.settings.backup.serverUrl}
            </Text>
            <TextInput
              value={webDavConfig.url}
              onChangeText={(t) => setWebDavConfig((prev) => ({ ...prev, url: t.trim() }))}
              className={`p-3.5 rounded-xl mb-6 border ${isDark ? 'border-zinc-700 text-white' : 'bg-gray-50 border-gray-200 text-black'}`}
              style={isDark ? { backgroundColor: Colors.dark.surfaceTertiary } : {}}
              placeholder="https://..."
              placeholderTextColor={isDark ? '#666' : '#999'}
              autoCapitalize="none"
            />

            <Text className={`text-sm font-medium mb-2 ${isDark ? 'text-gray-400' : 'text-gray-600'}`}>
              {t.settings.backup.username}
            </Text>
            <TextInput
              value={webDavConfig.username}
              onChangeText={(t) => setWebDavConfig((prev) => ({ ...prev, username: t.trim() }))}
              className={`p-3.5 rounded-xl mb-6 border ${isDark ? 'border-zinc-700 text-white' : 'bg-gray-50 border-gray-200 text-black'}`}
              style={isDark ? { backgroundColor: Colors.dark.surfaceTertiary } : {}}
              autoCapitalize="none"
            />

            <Text className={`text-sm font-medium mb-2 ${isDark ? 'text-gray-400' : 'text-gray-600'}`}>
              {t.settings.backup.password}
            </Text>
            <TextInput
              value={webDavConfig.password}
              onChangeText={(t) => setWebDavConfig((prev) => ({ ...prev, password: t.trim() }))}
              className={`p-3.5 rounded-xl mb-8 border ${isDark ? 'border-zinc-700 text-white' : 'bg-gray-50 border-gray-200 text-black'}`}
              style={isDark ? { backgroundColor: Colors.dark.surfaceTertiary } : {}}
              secureTextEntry
            />
            <Text className={`text-xs mb-8 leading-5 ${isDark ? 'text-gray-500' : 'text-gray-400'}`}>
              {t.settings.backup.appPasswordHint ||
                'Note: If using 2FA/Nutstore, use an App Password.'}
            </Text>

            <View className="flex-row gap-4">
              <TouchableOpacity
                onPress={handleWebDavTest}
                className={`flex-1 p-4 rounded-xl items-center border ${isDark ? 'border-zinc-700' : 'border-gray-300'}`}
                style={isDark ? { backgroundColor: Colors.dark.surfaceTertiary } : {}}
              >
                <Text className={`font-semibold ${isDark ? 'text-white' : 'text-black'}`}>
                  {t.settings.backup.testConnection}
                </Text>
              </TouchableOpacity>
              <TouchableOpacity
                onPress={() => {
                  saveConfig(webDavConfig);
                  setShowWebDavModal(false);
                }}
                style={{ backgroundColor: colors[600] }}
                className="flex-1 p-4 rounded-xl items-center shadow-sm"
              >
                <Text className="text-white font-bold">{t.settings.backup.save}</Text>
              </TouchableOpacity>
            </View>
          </ScrollView>
        </KeyboardAvoidingView>
      </GlassBottomSheet>

      {/* Remote Files List Modal */}
      <GlassBottomSheet
        visible={showRemoteFilesModal}
        onClose={() => setShowRemoteFilesModal(false)}
        title={t.settings.backup.selectBackupTitle}
      >
        <FlatList
          data={remoteFiles}
          keyExtractor={(item) => item.filename}
          contentContainerStyle={{ padding: 24 }}
          renderItem={({ item }) => (
            <TouchableOpacity
              onPress={() => confirmRemoteRestore(item)}
              className={`flex-row items-center p-4 mb-3 rounded-xl border ${isDark ? 'bg-zinc-800/50 border-zinc-700' : 'bg-white border-gray-200'}`}
            >
              <FileJson size={24} color={colors[500]} />
              <View className="ml-3 flex-1">
                <Text className={`font-medium ${isDark ? 'text-white' : 'text-black'}`}>
                  {item.filename}
                </Text>
                <Text className="text-xs text-gray-500 mt-1">
                  {new Date(item.lastModified).toLocaleString()} •{' '}
                  {(item.size / 1024 / 1024).toFixed(2)} MB
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
      </GlassBottomSheet>
    </>
  );
}
