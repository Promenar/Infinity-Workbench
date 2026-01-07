const { withAndroidManifest } = require('@expo/config-plugins');

const withWideColorGamut = (config) => {
    return withAndroidManifest(config, async (config) => {
        const androidManifest = config.modResults;
        if (androidManifest.manifest.application && androidManifest.manifest.application[0]) {
            const app = androidManifest.manifest.application[0];
            app.$['android:colorMode'] = 'wideColorGamut';
        }
        return config;
    });
};

module.exports = withWideColorGamut;
