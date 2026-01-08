import notifee, { EventType } from '@notifee/react-native';
import { staticServerService } from './workbench/StaticServerService';

// Handle background events (e.g. button press on notification when app is background/killed)
notifee.onBackgroundEvent(async ({ type, detail }) => {
    const { notification, pressAction } = detail;

    if (type === EventType.ACTION_PRESS && pressAction?.id === 'stop_service') {
        console.log('[Notifee] Stop Action Pressed');
        await staticServerService.stop(); // This handles stopping notifee service too

        // Remove the notification
        if (notification?.id) {
            await notifee.cancelNotification(notification.id);
        }
    }
});

// Register the foreground service task.
// The promise should not resolve until the service is meant to stop.
notifee.registerForegroundService((notification) => {
    return new Promise(() => {
        // Keep alive until stopped
    });
});

console.log('[Notifee] Foreground Service Task Registered');
