const fs = require('fs');
const path = require('path');

const buildGradlePath = path.resolve(__dirname, '../android/app/build.gradle');
if (!fs.existsSync(buildGradlePath)) {
    console.error(`File not found: ${buildGradlePath}`);
    process.exit(1);
}

let content = fs.readFileSync(buildGradlePath, 'utf8');

// Logic matches "if (signingConfigName != "debug" && signingConfigName != "none")"
const oldLogic = 'if (signingConfigName != "debug" && signingConfigName != "none")';
const newLogic = 'if (signingConfigName == "debug") { signLabel = "-Unsigned" } else { signLabel = "-Signed" } // Fixed logic';

// Regex replacement is slightly safer to account for whitespace
// Old block:
// if (signingConfigName != "debug" && signingConfigName != "none") {
//     signLabel = "-Signed"
// } else {
//     signLabel = "-Unsigned"
// }

// Simple logic:
// We want:
// if (buildType == "release") {
//    if (signingConfigName == "debug") { signLabel = "-Unsigned" } else { signLabel = "-Signed" }
// }

if (content.includes('signingConfigName != "debug" && signingConfigName != "none"')) {
    console.log('Patching naming logic...');
    // Replace the specific condition and block structure?
    // Let's just use string replacement on the condition line + following lines if possible?
    // Or just replace the condition 'signingConfigName != "debug" && signingConfigName != "none"' with 'true' to force Signed? 
    // No, cleaner code is better.

    // Original:
    /*
                    if (signingConfigName != "debug" && signingConfigName != "none") {
                        signLabel = "-Signed"
                    } else {
                        signLabel = "-Unsigned"
                    }
    */

    // We can replace the condition with `signingConfigName != "debug"` (removed the none check).
    // If none, it becomes Signed.
    content = content.replace('signingConfigName != "debug" && signingConfigName != "none"', 'signingConfigName != "debug"');

    fs.writeFileSync(buildGradlePath, content);
    console.log('✅ Patched APK naming logic to default to Signed.');
} else {
    console.log('Naming logic already patched or not found.');
}
