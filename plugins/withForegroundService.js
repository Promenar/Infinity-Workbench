const { withAndroidManifest } = require('@expo/config-plugins');

const withForegroundService = (config) => {
    return withAndroidManifest(config, async (config) => {
        const androidManifest = config.modResults;

        if (!androidManifest.manifest['uses-permission']) {
            androidManifest.manifest['uses-permission'] = [];
        }

        const permissions = [
            'android.permission.FOREGROUND_SERVICE',
            'android.permission.FOREGROUND_SERVICE_DATA_SYNC', // For Android 14+
        ];

        permissions.forEach((permission) => {
            const hasPermission = androidManifest.manifest['uses-permission'].some(
                (props) => props.$['android:name'] === permission
            );

            if (!hasPermission) {
                androidManifest.manifest['uses-permission'].push({
                    $: {
                        'android:name': permission,
                    },
                });
            }
        });

        // Verify MainApplication exists, though we typically add to <application>
        const mainApplication = androidManifest.manifest.application[0];

        // Define the Notifee Foreground Service override
        // We must explicitly declare the service with the types we intend to use (dataSync)
        const serviceName = 'app.notifee.core.ForegroundService';

        let service = mainApplication.service?.find(
            (s) => s.$['android:name'] === serviceName
        );

        if (!service) {
            if (!mainApplication.service) mainApplication.service = [];
            service = { $: { 'android:name': serviceName } };
            mainApplication.service.push(service);
        }

        // Add or merge foregroundServiceType
        // Note: We use "dataSync" as required by Android 14 for our usage
        service.$['android:foregroundServiceType'] = 'dataSync';

        // Ensure tools namespace is available for merging if needed, 
        // but direct attribute setting usually works for Expo's xml parser.
        // If Notifee already declares it, we are modifying the JS object which will overwrite/merge.

        return config;
    });
};

module.exports = withForegroundService;
