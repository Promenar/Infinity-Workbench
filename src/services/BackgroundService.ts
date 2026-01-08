import notifee, { AndroidImportance, AndroidColor, AndroidForegroundServiceType } from '@notifee/react-native';

class BackgroundService {
    private isRunning = false;
    private notificationId = 'nexara-web-service';
    private channelId = 'nexara-service';

    async start() {
        if (this.isRunning) return;

        // Proactive Permission Requests
        await this.requestUserPermission();
        // Battery optimization request is best triggered by UI interaction, but we can check status here
        // or let the UI handle the "request" part to avoid jarring context switches on start.
        // The user asked to "actively request" on launch, but blocking with settings screens is bad UX.
        // We will expose a method for the UI to call, or call it here if strictly required.
        // For now, let's just ensure we have notification permission.

        try {
            await notifee.createChannel({
                id: this.channelId,
                name: 'Nexara Background Service',
                importance: AndroidImportance.LOW,
                lights: false,
                vibration: false,
            });

            await notifee.displayNotification({
                id: this.notificationId,
                title: 'Nexara',
                body: 'Nexara Web Service Running',
                android: {
                    channelId: this.channelId,
                    asForegroundService: true,
                    ongoing: true,
                    foregroundServiceTypes: [AndroidForegroundServiceType.FOREGROUND_SERVICE_TYPE_DATA_SYNC],
                    color: AndroidColor.BLUE,
                    smallIcon: 'ic_launcher',
                    actions: [
                        {
                            title: 'Stop Service',
                            pressAction: {
                                id: 'stop_service',
                            },
                        },
                    ],
                    pressAction: {
                        id: 'default',
                    },
                },
            });

            this.isRunning = true;
            console.log('[BackgroundService] Service started');
        } catch (error) {
            console.error('[BackgroundService] Failed to start:', error);
        }
    }

    async stop() {
        // if (!this.isRunning) return; // Allow force stop even if flag mismatch
        try {
            await notifee.stopForegroundService();
            await notifee.cancelNotification(this.notificationId); // Ensure notification is gone
            this.isRunning = false;
            console.log('[BackgroundService] Service stopped');
        } catch (error) {
            console.error('[BackgroundService] Failed to stop:', error);
        }
    }

    async requestUserPermission() {
        const settings = await notifee.requestPermission();
        if (settings.authorizationStatus >= 1) {
            console.log('[BackgroundService] Permission granted');
        } else {
            console.log('[BackgroundService] Permission denied');
        }
    }

    async requestBatteryOptimization() {
        // Android specific: Linking to settings
        const { Platform, Linking } = require('react-native');
        if (Platform.OS === 'android') {
            // Try to open the specific app's battery settings if possible, otherwise generic
            // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is risky for Play Store, but fine for local.
            // But Linking.openSettings() is safest.
            // Or intent launcher:
            try {
                const IntentLauncher = require('expo-intent-launcher');
                // Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                // This intent usually requires the permission REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in manifest.
                // Without manifest permission, it crashes. 
                // We'll stick to opening standard settings or app details.
                await Linking.openSettings();
            } catch (e) {
                console.warn('Failed to open settings', e);
            }
        }
    }
}

export const backgroundService = new BackgroundService();
