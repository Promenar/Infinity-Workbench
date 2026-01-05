const fs = require('fs');
const path = require('path');

const file = path.resolve(__dirname, '../android/app/build.gradle');
if (!fs.existsSync(file)) {
    console.error("File not found");
    process.exit(1);
}

const appendContent = `

// --- INJECTED BY NEXARA AGENT ---
// Safely disable R8/Minification by merging into android block
android {
    buildTypes {
        release {
            minifyEnabled false
            shrinkResources false
        }
    }
}
// --------------------------------
`;

fs.appendFileSync(file, appendContent);
console.log("✅ Appended R8 disable config to build.gradle");
