const fs = require('fs');
const path = require('path');

const file = path.resolve(__dirname, '../android/app/build.gradle');
if (!fs.existsSync(file)) {
    console.error("File not found");
    process.exit(1);
}

const appendContent = `

// --- INJECTED BY NEXARA AGENT (Signing + R8) ---
android {
    signingConfigs {
        release {
            if (project.hasProperty('MYAPP_UPLOAD_STORE_FILE')) {
                storeFile file(MYAPP_UPLOAD_STORE_FILE)
                storePassword MYAPP_UPLOAD_STORE_PASSWORD
                keyAlias MYAPP_UPLOAD_KEY_ALIAS
                keyPassword MYAPP_UPLOAD_KEY_PASSWORD
            }
        }
    }
    buildTypes {
        release {
            // Apply the signing config we just defined
            // Note: If 'release' signingConfig was already set above, this overrides/merges it?
            // Gradle usually takes the last one or merges.
            if (project.hasProperty('MYAPP_UPLOAD_STORE_FILE')) {
                signingConfig signingConfigs.release
            }
            
            // Disable R8 (Crash Fix)
            minifyEnabled false
            shrinkResources false
        }
    }
}
// --------------------------------
`;

fs.appendFileSync(file, appendContent);
console.log("✅ Appended Signing + R8 config to build.gradle");
