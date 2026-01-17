const { withAndroidManifest } = require('@expo/config-plugins');

const withNativeLibraryTags = (config) => {
    return withAndroidManifest(config, async (config) => {
        const androidManifest = config.modResults;
        const manifest = androidManifest.manifest;
        const application = manifest.application[0];

        // Ensure 'uses-native-library' array exists
        if (!application['uses-native-library']) {
            application['uses-native-library'] = [];
        }

        const libsToAdd = [
            {
                'android:name': 'libOpenCL.so',
                'android:required': 'false',
            },
            {
                'android:name': 'libcdsprpc.so',
                'android:required': 'false',
            },
        ];

        libsToAdd.forEach((lib) => {
            const exists = application['uses-native-library'].some(
                (item) => item.$['android:name'] === lib['android:name']
            );

            if (!exists) {
                application['uses-native-library'].push({
                    $: lib,
                });
            }
        });

        return config;
    });
};

module.exports = withNativeLibraryTags;
